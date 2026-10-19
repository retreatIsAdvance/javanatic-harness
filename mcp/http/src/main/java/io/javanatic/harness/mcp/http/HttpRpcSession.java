package io.javanatic.harness.mcp.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpTimeoutException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 单个 Streamable HTTP 会话（it27.1）：POST JSON-RPC（JSON 或单请求 SSE 响应）、
 * {@code Mcp-Session-Id} 捕获/回传、GET SSE 通知通道、DELETE 收尾。
 *
 * <p>帧安全（T7）：响应体与单条 SSE 事件都有 {@link #MAX_FRAME_BYTES} 上限；
 * 超限按该请求的协议失败处理（不无界读），stdio 侧对应行为是断链重连。
 * {@link HttpRequest#timeout} 只覆盖到响应头——响应体阶段另设看门狗：超时或取消
 * 都会关闭流以唤醒阻塞读（JDK HttpClient 已知行为）。取消另外尽力 POST
 * {@code notifications/cancelled}。
 *
 * <p>错误分档与 stdio 同口径：404 = 会话失效（连接层重连）；协议畸形 = 会话
 * 不可信（级联断裂）；3xx（{@code Redirect.NEVER} 后的意外重定向）与其余
 * 非 2xx = 连接面失败；JSON-RPC error 成员 = 该调用协议失败。
 */
final class HttpRpcSession implements Closeable {

    /** 协议版本钉 dsh 同口径（与 stdio 一致；协商不一致 fail loud）。 */
    static final String PROTOCOL_VERSION = "2026-07-28";

    /** 单帧安全上限（T7）：响应体与单条 SSE 事件共用；与消费面 256 KiB 分层。 */
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT_SLICE = Duration.ofMillis(20);
    private static final int ABBREVIATE_LIMIT = 200;

    private final McpHttpServerConfig config;
    private final HttpClient client;
    private final System.Logger log;
    private final BiConsumer<HttpRpcSession, McpConnectionException> onBroken;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean invalid = new AtomicBoolean();
    private volatile String sessionId;
    private volatile boolean initialized;
    private volatile Runnable onListChangedHook;
    private volatile InputStream notificationStream;
    private volatile Thread notificationThread;

    private HttpRpcSession(McpHttpServerConfig config, HttpClient client,
                           BiConsumer<HttpRpcSession, McpConnectionException> onBroken) {
        this.config = config;
        this.client = client;
        this.onBroken = onBroken;
        this.log = System.getLogger("io.javanatic.harness.mcp.http." + config.id());
    }

    /**
     * 建会话：initialize 协商 + initialized 通知。失败即 fail loud（调用方收口）。
     *
     * @throws McpConnectionException 端点不可达 / 会话头非法
     * @throws McpProtocolException 握手响应畸形 / 版本不一致
     */
    static HttpRpcSession open(McpHttpServerConfig config, HttpClient client,
                               BiConsumer<HttpRpcSession, McpConnectionException> onBroken) {
        HttpRpcSession session = new HttpRpcSession(config, client, onBroken);
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", JSON.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "javanatic-harness");
        clientInfo.put("version", clientVersion());
        JsonNode result = session.request("initialize", params, config.timeout(), AbortSignal.never());
        JsonNode versionNode = result.get("protocolVersion");
        if (versionNode == null || !versionNode.isTextual()) {
            session.closeBestEffort();
            throw new McpProtocolException("mcp server '" + config.id()
                + "' initialize result missing 'protocolVersion'");
        }
        if (!PROTOCOL_VERSION.equals(versionNode.asText())) {
            session.closeBestEffort();
            throw new McpProtocolException("mcp server '" + config.id() + "' negotiated protocol version '"
                + versionNode.asText() + "', expected '" + PROTOCOL_VERSION + "'");
        }
        session.notify("notifications/initialized", null);
        session.initialized = true;
        return session;
    }

    String id() {
        return config.id();
    }

    String sessionId() {
        return sessionId;
    }

    boolean isInvalid() {
        return invalid.get() || closed.get();
    }

    /** 主 POST SSE 夹带的通知与 GET 通道共用回调（连接层设置；可空）。 */
    void onListChanged(Runnable hook) {
        this.onListChangedHook = hook;
    }

    /**
     * 发一个请求并阻塞等待响应（可中断；超时与取消即失败，不续等）。
     *
     * @throws McpTimeoutException 超时（响应头或响应体阶段）
     * @throws McpProtocolException 畸形响应 / JSON-RPC error 成员 / 缺 result / 帧超限
     * @throws McpConnectionException 会话失效（404）或连接面失败
     * @throws AbortedException 已取消或等待中被取消（正常路径，向上传播）
     */
    JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) {
        if (isInvalid()) {
            throw new McpConnectionException("mcp server '" + config.id() + "' session is not usable");
        }
        signal.checkAbort();
        long id = nextId.incrementAndGet();
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        String body;
        try {
            body = JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mcp request serialization failed", e);
        }
        HttpRequest request = requestBuilder(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        AtomicBoolean inFlight = new AtomicBoolean(true);
        signal.onCancel(() -> {
            if (inFlight.compareAndSet(true, false)) {
                notifyCancelled(id);
            }
        });
        try {
            HttpResponse<InputStream> response = execute(request, method, timeout, signal);
            try (BodyGuard guard = new BodyGuard(response.body(), method, timeout, signal)) {
                checkStatus(response, method);
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (contentType.startsWith("application/json")) {
                    return unwrapResponse(readJson(guard), id, method);
                }
                if (contentType.startsWith("text/event-stream")) {
                    return readEventStream(guard, id, method);
                }
                throw protocol(method, "unsupported response Content-Type: " + contentType);
            }
        } finally {
            inFlight.set(false);
        }
    }

    /** 单向通知（无响应；202/204 为正常，其余按连接面失败抛出）。 */
    void notify(String method, ObjectNode params) {
        if (isInvalid()) {
            return;
        }
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        String body;
        try {
            body = JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mcp notification serialization failed", e);
        }
        HttpRequest request = requestBuilder(config.timeout())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<InputStream> response = execute(request, method, config.timeout(), AbortSignal.never());
        try {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new McpConnectionException("mcp '" + method + "' to '" + config.id()
                    + "' failed: HTTP " + status);
            }
        } finally {
            closeQuietly(response.body());
        }
    }

    /** 打开 GET SSE 通知通道（尽力而为：405/网络失败降级 WARN，不影响主连接）。 */
    void startNotifications(Runnable onListChanged) {
        if (isInvalid()) {
            return;
        }
        if (sessionId == null) {
            log.log(System.Logger.Level.INFO, "mcp server '" + config.id()
                + "' issued no Mcp-Session-Id; notification channel disabled");
            return;
        }
        notificationThread = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-http-notify")
            .start(() -> notificationLoop(onListChanged));
    }

    private void notificationLoop(Runnable onListChanged) {
        HttpRequest request;
        try {
            request = requestBuilder(null)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                + "' notification channel setup failed: " + e.getMessage());
            return;
        }
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 405) {
                closeQuietly(response.body());
                log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                    + "' does not offer a GET notification channel (HTTP 405);"
                    + " catalog changes are reconciled on reconnect");
                return;
            }
            if (status == 404) {
                closeQuietly(response.body());
                invalidate(new McpConnectionException("mcp server '" + config.id()
                    + "' session expired on notification channel (HTTP 404)"));
                return;
            }
            if (status != 200) {
                closeQuietly(response.body());
                log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                    + "' notification channel returned HTTP " + status + "; degraded");
                return;
            }
            try (InputStream body = response.body()) {
                notificationStream = body;
                BoundedLineReader reader = new BoundedLineReader(body, MAX_FRAME_BYTES);
                String event = null;
                StringBuilder data = new StringBuilder();
                long eventBytes = 0;
                String line;
                while (!isInvalid() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatchNotification(event, data, onListChanged);
                        event = null;
                        data.setLength(0);
                        eventBytes = 0;
                    } else if (line.startsWith(":")) {
                        // SSE comment/keepalive
                    } else if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        String payload = line.substring("data:".length()).stripLeading();
                        eventBytes += (data.isEmpty() ? 0 : 1)
                            + payload.getBytes(StandardCharsets.UTF_8).length;
                        if (eventBytes > MAX_FRAME_BYTES) {
                            throw new McpProtocolException("mcp server '" + config.id()
                                + "' notification event exceeds " + MAX_FRAME_BYTES + " bytes");
                        }
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(payload);
                    }
                }
                if (!isInvalid() && !closed.get()) {
                    log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                        + "' notification channel closed; degraded (no polling)");
                }
            }
        } catch (IOException e) {
            if (!isInvalid() && !closed.get()) {
                log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                    + "' notification channel failed: " + e.getMessage() + "; degraded");
            }
        } catch (McpProtocolException e) {
            invalidate(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            notificationStream = null;
        }
    }

    private void dispatchNotification(String event, StringBuilder data, Runnable onListChanged) {
        if (data.isEmpty()) {
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data.toString());
        } catch (JsonProcessingException e) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' sent a malformed SSE event: " + abbreviate(data), e);
        }
        if (node == null || !node.isObject()) {
            throw new McpProtocolException("mcp server '" + config.id()
                + "' sent a non-object SSE event: " + abbreviate(data));
        }
        JsonNode methodNode = node.get("method");
        if (methodNode != null && methodNode.isTextual()) {
            String method = methodNode.asText();
            if ("notifications/tools/list_changed".equals(method)) {
                runListChangedHook(onListChanged);
            } else if (node.get("id") != null) {
                respondToServerRequest(node);
            }
            return;
        }
        log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id()
            + "' notification event '" + event + "' ignored");
    }

    private void runListChangedHook(Runnable hook) {
        if (hook == null) {
            return;
        }
        try {
            hook.run(); // 回调不得杀死通知读线程（一次坏刷新不拖垮通道可观测性）
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                + "' list_changed callback failed: " + e.getMessage());
        }
    }

    /** server→client 请求不得悬挂：ping 空结果，其余 -32601（尽力 POST 响应）。 */
    private void respondToServerRequest(JsonNode request) {
        JsonNode idNode = request.get("id");
        String method = request.path("method").asText("");
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode);
        if ("ping".equals(method)) {
            response.set("result", JSON.createObjectNode());
        } else {
            ObjectNode error = response.putObject("error");
            error.put("code", -32601);
            error.put("message", "method not found: " + method);
        }
        postMessage(response);
    }

    /** 尽力异步 POST 一条 client→server 报文（server 请求的应答 / cancelled 通知）。 */
    private void postMessage(ObjectNode message) {
        if (isInvalid()) {
            return;
        }
        String body;
        try {
            body = JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.log(System.Logger.Level.WARNING, "mcp server '" + config.id()
                + "' response serialization failed: " + e.getMessage());
            return;
        }
        Thread.ofVirtual().name("jh-mcp-" + config.id() + "-http-reply").start(() -> {
            HttpRequest request = requestBuilder(config.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            try {
                HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
                closeQuietly(response.body());
            } catch (IOException e) {
                log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id()
                    + "' response POST failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 取消是正常路径：尽力让远端撤下在途请求（副作用不重复执行）。 */
    private void notifyCancelled(long id) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", "notifications/cancelled");
        ObjectNode params = message.putObject("params");
        params.put("requestId", id);
        params.put("reason", "cancelled");
        postMessage(message);
    }

    private JsonNode readEventStream(BodyGuard guard, long id, String method) {
        BoundedLineReader reader = new BoundedLineReader(guard.body(), MAX_FRAME_BYTES);
        StringBuilder data = new StringBuilder();
        long eventBytes = 0;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    JsonNode response = eventResponse(data, id, method);
                    if (response != null) {
                        return response;
                    }
                    data.setLength(0);
                    eventBytes = 0;
                } else if (line.startsWith(":")) {
                    // keepalive
                } else if (line.startsWith("data:")) {
                    String payload = line.substring("data:".length()).stripLeading();
                    eventBytes += (data.isEmpty() ? 0 : 1) + payload.getBytes(StandardCharsets.UTF_8).length;
                    if (eventBytes > MAX_FRAME_BYTES) {
                        throw protocol(method, "response event exceeds " + MAX_FRAME_BYTES + " bytes");
                    }
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(payload);
                }
                // event:/id:/retry: are not needed for response matching
            }
            McpConnectionException ended = new McpConnectionException("mcp '" + method + "' on '"
                + config.id() + "' ended without a matching response");
            invalidate(ended);
            throw ended;
        } catch (IOException e) {
            throw readFailure(guard, e);
        }
    }

    /** SSE 事件 → 响应（匹配 id）或旁路通知；返回 null 表示继续读。 */
    private JsonNode eventResponse(StringBuilder data, long id, String method) {
        if (data.isEmpty()) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data.toString());
        } catch (JsonProcessingException e) {
            throw protocol(method, "malformed SSE response: " + abbreviate(data), e);
        }
        if (node == null || !node.isObject()) {
            throw protocol(method, "non-object SSE response: " + abbreviate(data));
        }
        JsonNode idNode = node.get("id");
        if (idNode != null && idNode.isNumber() && idNode.asLong() == id) {
            return validateResponse(node, id, method);
        }
        JsonNode methodNode = node.get("method");
        if (methodNode != null && methodNode.isTextual()) {
            if ("notifications/tools/list_changed".equals(methodNode.asText())) {
                // 主 POST 的 SSE 也可能夹带通知——与 GET 通道同一处理
                runListChangedHook(onListChangedHook);
            } else if (idNode != null) {
                respondToServerRequest(node);
            }
        }
        return null;
    }

    private JsonNode readJson(BodyGuard guard) {
        byte[] bytes = readBounded(guard);
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw protocol(guard.method(), "malformed response: "
                + abbreviate(new String(bytes, StandardCharsets.UTF_8)), e);
        }
    }

    private byte[] readBounded(BodyGuard guard) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = guard.body().read(buffer)) != -1) {
                if (out.size() + read > MAX_FRAME_BYTES) {
                    throw protocol(guard.method(), "response exceeds " + MAX_FRAME_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw readFailure(guard, e);
        }
    }

    /** 体读失败分档：取消（正常路径）> 超时 > 连接面失败（级联断裂）。 */
    private RuntimeException readFailure(BodyGuard guard, IOException cause) {
        if (guard.cancelled.get()) {
            return new AbortedException("mcp '" + guard.method() + "' on '" + config.id() + "' cancelled");
        }
        if (guard.timedOut.get()) {
            return timeout(guard.method(), guard.timeout());
        }
        return connectionFailure(guard.method(), "response stream failed", cause);
    }

    private JsonNode validateResponse(JsonNode node, long id, String method) {
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            throw protocol(method, "rpc error: " + abbreviate(error));
        }
        JsonNode result = node.get("result");
        if (result == null) {
            throw protocol(method, "response has neither result nor error (id " + id + ")");
        }
        return result;
    }

    private JsonNode unwrapResponse(JsonNode node, long id, String method) {
        if (node == null || !node.isObject()) {
            throw protocol(method, "non-object response: " + abbreviate(node));
        }
        JsonNode idNode = node.get("id");
        if (idNode == null || !idNode.isNumber() || idNode.asLong() != id) {
            throw protocol(method, "unexpected response id (expected " + id + ", got " + idNode + ")");
        }
        return validateResponse(node, id, method);
    }

    private HttpRequest.Builder requestBuilder(Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.url());
        if (timeout != null) {
            builder.timeout(timeout);
        }
        config.headers().forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (initialized) {
            builder.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        return builder;
    }

    private HttpResponse<InputStream> execute(HttpRequest request, String method, Duration timeout,
                                              AbortSignal signal) {
        CompletableFuture<HttpResponse<InputStream>> future =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-http-timeout")
            .start(() -> {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    return;
                }
                if (!future.isDone()) {
                    timedOut.set(true);
                    future.cancel(true);
                }
            });
        signal.onCancel(() -> future.cancel(true));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!future.isDone()) {
                signal.checkAbort();
                if (System.nanoTime() - deadline >= 0) {
                    timedOut.set(true);
                    future.cancel(true);
                    throw timeout(method, timeout);
                }
                try {
                    Thread.sleep(WAIT_SLICE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    throw new McpConnectionException("mcp '" + method + "' on '" + config.id()
                        + "' interrupted");
                }
            }
            HttpResponse<InputStream> response;
            try {
                response = future.join();
            } catch (CompletionException | CancellationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                // JDK 自带的响应头阶段超时与看门狗同时在场：HttpTimeoutException 同归超时档，
                // 不按连接面失败处理（超时是可用性事实，不失效会话、不触发重连）
                if (timedOut.get() || cause instanceof HttpTimeoutException) {
                    throw timeout(method, timeout);
                }
                signal.checkAbort(); // 取消 → AbortedException（正常路径）
                throw connectionFailure(method, "request failed", cause);
            }
            if (response.statusCode() == 404) {
                McpConnectionException expired = new McpConnectionException("mcp server '"
                    + config.id() + "' session expired (HTTP 404)");
                closeQuietly(response.body());
                invalidate(expired);
                throw expired;
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                closeQuietly(response.body());
                throw new McpConnectionException("mcp '" + method + "' to '" + config.id()
                    + "' got an unexpected HTTP redirect (" + response.statusCode()
                    + "); redirects are disabled");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new McpConnectionException("mcp '" + method + "' to '" + config.id()
                    + "' failed: HTTP " + response.statusCode());
            }
            captureSessionId(response);
            return response;
        } finally {
            watchdog.interrupt();
        }
    }

    private void captureSessionId(HttpResponse<InputStream> response) {
        String issued = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (issued == null || issued.isEmpty()) {
            return;
        }
        if (sessionId == null) {
            sessionId = issued;
            log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id()
                + "' issued session id (captured)");
        } else if (!sessionId.equals(issued)) {
            closeQuietly(response.body());
            throw new McpProtocolException("mcp server '" + config.id()
                + "' changed Mcp-Session-Id within a session");
        }
    }

    private void checkStatus(HttpResponse<InputStream> response, String method) {
        int status = response.statusCode();
        if (status == 202 || status == 204) {
            throw protocol(method, "response expected but got HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            throw new McpConnectionException("mcp '" + method + "' to '" + config.id()
                + "' failed: HTTP " + status);
        }
    }

    private McpProtocolException protocol(String method, String detail) {
        return new McpProtocolException("mcp '" + method + "' on '" + config.id() + "': " + detail);
    }

    private McpProtocolException protocol(String method, String detail, Throwable cause) {
        return new McpProtocolException("mcp '" + method + "' on '" + config.id() + "': " + detail, cause);
    }

    private McpConnectionException connectionFailure(String method, String detail, Throwable cause) {
        McpConnectionException failure = new McpConnectionException("mcp '" + method + "' on '"
            + config.id() + "' " + detail + ": " + cause.getMessage(), cause);
        invalidate(failure);
        return failure;
    }

    private McpTimeoutException timeout(String method, Duration limit) {
        return new McpTimeoutException("mcp '" + method + "' on '" + config.id()
            + "' timed out after " + limit.toSeconds() + "s");
    }

    /** 外部判定的会话级失败（如清单刷新协议畸形）：按失效处理（级联 + 重连）。 */
    void invalidate(McpConnectionException cause) {
        if (invalid.compareAndSet(false, true)) {
            onBroken.accept(this, cause);
        }
    }

    /** 关闭会话：关通知流 → 尽力 DELETE → 不再接受新请求。幂等。 */
    void closeBestEffort() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(notificationStream);
        Thread thread = notificationThread;
        if (thread != null) {
            thread.interrupt();
        }
        if (sessionId != null) {
            HttpRequest request = requestBuilder(Duration.ofSeconds(2)).DELETE().build();
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                log.log(System.Logger.Level.DEBUG, "mcp server '" + config.id()
                    + "' DELETE failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        closeBestEffort();
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败无补救路径（连接已不可信）
        }
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= ABBREVIATE_LIMIT ? text : text.substring(0, ABBREVIATE_LIMIT) + "…";
    }

    private static String clientVersion() {
        String version = HttpRpcSession.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** 响应体看门狗：{@code HttpRequest.timeout} 只管到响应头，体读超时/取消靠关流唤醒。 */
    private final class BodyGuard implements Closeable {

        private final InputStream body;
        private final String method;
        private final Duration timeout;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Thread watchdog;

        BodyGuard(InputStream body, String method, Duration timeout, AbortSignal signal) {
            this.body = body;
            this.method = method;
            this.timeout = timeout;
            signal.onCancel(this::abortForCancel);
            this.watchdog = Thread.ofVirtual().name("jh-mcp-" + config.id() + "-http-body-timeout")
                .start(this::watch);
        }

        InputStream body() {
            return body;
        }

        String method() {
            return method;
        }

        Duration timeout() {
            return timeout;
        }

        private void watch() {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                return;
            }
            if (finished.compareAndSet(false, true)) {
                timedOut.set(true);
                closeQuietly(body);
            }
        }

        private void abortForCancel() {
            if (finished.compareAndSet(false, true)) {
                cancelled.set(true);
                closeQuietly(body);
            }
        }

        @Override
        public void close() {
            finished.set(true);
            watchdog.interrupt();
            closeQuietly(body);
        }
    }

    /** 有界行读（UTF-8；单行字节上限 = T7 帧帽；超限抛协议异常）。 */
    static final class BoundedLineReader implements Closeable {

        private final BufferedInputStream in;
        private final int maxBytes;

        BoundedLineReader(InputStream in, int maxBytes) {
            this.in = new BufferedInputStream(in, 8192);
            this.maxBytes = maxBytes;
        }

        /** @return 一行（不含换行；EOF 返回 null） */
        String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int next;
            while ((next = in.read()) != -1) {
                if (next == '\n') {
                    return stripCr(line);
                }
                if (line.size() >= maxBytes) {
                    throw new McpProtocolException("mcp frame exceeds " + maxBytes + " bytes");
                }
                line.write(next);
            }
            return line.size() == 0 ? null : stripCr(line);
        }

        private static String stripCr(ByteArrayOutputStream line) {
            byte[] bytes = line.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
