package io.javanatic.harness.delegation.local;

import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.delegation.Delegation;
import io.javanatic.harness.delegation.DelegationException;
import io.javanatic.harness.delegation.DelegationFailureReason;
import io.javanatic.harness.delegation.DelegationNotFoundException;
import io.javanatic.harness.delegation.DelegationRequest;
import io.javanatic.harness.delegation.DelegationService;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * in-JVM 委派 Provider（it31 停 2）。事实源 = 父会话事件账：每次状态转换先
 * session.append（delegation/snapshot 全量快照、含 budgetTokens）后内存可见。
 * 子代 = durable 会话 Agent（轮末 save）；四路终局映射见 {@link TurnClassifier}。
 * maxActive 根会话单池（预留-放行-还槽，降额不杀驻留）；取消 = 子轮 cancel +
 * cancelling 标志先置（it30 竞态修同款纪律）。Lost：装配期 fold 父账，前生命期
 * 非终局委派标记失联——**v1 显式不认领恢复**（子会话可独立 --resume，服务不复活
 * 树管理关系；重访条件见停 2 设计 §1）。服务不持有子 scope（快照执法唯一经
 * {@link DelegationChildSetup}）。
 */
public final class LocalDelegationService implements DelegationService {

    private final AgentRegistry agents;
    private final Scope ownerScope;
    private final SessionPersistence persistence;
    private final AgentOptions childOptions;
    private final int maxActive;
    private final Map<Id<Delegation>, Entry> delegations = new ConcurrentHashMap<>();
    private final AtomicReference<Session> parentSession = new AtomicReference<>();

    /**
     * @param ownerScope 父装配 scope（子代创建的 owner）
     * @param agents 子代工厂 @param persistence 子/父会话持久化
     * @param provider 子代 provider 名 @param model 子代模型名
     * @param maxActive 根会话并发上限（正数）
     */
    public LocalDelegationService(Scope ownerScope, AgentRegistry agents, SessionPersistence persistence,
                                  String provider, String model, int maxActive) {
        this.ownerScope = Objects.requireNonNull(ownerScope, "ownerScope");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.childOptions = new AgentOptions(provider, model);
        if (maxActive <= 0) {
            throw new IllegalArgumentException("maxActive must be positive");
        }
        this.maxActive = maxActive;
    }

    /** 绑定父会话并执行一次 Lost 扫描；**首绑即父**——后续 created（含自建子代）不重绑。 */
    public synchronized void bindSession(Session session) {
        Objects.requireNonNull(session, "session");
        if (parentSession.get() != null) {
            return;
        }
        parentSession.set(session);
        Map<Id<Delegation>, DelegationSnapshot> folded = new java.util.HashMap<>();
        for (LoggedEvent<?> logged : session.events()) {
            if (logged.event() instanceof DelegationSnapshotEvent event) {
                folded.put(event.snapshot().id(), event.snapshot());
            }
        }
        Instant now = Instant.now();
        for (Map.Entry<Id<Delegation>, DelegationSnapshot> fold : folded.entrySet()) {
            if (delegations.containsKey(fold.getKey())) {
                continue;
            }
            DelegationSnapshot snapshot = fold.getValue();
            if (!snapshot.state().terminal()) {
                DelegationState lost = new DelegationState.Lost(startedOf(snapshot.state()), now);
                snapshot = new DelegationSnapshot(snapshot.id(), snapshot.taskSummary(),
                        snapshot.childSessionId(), snapshot.budgetTokens(), lost);
                session.append(new DelegationSnapshotEvent(System.currentTimeMillis(), snapshot));
            }
            delegations.put(fold.getKey(), new Entry(snapshot));
        }
    }

    @Override
    public DelegationSnapshot start(DelegationRequest request) {
        Session parent = requireSession();
        if (activeCount() >= maxActive) {
            throw new DelegationException("maxActive=" + maxActive + " reached (reserve-release-return)");
        }
        Id<Delegation> id = Delegation.newId(
                "del-" + System.currentTimeMillis() + "-" + String.format("%08x",
                        new java.security.SecureRandom().nextInt()));
        Id<Session> childSessionId = Session.newId("child-" + id.value());
        Instant startedAt = Instant.now();
        DelegationSnapshot initial = new DelegationSnapshot(id, summarize(request.task()), childSessionId,
                request.budgetTokens(), new DelegationState.Running(startedAt));
        AgentHandle handle = agents.create(ownerScope, new CreateAgentOptions(childSessionId, childOptions,
                null, null, new DelegationChildSetup()));
        Entry entry = new Entry(initial, handle, startedAt.toEpochMilli());
        parent.append(new DelegationSnapshotEvent(System.currentTimeMillis(), initial));
        delegations.put(id, entry);
        driveTurn(entry, request.task());
        return entry.snapshot.get();
    }

    private static String summarize(String task) {
        return task.length() <= 200 ? task : task.substring(0, 200) + "…";
    }

    @Override
    public DelegationSnapshot resume(Id<Delegation> id, String answer) {
        Entry entry = entry(id);
        Objects.requireNonNull(answer, "answer");
        if (!entry.snapshot.get().state().paused()) {
            throw new IllegalStateException("resume requires NeedsParentInput, was "
                    + entry.snapshot.get().state());
        }
        // 同步先转 Running（先账后内存）：resume 的返回值不得是过期的暂停快照
        appendAndShow(entry, requireSession(), new DelegationState.Running(
                startedOf(entry.snapshot.get().state())));
        driveTurn(entry, answer);
        return entry.snapshot.get();
    }

