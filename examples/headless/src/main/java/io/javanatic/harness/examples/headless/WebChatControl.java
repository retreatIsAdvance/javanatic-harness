package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.delegation.DelegationService;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.local.LocalDelegationService;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.jobs.local.LocalJobService;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.events.WaterfallArgs;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.WriterLockException;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.workbench.rpc.ChatControl;
import io.javanatic.harness.workbench.rpc.RpcException;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * serve 写面宿主（it38 H2）：把 {@link ChatControl} 四动词接到既有执行面（AppBoot 组合 +
 * AgentRegistry），一次 {@code session/start|send} 从持久化事实重组组合、开轮，轮毕即关
 * （政策 (a) 按轮挂载）；delegation 子代 / jobs 未终局时该 runtime 留到后台工作终局
 * 且无后续轮（后台例外，同一政策档内写明）。
 *
 * <p>租约：每轮在真实 REQUEST waterfall 上以 carrier 的 session / turn 建租约，把该轮
 * 的真实 signal identity 交给 {@link WebApprovalHub}；同轮多 step / 重试不换租约，
 * 子代用自己的 session / turn 入父 owner 的 feed。PRE_STEP 视图不参与路由（F2）。
 *
 * <p>取消与所有权：{@code cancel} 只取消该会话在途轮（先关该轮租约再调既有 cancel），
 * 无父取消级联；一 session 一所有者由 in-flight 标记保证（不发双重 resume）。
 *
 * <p>观测面（包内）：{@link #openRuntimeCount()} / {@link #runtimeBoots(String)} /
 * {@link #completedTurns(String)} / {@link #inFlight(String)} / {@link #retained(String)}。
 * 会话记录随 serve 生命期保留（仅记账，退休后不持 runtime 资源）。
 */
final class WebChatControl implements ChatControl, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(WebChatControl.class.getName());

    /** 后台工作终局的观察节拍（毫秒）。 */
    private static final int WATCH_MILLIS = 100;

    /** 退休后等待后台 watcher 收尾的上限（close 用）。 */
    private static final long CLOSE_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    /** 一次 serve 生命期内的会话记账（不持 runtime 资源）。 */
    private static final class SessionState {
        private final String id;
        private long gen;
        private boolean inFlight;
        private boolean retained;
        private boolean retired;
        private Runtime runtime;
        private AgentHandle handle;
        private volatile AbortSignal signal;
        private boolean retiring;
        private int boots;
        private int completedTurns;

        private SessionState(String id) {
            this.id = id;
        }
    }

    private final AppBoot.BootOptions boot;
    private final AgentOptions agentOptions;
    private final WebApprovalHub hub;
    private final SessionPersistence probe;
    private final boolean injectPrompt;
    private final Object lock = new Object();
    private final Map<String, SessionState> sessions = new LinkedHashMap<>();
    private boolean closing;

    /**
     * @param boot         每轮装配选项（与 serve 启动期同源）
     * @param agentOptions 路由身份（provider / model）
     * @param hub          审批 feed 与共享 relay（写面恒有）
     * @param probe        未知会话探测面（serve 根组合的持久化服务）
     * @throws IOException 读取 profile 失败（组合判定注入面时）
     */
    WebChatControl(AppBoot.BootOptions boot, AgentOptions agentOptions, WebApprovalHub hub,
                   SessionPersistence probe) throws IOException {
        this.boot = Objects.requireNonNull(boot, "boot");
        this.agentOptions = Objects.requireNonNull(agentOptions, "agentOptions");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.probe = Objects.requireNonNull(probe, "probe");
        // H1：只有组合实际启用 approval-ask 才注入中继 prompt；auto / deny 走原装配
        this.injectPrompt = AppBoot.resolve(AppBoot.compose(boot)).stream()
            .anyMatch(row -> "approval-ask".equals(row.plugin()));
    }

    @Override
    public String start(String text) {
        requireText(text);
        String id = newSessionId();
        SessionState st = new SessionState(id);
        synchronized (lock) {
            requireOpenLocked();
            st.gen = 1;
            st.inFlight = true;
            sessions.put(id, st);
        }
        try {
            bootFresh(st, false);
        } catch (IOException | RuntimeException e) {
            discard(st, true);
            throw translate("session boot failed", e);
        }
        drive(st, text);
        return id;
    }

    @Override
    public void send(String session, String text) {
        requireText(text);
        Objects.requireNonNull(session, "session");
        SessionState st;
        boolean reuse;
        boolean createdNow;
        synchronized (lock) {
            requireOpenLocked();
            st = sessions.get(session);
            if (st != null && st.inFlight) {
                throw new RpcException(-32006, "turn already in flight");
            }
            createdNow = st == null;
            if (createdNow) {
                st = new SessionState(session);
                sessions.put(session, st);
            }
            st.inFlight = true;
        }
        // 旧清理未收口（retired 仍持 runtime / retiring）时先等它结束，绝不并发换代：
        // 新代不得与旧代的 dispose / 落盘 / close 交错，否则旧清理会删到新代资源。
        try {
            awaitRetirement(st);
        } catch (RuntimeException e) {
            discard(st, createdNow);
            throw e;
        }
        synchronized (lock) {
            requireOpenLocked();
            st.gen++;
            reuse = !createdNow && st.retained && st.runtime != null;
            st.retained = false;
        }
        if (!reuse) {
            try {
                bootFresh(st, true);
            } catch (NoSuchElementException e) {
                discard(st, createdNow);
                throw new RpcException(-32001, "session not found");
            } catch (IOException | RuntimeException e) {
                discard(st, createdNow);
                WriterLockException lockCause = writerLock(e);
                if (lockCause != null) {
                    throw new RpcException(-32006, "session locked by writer: " + lockCause.getMessage());
                }
                throw translate("session resume failed", e);
            }
        }
        drive(st, text);
    }

    /** 有界等待本会话旧代的退休收口（close 同款上限）；超时按忙拒绝。 */
    private void awaitRetirement(SessionState st) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
        while (true) {
            synchronized (lock) {
                if (!st.retiring && !(st.retired && st.runtime != null)) {
                    return;
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new RpcException(-32006, "session retirement in progress");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RpcException(-32603, "interrupted while waiting for retirement");
            }
        }
    }

    @Override
    public void cancel(String session) {
        Objects.requireNonNull(session, "session");
        SessionState st;
        synchronized (lock) {
            requireOpenLocked();
            st = sessions.get(session);
        }
        if (st == null) {
            // 子代单独 cancel（H2）：子会话不在根会话表，但活动 runtime 的委派清单持有其 id。
            if (!cancelDelegation(session)) {
                probeKnown(session);
            }
            return;
        }
        AbortSignal signal;
        AgentHandle handle;
        boolean active;
        synchronized (lock) {
            active = st.inFlight;
            signal = st.signal;
            handle = st.handle;
        }
        if (!active) {
            return;
        }
        // 先关该轮租约再取消（H2）：无父取消级联——子代租约不在此撤
        if (signal != null) {
            hub.closeSignal(signal);
        }
        if (handle != null) {
            handle.agent().cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
        }
    }

    /**
     * 按 childSessionId 找活动 runtime 的委派清单并取消该子代（只收敛该子代，不触父）。
     *
     * @return true = 找到并已发起收敛；false = 非本宿主在管的子代
     */
    private boolean cancelDelegation(String childSession) {
        List<Runtime> runtimes = new ArrayList<>();
        synchronized (lock) {
            for (SessionState st : sessions.values()) {
                if (st.runtime != null) {
                    runtimes.add(st.runtime);
                }
            }
        }
        for (Runtime runtime : runtimes) {
            DelegationService service = runtime.root().resolve(DelegationService.KEY).orElse(null);
            if (service == null) {
                continue;
            }
            for (DelegationSnapshot snap : service.list()) {
                if (snap.state().terminal() || !snap.childSessionId().value().equals(childSession)) {
                    continue;
                }
                return service.cancel(snap.id());
            }
        }
        return false;
    }

    @Override
    public void respondApproval(String requestId, boolean allow) {
        hub.respond(requestId, allow);
    }

    /** 停服收口：撤销整树租约、取消在途轮、退休全部 runtime（幂等）。 */
    @Override
    public void close() {
        List<SessionState> snapshot;
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            snapshot = new ArrayList<>(sessions.values());
        }
        for (SessionState st : snapshot) {
            AbortSignal signal;
            AgentHandle handle;
            boolean active;
            synchronized (lock) {
                signal = st.signal;
                st.signal = null;
                handle = st.handle;
                active = st.inFlight;
            }
            if (signal != null) {
                hub.closeSignal(signal);
            }
            if (active && handle != null) {
                try {
                    handle.agent().cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "cancel during close failed: {0}", e.getMessage());
                }
            }
        }
        for (SessionState st : snapshot) {
            boolean holding;
            long gen;
            synchronized (lock) {
                holding = st.runtime != null || st.handle != null || st.retiring;
                gen = st.gen;
                st.retired = true;
                st.retained = false;
                st.inFlight = false;
            }
            if (holding) {
                retire(st, gen);
            }
        }
        awaitRetirements(snapshot);
    }

    // ────────── 观测面（包内） ──────────

    int openRuntimeCount() {
        synchronized (lock) {
            return (int) sessions.values().stream().filter(st -> st.runtime != null).count();
        }
    }

    int runtimeBoots(String session) {
        synchronized (lock) {
            SessionState st = sessions.get(session);
            return st == null ? 0 : st.boots;
        }
    }

    int completedTurns(String session) {
        synchronized (lock) {
            SessionState st = sessions.get(session);
            return st == null ? 0 : st.completedTurns;
        }
    }

    boolean inFlight(String session) {
        synchronized (lock) {
            SessionState st = sessions.get(session);
            return st != null && st.inFlight;
        }
    }

    boolean retained(String session) {
        synchronized (lock) {
            SessionState st = sessions.get(session);
            return st != null && st.retained;
        }
    }

    // ────────── 装配与轮驱动 ──────────

    private void bootFresh(SessionState st, boolean resume) throws IOException {
        AppBoot.Booted booted = AppBoot.bootReported(boot, injectPrompt ? hub.prompt() : null);
        Runtime runtime = booted.runtime();
        AgentHandle handle = null;
        try {
            AgentRegistry agents = runtime.root().require(AgentRegistry.KEY);
            Id<Session> id = Session.newId(st.id);
            if (resume) {
                SessionPersistence persistence = runtime.root().require(SessionPersistence.KEY);
                SessionPersistence.Loaded loaded = persistence.load(id);
                runtime.root().require(SessionStore.KEY)
                    .create(runtime.root(), id, new CreateOptions(loaded.events(), loaded.header()));
                handle = agents.resume(runtime.root(), new ResumeAgentOptions(id, agentOptions));
            } else {
                handle = agents.create(runtime.root(), CreateAgentOptions.of(id, agentOptions));
            }
            // 先把每轮关键路径挂好，再对锁定案：wire 失败必须连 handle 一起收口，不能只关 runtime
            wire(st, runtime.root(), handle.agent());
        } catch (IOException | RuntimeException e) {
            if (handle != null) {
                disposeQuietly(handle);
            }
            closeQuietly(runtime, e);
            throw e;
        }
        boolean shutdown;
        synchronized (lock) {
            shutdown = closing;
            if (!shutdown) {
                st.runtime = runtime;
                st.handle = handle;
                st.retired = false;
                st.boots++;
            }
        }
        if (shutdown) {
            // 停服竞态：close 快照时本会话尚未落位，它不会替我们退休——就地收口（H2 停服全撤）
            disposeQuietly(handle);
            closeQuietly(runtime);
            throw new RpcException(-32603, "server is shutting down");
        }
    }

    /**
     * 轮管线关键路径挂载：提示词段、delegation / jobs 的同步父绑定（首次 followup 前）、
     * REQUEST 租约。订阅随本 runtime 的 root scope 回收。
     */
    private void wire(SessionState st, Scope root, Agent agent) {
        root.require(SystemPromptService.KEY)
            .register(new PromptSection.Static(0, "You are Javanatic Harness (headless). Be terse."));
        if (root.resolve(DelegationService.KEY).orElse(null) instanceof LocalDelegationService local) {
            local.bindSession(agent.session());
        }
        if (root.resolve(JobService.KEY).orElse(null) instanceof LocalJobService local) {
            local.bindSession(agent.session());
        }
        root.events().onWaterfall(AgentEvents.REQUEST, (carrier, args) -> {
            bindLease(st, carrier, args);
            return args.next();
        });
    }

    private void bindLease(SessionState st, Object carrier, WaterfallArgs<?> args) {
        List<Object> values = args.args();
        if (values.size() < 3 || !(values.get(2) instanceof AbortSignal signal)
                || !(carrier instanceof Agent agent) || !(values.get(0) instanceof Integer turn)) {
            // 形状漂移是本宿主与 agent-loop 的契约破坏：不能装配租约；留痕后失败关闭（ask=false）
            LOG.log(Level.WARNING, "REQUEST shape mismatch; no approval lease for this round");
            return;
        }
        // 同轮多 step / 重试复用同一个 signal：只建一个租约
        if (!hub.bind(signal, st.id, agent.id(), turn)) {
            return;
        }
        if (agent.id().value().equals(st.id)) {
            st.signal = signal;
        }
        agent.whenIdle().thenRun(() -> Thread.startVirtualThread(() -> hub.closeSignal(signal)));
    }

    private void drive(SessionState st, String text) {
        AgentHandle handle;
        long gen;
        synchronized (lock) {
            // 组合已落位、close 已开始但尚未退休的窄窗：不再开新轮，收口归 close
            handle = closing ? null : st.handle;
            gen = st.gen;
        }
        if (handle == null) {
            throw new RpcException(-32603, "server is shutting down");
        }
        Agent agent = handle.agent();
        Thread.startVirtualThread(() -> {
            try {
                agent.followup(UserMessage.of(text, new MessageSource.User()));
                agent.whenIdle().join();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "turn failed in session " + st.id, e);
            } finally {
                finishRound(st, gen);
            }
        });
    }

    /**
     * 轮毕：关本轮租约；后台工作未终局 → 保留 runtime 等其终局（例外）；否则退休。
     * 代守卫：旧代的清理只删自己那一代（gen 不符即退出）。
     */
    private void finishRound(SessionState st, long gen) {
        synchronized (lock) {
            // 代守卫先行：旧代的迟到清理不得碰新代字段——st.signal 是所有代共用槽，
            // 若先关信号再校验代，旧回调会关掉新代在途轮的租约
            if (st.gen != gen || st.retired || closing) {
                return;
            }
        }
        boolean background = backgroundActive(st);
        AbortSignal signal;
        synchronized (lock) {
            if (st.gen != gen || st.retired || closing) {
                return;
            }
            signal = st.signal;
            st.signal = null;
            st.inFlight = false;
            AgentHandle handle = st.handle;
            if (handle != null) {
                st.completedTurns = Math.max(st.completedTurns, lastTurn(handle.agent().session()));
            }
            if (background) {
                st.retained = true;
                startWatcher(st, gen);
                return;
            }
            st.retired = true;
            st.retained = false;
        }
        retire(st, gen);
    }

    /** 后台例外：delegation 子代 / jobs 存在非终局即保留；终局且无后续轮才退休。 */
    private void startWatcher(SessionState st, long gen) {
        Thread.startVirtualThread(() -> {
            while (true) {
                synchronized (lock) {
                    if (closing || st.retired || !st.retained || st.inFlight || st.gen != gen) {
                        return;
                    }
                }
                if (!backgroundActive(st)) {
                    break;
                }
                try {
                    Thread.sleep(WATCH_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            synchronized (lock) {
                if (closing || st.retired || !st.retained || st.inFlight || st.gen != gen) {
                    return;
                }
                st.retired = true;
                st.retained = false;
            }
            retire(st, gen);
        });
    }

    private boolean backgroundActive(SessionState st) {
        Runtime runtime;
        synchronized (lock) {
            runtime = st.runtime;
        }
        if (runtime == null) {
            return false;
        }
        Scope root = runtime.root();
        try {
            boolean delegation = root.resolve(DelegationService.KEY)
                .map(service -> service.list().stream().anyMatch(snap -> !snap.state().terminal()))
                .orElse(false);
            if (delegation) {
                return true;
            }
            return root.resolve(JobService.KEY)
                .map(service -> service.list().stream().anyMatch(snap -> !snap.state().terminal()))
                .orElse(false);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "background probe failed in session {0}: {1}",
                st.id, e.getMessage());
            return false;
        }
    }

    /**
     * 退休：dispose → 落盘 → 关 runtime；失败只记警告（不吞首因到 watcher 线程外）。
     *
     * <p>{@code retiring} 在收口全程持有，{@code runtime} 字段到收口结束才置空——
     * 观测面（{@link #openRuntimeCount()}）因此代表「资源确已释放」，不是「开始释放」，
     * 退场方据此不误判耐久步骤已完成。
     *
     * <p>代守卫（H2 条款 6）：旧回调只收自己那一代——入口与出口都核对 {@code gen}，
     * 代已换则绝不置空新代的 handle / runtime（旧引用仍在本地关闭，不泄漏）。
     */
    private void retire(SessionState st, long gen) {
        AgentHandle handle;
        Runtime runtime;
        synchronized (lock) {
            if (st.gen != gen || st.retiring || (st.handle == null && st.runtime == null)) {
                return;
            }
            st.retiring = true;
            handle = st.handle;
            runtime = st.runtime;
        }
        try {
            if (handle != null) {
                try {
                    handle.disposeAndAwait();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "dispose failed in session " + st.id, e);
                }
                if (runtime != null) {
                    try {
                        runtime.root().require(SessionPersistence.KEY).save(handle.agent().session());
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.WARNING, "save failed in session " + st.id, e);
                    }
                }
            }
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "runtime close failed in session " + st.id, e);
                }
            }
        } finally {
            synchronized (lock) {
                if (st.gen == gen) {
                    st.handle = null;
                    st.runtime = null;
                }
                st.retiring = false;
            }
        }
    }

    /** close 收口：等在途退休把 dispose / 落盘 / runtime 关闭走完（有界）。 */
    private void awaitRetirements(List<SessionState> states) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
        for (SessionState st : states) {
            while (System.nanoTime() < deadline) {
                synchronized (lock) {
                    if (!st.retiring) {
                        break;
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ────────── 辅助 ──────────

    private void discard(SessionState st, boolean createdNow) {
        synchronized (lock) {
            st.inFlight = false;
            st.retained = false;
            if (createdNow) {
                sessions.remove(st.id);
            }
        }
    }

    /** 未知会话判定：不存在 → -32001；写者占用视为已知（在途轮归别的实例）。 */
    private void probeKnown(String session) {
        try {
            probe.load(Session.newId(session));
        } catch (NoSuchElementException e) {
            throw new RpcException(-32001, "session not found");
        } catch (IOException | RuntimeException e) {
            WriterLockException lockCause = writerLock(e);
            if (lockCause == null) {
                throw new RpcException(-32603, "session probe failed: " + e.getMessage());
            }
        }
    }

    private static WriterLockException writerLock(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof WriterLockException lock) {
                return lock;
            }
            if (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
            } else {
                return null;
            }
        }
        return null;
    }

    private static RpcException translate(String prefix, Exception failure) {
        if (failure instanceof RpcException rpc) {
            return rpc;
        }
        return new RpcException(-32603, prefix + ": " + failure.getMessage());
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new RpcException(-32602, "text must be non-blank");
        }
    }

    private void requireOpenLocked() {
        if (closing) {
            throw new RpcException(-32603, "server is shutting down");
        }
    }

    private static void closeQuietly(Runtime runtime, Throwable failure) {
        try {
            runtime.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeQuietly(Runtime runtime) {
        try {
            runtime.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "runtime close during shutdown failed: {0}", e.getMessage());
        }
    }

    private static void disposeQuietly(AgentHandle handle) {
        try {
            handle.disposeAndAwait();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "dispose during shutdown failed: {0}", e.getMessage());
        }
    }

    /** 会话最后完成的轮号（TurnEnd 的 turn 最大值；无终局 = 0）。 */
    private static int lastTurn(Session session) {
        int last = 0;
        for (var entry : session.events()) {
            SessionEvent event = entry.event();
            if (event instanceof TurnEnd end) {
                last = Math.max(last, end.turn());
            }
        }
        return last;
    }

    private static String newSessionId() {
        return "web-" + System.currentTimeMillis() + "-"
            + Long.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }
}
