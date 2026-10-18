package io.javanatic.harness.mcp.stdio;

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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单 server 的 stdio 连接（子进程 + JSON-RPC 会话 + 清单代际 + 有限重连，it27.1）。
 * 状态机：CONNECTED --断线--> RECONNECTING --（退避 ×N，逐次重建 + 按新清单换代）-->
 * CONNECTED，或 --（退避耗尽）--> FAILED。
 * <p>
 * 清单语义（T4 重裁）：{@link #tools()} 是当前已知清单，{@link #catalogGeneration()}
 * 在内容（名称 / description / schema）变化时递增；通知
 * {@code notifications/tools/list_changed} 触发异步重取（合并重复通知），重连握手
 * 也以新清单换代——两者都不再把清单变化终态化。刷新失败分档：网络 / 超时 WARN
 * 保留旧代；协议畸形按连接断裂处理（重连，不静默吞）。轮屏障采纳在消费面。
 * <p>
 * 装配期启动：{@link #start} 同步完成 initialize 协商（协议版本钉 2026-07-28）与
 * tools/list，失败即 fail loud（装配失败，不留半装配）。进程树回收随
 * {@link #close()}（scope close 联动）。
 */
final class StdioMcpConnection implements McpServerConnection {

    /** 协议版本钉 dsh 同口径（design 增量；协商不一致 fail loud）。 */
    static final String PROTOCOL_VERSION = "2026-07-28";

    private static final String CLIENT_NAME = "javanatic-harness";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LIST_PAGES = 100;
    /** env 清洗的缺省最小环境（缺省即此三项；行配置 env 白名单叠加其上）。 */
    private static final List<String> MINIMAL_ENV = List.of("PATH", "HOME", "TMPDIR");

    private final McpServerConfig config;
    private final Duration[] backoff;
    private final System.Logger log;
    /** 当前清单快照（代际 / 冻结工具 / 描述符同代发布；volatile 读一次拿全）。 */
    private volatile Catalog catalog = new Catalog(0, List.of(), List.of());
    private volatile JsonRpcPeer peer;
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

    private StdioMcpConnection(McpServerConfig config, Duration[] backoff) {
        this.config = config;
        this.backoff = backoff;
        this.log = System.getLogger("io.javanatic.harness.mcp.stdio." + config.id());
    }

    /**
     * 装配期启动：拉起子进程并同步完成握手与清单快照。
     *
     * @param config server 配置
     * @param backoff 重连退避档（发射间隔；数组长度 = 最大尝试次数）
     * @return 已连接的连接面
     * @throws McpConnectionException 进程无法启动或起步握手失败（装配 fail loud）
     * @throws McpProtocolException 握手期协议畸形（同上）
     */
    static StdioMcpConnection start(McpServerConfig config, Duration[] backoff) {
        StdioMcpConnection connection = new StdioMcpConnection(config, backoff);
        JsonRpcPeer peer = connection.connectProcess();
        List<FrozenTool> tools;
        try {
            tools = connection.handshake(peer);
        } catch (RuntimeException e) {
            peer.close(); // 启动失败:回收已拉起进程,不留半装配
            throw e;
        }
        connection.catalog = connection.snapshot(1, tools);
        synchronized (connection) {
            connection.peer = peer;
            connection.started = true;
            if (peer.isBroken()) {
                // 握手后、发布前进程已退出:按运行期断线处理(重连;按新清单换代)
                connection.state = McpServerState.RECONNECTING;
                connection.reconnector = Thread.ofVirtual()
                    .name("jh-mcp-" + config.id() + "-reconnect").start(connection::reconnectLoop);
            } else {
                connection.state = McpServerState.CONNECTED;
                connection.log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id()
                    + "' connected: " + tools.size() + " tools (generation 1)");
            }
        }
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
        JsonRpcPeer active = peer;
        if (active == null || active.isBroken()) {
            // 断裂已判定但 handleBroken 尚未落状态(RECONNECTING)的窗口:同样快速失败,
            // 不得把调用漂到待弃置的会话上
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
     * 置 FAILED、停重连/刷新、关会话（级联失败在途、击杀进程树）。幂等。 */
    void close() {
        JsonRpcPeer active;
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
            active = peer;
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
            active.close();
        }
    }

    /** 会话断裂回调（每 peer 至多一次；启动完成前不触发——装配错误走同步路径）。 */
    private void handleBroken(JsonRpcPeer brokenPeer) {
        synchronized (this) {
            if (closed || !started || peer != brokenPeer || state != McpServerState.CONNECTED) {
                return;
            }
            state = McpServerState.RECONNECTING;
            log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                + "' connection lost; reconnecting (up to " + backoff.length + " attempts)");
            reconnector = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-reconnect")
                .start(this::reconnectLoop);
        }
    }

    /** 通知回调（读线程只置位/拉线程，不阻塞帧读取）：刷新由虚拟线程异步执行并合并。 */
    private void handleListChanged(JsonRpcPeer source) {
        synchronized (this) {
            if (closed || peer != source || state != McpServerState.CONNECTED) {
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
            JsonRpcPeer active;
            synchronized (this) {
                if (closed || !refreshRequested) {
                    refresher = null;
                    return;
                }
                refreshRequested = false;
                active = peer;
            }
            if (active == null || active.isBroken()) {
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

    /** 采纳通知刷新结果：仅当仍指向同一 peer 且内容变化才换代（顺序变化不算）。 */
    private void adoptRefresh(JsonRpcPeer source, List<FrozenTool> tools) {
        synchronized (this) {
            if (closed || peer != source) {
                return; // 结果已过期（重连换了 peer）
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

    /** 有限重连：退避逐档重试（重建进程 + 握手）；清单变化按新代发布，不再终态化。
     * 断裂方会话（stale）一切退出路径都回收——成功换到新 peer 后它不再被任何引用
     * 持有，不回收就是孤儿进程。 */
    private void reconnectLoop() {
        JsonRpcPeer stale = peer;
        for (int round = 0; round < backoff.length; round++) {
            attempt = round + 1;
            if (!pauseFor(backoff[round])) {
                return; // 已关闭/中断:终止重连(闭合会话由 close() 负责)
            }
            JsonRpcPeer tentative = null;
            try {
                tentative = connectProcess();
                List<FrozenTool> current = handshake(tentative);
                boolean adopt;
                synchronized (this) {
                    adopt = !closed && !tentative.isBroken();
                    if (adopt) {
                        Catalog previous = catalog;
                        long generation = sameCatalog(previous.tools(), current)
                            ? previous.generation() : previous.generation() + 1;
                        catalog = snapshot(generation, current);
                        peer = tentative;
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
                    tentative.close(); // 锁外回收:close 要 join 读线程,读线程可能正等本锁
                    if (closed) {
                        return;
                    }
                    continue; // 重连期内再次断线:本档作废,下一档继续
                }
                closeStale(stale);
                log.log(System.Logger.Level.INFO, "mcp server '" + config.id() + "' reconnected ("
                    + current.size() + " tools, generation " + catalogGeneration() + ")");
                return;
            } catch (RuntimeException e) {
                if (tentative != null) {
                    tentative.close();
                }
                log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id() + "' reconnect attempt "
                    + attempt + "/" + backoff.length + " failed: " + e.getMessage());
            }
        }
        closeStale(stale);
        failExhausted();
    }

    private static void closeStale(JsonRpcPeer stale) {
        if (stale != null) {
            stale.close();
        }
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

    /** 启动/重连握手：initialize 协商（版本钉）+ initialized 通知 + tools/list 快照。 */
    private List<FrozenTool> handshake(JsonRpcPeer peer) {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", JSON.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", clientVersion());
        JsonNode result = peer.request("initialize", params, config.timeout(), AbortSignal.never());
        JsonNode versionNode = result.get("protocolVersion");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' initialize result missing 'protocolVersion'");
        }
        if (!PROTOCOL_VERSION.equals(versionNode.asText())) {
            throw new McpProtocolException("mcp server '" + config.id() + "' negotiated protocol version '"
                + versionNode.asText() + "', expected '" + PROTOCOL_VERSION + "'");
        }
        peer.notify("notifications/initialized", null);
        return fetchTools(peer);
    }

    /** tools/list（含分页游标跟进；页数上限 fail loud——不静默截断清单）。 */
    private List<FrozenTool> fetchTools(JsonRpcPeer peer) {
        List<FrozenTool> tools = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = peer.request("tools/list", params, config.timeout(), AbortSignal.never());
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

    private JsonRpcPeer connectProcess() {
        Process process;
        try {
            process = spawn(config);
        } catch (IOException e) {
            throw new McpConnectionException("failed to start mcp server '" + config.id() + "': "
                + e.getMessage(), e);
        }
        return new JsonRpcPeer(process, config.id(), this::handleBroken, this::handleListChanged);
    }

    /** env 清洗（R4 面）：不继承全量、不做黑名单——缺省最小环境 + 行配置白名单叠加。 */
    private static Process spawn(McpServerConfig config) throws IOException {
        List<String> argv = new ArrayList<>(config.args().size() + 1);
        argv.add(config.command());
        argv.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(argv);
        Map<String, String> environment = builder.environment();
        environment.clear();
        for (String name : MINIMAL_ENV) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.putAll(config.env());
        return builder.start();
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

    private static String clientVersion() {
        String version = McpStdioPlugin.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
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