    @Override
    public boolean cancel(Id<Delegation> id) {
        Entry entry = entry(id);
        DelegationState state = entry.snapshot.get().state();
        if (state.terminal()) {
            return false;
        }
        entry.cancelling.set(true);
        if (state.paused()) {
            // 暂停态无在途轮可 abort：直接终局化（轮已结束，子代不再动作）
            if (entry.finalized.compareAndSet(false, true)) {
                finalizeSnapshot(entry, requireSession(), new DelegationState.Cancelled(
                        startedOf(entry.snapshot.get().state()), Instant.now()));
            }
            return true;
        }
        entry.handle.agent().cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
        return true;
    }

    @Override
    public DelegationSnapshot snapshot(Id<Delegation> id) {
        return entry(id).snapshot.get();
    }

    @Override
    public List<DelegationSnapshot> list() {
        List<Entry> ordered = new ArrayList<>(delegations.values());
        ordered.sort(Comparator.comparingLong(entry -> entry.startedAtMillis));
        return ordered.stream().map(entry -> entry.snapshot.get()).toList();
    }

    /** 拥有 scope 关闭：级联取消未终局子代（父会话收口 = 树收口）。 */
    @Override
    public void close() {
        for (Entry entry : delegations.values()) {
            if (!entry.snapshot.get().state().terminal()) {
                cancel(entry.snapshot.get().id());
            }
        }
    }

    private long activeCount() {
        return delegations.values().stream()
                .filter(entry -> !entry.snapshot.get().state().terminal()).count();
    }

    private Session requireSession() {
        Session session = parentSession.get();
        if (session == null) {
            throw new IllegalStateException("delegation service session not attached");
        }
        return session;
    }

    /** 子代轮驱动：followup → whenIdle → 四路分类 → 先父账后内存；轮末 save 子会话。 */
    private void driveTurn(Entry entry, String message) {
        Session parent = requireSession();
        Thread.startVirtualThread(() -> {
            try {
                entry.handle.agent().followup(UserMessage.of(message, new MessageSource.User()));
                entry.handle.agent().whenIdle().join();
            } catch (RuntimeException e) {
                if (entry.finalized.compareAndSet(false, true)) {
                    finalizeSnapshot(entry, parent, new DelegationState.Failed(
                            DelegationFailureReason.EXECUTION_ERROR,
                            Instant.ofEpochMilli(entry.startedAtMillis), Instant.now()));
                }
                return;
            }
            DelegationState next = TurnClassifier.classify(
                    entry.handle.agent().session().events(),
                    Instant.ofEpochMilli(entry.startedAtMillis), entry.cancelling.get());
            if (next.terminal()) {
                // 仅终局消耗 finalized CAS；暂停（NeedsParentInput）不消耗——resume 后
                // 的终局仍须能落账（集成先行钓出的真缺陷：暂停吞 CAS 则 resume/cancel 全哑）
                if (entry.finalized.compareAndSet(false, true)) {
                    finalizeSnapshot(entry, parent, next);
                }
            } else {
                appendAndShow(entry, parent, next);
            }
        });
    }

    private void finalizeSnapshot(Entry entry, Session parent, DelegationState terminal) {
        try {
            persistence.save(entry.handle.agent().session());
        } catch (java.io.IOException e) {
            throw new DelegationException("cannot persist child session", e);
        }
        appendAndShow(entry, parent, terminal);
    }

    private void appendAndShow(Entry entry, Session parent, DelegationState state) {
        DelegationSnapshot snapshot = new DelegationSnapshot(entry.snapshot.get().id(),
                entry.snapshot.get().taskSummary(), entry.snapshot.get().childSessionId(),
                entry.snapshot.get().budgetTokens(), state);
        parent.append(new DelegationSnapshotEvent(System.currentTimeMillis(), snapshot));
        entry.snapshot.set(snapshot);
    }

    private static Instant startedOf(DelegationState state) {
        return switch (state) {
            case DelegationState.Running running -> running.startedAt();
            case DelegationState.NeedsParentInput needsParentInput -> needsParentInput.startedAt();
            case DelegationState.Succeeded succeeded -> succeeded.startedAt();
            case DelegationState.Failed failed -> failed.startedAt();
            case DelegationState.Cancelled cancelled -> cancelled.startedAt();
            case DelegationState.Lost lost -> lost.startedAt();
        };
    }

    private Entry entry(Id<Delegation> id) {
        Entry entry = delegations.get(Objects.requireNonNull(id, "id"));
        if (entry == null) {
            throw new DelegationNotFoundException(id.value());
        }
        return entry;
    }

    /** 运行时记录：快照原子可见；finalized 保证终局/暂停事件恰一次（it30 同款 CAS）。 */
    private static final class Entry {
        final AtomicReference<DelegationSnapshot> snapshot;
        final AgentHandle handle;
        final long startedAtMillis;
        final AtomicBoolean finalized = new AtomicBoolean();
        final AtomicBoolean cancelling = new AtomicBoolean();

        Entry(DelegationSnapshot initial) {
            this(initial, null, System.currentTimeMillis());
        }

        Entry(DelegationSnapshot initial, AgentHandle handle, long startedAtMillis) {
            this.snapshot = new AtomicReference<>(initial);
            this.handle = handle;
            this.startedAtMillis = startedAtMillis;
        }
    }
}
