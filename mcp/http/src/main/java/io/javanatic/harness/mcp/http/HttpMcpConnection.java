package io.javanatic.harness.mcp.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpTimeoutException;
import io.javanatic.harness.mcp.McpToolDescriptor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单 server 的 Streamable HTTP 连接（会话 + 清单代际 + 有限重连，it27.1）。
 * 状态机与 stdio 同形：CONNECTED --断线/会话失效--> RECONNECTING --（退避 ×N，
 * 重新 initialize + 按新清单换代）--> CONNECTED，或 --（退避耗尽）--> FAILED。
 *
 * <p>会话语义（T4 重裁 + HTTP 增量）：{@code tools/call} 绝不自动重试（副作用
 * 不重复）；404 / 连接面失败的调用以失败收口，重连完成后的后续调用恢复。
 * {@code initialize} / {@code tools/list} 等安全请求可在重连内重做。GET 通知
 * 通道 405 或中途关闭只降级 WARN（不轮询）；清单变化在通知驱动刷新或重连对账
 * 时递增代际，由消费面在轮屏障采纳。
 */
final class HttpMcpConnection implements McpServerConnection {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LIST_PAGES = 100;

    private final McpHttpServerConfig config;
    private final HttpClient client;
    private final Duration[] backoff;
    private final System.Logger log;
    /** 当前清单快照（代际 / 冻结工具 / 描述符同代发布；volatile 读一次拿全）。 */
    private volatile Catalog catalog = new Catalog(0, List.of(), List.of());
    private volatile HttpRpcSession session;
    private volatile McpServerState state = McpServerState.FAILED;
    private volatile String failedReason;
    private volatile int attempt;
    private volatile Thread reconnector;
    private volatile Thread refresher;
    private volatile boolean closed;
    /** 启动完成门闩（句柄锁保护）：装配期断裂不触发重连（错误同步上抛，fail loud）。 */
    private boolean started;
    /** 待刷新标记（句柄锁保护）：通知合并——运行中的刷新线程收尾前会再取一次。 */
    private boolean refreshRequested;

    private HttpMcpConnection(McpHttpServerConfig config, HttpClient client, Duration[] backoff) {
        this.config = config;
        this.client = client;
        this.backoff = backoff;
        this.log = System.getLogger("io.javanatic.harness.mcp.http." + config.id());
    }

