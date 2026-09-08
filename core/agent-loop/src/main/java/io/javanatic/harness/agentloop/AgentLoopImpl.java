package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.AgentStatus;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.Inbox;
import io.javanatic.harness.agent.InboxTarget;
import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.ToolSchema;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;

import java.lang.System.Logger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Turn/Step 状态机驱动。治理依赖（Session/ToolExecutor/LoopGuard/SystemPromptService）
 * 全部构造器强制——「可选的治理」组装不出来（R4）。driver 是每次唤醒新建的虚拟线程，
 * 取消只经 {@link AbortSignal#checkAbort()} 显式传播点；模型 toolCalls 只交
 * {@link ToolExecutor}（全库唯一分发点，R2 架构测试断言）；请求指纹先于调用落账（R1）。
 */
public final class AgentLoopImpl implements Agent {

    private static final Logger LOG = System.getLogger(AgentLoopImpl.class.getName());

    private final Scope agentScope;
    private final Session session;
    private final LlmService llm;
    private final ToolRegistry tools;
    private final ToolExecutor executor;
    private final SystemPromptService prompts;
    private final LoopGuard guard;
    private final AgentRegistry registry;
    private final Clock clock;
    private final AgentOptions options;
    private final Events events;
    private final Inbox inbox = new Inbox();

    private int nextTurn;
    private AbortController activeAbort;

    // driver 槽位（synchronized(this) 保护）
    private AgentStatus status = AgentStatus.IDLE;
    private CompletableFuture<Void> driver;
    private CompletableFuture<Void> idleFuture = CompletableFuture.completedFuture(null);

    // CHECKSTYLE:OFF ParameterNumber —— R4 构造器强制:治理依赖全显式注入(04 §4),拆分即弱化证明
    AgentLoopImpl(Scope agentScope, Session session, LlmService llm, ToolRegistry tools,
                  ToolExecutor executor, SystemPromptService prompts, LoopGuard guard,
                  AgentRegistry registry, Clock clock, AgentOptions options) {
        this.agentScope = Objects.requireNonNull(agentScope, "agentScope");
        this.session = Objects.requireNonNull(session, "session");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.options = Objects.requireNonNull(options, "options");
        this.events = agentScope.require(Runtime.KEY).events();
        this.nextTurn = (int) session.events().stream()
            .filter(entry -> entry.event() instanceof TurnStart).count();
    }

    // CHECKSTYLE:ON ParameterNumber

    @Override
    public Id<Session> id() {
        return session.id();
    }

    @Override
    public AgentOptions options() {
        return options;
    }

    @Override
    public Session session() {
        return session;
    }

    @Override
    public Inbox inbox() {
        return inbox;
    }

    @Override
    public synchronized AgentStatus status() {
        return status;
    }

    @Override
    public Scope scope() {
        return agentScope;
    }

    // ────────── 输入投递 ──────────

    @Override
    public void send(UserMessage message, InboxTarget target, boolean wakeup) {
        inbox.append(target, message);
        if (wakeup) {
            ensureDriver();
        }
    }

    // ────────── driver ──────────

    /** 确保 driver 在跑：幂等；唤醒竞态由 settle 的退出窗口重查兜底。 */
    private synchronized void ensureDriver() {
        if (driver != null && !driver.isDone()) {
            return;
        }
        idleFuture = new CompletableFuture<>();
        status = AgentStatus.RUNNING;
        events.notify(AgentEvents.STATUS, agentScope, this, AgentStatus.RUNNING);
        driver = spawnDriver(() -> {
            drivePendingWork();
            return null;
        });
    }

    /**
     * driver 线程体：整段跑在 initiator 绑定内；finally 收敛状态并处理唤醒竞态
     * （退出窗口内新 work → 立即重启 driver，不丢唤醒）。
     */
    private CompletableFuture<Void> spawnDriver(Supplier<?> body) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        done.whenComplete((ignored, failure) -> {
            if (failure != null) {
                // fail loud：驱动体异常不静默（turn 收敛路径之外的意外失败）
                LOG.log(Logger.Level.ERROR, "agent driver failed", failure);
            }
        });
        Thread.ofVirtual().name("jh-agent-driver").start(() -> {
            try {
                registry.withInitiator(this, () -> {
                    body.get();
                    return null;
                });
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            } finally {
                settleDriver();
            }
        });
        return done;
    }

    private void drivePendingWork() {
        while (hasWork()) {
            runTurn();
        }
    }

    private boolean hasWork() {
        return !inbox.nextTurn().isEmpty() || !inbox.nextStep().isEmpty();
    }

    private void settleDriver() {
        CompletableFuture<Void> settled;
        synchronized (this) {
            status = AgentStatus.IDLE;
            driver = null;
            if (hasWork()) {
                ensureDriver();
                return;
            }
            settled = idleFuture;
        }
        events.notify(AgentEvents.STATUS, agentScope, this, AgentStatus.IDLE);
        settled.complete(null);
    }

    @Override
    public synchronized CompletableFuture<Void> whenIdle() {
        return idleFuture;
    }

    @Override
    public <T> CompletableFuture<T> runMaintenance(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this) {
            if (driver != null && !driver.isDone()) {
                throw new IllegalStateException("agent is busy: turn driver or maintenance already running");
            }
            idleFuture = new CompletableFuture<>();
            status = AgentStatus.RUNNING;
            events.notify(AgentEvents.STATUS, agentScope, this, AgentStatus.RUNNING);
            driver = spawnDriver(() -> {
                try {
                    result.complete(task.get());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
                drivePendingWork();
                return null;
            });
        }
        return result;
    }

    // ────────── cancel ──────────

    @Override
    public void cancel(AgentCancelCause cause, CancelOptions cancelOptions) {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(cancelOptions, "cancelOptions");
        AbortController abort = activeAbort;
        if (abort != null) {
            abort.cancel(cause);
        }
        if (!cancelOptions.keepInbox()) {
            inbox.clear();
        }
    }

    // ────────── Turn ──────────

    private void runTurn() {
        AbortController abort = new AbortController();
        activeAbort = abort;
        AbortSignal signal = abort.signal();
        try {
            int turn = ++nextTurn;
            session.append(new TurnStart(clock.millis(), turn));

            List<UserMessage> claimed = inbox.claim(InboxTarget.NEXT_TURN);
            if (claimed.isEmpty()) {
                claimed = inbox.claim(InboxTarget.NEXT_STEP);
            }
            if (claimed.isEmpty()) {
                // cancel(keepInbox=false) 在 hasWork 之后清空了 inbox：空转关轮
                session.append(new TurnEnd(clock.millis(), turn, new TurnEndReason.Completed()));
                return;
            }

            TurnEndReason reason;
            try {
                // guard 在 reason-try 内：超限必须以 turn/end(Error) 关轮，
                // 不能留下开着的 turn/start
                guard.checkBudget(session, turn, 0);
                Optional<List<UserMessage>> admitted = admit(claimed, turn, signal);
                if (admitted.isEmpty()) {
                    // pre-step 拒绝：本轮不落 user/message、不开 step
                    session.append(new TurnEnd(clock.millis(), turn, new TurnEndReason.Completed()));
                    return;
                }
                for (UserMessage message : admitted.orElseThrow()) {
                    session.append(toUserMessageEvent(message));
                }
                runStepLoop(turn, abort);
                events.notifyOrdered(AgentEvents.TURN_STOPPING, agentScope, this,
                    new AgentEvents.TurnStopping(turn));
                reason = new TurnEndReason.Completed();
            } catch (AbortedException e) {
                reason = new TurnEndReason.Aborted(
                    abort.isAborted() ? AbortController.describe(abort.cause()) : e.getMessage());
            } catch (GuardRejectException e) {
                reason = new TurnEndReason.Error(e.getMessage());
            } catch (RuntimeException e) {
                // 意外失败收敛为 Error 关轮（turn 隔离）；驱动继续排空后续 work
                LOG.log(Logger.Level.ERROR, "turn " + turn + " failed", e);
                reason = new TurnEndReason.Error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            session.append(new TurnEnd(clock.millis(), turn, reason));
        } finally {
            activeAbort = null;
        }
    }

    /** pre-step 裁决：Reject 返回 empty（调用方关轮），Enter 返回 admitted 批。 */
    private Optional<List<UserMessage>> admit(List<UserMessage> claimed, int turn, AbortSignal signal) {
        PreStepDecision decision = events.waterfall(AgentEvents.PRE_STEP, agentScope, this,
            List.of(claimed, turn, signal), fallback -> new PreStepDecision.Enter(claimed));
        return switch (decision) {
            case PreStepDecision.Enter enter -> Optional.of(enter.messages());
            case PreStepDecision.Reject reject -> {
                LOG.log(Logger.Level.DEBUG, "pre-step rejected turn {0}: {1}", turn, reject.reason());
                yield Optional.empty();
            }
        };
    }

    // ────────── Step loop ──────────

    private void runStepLoop(int turn, AbortController abort) {
        AbortSignal signal = abort.signal();
        int step = 0;
        int retriesLeft = 0;
        while (true) {
            signal.checkAbort();
            guard.checkBudget(session, turn, step);
            session.append(new StepStart(clock.millis(), turn, step));

            String systemPrompt = prompts.assemble(session);
            List<ToolSchema> schemas = tools.schemas();
            LlmCallConfig config = events.waterfall(AgentEvents.REQUEST, agentScope, this,
                List.of(turn, step, signal),
                fallback -> new LlmCallConfig(options.provider(), options.model()));

            // R1 锚点：请求指纹先于调用落账（消息窗口 = 当前日志前缀）
            session.append(new LlmRequestEvent(clock.millis(), turn, step,
                RequestFingerprints.sha256(systemPrompt),
                RequestFingerprints.sha256(RequestFingerprints.toolSchemaFingerprint(schemas)),
                0, session.seq() - 1, Map.of()));

            ChunkAssembly.Assembled assembled;
            try (Stream<StreamChunk> chunks = llm.stream(config, new LlmRequest(
                systemPrompt.isEmpty() ? null : systemPrompt,
                session.deriveMessages(), schemas, Map.of()), signal)) {
                assembled = ChunkAssembly.fold(chunks.toList());
            } catch (AbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                Optional<AgentEvents.RequestErrorDecision> decision = events.firstOf(
                    AgentEvents.REQUEST_ERROR, agentScope, this, List.of(turn, step, e));
                if (decision.isPresent() && retriesLeft < decision.orElseThrow().maxRetries()) {
                    retriesLeft++;
                    continue;
                }
                throw e;
            }
            retriesLeft = 0;

            session.append(new AssistantMessageEvent(clock.millis(), turn, step,
                new AssistantMessage(new MessageSource.Model(config.provider(), config.model()),
                    contentBlocks(assembled)),
                assembled.usage(), new SurfaceOp.Append(), null));

            List<ToolUseBlock> calls = assembled.toolCalls().stream()
                .map(call -> new ToolUseBlock(call.id(), call.name(), call.arguments())).toList();
            if (calls.isEmpty()) {
                session.append(new StepEnd(clock.millis(), turn, step));
                return;
            }
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(calls, session, turn, step, signal);
            session.append(new StepEnd(clock.millis(), turn, step));

            if (abort.isAborted()) {
                return;
            }
            if (results.stream().anyMatch(entry -> entry.event().concludesTurn())) {
                return;
            }
            for (UserMessage steering : inbox.claim(InboxTarget.NEXT_STEP)) {
                session.append(toUserMessageEvent(steering));
            }
            step++;
        }
    }

    private UserMessageEvent toUserMessageEvent(UserMessage message) {
        return new UserMessageEvent(clock.millis(), message, new SurfaceOp.Append(), null);
    }

    private static List<ContentBlock> contentBlocks(ChunkAssembly.Assembled assembled) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!assembled.text().isEmpty()) {
            blocks.add(new TextBlock(assembled.text()));
        }
        assembled.toolCalls().forEach(call ->
            blocks.add(new ToolUseBlock(call.id(), call.name(), call.arguments())));
        return blocks;
    }

}
