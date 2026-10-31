package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspAnchor;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspObservation;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单项目 JDT LS 观察：输入采样、全文同步 flush、完整窗口、原子冻结候选、after 复验。
 * 所有步骤共用入口总期限；项目串行区排队可取消。候选每 URI 末值胜，观察不是索引完成屏障。
 * 正常关闭保持协议应答，随后有界排水、TERM/KILL 升级与自有进程复验；会话先于握手终结 = CLOSE_FAILED。
 * 观察入口带 document 路径围栏（T6）：规范化 URI 必须落在 projectRoot 内，越界 UNSUPPORTED
 * ——围栏只约束 JH 把哪些文件交给 LS，不约束 LS 自发扫描（B23，它本来就全权）。
 */
public final class JdtlsProvider implements LspService {

    static final Duration DEFAULT_INITIALIZE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_DRAIN_GRACE = Duration.ofSeconds(2);
    private static final Duration DEFAULT_TERM_GRACE = Duration.ofSeconds(2);
    private static final Duration DEFAULT_KILL_GRACE = Duration.ofSeconds(2);
    private static final long SLICE_NANOS = 20_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 每段关闭宽限均有界，测试可注入缩短预算。 */
    record CloseBudget(Duration shutdownTimeout, Duration drainGrace,
                       Duration termGrace, Duration killGrace) {
        CloseBudget {
            requirePositive(shutdownTimeout, "shutdownTimeout");
            requirePositive(drainGrace, "drainGrace");
            requirePositive(termGrace, "termGrace");
            requirePositive(killGrace, "killGrace");
        }

        static CloseBudget defaults() {
            return new CloseBudget(DEFAULT_SHUTDOWN_TIMEOUT, DEFAULT_DRAIN_GRACE,
                DEFAULT_TERM_GRACE, DEFAULT_KILL_GRACE);
        }
    }

    private record OpenDocument(String hash, int version) {
    }

    private final Path projectRoot;
    private final String serverId;
    private final System.Logger log;
    private final CloseBudget budget;
    private final ProviderInputs.FileReader fileReader;
    private final Runnable beforeSamplingRegistration;
    private final ReentrantLock zoneLock = new ReentrantLock();
    private final Object lifecycleLock = new Object();
    private final DiagnosticsCache cache = new DiagnosticsCache();
    private final Map<URI, OpenDocument> openedDocuments = new HashMap<>();
    private final Map<URI, Optional<String>> sampledDocuments = new HashMap<>();
    private String lastProjectHash;
    private final UUID incarnation = UUID.randomUUID();
    private long generation;
    private final Session.Launch session;
    private final LspPeer peer;
    private final CompletableFuture<Void> initialized;
    private volatile boolean closed;
    private volatile Thread samplingThread;

    public JdtlsProvider(JdtlsLaunch launch) {
        this(launch, DEFAULT_INITIALIZE_TIMEOUT, CloseBudget.defaults());
    }

    JdtlsProvider(JdtlsLaunch launch, Duration initializeTimeout, CloseBudget budget) {
        this(launch.projectRoot(), launch.argv(), launch.env(), initializeTimeout, budget);
    }

    /** 测试缝：真实对端进程，复用生产启动和关闭路径。 */
    JdtlsProvider(Path projectRoot, List<String> argv, Map<String, String> env,
                  Duration initializeTimeout, CloseBudget budget) {
        this(projectRoot, argv, env, initializeTimeout, budget, Files::readAllBytes, () -> { });
    }

    /** 包内文件系统边界；生产构造器固定真实读取，不提供配置或静态测试开关。 */
    JdtlsProvider(Path projectRoot, List<String> argv, Map<String, String> env,
                  Duration initializeTimeout, CloseBudget budget, ProviderInputs.FileReader fileReader) {
        this(projectRoot, argv, env, initializeTimeout, budget, fileReader, () -> { });
    }