    /**
     * 装配期启动：建会话并同步完成握手与清单快照。
     *
     * @param config server 配置
     * @param client 共享 HTTP 客户端（{@code Redirect.NEVER}）
     * @param backoff 重连退避档（发射间隔；数组长度 = 最大尝试次数）
     * @return 已连接的连接面
     * @throws McpConnectionException 端点不可达或起步握手失败（装配 fail loud）
     * @throws McpProtocolException 握手期协议畸形（同上）
     */
    static HttpMcpConnection start(McpHttpServerConfig config, HttpClient client, Duration[] backoff) {
        HttpMcpConnection connection = new HttpMcpConnection(config, client, backoff);
        HttpRpcSession opened = HttpRpcSession.open(config, client, connection::handleBroken);
        List<FrozenTool> tools;
        try {
            tools = connection.fetchTools(opened);
        } catch (RuntimeException e) {
            opened.closeBestEffort(); // 启动失败:回收已建会话,不留半装配
            throw e;
        }
        connection.catalog = connection.snapshot(1, tools);
        opened.onListChanged(() -> connection.handleListChanged(opened));
        synchronized (connection) {
            connection.session = opened;
            connection.started = true;
            if (opened.isInvalid()) {
                // 握手后、发布前会话已失效:按运行期断线处理(重连;按新清单换代)
                connection.state = McpServerState.RECONNECTING;
                connection.reconnector = Thread.ofVirtual()
                    .name("jh-mcp-" + config.id() + "-reconnect").start(connection::reconnectLoop);
            } else {
                connection.state = McpServerState.CONNECTED;
                connection.log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id()
                    + "' connected: " + tools.size() + " tools (generation 1)");
            }
        }
        opened.startNotifications(() -> connection.handleListChanged(opened));
        return connection;
    }

    @Override
    public String serverId() {
        return config.id();
    }

    @Override
    public McpServerState state() {
        return state;
    }

    @Override
    public List<McpToolDescriptor> tools() {
        return catalog.descriptors();
    }

    @Override
    public long catalogGeneration() {
        return catalog.generation();
    }

    @Override
    public McpCallResult call(String toolName, String argumentsJson, AbortSignal signal) {
        Objects.requireNonNull(signal, "signal");
        McpServerState current = state;
        if (closed || current == McpServerState.FAILED) {
            throw new McpConnectionException("mcp server '" + config.id() + "' unavailable: "
                + (failedReason == null ? "not started" : failedReason));
        }
        if (current == McpServerState.RECONNECTING) {
            throw new McpConnectionException("mcp server '" + config.id() + "' is reconnecting (attempt "
                + attempt + "/" + backoff.length + ")");
        }
        HttpRpcSession active = session;
        if (active == null || active.isInvalid()) {
            // 失效已判定但 handleBroken 尚未落状态(RECONNECTING)的窗口:同样快速失败,
            // 不得把调用漂到待弃置的会话上;tools/call 不自动重试(副作用不重复)
            throw new McpConnectionException("mcp server '" + config.id()
                + "' connection lost; reconnecting");
        }
        ObjectNode params = JSON.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", parseArguments(argumentsJson));
        JsonNode result = active.request("tools/call", params, config.timeout(), signal);
        return toCallResult(result);
    }

    /** 断开回收（提供者内部生命周期方法，非 {@link McpServerConnection} 契约面）：
     * 置 FAILED、停重连/刷新、关会话（尽力 DELETE）。幂等。 */
    void close() {
        HttpRpcSession active;
        Thread thread;
        Thread refreshThread;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            state = McpServerState.FAILED;
            if (failedReason == null) {
                failedReason = "closed";
            }
            active = session;
            thread = reconnector;
            refreshThread = refresher;
        }
        if (thread != null) {
            thread.interrupt();
        }
        if (refreshThread != null) {
            refreshThread.interrupt();
        }
        if (active != null) {
            active.closeBestEffort();
        }
    }

    /** 会话断裂回调（每会话至多一次；启动完成前不触发——装配错误走同步路径）。 */
    private void handleBroken(HttpRpcSession brokenSession, McpConnectionException cause) {
        synchronized (this) {
            if (closed || !started || session != brokenSession || state != McpServerState.CONNECTED) {
                return;
            }
            state = McpServerState.RECONNECTING;
            log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                + "' connection lost (" + cause.getMessage() + "); reconnecting (up to "
                + backoff.length + " attempts)");
            reconnector = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-reconnect")
                .start(this::reconnectLoop);
        }
    }

    /** 通知回调（通知读线程只置位/拉线程，不阻塞帧读取）：刷新由虚拟线程异步执行并合并。 */
    private void handleListChanged(HttpRpcSession source) {
        synchronized (this) {
            if (closed || session != source || state != McpServerState.CONNECTED) {
                return;
            }
            refreshRequested = true;
            if (refresher != null) {
                return; // 运行中的刷新线程收尾时会再取一次
            }
            refresher = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-refresh")
                .start(this::refreshLoop);
        }
    }

    /** 通知驱动的清单重取：内容变化才递增代际；协议畸形按连接断裂处理。 */
    private void refreshLoop() {
        while (true) {
            HttpRpcSession active;
            synchronized (this) {
                if (closed || !refreshRequested) {
                    refresher = null;
                    return;
                }
                refreshRequested = false;
                active = session;
            }
            if (active == null || active.isInvalid()) {
                continue; // 断线路径的重连握手会以新清单换代
            }
            try {
                adoptRefresh(active, fetchTools(active));
            } catch (McpProtocolException e) {
                log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                    + "' catalog refresh returned malformed data; reconnecting: " + e.getMessage());
                active.invalidate(e);
            } catch (McpTimeoutException e) {
                log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                    + "' catalog refresh timed out; keeping generation " + catalog.generation()
                    + ": " + e.getMessage());
            } catch (McpConnectionException e) {
                log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                    + "' catalog refresh failed; keeping generation " + catalog.generation()
                    + ": " + e.getMessage());
            }
        }
    }

    /** 采纳通知刷新结果：仅当仍指向同一会话且内容变化才换代（顺序变化不算）。 */
    private void adoptRefresh(HttpRpcSession source, List<FrozenTool> tools) {
        synchronized (this) {
            if (closed || session != source) {
                return; // 结果已过期（重连换了会话）
            }
            Catalog current = catalog;
            if (sameCatalog(current.tools(), tools)) {
                return;
            }
            catalog = snapshot(current.generation() + 1, tools);
            log.log(System.Logger.Level.INFO, "mcp server '" + config.id() + "' catalog generation "
                + current.generation() + " -> " + catalog.generation() + " ("
                + tools.size() + " tools, list_changed)");
        }
    }

    /** 有限重连：退避逐档重试（重开会话 + 握手）；清单变化按新代发布，不再终态化。
     * 失效会话（stale）成功换到新会话后关闭——DELETE 尽最大努力终止远端会话。 */
    private void reconnectLoop() {
        HttpRpcSession stale = session;
        for (int round = 0; round < backoff.length; round++) {
            attempt = round + 1;
            if (!pauseFor(backoff[round])) {
                return; // 已关闭/中断:终止重连(闭合会话由 close() 负责)
            }
            HttpRpcSession tentative = null;
            try {
                tentative = HttpRpcSession.open(config, client, this::handleBroken);
                final HttpRpcSession opened = tentative;
                List<FrozenTool> current = fetchTools(opened);
                boolean adopt;
                synchronized (this) {
                    adopt = !closed && !tentative.isInvalid();
                    if (adopt) {
                        Catalog previous = catalog;
                        long generation = sameCatalog(previous.tools(), current)
                            ? previous.generation() : previous.generation() + 1;
                        catalog = snapshot(generation, current);
                        session = tentative;
                        state = McpServerState.CONNECTED;
                        attempt = 0;
                        if (generation != previous.generation()) {
                            log.log(System.Logger.Level.INFO, "mcp server '" + config.id()
                                + "' catalog generation " + previous.generation() + " -> " + generation
                                + " across reconnect (" + current.size() + " tools)");
                        }
                    }
                }
                if (!adopt) {
                    tentative.closeBestEffort(); // 锁外收口:DELETE 是阻塞网络操作
                    if (closed) {
                        return;
                    }
                    continue; // 重连期内再次失效:本档作废,下一档继续
                }
                tentative.onListChanged(() -> handleListChanged(opened));
                tentative.startNotifications(() -> handleListChanged(opened));
                if (stale != null) {
                    stale.closeBestEffort();
                }
                log.log(System.Logger.Level.INFO, "mcp server '" + config.id() + "' reconnected ("
                    + current.size() + " tools, generation " + catalogGeneration() + ")");
                return;
            } catch (RuntimeException e) {
                if (tentative != null) {
                    tentative.closeBestEffort();
                }
                log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id() + "' reconnect attempt "
                    + attempt + "/" + backoff.length + " failed: " + e.getMessage());
            }
        }
        if (stale != null) {
            stale.closeBestEffort();
        }
        failExhausted();
    }

    private boolean pauseFor(Duration duration) {
        if (closed) {
            return false;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !closed;
    }

    private synchronized void failExhausted() {
        if (closed) {
            return;
        }
        failedReason = "reconnect exhausted after " + backoff.length + " attempts";
        state = McpServerState.FAILED;
        log.log(System.Logger.Level.ERROR, "mcp server '" + config.id() + "' failed: " + failedReason);
    }

    /** tools/list（含分页游标跟进；页数上限 fail loud——不静默截断清单）。 */
    private List<FrozenTool> fetchTools(HttpRpcSession active) {
        List<FrozenTool> tools = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = active.request("tools/list", params, config.timeout(), AbortSignal.never());
            JsonNode list = result.get("tools");
            if (list == null || !list.isArray()) {
                throw new McpProtocolException("mcp server '" + config.id()
                    + "' tools/list result missing 'tools' array");
            }
            for (JsonNode tool : list) {
                tools.add(parseTool(tool, seen));
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull()) {
                return tools;
            }
            if (!next.isTextual() || next.asText().isEmpty()) {
                throw new McpProtocolException("mcp server '" + config.id()
                    + "' tools/list 'nextCursor' must be a non-empty string");
            }
            cursor = next.asText();
        }
        throw new McpProtocolException("mcp server '" + config.id()
            + "' tools/list pagination exceeds " + MAX_LIST_PAGES + " pages");
    }

    /**
     * 单条工具校验（raw 名非空——含 "__"/非法字符/超长不拒，由消费面确定性归一；
     * 重名 fail loud；inputSchema 必填——畸形不静默）。
     */
    private FrozenTool parseTool(JsonNode node, Set<String> seen) {
        if (!node.isObject()) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' tools/list entry is not an object: " + node);
        }
        JsonNode nameNode = node.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isEmpty()) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' tools/list entry missing 'name': " + node);
        }
        String name = nameNode.asText();
        if (!seen.add(name)) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' lists duplicate tool '" + name + "'");
        }
        JsonNode descriptionNode = node.get("description");
        String description = "";
        if (descriptionNode != null && !descriptionNode.isNull()) {
            if (!descriptionNode.isTextual()) {
                throw new McpProtocolException("mcp server '" + config.id() + "' tool '" + name
                    + "' description must be a string");
            }
            description = descriptionNode.asText();
        }
        JsonNode schema = node.get("inputSchema");
        if (schema == null || !schema.isObject()) {
            throw new McpProtocolException("mcp server '" + config.id() + "' tool '" + name
                + "' missing 'inputSchema' object");
        }
        return new FrozenTool(name, description, schema);
    }

    /** 清单内容比较：按名比对（description + schema 语义相等；顺序不算变更）。 */
    private static boolean sameCatalog(List<FrozenTool> expected, List<FrozenTool> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        Map<String, FrozenTool> actualByName = index(actual);
        for (FrozenTool tool : expected) {
            FrozenTool other = actualByName.get(tool.name());
            if (other == null || !tool.description().equals(other.description())
                    || !tool.schema().equals(other.schema())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, FrozenTool> index(List<FrozenTool> tools) {
        Map<String, FrozenTool> byName = new LinkedHashMap<>();
        for (FrozenTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        return byName;
    }

    /** 同代发布：工具快照与描述符一次性构建（读侧 volatile 读一次拿全，无撕裂）。 */
    private Catalog snapshot(long generation, List<FrozenTool> tools) {
        List<FrozenTool> frozen = List.copyOf(tools);
        return new Catalog(generation, frozen, frozen.stream()
            .map(tool -> new McpToolDescriptor(config.id(), tool.name(), tool.description(),
                tool.schema().toString()))
            .toList());
    }

    private McpCallResult toCallResult(JsonNode result) {
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' tools/call result missing 'content' array");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if (!block.isObject() || !block.path("type").isTextual()) {
                throw new McpProtocolException("mcp server '" + config.id()
                    + "' tools/call content block missing 'type': " + block);
            }
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                JsonNode blockText = block.get("text");
                if (blockText == null || !blockText.isTextual()) {
                    throw new McpProtocolException("mcp server '" + config.id()
                        + "' tools/call text block missing 'text'");
                }
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(blockText.asText());
            } else {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append('[').append(type).append(" content omitted]");
            }
        }
        JsonNode isError = result.get("isError");
        if (isError != null && !isError.isNull() && !isError.isBoolean()) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' tools/call result 'isError' must be boolean");
        }
        return new McpCallResult(text.toString(), isError != null && isError.asBoolean());
    }

    private static JsonNode parseArguments(String argumentsJson) {
        try {
            JsonNode node = JSON.readTree(argumentsJson);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("mcp tool arguments must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mcp tool arguments are not valid JSON", e);
        }
    }

    /** 会话启动时的冻结工具条目（schema 语义比较面；descriptor 文本由其 toString 派生）。 */
    private record FrozenTool(String name, String description, JsonNode schema) {

        private FrozenTool {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /** 同代清单快照（代际 + 冻结工具 + 对外描述符；不可变，整体替换发布）。 */
    private record Catalog(long generation, List<FrozenTool> tools,
                           List<McpToolDescriptor> descriptors) {

        private Catalog {
            Objects.requireNonNull(tools, "tools");
            Objects.requireNonNull(descriptors, "descriptors");
        }
    }
}
