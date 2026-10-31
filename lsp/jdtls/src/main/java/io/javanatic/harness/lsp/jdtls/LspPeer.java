package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * 单个 LSP server 进程上的最小 Content-Length JSON-RPC 2.0 会话（it29 停 2，T5 自有边界）：
 * 与 mcp/stdio 的 {@code JsonRpcPeer} 同构但保持本 transport 私有（设计注记：
 * 若第三个消费者出现可重访抽取）。LSP 特有边界义务（T5 边界 2 / 3）：
 * <ul>
 *   <li>Content-Length 按 UTF-8 字节计；畸形帧（非数字 / 缺头 / 超限）= 流级断裂，
 *       级联全部在途（残帧后流不可再信，照 mcp/stdio 分档先例）</li>
 *   <li>null result 是合法成功（LSP 语义，与 mcp 的 unwrapResult 不同）</li>
 *   <li>server 发起的请求必须应答：window/workDoneProgress/create 答 result null，
 *       未实现的动态注册、配置等其余方法答 -32601，不伪称能力已生效</li>
 * </ul>
 * 错误分档：error 成员只失败该调用（流仍可用）；错 id 响应记日志丢弃、调用按自身超时收口；
 * EOF / 写失败 = CONNECTION 级联。取消走 {@code $/cancelRequest}（LSP 参数形 {@code id}）。
 */