    /** 测试缝：采样线程登记/启动前在 lifecycleLock 锁内同步回调；生产构造器固定无操作，不提供静态测试开关。 */
    // CHECKSTYLE:OFF ParameterNumber —— 依赖注入测试缝构造器：治理依赖全显式注入，拆分即弱化证明
    JdtlsProvider(Path projectRoot, List<String> argv, Map<String, String> env,
                  Duration initializeTimeout, CloseBudget budget, ProviderInputs.FileReader fileReader,
                  Runnable beforeSamplingRegistration) {
        this.fileReader = Objects.requireNonNull(fileReader, "fileReader");
        this.beforeSamplingRegistration =
            Objects.requireNonNull(beforeSamplingRegistration, "beforeSamplingRegistration");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        this.budget = Objects.requireNonNull(budget, "budget");
        requirePositive(initializeTimeout, "initializeTimeout");
        Path name = projectRoot.getFileName();
        this.serverId = "jdtls-" + (name == null ? "root" : name);
        this.log = System.getLogger("io.javanatic.harness.lsp.jdtls.provider");
        try {
            this.session = Session.start(argv, env, projectRoot);
        } catch (IOException e) {
            throw new LspException(LspErrorCode.IO, "cannot start LSP server: " + e.getMessage(), e);
        }
        if (!session.dedicatedGroup()) {
            log.log(System.Logger.Level.WARNING,
                "lsp server '" + serverId + "' has no dedicated process group: " + session.note());
        }
        this.peer = new LspPeer(session.process(), serverId, this::onServerMessage);
        this.initialized = CompletableFuture.supplyAsync(() -> handshake(initializeTimeout));
    }
    // CHECKSTYLE:ON ParameterNumber

