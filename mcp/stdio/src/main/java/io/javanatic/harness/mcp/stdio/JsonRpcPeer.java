package io.javanatic.harness.mcp.stdio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpTimeoutException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单个 MCP server 进程上的最小 JSON-RPC 2.0 会话（换行分帧，it27）：
 * 请求/响应按 id 关联（pending 表），通知单向；server→client 请求不悬挂远端
 * （ping 答空结果，其余 -32601）。错误分档：
 * <ul>
 *   <li>非法 JSON 帧 = 残帧后流不可再信 → 全部 pending 协议级联失败 +
 *       通知连接层重建（重连）</li>
 *   <li>匹配 id 但缺 result/error 或带 error 成员 → 该调用协议失败（流仍可用）</li>
 *   <li>错 id / 无 id 响应 → 记日志丢弃；对应调用按自身超时收口（不假装成功）</li>
 * </ul>
 * 取消/超时 → 撤销 pending + 尽力发 notifications/cancelled（异步，取消方
 * 阻塞面上不做管道写）；晚到响应按已撤销静默丢弃（有界记忆）。
 */
final class JsonRpcPeer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT_SLICE = Duration.ofMillis(20);
    private static final int CANCELLED_MEMORY = 1024;
    private static final int ABBREVIATE_LIMIT = 200;

    /** 单帧安全上限（T7）：超限协议失败并按会话断裂处理；与消费面 256 KiB 分层。 */
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private final Process process;
    private final String serverId;
    private final System.Logger log;
    private final Consumer<JsonRpcPeer> onBroken;
    private final Consumer<JsonRpcPeer> onListChanged;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private final Map<Long, PendingSlot> pending = new ConcurrentHashMap<>();
    /** 已撤销请求 id 的有界记忆（晚到响应静默丢弃的依据；仅撤销线程与读线程触碰）。 */
    private final ArrayDeque<Long> cancelledIds = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean broken = new AtomicBoolean();
    private final Thread reader;
    private final Thread stderrDrain;
    private volatile boolean closed;

    /** 一次在途请求：响应到达（或失败）时完成的槽位。 */
    private static final class PendingSlot {
        private final CompletableFuture<JsonNode> future = new CompletableFuture<>();
    }

    /**
     * @param process 已启动的 server 进程（stdin/stdout/stderr 为管道）
     * @param serverId server id（日志与诊断定位）
     * @param onBroken 会话断裂回调（每 peer 至多一次；调用线程 = 读线程或写失败线程）
     * @param onListChanged 清单变化通知回调（调用线程 = 读线程；实现方不得阻塞帧读取）
     */
    JsonRpcPeer(Process process, String serverId, Consumer<JsonRpcPeer> onBroken,
                Consumer<JsonRpcPeer> onListChanged) {
        this.process = process;
        this.serverId = serverId;
        this.onBroken = onBroken;
        this.onListChanged = onListChanged;
        this.log = System.getLogger("io.javanatic.harness.mcp.stdio." + serverId);
        this.writer = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Thread.ofVirtual().name("jh-mcp-" + serverId + "-reader").start(this::readLoop);
        this.stderrDrain = Thread.ofVirtual().name("jh-mcp-" + serverId + "-stderr").start(this::drainStderr);
    }

    /** 会话是否已断裂（读侧或写侧判定；可靠性的唯一权威 = 读线程）。 */
    boolean isBroken() {
        return broken.get();
    }

    /** 外部判定的会话级失败（如清单刷新协议畸形）：按断裂处理（级联 + 重连）。 */
    void invalidate(McpConnectionException cause) {
        broke(cause);
    }

    /**
     * 发一个请求并阻塞等待响应（可中断；超时与取消即失败，不续等）。
     *
     * @param method JSON-RPC 方法名
     * @param params 参数对象（null = 空对象）
     * @param timeout 该请求的超时
     * @param signal 取消信号
     * @return result 成员（error 成员与非 JSON-RPC 报文按协议级失败抛出）
     * @throws McpTimeoutException 超时
     * @throws McpProtocolException 畸形响应（error 成员 / 缺 result）
     * @throws McpConnectionException 连接面失败（写失败、断裂级联、中断）
     * @throws AbortedException 已取消或等待中被取消（正常路径，向上传播）
     */
    JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) {
        long id = nextId.incrementAndGet();
        PendingSlot slot = new PendingSlot();
        pending.put(id, slot);
        signal.onCancel(() -> cancelPending(id, slot,
            new AbortedException("mcp '" + method + "' on '" + serverId + "' cancelled")));
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        try {
            writeLine(JSON.writeValueAsString(message));
        } catch (IOException e) {
            McpConnectionException writeFailed = new McpConnectionException("mcp '" + method + "' to '"
                + serverId + "' write failed: " + e.getMessage(), e);
            broke(writeFailed); // 通道已断:级联全部在途
            pending.remove(id, slot); // closed 竞态早于本槽入表时 failAll 未及:本槽单独收口
            slot.future.completeExceptionally(writeFailed);
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!slot.future.isDone()) {
            try {
                signal.checkAbort();
            } catch (AbortedException e) {
                // 轮询型 signal(不实现 onCancel)的唯一收口点:直接抛出则槽位泄漏 +
                // 远端悬挂 + 撤销记忆漏记(晚到响应误报 unmatched)——评审 F1;
                // onCancel 型已由注册回调收口,cancelPending 幂等去重
                cancelPending(id, slot, e);
                throw e;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                McpTimeoutException timedOut = new McpTimeoutException("mcp '" + method + "' on '"
                    + serverId + "' timed out after " + timeout.toSeconds() + "s");
                cancelPending(id, slot, timedOut);
                throw timedOut;
            }
            try {
                Thread.sleep(WAIT_SLICE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                McpConnectionException interrupted =
                    new McpConnectionException("mcp '" + method + "' on '" + serverId + "' interrupted");
                cancelPending(id, slot, interrupted);
                throw interrupted;
            }
        }
        return unwrapResult(method, joinSlot(slot));
    }

    /** 单向通知（无响应；写失败即断裂）。 */
    void notify(String method, ObjectNode params) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        try {
            writeLine(JSON.writeValueAsString(message));
        } catch (IOException e) {
            broke(new McpConnectionException("mcp '" + method + "' to '" + serverId
                + "' write failed: " + e.getMessage(), e));
        }
    }

    /** 关闭会话：置关闭、级联失败 pending、关 stdin、击杀进程树、收束读线程。 */
    void close() {
        closed = true;
        failAllPending(new McpConnectionException("mcp server '" + serverId + "' closed"));
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 进程可能已死:关闭 stdin 失败无补救路径
        }
        killTree(process);
        joinQuietly(reader);
        joinQuietly(stderrDrain);
    }

    private void cancelPending(long id, PendingSlot slot, RuntimeException failure) {
        if (pending.remove(id, slot)) {
            rememberCancelled(id);
            slot.future.completeExceptionally(failure);
            notifyCancelled(id);
        }
    }

    /** 尽力通知远端撤下在途请求；异步执行——取消方常在事件派发/审批线程，阻塞面不做管道写。 */
    private void notifyCancelled(long id) {
        if (closed) {
            return;
        }
        Thread.ofVirtual().name("jh-mcp-" + serverId + "-cancel-notify").start(() -> {
            ObjectNode params = JSON.createObjectNode();
            params.put("requestId", id);
            params.put("reason", "cancelled");
            notify("notifications/cancelled", params);
        });
    }

    private void rememberCancelled(long id) {
        synchronized (cancelledIds) {
            cancelledIds.addLast(id);
            while (cancelledIds.size() > CANCELLED_MEMORY) {
                cancelledIds.removeFirst();
            }
        }
    }

    private boolean forgetCancelled(long id) {
        synchronized (cancelledIds) {
            return cancelledIds.remove(id);
        }
    }

    private void readLoop() {
        try (BoundedLineReader lines = new BoundedLineReader(
                process.getInputStream(), MAX_FRAME_BYTES)) {
            String line;
            while ((line = lines.readLine()) != null) {
                dispatch(line);
                if (closed) {
                    return;
                }
            }
            broke(new McpConnectionException("mcp server '" + serverId + "' closed stdout (process exited)"));
        } catch (McpProtocolException e) {
            broke(e); // 超限帧:残帧后流不可再信,按会话断裂处理(重连)
        } catch (IOException e) {
            broke(new McpConnectionException("mcp server '" + serverId + "' read failed: " + e.getMessage(), e));
        }
    }

    private void dispatch(String line) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            broke(new McpProtocolException("mcp server '" + serverId + "' sent a malformed frame: "
                + abbreviate(line), e));
            return;
        }
        if (node == null || !node.isObject()) {
            broke(new McpProtocolException("mcp server '" + serverId + "' sent a non-object frame: "
                + abbreviate(line)));
            return;
        }
        ObjectNode message = (ObjectNode) node;
        JsonNode idNode = message.get("id");
        if (message.has("method")) {
            handleRemoteMessage(message, idNode);
            return;
        }
        if (idNode == null || !idNode.canConvertToLong()) {
            log.log(System.Logger.Level.WARNING,
                "mcp server '" + serverId + "' sent a response without numeric id; dropped: " + abbreviate(line));
            return;
        }
        long id = idNode.longValue();
        PendingSlot slot = pending.remove(id);
        if (slot == null) {
            if (!forgetCancelled(id)) {
                log.log(System.Logger.Level.WARNING,
                    "mcp server '" + serverId + "' sent an unmatched response id " + id + "; dropped");
            }
            return;
        }
        slot.future.complete(message);
    }

    /** server→client 报文：通知记录；请求必须应答（ping 空结果、其余 -32601——不得悬挂远端）。 */
    private void handleRemoteMessage(ObjectNode message, JsonNode idNode) {
        String method = message.path("method").asText("");
        if (method.startsWith("notifications/")) {
            if ("notifications/tools/list_changed".equals(method)) {
                // T4 重裁（it27.1）：清单变化不再是冻结/漂移——交给连接层异步重取并按代发布。
                // 回调异常不得杀死读线程（否则一次坏刷新拖垮整个会话的可观测性）
                try {
                    onListChanged.accept(this);
                } catch (RuntimeException e) {
                    log.log(System.Logger.Level.WARNING, "mcp server '" + serverId
                        + "' list_changed callback failed: " + e.getMessage());
                }
            } else {
                log.log(System.Logger.Level.DEBUG, "mcp notification '" + method + "' ignored");
            }
            return;
        }
        if (idNode == null || idNode.isNull()) {
            log.log(System.Logger.Level.WARNING,
                "mcp server '" + serverId + "' sent a request without id; dropped: " + method);
            return;
        }
        ObjectNode reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", idNode);
        if ("ping".equals(method)) {
            reply.set("result", JSON.createObjectNode());
        } else {
            ObjectNode error = reply.putObject("error");
            error.put("code", -32601);
            error.put("message", "method not found: " + method);
        }
        try {
            writeLine(JSON.writeValueAsString(reply));
        } catch (IOException e) {
            broke(new McpConnectionException("mcp server '" + serverId + "' reply write failed: "
                + e.getMessage(), e));
        }
    }

    private void drainStderr() {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                log.log(System.Logger.Level.DEBUG, "stderr: " + line);
            }
        } catch (IOException e) {
            // 进程被击杀导致流关闭:残余 stderr 无补救路径
        }
    }

    private void broke(McpConnectionException cause) {
        if (closed || !broken.compareAndSet(false, true)) {
            return;
        }
        log.log(System.Logger.Level.DEBUG, "mcp peer '" + serverId + "' broke: " + cause.getMessage());
        failAllPending(cause);
        onBroken.accept(this);
    }

    private void failAllPending(McpConnectionException cause) {
        for (Map.Entry<Long, PendingSlot> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().future.completeExceptionally(cause);
            }
        }
    }

    private JsonNode joinSlot(PendingSlot slot) {
        try {
            return slot.future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime; // Aborted / Protocol / Timeout / Connection 分档原样上抛
            }
            throw new McpConnectionException("mcp request to '" + serverId + "' failed", e);
        }
    }

    private JsonNode unwrapResult(String method, JsonNode response) {
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new McpProtocolException("mcp '" + method + "' on '" + serverId
                + "' returned an error: " + abbreviate(error.toString()));
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            throw new McpProtocolException("mcp '" + method + "' response from '" + serverId
                + "' has neither result nor error: " + abbreviate(response.toString()));
        }
        return result;
    }

    private void writeLine(String line) throws IOException {
        synchronized (writeLock) {
            writer.write(line);
            writer.newLine();
            writer.flush();
        }
    }

    /** 击杀进程树：孙进程先于本体（否则父死孙脱管）。幂等。 */
    private static void killTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.toHandle().destroyForcibly();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= ABBREVIATE_LIMIT ? text : text.substring(0, ABBREVIATE_LIMIT) + "…";
    }
}