final class LspPeer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Duration WAIT_SLICE = Duration.ofMillis(20);
    private static final Duration DEFAULT_FLUSH_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(2);
    private static final int WRITE_QUEUE_CAPACITY = 64;
    private static final int CANCELLED_MEMORY = 1024;
    private static final int ABBREVIATE_LIMIT = 200;

    private final Process process;
    private final String serverId;
    private final System.Logger log;
    private final Consumer<ObjectNode> onNotification;
    /** 只保护准入/撤销/出队状态，绝不持锁操作管道。 */
    private final Object stateLock = new Object();
    private final ArrayBlockingQueue<Outbound> writes = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private Outbound activeWrite;
    private boolean closing;
    /** 读侧 EOF 已断开调用面，但 EOF 前解析出的应答仍由 writer 排水。 */
    private boolean drainingAfterEof;
    private final Map<Long, PendingSlot> pending = new ConcurrentHashMap<>();
    /** 已撤销请求 id 的有界记忆（晚到响应静默丢弃的依据；仅撤销线程与读线程触碰）。 */
    private final ArrayDeque<Long> cancelledIds = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicLong receiveSequence = new AtomicLong();
    private final Object publicationLock = new Object();
    private final AtomicBoolean broken = new AtomicBoolean();
    private volatile LspException failure;
    /** 退出排水期收到的 server 请求计数（含 id 的请求面）与已应答计数：到期未答 = CLOSE_FAILED 依据。 */
    private final AtomicLong serverRequestsReceived = new AtomicLong();
    private final AtomicLong serverRequestsAnswered = new AtomicLong();
    /** 应答开关：排水宽限到期后置 false——到点后到达的请求记未答，不伪称全答（设计 §4.2 步 3）。 */
    private final AtomicBoolean answerEnabled = new AtomicBoolean(true);
    private final CountDownLatch stdoutEof = new CountDownLatch(1);
    private final Thread writer;
    private final Thread reader;
    private final Thread stderrDrain;
    private volatile boolean closed;

    private enum WriteKind { ORDINARY, CONTROL, REPLY }

    /** 已开始的帧不可截断；调用取消仅结束等待，writer 仍负责写完整帧。 */
    private static final class Outbound {
        private final String body;
        private final WriteKind kind;
        private final CompletableFuture<Void> flushed = new CompletableFuture<>();
        private boolean started;

        private Outbound(String body, WriteKind kind) {
            this.body = body;
            this.kind = kind;
        }
    }

    /** 响应与 flush 两者成功才完成调用；槽位保留到调用方收口。 */
    private static final class PendingSlot {
        private final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        private final Outbound outbound;

        private PendingSlot(Outbound outbound) {
            this.outbound = outbound;
        }
    }

    /**
     * @param process 已启动的 server 进程（stdin/stdout/stderr 为管道）
     * @param serverId server id（日志与诊断定位）
     * @param onNotification 通知回调（调用线程 = 读线程；实现方不得阻塞帧读取，
     *                       解码失败必须断裂并保留协议分类，不得吞成空候选）
     */
    LspPeer(Process process, String serverId, Consumer<ObjectNode> onNotification) {
        this.process = process;
        this.serverId = serverId;
        this.onNotification = onNotification;
        this.log = System.getLogger("io.javanatic.harness.lsp.jdtls." + serverId);
        this.writer = Thread.ofVirtual().name("jh-lsp-" + serverId + "-writer").unstarted(this::writeLoop);
        this.reader = Thread.ofVirtual().name("jh-lsp-" + serverId + "-reader").unstarted(this::readLoop);
        this.stderrDrain = Thread.ofVirtual().name("jh-lsp-" + serverId + "-stderr").unstarted(this::drainStderr);
        writer.start();
        reader.start();
        stderrDrain.start();
    }

    /** 会话是否已断裂（读侧或写侧判定；关闭后恒为 true）。 */
    boolean isBroken() {
        return broken.get();
    }

    /** 与候选发布互斥，不能观察到回调尚未完成的序号。 */
    long receiveSequence() {
        synchronized (publicationLock) {
            return receiveSequence.get();
        }
    }

    /** 在 reader 发布锁内冻结序号与候选；回调不得做 IO 或等待其他线程。 */
    <T> T capture(LongFunction<T> capture) {
        synchronized (publicationLock) {
            checkHealthy();
            return capture.apply(receiveSequence.get());
        }
    }

    /** 保留首次断裂分类，后续调用不得把 PROTOCOL 降成 CONNECTION。 */
    void checkHealthy() {
        LspException cause = failure;
        if (cause != null) {
            throw cause;
        }
        if (broken.get()) {
            throw new LspException(LspErrorCode.CONNECTION, "lsp peer '" + serverId + "' is closed");
        }
    }

    /**
     * 发一个请求并阻塞等待响应（可中断；超时与取消即失败，不续等）。
     *
     * @param method LSP 方法名
     * @param params 参数对象（null = 空对象）
     * @param timeout 该请求的超时
     * @param signal 取消信号
     * @return 完整响应报文（result 成员经 {@link #resultOf(JsonNode)} 提取，
     *         null result 是合法成功）
     * @throws LspRequestTimeoutException 超时
     * @throws LspException 协议面失败（error 成员 = PROTOCOL）或连接面失败（CONNECTION）
     * @throws AbortedException 已取消或等待中被取消（正常路径，向上传播）
     */
    JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) {
        long deadline = System.nanoTime() + timeout.toNanos();
        signal.checkAbort();
        long id = nextId.incrementAndGet();
        ObjectNode message = notification(method, params);
        message.put("id", id);
        PendingSlot slot = new PendingSlot(outbound(message, false));
        synchronized (stateLock) {
            checkAdmission(method);
            enqueue(slot.outbound);
            pending.put(id, slot);
        }
        try {
            signal.onCancel(() -> cancelPending(id, slot,
                new AbortedException("lsp '" + method + "' on '" + serverId + "' cancelled")));
            await(slot.outbound.flushed, deadline, signal);
            JsonNode response = await(slot.future, deadline, signal);
            resultOf(response); // error 成员只失败该调用；null result 合法
            return response;
        } catch (RuntimeException failure) {
            cancelPending(id, slot, failure);
            throw failure;
        } finally {
            pending.remove(id, slot);
        }
    }

    /** 同一入口期限覆盖排队、flush 和响应；轮询型与回调型取消都不做管道操作。 */
    private <T> T await(CompletableFuture<T> future, long deadline, AbortSignal signal) {
        while (true) {
            if (future.isCompletedExceptionally()) {
                return joinFuture(future);
            }
            signal.checkAbort();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new LspRequestTimeoutException("lsp call on '" + serverId + "' timed out");
            }
            if (future.isDone()) {
                return joinFuture(future);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, WAIT_SLICE.toNanos()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LspException(LspErrorCode.CONNECTION,
                    "lsp call on '" + serverId + "' interrupted", e);
            }
        }
    }

    /**
     * 提取响应 result 成员：result / error 必须恰有一个；error 或无结果均失败该调用。
     * null result 是合法成功（返回 Java null），不等于 error:null 合法。
     */
    static JsonNode resultOf(JsonNode response) {
        if (response.has("result") && response.has("error")) {
            throw new LspException(LspErrorCode.PROTOCOL, "lsp response has both result and error");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new LspException(LspErrorCode.PROTOCOL,
                "lsp response returned an error: " + abbreviate(error.toString()));
        }
        if (response.has("result")) {
            JsonNode result = response.get("result");
            return result == null || result.isNull() ? null : result;
        }
        throw new LspException(LspErrorCode.PROTOCOL,
            "lsp response has neither result nor error: " + abbreviate(response.toString()));
    }

    /** 同步通知：有限默认期限，返回仅表示已 flush；失败不吞掉。 */
    void notify(String method, ObjectNode params) {
        notify(method, params, DEFAULT_FLUSH_TIMEOUT, AbortSignal.never());
    }

    /** 等待通知 flush；排队计入期限，取消/超时只结束本次等待，不杀项目进程。 */
    void notify(String method, ObjectNode params, Duration timeout, AbortSignal signal) {
        long deadline = System.nanoTime() + timeout.toNanos();
        signal.checkAbort();
        Outbound frame = outbound(notification(method, params), false);
        synchronized (stateLock) {
            checkAdmission(method);
            enqueue(frame);
        }
        try {
            signal.onCancel(() -> discard(frame,
                new AbortedException("lsp notification on '" + serverId + "' cancelled")));
            await(frame.flushed, deadline, signal);
        } catch (RuntimeException failure) {
            discard(frame, failure);
            throw failure;
        }
    }

    /**
     * 入队即 wire 承诺（文档同步专用）：返回 flush 未来，等待被取消/超时都不撤销已入队帧
     * ——同步帧一旦入队必然被 writer 写完（或会话断裂），调用方应在入队后立即记账再等 flush，
     * 使本地文档状态与 wire 同进退。与 {@link #notify(String, ObjectNode, Duration, AbortSignal)}
     * 不同：不接取消丢弃，也不为等待者置取消钩子。
     */
    CompletableFuture<Void> enqueueNotify(String method, ObjectNode params, AbortSignal signal) {
        signal.checkAbort();
        Outbound frame = outbound(notification(method, params), false);
        synchronized (stateLock) {
            checkAdmission(method);
            enqueue(frame);
        }
        return frame.flushed;
    }

    private static ObjectNode notification(String method, ObjectNode params) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        return message;
    }

    /** 拒绝普通新工作并失败在途；排水仍准 shutdown/exit 与 server 应答。 */
    void beginClose() {
        synchronized (stateLock) {
            if (closing) {
                return;
            }
            closing = true;
            LspException cause = new LspException(LspErrorCode.CONNECTION,
                "lsp server '" + serverId + "' closing");
            failAllPending(cause);
            for (Outbound frame : writes) {
                if (frame.kind == WriteKind.ORDINARY) {
                    discard(frame, cause);
                }
            }
            if (activeWrite != null && activeWrite.kind == WriteKind.ORDINARY) {
                discard(activeWrite, cause);
            }
        }
    }

    /** 先失败等待者，再 KILL/收割，最后有界收束三条自有 I/O 线程。幂等。 */
    @Override
    public void close() {
        stop(new LspException(LspErrorCode.CONNECTION, "lsp server '" + serverId + "' closed"));
        killTree(process); // 不先 close stdin：输出流内部锁可能被堵塞的 writer 持有
        long deadline = System.nanoTime() + JOIN_TIMEOUT.toNanos();
        boolean exited = awaitExit(JOIN_TIMEOUT);
        boolean stopped = joinIo(deadline);
        if (!exited || !stopped) {
            throw new LspException(LspErrorCode.CLOSE_FAILED,
                "lsp server '" + serverId + "' process or I/O threads did not stop");
        }
        closeStreams();
    }

    // ---- 退出排水与升级序列（设计 §4.2；Provider.close 编排，本 peer 只暴露原语） ----

    /** 排水宽限到期：此后到达的 server 请求只记未答（CLOSE_FAILED 面），不再应答。 */
    void disableAnswering() {
        answerEnabled.set(false);
    }

    /** 收到但未应答的 server 请求数（含排水期到期后到达的）；> 0 = 未完成全答。 */
    long unansweredServerRequests() {
        return serverRequestsReceived.get() - serverRequestsAnswered.get();
    }

    /** 仅等待完整帧边界的真实 stdout EOF；读异常/线程结束不能代替 EOF。 */
    boolean awaitStdoutEof(Duration grace) {
        try {
            return stdoutEof.await(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** TERM 只发信号：Process.destroy 会提前关闭三路管道，丢失宽限外未答请求的证据。 */
    void terminate() {
        process.toHandle().destroy();
    }

    /** 进程是否存活（未收割）。 */
    boolean isAlive() {
        return process.isAlive();
    }

    /** 有界等待进程退出（waitFor 会收割僵尸）；到期未退返回 false。 */
    boolean awaitExit(Duration grace) {
        try {
            return process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** KILL 升级：孙进程先于本体 destroyForcibly（防脱管）。幂等。 */
    void killTree() {
        killTree(process);
    }

    /** 进程退出码；未退出 = empty（证据面：每轮 EXIT 值）。 */
    OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    /** 数值 pid（启动时捕获面）。 */
    long pid() {
        return process.pid();
    }

    /** 关闭序列末尾的有界收束；返回三条 I/O 线程是否已停，不代替 Provider 的进程升级。 */
    boolean finish() {
        stop(new LspException(LspErrorCode.CLOSE_FAILED, "lsp server '" + serverId + "' closed"));
        boolean stopped = joinIo(System.nanoTime() + JOIN_TIMEOUT.toNanos());
        if (stopped && !process.isAlive()) {
            closeStreams();
        }
        return stopped;
    }

    /** 生产生命周期证据：仍需收口的请求数。 */
    int pendingCount() {
        return pending.size();
    }

    /** 生产生命周期证据：writer/reader/stderr 必须全部收敛。 */
    boolean ioThreadsStopped() {
        return !writer.isAlive() && !reader.isAlive() && !stderrDrain.isAlive();
    }

    private void stop(LspException cause) {
        synchronized (stateLock) {
            closed = true;
            broken.set(true);
            failAllPending(cause);
            failWrites(cause);
        }
        writer.interrupt(); // 唤醒空队列等待；不能靠 interrupt 打断平台管道 write
    }

    private void cancelPending(long id, PendingSlot slot, RuntimeException failure) {
        synchronized (stateLock) {
            if (pending.remove(id, slot)) {
                rememberCancelled(id);
                slot.future.completeExceptionally(failure);
                discard(slot.outbound, failure);
                if (slot.outbound.started && !closed && !broken.get()) {
                    notifyCancelled(id);
                }
            }
        }
    }

    /** 非阻塞入同一有界队列，不为每次取消创建线程；溢出级联 typed 失败但不杀进程。 */
    private void notifyCancelled(long id) {
        ObjectNode params = JSON.createObjectNode().put("id", id);
        try {
            enqueue(outbound(notification("$/cancelRequest", params), false));
        } catch (LspException failure) {
            // enqueue 已级联断裂；保留原调用的取消/超时分类，不让取消回调覆盖原失败。
            log.log(System.Logger.Level.WARNING, failure.getMessage());
        }
    }

    private void discard(Outbound frame, RuntimeException failure) {
        synchronized (stateLock) {
            frame.flushed.completeExceptionally(failure);
            writes.remove(frame); // 未开始的帧不再发出；已开始的帧必须写完，不能截断流
        }
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
        LspFrameReader frames = new LspFrameReader(process.getInputStream(), LspFrameReader.MAX_FRAME_BYTES);
        try {
            String body;
            while ((body = frames.readFrame()) != null) {
                synchronized (publicationLock) {
                    dispatch(body);
                }
                if (closed || broken.get()) {
                    return;
                }
            }
            endInput();
            stdoutEof.countDown(); // 仅完整帧边界的真实 EOF，异常/主动停止不是 EOF
        } catch (LspException e) {
            broke(e); // 帧层 PROTOCOL：残帧后流不可再信，按会话断裂处理（重连）
        } catch (IOException e) {
            broke(new LspException(LspErrorCode.CONNECTION,
                "lsp server '" + serverId + "' read failed: " + e.getMessage(), e));
        }
    }

    private void dispatch(String body) {
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            broke(new LspException(LspErrorCode.PROTOCOL,
                "lsp server '" + serverId + "' sent a malformed frame: " + abbreviate(body), e));
            return;
        }
        if (node == null || !node.isObject()) {
            broke(new LspException(LspErrorCode.PROTOCOL,
                "lsp server '" + serverId + "' sent a non-object frame: " + abbreviate(body)));
            return;
        }
        if (!node.path("jsonrpc").isTextual() || !"2.0".equals(node.path("jsonrpc").textValue())
                || (node.has("method") && !node.get("method").isTextual())) {
            broke(new LspException(LspErrorCode.PROTOCOL,
                "lsp server '" + serverId + "' sent an invalid JSON-RPC envelope"));
            return;
        }
        receiveSequence.incrementAndGet();
        ObjectNode message = (ObjectNode) node;
        if (message.has("method")) {
            handleRemoteMessage(message);
            return;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || !idNode.isIntegralNumber() || !idNode.canConvertToLong()) {
            log.log(System.Logger.Level.WARNING,
                "lsp server '" + serverId + "' sent a response without integral id; dropped: "
                    + abbreviate(body));
            return;
        }
        long id = idNode.longValue();
        PendingSlot slot = pending.get(id);
        if (slot == null) {
            if (!forgetCancelled(id)) {
                log.log(System.Logger.Level.WARNING,
                    "lsp server '" + serverId + "' sent an unmatched response id " + id + "; dropped");
            }
            return;
        }
        slot.future.complete(message);
    }

    /**
     * server→client 报文：通知入 sink；请求必须应答（T5 边界 3）——
     * workDoneProgress/create 答 null result，未实现方法显式 -32601；
     * 无 id 的消息是通知，不伪造请求或响应 id。
     */
    private void handleRemoteMessage(ObjectNode message) {
        String method = message.path("method").asText("");
        JsonNode idNode = message.get("id");
        if (idNode == null) {
            try {
                onNotification.accept(message);
            } catch (LspException e) {
                broke(e);
            } catch (RuntimeException e) {
                broke(new LspException(LspErrorCode.PROTOCOL,
                    "lsp notification cannot be decoded: " + e.getMessage(), e));
            }
            return;
        }
        if (!idNode.isTextual() && !(idNode.isIntegralNumber() && idNode.canConvertToLong())) {
            throw new LspException(LspErrorCode.PROTOCOL,
                "lsp server '" + serverId + "' sent a request with unusable id: " + method);
        }
        serverRequestsReceived.incrementAndGet();
        if (!answerEnabled.get()) {
            // 排水宽限已到期：只记未答（计入 CLOSE_FAILED 面），不悬挂远端也不伪称全答
            log.log(System.Logger.Level.WARNING, "lsp server '" + serverId
                + "' request arrived after drain grace and will not be answered: " + method);
            return;
        }
        ObjectNode reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", idNode);
        if (answerableWithNull(method)) {
            reply.putNull("result");
        } else {
            ObjectNode error = reply.putObject("error");
            error.put("code", -32601);
            error.put("message", "method not found: " + method);
        }
        Outbound frame = outbound(reply, true);
        synchronized (stateLock) {
            enqueue(frame); // reader 只入队，不等待 flush；成功计数由唯一 writer 负责
        }
    }

    /** 仅对实际支持的 server→client 请求答成功；未实现注册不得假成功。 */
    private static boolean answerableWithNull(String method) {
        return "window/workDoneProgress/create".equals(method);
    }

    private void drainStderr() {
        byte[] buffer = new byte[2048];
        try {
            int n;
            while ((n = process.getErrorStream().read(buffer)) != -1) {
                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                log.log(System.Logger.Level.DEBUG, "stderr: " + chunk);
            }
        } catch (IOException e) {
            // 进程被击杀导致流关闭：残余 stderr 无补救路径
        }
    }

    /** EOF 禁止新调用，不截断正在写的帧；仅留下已解析的 REPLY，生命周期负责有界等候。 */
    private void endInput() {
        synchronized (stateLock) {
            if (closed || broken.get()) {
                return;
            }
            failure = new LspException(LspErrorCode.CONNECTION,
                "lsp server '" + serverId + "' closed stdout");
            broken.set(true);
            drainingAfterEof = true;
            failAllPending(failure);
            for (Outbound frame : writes) {
                if (frame.kind != WriteKind.REPLY) {
                    discard(frame, failure);
                }
            }
            if (activeWrite != null && activeWrite.kind != WriteKind.REPLY) {
                discard(activeWrite, failure);
            }
        }
    }

    private void broke(LspException cause) {
        synchronized (stateLock) {
            if (closed || (broken.get() && !drainingAfterEof)) {
                return;
            }
            if (failure == null) {
                failure = cause;
            }
            drainingAfterEof = false;
            broken.set(true);
            log.log(System.Logger.Level.DEBUG, "lsp peer '" + serverId + "' broke: " + cause.getMessage());
            failAllPending(cause);
            failWrites(cause);
        }
        writer.interrupt();
    }

    private void failAllPending(LspException cause) {
        for (Map.Entry<Long, PendingSlot> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                rememberCancelled(entry.getKey());
                entry.getValue().future.completeExceptionally(cause);
                discard(entry.getValue().outbound, cause);
            }
        }
    }

    private void failWrites(LspException cause) {
        Outbound frame;
        while ((frame = writes.poll()) != null) {
            frame.flushed.completeExceptionally(cause);
        }
        if (activeWrite != null) {
            activeWrite.flushed.completeExceptionally(cause);
        }
    }

    private <T> T joinFuture(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime; // Aborted / Timeout / Protocol / Connection 分档原样上抛
            }
            throw new LspException(LspErrorCode.CONNECTION, "lsp request to '" + serverId + "' failed", e);
        }
    }

    private void checkAdmission(String method) {
        checkHealthy();
        if (closing && !"shutdown".equals(method) && !"exit".equals(method)) {
            throw new LspException(LspErrorCode.CONNECTION,
                "lsp peer '" + serverId + "' is closing or broken");
        }
    }

    private Outbound outbound(ObjectNode message, boolean reply) {
        WriteKind kind = reply ? WriteKind.REPLY : switch (message.path("method").asText()) {
            case "shutdown", "exit" -> WriteKind.CONTROL;
            default -> WriteKind.ORDINARY;
        };
        try {
            return new Outbound(JSON.writeValueAsString(message), kind);
        } catch (JsonProcessingException e) {
            throw new LspException(LspErrorCode.PROTOCOL, "cannot encode lsp frame", e);
        }
    }

    /** 调用方持 stateLock；队满立即失败，不阻塞 reader/取消方，也不静默丢帧。 */
    private void enqueue(Outbound frame) {
        if (closed || broken.get()) {
            throw new LspException(LspErrorCode.CONNECTION, "lsp peer '" + serverId + "' is broken");
        }
        if (!writes.offer(frame)) {
            LspException overflow = new LspException(LspErrorCode.PROTOCOL,
                "lsp peer '" + serverId + "' write queue overflow");
            broke(overflow);
            throw overflow;
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                Outbound frame = writes.poll(WAIT_SLICE.toNanos(), TimeUnit.NANOSECONDS);
                synchronized (stateLock) {
                    if (frame == null) {
                        if (closed || broken.get()) {
                            return;
                        }
                        continue;
                    }
                    if (closed || (broken.get() && !drainingAfterEof)) {
                        frame.flushed.completeExceptionally(new LspException(LspErrorCode.CONNECTION,
                            "lsp peer '" + serverId + "' is broken"));
                        return;
                    }
                    if (drainingAfterEof && frame.kind != WriteKind.REPLY) {
                        frame.flushed.completeExceptionally(failure);
                        continue;
                    }
                    if (closing && frame.kind == WriteKind.ORDINARY) {
                        frame.flushed.completeExceptionally(new LspException(LspErrorCode.CONNECTION,
                            "lsp peer '" + serverId + "' is closing"));
                    }
                    if (frame.flushed.isDone()) {
                        continue;
                    }
                    frame.started = true;
                    activeWrite = frame;
                }
                LspFrames.write(process.getOutputStream(), frame.body);
                if (frame.kind == WriteKind.REPLY) {
                    serverRequestsAnswered.incrementAndGet();
                }
                frame.flushed.complete(null);
                synchronized (stateLock) {
                    activeWrite = null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 仅 stop/broke 会中断 writer；相应等待者已失败。
        } catch (IOException e) {
            broke(new LspException(LspErrorCode.CONNECTION,
                "lsp peer '" + serverId + "' write failed: " + e.getMessage(), e));
        } finally {
            synchronized (stateLock) {
                activeWrite = null;
            }
        }
    }

    /** 击杀进程树：孙进程先于本体（否则父死孙脱管）。幂等。 */
    private static void killTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.toHandle().destroyForcibly();
    }

    private boolean joinIo(long deadline) {
        for (Thread thread : new Thread[] {writer, reader, stderrDrain}) {
            long remaining = deadline - System.nanoTime();
            if (thread != Thread.currentThread() && remaining > 0) {
                try {
                    thread.join(Duration.ofNanos(remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return ioThreadsStopped();
    }

    /** 仅在进程已收割且自有线程全停后关闭管道，不能让管道锁挡住 KILL/join。 */
    private void closeStreams() {
        try {
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (IOException e) {
            // 进程和线程已收敛，死管道关闭失败无可恢复数据；保留诊断。
            log.log(System.Logger.Level.DEBUG, "lsp dead pipe close failed: " + e.getMessage());
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= ABBREVIATE_LIMIT ? text : text.substring(0, ABBREVIATE_LIMIT) + "…";
    }
}