    private Void handshake(Duration initializeTimeout) {
        long deadline = System.nanoTime() + initializeTimeout.toNanos();
        try {
            JsonNode response = peer.request("initialize", initializeParams(), initializeTimeout, AbortSignal.never());
            validateInitialize(LspPeer.resultOf(response));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new LspRequestTimeoutException("initialize flush deadline expired");
            }
            peer.notify("initialized", null, Duration.ofNanos(remaining), AbortSignal.never());
            return null;
        } catch (LspRequestTimeoutException e) {
            LspException failure = new LspException(LspErrorCode.CONNECTION, "lsp initialize on '" + serverId
                + "' timed out after " + initializeTimeout.toSeconds() + "s", e);
            rollbackInitialization(failure);
            throw failure;
        } catch (RuntimeException e) {
            rollbackInitialization(e);
            throw e;
        }
    }

    /** 与 close 的所有权声明互斥，正常关闭期间初始化失败不得抢先 KILL。 */
    private void rollbackInitialization(RuntimeException failure) {
        synchronized (lifecycleLock) {
            if (!closed) {
                try {
                    peer.close();
                } catch (LspException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    /** initialize 业务结果独立验证：合法 JSON-RPC 包络不代表初始化成功（R3-4），
     *  shutdown/定位允许 null 的规则不外推。结果须为对象且声明本 Provider 依赖的
     *  文档同步能力（全文 didOpen/didChange/didClose 推送需要 textDocumentSync 非 None）。 */
    private static void validateInitialize(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw new LspException(LspErrorCode.PROTOCOL, "initialize response result must be an object: "
                + (result == null ? "null" : result.getNodeType().name().toLowerCase()));
        }
        JsonNode capabilities = result.get("capabilities");
        if (capabilities == null || !capabilities.isObject()) {
            throw new LspException(LspErrorCode.PROTOCOL, "initialize response must declare capabilities");
        }
        JsonNode sync = capabilities.get("textDocumentSync");
        boolean syncSupported;
        if (sync == null) {
            syncSupported = false;
        } else if (sync.isIntegralNumber()) {
            syncSupported = sync.intValue() >= 1; // 1=Full、2=Incremental；0/缺省=None
        } else if (sync.isObject()) {
            syncSupported = sync.path("change").asInt(0) >= 1 && sync.path("openClose").asBoolean(false);
        } else {
            syncSupported = false;
        }
        if (!syncSupported) {
            throw new LspException(LspErrorCode.PROTOCOL,
                "initialize response declares no text document sync usable for full-text pushes");
        }
    }

    private ObjectNode initializeParams() {
        ObjectNode params = JSON.createObjectNode();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", projectRoot.toUri().toString());
        ObjectNode folder = params.putArray("workspaceFolders").addObject();
        folder.put("uri", projectRoot.toUri().toString());
        folder.put("name", serverId);
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "jh-lsp-jdtls");
        clientInfo.put("version", "0.3.0");
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("window").put("workDoneProgress", true);
        capabilities.putObject("textDocument").putObject("publishDiagnostics").put("versionSupport", true);
        return params;
    }

    /** 未完成初始化允许等待到本次期限，短预算仍返回 INITIALIZING。
     *  等待只做中止/closed 检查：peer 健康与否由 initialized 未来自身承载——
     *  健康检查抢先会永久掩盖已存储的初始化失败（rollback 已关闭 peer）。 */
    private boolean checkInitialized(Call call) {
        while (!initialized.isDone()) {
            call.checkLifecycle();
            if (call.deadline - System.nanoTime() <= 0) {
                return false;
            }
            call.pauseUntil(call.deadline);
        }
        try {
            initialized.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LspException(LspErrorCode.CONNECTION, "lsp initialize failed", e.getCause());
        }
        return true;
    }

    @Override
    public LspResult<LspDiagnostic> diagnostics(LspQuery query, AbortSignal signal) {
        requireInsideProject(query.document());
        Call call = new Call(query, signal);
        return observe(call, () -> diagnosticsInZone(call));
    }

    private LspResult<LspDiagnostic> diagnosticsInZone(Call call) {
        URI uri = call.query.document();
        ProviderInputs.Snapshot before = before(call);
        LspAnchor beforeAnchor = anchor(before, uri);
        syncDocuments(before, call);
        long flushDone = System.nanoTime();
        call.awaitWindow(flushDone);
        DiagnosticsCache.Candidate candidate = peer.capture(sequence -> cache.capture(uri, sequence));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - flushDone);
        ProviderInputs.Snapshot after = sample(call);
        call.check();
        LspAnchor afterAnchor = anchor(after, uri);
        // 截点前溢出毒化当轮拉取（PROTOCOL 优先于 Stale：溢出是窗口内协议事件，输入竞态不得吞掉）。
        if (candidate.overflowed()) {
            throw new LspException(LspErrorCode.PROTOCOL, "diagnostics overflow for document " + uri);
        }
        if (!beforeAnchor.equals(afterAnchor)) {
            return new LspResult.Stale<>(beforeAnchor, afterAnchor);
        }
        Optional<DiagnosticsCache.Batch> batch = candidate.batch();
        LspEvidence evidence = batch.map(value -> new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS,
            value.sequence(), value.version())).orElseGet(() -> new LspEvidence(LspEvidence.Origin.QUIET_WINDOW,
                candidate.sequence(), OptionalInt.empty()));
        LspObservation observation = new LspObservation(beforeAnchor, afterAnchor,
            call.query.observationWindow(), elapsed, Instant.now(), evidence);
        return new LspResult.Observed<>(observation, batch.map(DiagnosticsCache.Batch::items).orElse(List.of()));
    }

    @Override
    public LspResult<LspLocation> definition(LspPositionQuery query, AbortSignal signal) {
        requireInsideProject(query.query().document());
        Call call = new Call(query.query(), signal);
        return observe(call, () -> locate("textDocument/definition", query, call));
    }

    @Override
    public LspResult<LspLocation> references(LspPositionQuery query, AbortSignal signal) {
        requireInsideProject(query.query().document());
        Call call = new Call(query.query(), signal);
        return observe(call, () -> locate("textDocument/references", query, call));
    }

    private LspResult<LspLocation> locate(String method, LspPositionQuery query, Call call) {
        URI uri = call.query.document();
        ProviderInputs.Snapshot before = before(call);
        LspAnchor beforeAnchor = anchor(before, uri);
        syncDocuments(before, call);
        long flushDone = System.nanoTime();
        ObjectNode params = JSON.createObjectNode();
        params.putObject("textDocument").put("uri", uri.toString());
        params.putObject("position").put("line", query.position().line()).put("character", query.position().character());
        if ("textDocument/references".equals(method)) {
            params.putObject("context").put("includeDeclaration", true);
        }
        JsonNode response = peer.request(method, params, call.remaining(), call.signal);
        List<LspLocation> items = ProviderProtocol.locations(LspPeer.resultOf(response));
        call.awaitWindow(flushDone);
        long sequence = peer.capture(value -> value);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - flushDone);
        ProviderInputs.Snapshot after = sample(call);
        call.check();
        LspAnchor afterAnchor = anchor(after, uri);
        if (!beforeAnchor.equals(afterAnchor)) {
            return new LspResult.Stale<>(beforeAnchor, afterAnchor);
        }
        LspObservation observation = new LspObservation(beforeAnchor, afterAnchor,
            call.query.observationWindow(), elapsed, Instant.now(),
            new LspEvidence(LspEvidence.Origin.REQUEST_RESPONSE, sequence, OptionalInt.empty()));
        return new LspResult.Observed<>(observation, items);
    }

    private <T> LspResult<T> observe(Call call, Supplier<LspResult<T>> operation) {
        boolean acquired = false;
        try {
            // 初始化失败已存储时不得被健康检查抢先掩盖（rollback 已关闭 peer）：先验未来，后验健康。
            call.checkLifecycle();
            if (!checkInitialized(call)) {
                call.checkLifecycle();
                return new LspResult.NotReady<>(LspResult.NotReadyReason.INITIALIZING);
            }
            while (!acquired) {
                acquired = zoneLock.tryLock(Math.min(call.remaining().toNanos(), SLICE_NANOS), TimeUnit.NANOSECONDS);
            }
            call.check();
            LspResult<T> result = operation.get();
            call.check(); // 包括 after 采样与组装结果；期限之后绝不发布 Observed
            return result;
        } catch (LspRequestTimeoutException e) {
            call.signal.checkAbort();
            if (closed) {
                return new LspResult.NotReady<>(LspResult.NotReadyReason.CLOSED);
            }
            peer.checkHealthy();
            return new LspResult.TimedOut<>(call.query.timeout());
        } catch (LspException e) {
            call.signal.checkAbort();
            if (closed) {
                return new LspResult.NotReady<>(LspResult.NotReadyReason.CLOSED);
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.signal.checkAbort();
            throw new LspException(LspErrorCode.CONNECTION, "lsp observation queue interrupted", e);
        } finally {
            if (acquired) {
                zoneLock.unlock();
            }
        }
    }

    /** 观察入口围栏（T6）：document URI 规范化后必须落在 projectRoot 内，越界 fail loud。 */
    private void requireInsideProject(URI document) {
        String root = projectRoot.toUri().normalize().toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (!document.normalize().toString().startsWith(root)) {
            throw new LspException(LspErrorCode.UNSUPPORTED, "document outside project root");
        }
    }

    /** 一个调用只创建一次，不在同步、定位请求或采样时续期。 */
    private final class Call {
        private final long deadline;
        private final LspQuery query;
        private final AbortSignal signal;

        private Call(LspQuery query, AbortSignal signal) {
            this.deadline = System.nanoTime() + query.timeout().toNanos();
            this.query = query;
            this.signal = Objects.requireNonNull(signal, "signal");
        }

        private void checkState() {
            checkLifecycle();
            peer.checkHealthy();
        }

        /** 中止与 closed 检查；不含 peer 健康（初始化等待段使用：健康由握手未来自身承载）。 */
        private void checkLifecycle() {
            signal.checkAbort();
            if (closed) {
                throw new LspException(LspErrorCode.CONNECTION, "lsp provider closed");
            }
        }

        private void check() {
            remaining();
        }

        private Duration remaining() {
            checkState();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new LspRequestTimeoutException("lsp observation timed out");
            }
            return Duration.ofNanos(remaining);
        }

        private void pauseUntil(long end) {
            long duration = Math.min(Math.min(end - System.nanoTime(), deadline - System.nanoTime()), SLICE_NANOS);
            if (duration <= 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                signal.checkAbort();
                throw new LspException(LspErrorCode.CONNECTION, "lsp observation interrupted", e);
            }
        }

        private void awaitWindow(long start) {
            long end = start + query.observationWindow().toNanos();
            while (true) {
                check();
                if (System.nanoTime() - end >= 0) {
                    return;
                }
                pauseUntil(end);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            peer.beginClose();
        }
        List<String> failures = new ArrayList<>();
        if (!shutdownAndNotify()) {
            failures.add("shutdown unanswered");
        }
        long drainDeadline = System.nanoTime() + budget.drainGrace().toNanos();
        boolean eofSeen = peer.awaitStdoutEof(budget.drainGrace());
        awaitReplyFlush(drainDeadline);
        peer.disableAnswering();
        if (!terminateOrKill()) {
            failures.add("process survived TERM and KILL");
        }
        if (!peer.finish()) {
            failures.add("I/O threads did not stop");
        }
        if (peer.pendingCount() != 0) {
            failures.add("pending requests=" + peer.pendingCount());
        }
        if (!stopSampling()) {
            failures.add("input sampling thread did not stop");
        }
        if (!Session.noResidue(session.pid(), session.pgid(), session.dedicatedGroup())) {
            failures.add("pid/pgid residue cannot be proven gone");
        }
        long unanswered = peer.unansweredServerRequests();
        if (unanswered > 0) {
            failures.add("unanswered server requests=" + unanswered);
        }
        log.log(System.Logger.Level.INFO, "lsp server '" + serverId + "' close: exit=" + peer.exitCode()
            + ", stdoutEof=" + eofSeen + ", dedicatedGroup=" + session.dedicatedGroup()
            + " (" + session.note() + "), unanswered=" + unanswered
            + ", pending=" + peer.pendingCount() + ", ioThreadsStopped=" + peer.ioThreadsStopped());
        if (!failures.isEmpty()) {
            throw new LspException(LspErrorCode.CLOSE_FAILED,
                "lsp server '" + serverId + "' did not close cleanly: " + String.join("; ", failures));
        }
    }

    private boolean stopSampling() {
        Thread worker = samplingThread;
        if (worker == null) {
            return true;
        }
        worker.interrupt();
        try {
            worker.join(budget.killGrace());
            return !worker.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean shutdownAndNotify() {
        boolean answered = true;
        try {
            peer.request("shutdown", null, budget.shutdownTimeout(), AbortSignal.never());
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "lsp shutdown on '" + serverId + "' failed: " + e.getMessage());
            answered = false;
        }
        try {
            peer.notify("exit", null, budget.shutdownTimeout(), AbortSignal.never());
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "lsp exit on '" + serverId + "' failed: " + e.getMessage());
            answered = false;
        }
        return answered;
    }

    /** 排水宽限余量内等待已解析 server 请求的应答 flush 完成；到期未答由收尾检查记 CLOSE_FAILED（不伪称全答）。 */
    private void awaitReplyFlush(long drainDeadline) {
        while (peer.unansweredServerRequests() > 0 && peer.isAlive()) {
            long remaining = drainDeadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(SLICE_NANOS, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean terminateOrKill() {
        peer.awaitExit(Duration.ofMillis(50));
        if (!peer.isAlive()) {
            return true;
        }
        peer.terminate();
        if (peer.awaitExit(budget.termGrace())) {
            return true;
        }
        peer.killTree();
        return peer.awaitExit(budget.killGrace());
    }

    private void onServerMessage(ObjectNode message) {
        String method = message.path("method").asText("");
        switch (method) {
            case "textDocument/publishDiagnostics" -> {
                ProviderProtocol.Publication publication = ProviderProtocol.publication(message.get("params"));
                cache.publish(publication.uri(), peer.receiveSequence(), publication.version(), publication.items());
            }
            default -> log.log(System.Logger.Level.DEBUG, "lsp server '" + serverId + "' notification: " + method);
        }
    }

    private ProviderInputs.Snapshot before(Call call) {
        call.check();
        ProviderInputs.Snapshot snapshot = sample(call);
        call.check();
        if (snapshot.documents().get(call.query.document()).anchor().sha256().isEmpty()) {
            throw new LspException(LspErrorCode.IO, "document does not exist: " + call.query.document());
        }
        return snapshot;
    }

    private ProviderInputs.Snapshot sample(Call call) {
        Set<URI> tracked = new HashSet<>(sampledDocuments.keySet());
        tracked.add(call.query.document());
        ProviderInputs.Snapshot snapshot = readInputs(tracked, call);
        call.check();
        boolean changed = lastProjectHash != null && !lastProjectHash.equals(snapshot.projectHash());
        for (Map.Entry<URI, Optional<String>> prior : sampledDocuments.entrySet()) {
            if (!prior.getValue().equals(snapshot.documents().get(prior.getKey()).anchor().sha256())) {
                changed = true;
            }
        }
        if (changed) {
            peer.capture(sequence -> {
                generation++;
                cache.clear();
                return sequence;
            });
        }
        lastProjectHash = snapshot.projectHash();
        for (URI document : tracked) {
            sampledDocuments.put(document, snapshot.documents().get(document).anchor().sha256());
        }
        return snapshot;
    }

    /** 文件 IO 不持有返回路径；过期任务仅产出私有值，不能再改变代或候选。 */
    private ProviderInputs.Snapshot readInputs(Set<URI> tracked, Call call) {
        // 不可中断的宿主 IO 尚未退出时，不累积新的采样线程。
        while (samplingThread != null && samplingThread.isAlive()) {
            call.check();
            call.pauseUntil(call.deadline);
        }
        call.check();
        FutureTask<ProviderInputs.Snapshot> task = new FutureTask<>(() ->
            ProviderInputs.sample(projectRoot, tracked, path -> {
                call.check();
                return fileReader.read(path);
            }));
        // 与 close 的所有权声明（closed=true）互斥：close 先到则拒登记，后到则 stopSampling 收已登记线程；
        // 锁内只登记并启动、不等待采样完成（文件 IO 无宿主等待）。
        synchronized (lifecycleLock) {
            beforeSamplingRegistration.run();
            call.checkState();
            samplingThread = Thread.ofVirtual().name("jh-lsp-" + serverId + "-sample").unstarted(task);
            samplingThread.start();
        }
        try {
            while (!task.isDone()) {
                call.check();
                call.pauseUntil(call.deadline);
            }
            call.check();
            return task.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LspException(LspErrorCode.IO, "input sampling failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.signal.checkAbort();
            throw new LspException(LspErrorCode.IO, "input sampling interrupted", e);
        } finally {
            task.cancel(true);
        }
    }

    private LspAnchor anchor(ProviderInputs.Snapshot snapshot, URI uri) {
        return new LspAnchor(projectRoot.toUri(), incarnation, generation, snapshot.projectHash(),
            snapshot.documents().get(uri).anchor());
    }

    private void syncDocuments(ProviderInputs.Snapshot snapshot, Call call) {
        Set<URI> documents = new HashSet<>(openedDocuments.keySet());
        documents.add(call.query.document());
        for (URI uri : documents) {
            call.check();
            ProviderInputs.Document current = snapshot.documents().get(uri);
            OpenDocument opened = openedDocuments.get(uri);
            ObjectNode params = JSON.createObjectNode();
            ObjectNode textDocument = params.putObject("textDocument").put("uri", uri.toString());
            if (current.anchor().sha256().isEmpty()) {
                if (opened != null) {
                    CompletableFuture<Void> flushed =
                        peer.enqueueNotify("textDocument/didClose", params, call.signal);
                    openedDocuments.remove(uri); // 入队即 wire 承诺：帧不被撤销，本地状态与 wire 同进退
                    awaitSync(flushed, call);
                }
                continue;
            }
            String hash = current.anchor().sha256().orElseThrow();
            if (opened != null && opened.hash().equals(hash)) {
                continue;
            }
            int version = opened == null ? 1 : Math.incrementExact(opened.version());
            textDocument.put("version", version);
            CompletableFuture<Void> flushed;
            if (opened == null) {
                textDocument.put("languageId", "java").put("text", current.text());
                flushed = peer.enqueueNotify("textDocument/didOpen", params, call.signal);
            } else {
                params.putArray("contentChanges").addObject().put("text", current.text());
                flushed = peer.enqueueNotify("textDocument/didChange", params, call.signal);
            }
            openedDocuments.put(uri, new OpenDocument(hash, version)); // 入队即 wire 承诺
            awaitSync(flushed, call);
        }
    }

    /** 等待同步帧 flush：超时/取消只结束等待（帧已承诺不撤销），连接面失败原样上抛。 */
    private void awaitSync(CompletableFuture<Void> flushed, Call call) {
        while (!flushed.isDone()) {
            call.check();
            call.pauseUntil(call.deadline);
        }
        try {
            flushed.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LspException(LspErrorCode.CONNECTION, "lsp document sync failed", e.getCause());
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    OptionalInt exitCode() {
        return peer.exitCode();
    }

    long pid() {
        return session.pid();
    }

    OptionalLong pgid() {
        return session.pgid();
    }

    boolean dedicatedGroup() {
        return session.dedicatedGroup();
    }

    int pendingCount() {
        return peer.pendingCount();
    }

    boolean ioThreadsStopped() {
        return peer.ioThreadsStopped();
    }
}
