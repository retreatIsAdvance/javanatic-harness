# 04 · Agent Loop — Turn / Step 状态机

Agent Loop 是整个系统的驱动核心。它消费输入、开 Turn、跑 Step、调模型、跑工具、关 Turn，全程把**模型可见的每个事实都写进 Session 日志**。

> **关键原则（移植自 dsh）**：新行为挂扩展点（事件），**不改 loop 本身**。Loop 只读日志、写日志、走 waterfall。

本篇是不变式 **R1（落账侧）**、**R2（单一分发点）**、**R4（构造器强制治理依赖）** 的 loop 侧载体，见 §12。

## 1. 三层循环层级（移植 dsh loop hierarchy）

```
Round（外层策略迭代，如 Goal round）
  └─ Turn（一次 admitted input 的排空，含 0+ Step）
       └─ Step（一次模型请求 + 它触发的工具调用）
```

- **Turn**：一次输入的完整排空，模型和工具都停下来或被策略终止时结束。
- **Step**：一次模型请求 + 它引发的工具执行；一个 Turn 含 0 或多个 Step。
- **Round**：外层策略迭代（Goal round / Ralph round）。MVP 不实现 Round，留接口。

## 2. Agent 接口（公开契约）

```java
// io.javanatic.harness.agent.Agent
package io.javanatic.harness.agent;

import java.util.concurrent.CompletableFuture;

/**
 * 活跃 Agent 的公开句柄。对应 dsh 的 Agent interface。
 * UI、编排器、插件都通过这个接口操作 agent；具体实现在 agent-loop 模块内部。
 */
public interface Agent {

    SessionId id();                 // 唯一身份（与 session 共享）
    AgentOptions options();         // provider 路由 + model
    Session session();              // 驱动的 live session；其日志是真相源
    Inbox inbox();                  // agent 拥有的 pending work 投影
    AgentStatus status();           // idle / running
    Scope scope();                  // agent-local 注册域（dispose 时回收）

    // ────────── 输入投递 ──────────

    /** 把输入路由到 inbox 边界，可选唤醒 driver。 */
    void send(UserMessage message, InboxTarget target, boolean wakeup);

    /** 排一个普通 follow-up turn 并唤醒。该消息成为自己 turn 的唯一 ordinary 消息。 */
    void followup(UserMessage message);

    /** 提交 steering 给最近的 step。idle driver 开 turn；running driver 下一步消费。 */
    void steer(UserMessage message);

    /** 排模型可见上下文给下一个 pre-step，不唤醒。 */
    void inject(UserMessage message);

    // ────────── 控制 ──────────

    /** 取消活跃 turn 或 between-turn 任务。cause 是稳定的调用方意图。 */
    void cancel(AgentCancelCause cause, CancelOptions options);

    /** 等当前整个 agent 活动达到静止。 */
    CompletableFuture<Void> whenIdle();

    /**
     * 在 true idle 阶段跑一个非 turn 维护任务（如 compaction、标题生成）。
     * 任务同步启动占住 idle 阶段；后来的 waking input 留 inbox 等任务 settle。
     * @throws IllegalStateException 当 turn 驱动或另一维护任务已占用 agent。
     */
    <T> CompletableFuture<T> runMaintenance(java.util.function.Function<AbortSignal, T> task);
}

enum AgentStatus { IDLE, RUNNING }
```

`InboxTarget`（`NEXT_TURN` / `NEXT_STEP`）与 `AgentCancelCause`（sealed：`User` / `Parent` / `Hook(reason)` / `Disposed`）与前一版一致，不赘述。

## 3. Inbox — 投递与排空

```java
// io.javanatic.harness.agent.Inbox
/**
 * Agent 拥有的 pending 消息投影：nextTurn（普通排队）、nextStep（steering + injected）。
 * 修改记录为 durable 的 agent/inbox/* 事件。对应 dsh 的 Inbox。
 */
public final class Inbox {

    private final Deque<UserMessage> nextTurn = new ArrayDeque<>();
    private final Deque<UserMessage> nextStep = new ArrayDeque<>();
    private final Set<String> pendingIds = new HashSet<>();

    /** 追加到指定列表尾部。重复 id fail loud。 */
    public synchronized void append(InboxTarget target, UserMessage msg) { /* ... */ }

    /**
     * 认领 step 批次：全部 nextStep + （turn 边界时）一条 nextTurn。
     * 纯删除 splice；loop 单独发 claimed 通知。
     */
    public synchronized List<UserMessage> claim(InboxTarget context) { /* ... */ }

    /** cancel(keepInbox=false) 时清空；keepInbox=true 时保留。 */
    public synchronized void clear() { /* ... */ }

    public synchronized List<UserMessage> nextTurn() { return List.copyOf(nextTurn); }
    public synchronized List<UserMessage> nextStep() { return List.copyOf(nextStep); }
}
```

## 4. 构造与治理依赖（R4）

Loop 不接受"可选的治理"。构造器签名即治理证明：

```java
// io.javanatic.harness.agentloop.AgentLoopImpl
class AgentLoopImpl implements Agent {

    private final Scope agentScope;        // agent-local 注册域
    private final Session session;         // 审计日志（R4：无审计不成 loop）
    private final LlmService llm;
    private final ToolRegistry tools;      // 唯一工具来源（R2）
    private final ToolExecutor executor;   // 唯一执行路径（R2；其构造器强制 ApprovalService）
    private final SystemPromptService prompts;
    private final LoopGuard guard;         // 停止条件（R4：max-turns/max-steps/budget）
    private final AgentRegistry registry;
    private final Clock clock;             // 事件时间来源（R1：可注入，测试可冻结）
    private final Inbox inbox = new Inbox();
    private final AgentOptions options;

    AgentLoopImpl(Scope agentScope, Session session, LlmService llm, ToolRegistry tools,
                  ToolExecutor executor, SystemPromptService prompts, LoopGuard guard,
                  AgentRegistry registry, Clock clock, AgentOptions options) { /* 直接赋值，无 null 默认 */ }
```

**治理从"是否挂载"变成"挂的是哪个实现"**：没有 `LoopGuard` 就组装不出 loop——缺依赖是组合错误（装配期 fail loud，07 的 boot 校验），不是运行时静默裸奔。`ToolExecutor` 的构造器同理强制 `ApprovalService`（05 §ToolExecutor）。配置层只选择实现（auto-approve vs human-gate、限流参数），`--verify` 可断言（07）。

## 5. driver — 虚拟线程排空循环

```java
    private volatile AgentStatus status = AgentStatus.IDLE;
    private volatile CompletableFuture<Void> driver = null;
    private volatile AbortController activeAbort = null;

    // ────────── 输入投递（线程安全，可从任意线程调用）──────────

    @Override
    public void send(UserMessage message, InboxTarget target, boolean wakeup) {
        inbox.append(target, message);
        if (wakeup) ensureDriver();
    }

    // ────────── driver ──────────

    /** 确保 driver 在跑。幂等。 */
    private synchronized void ensureDriver() {
        if (driver != null && !driver.isDone()) return;
        status = AgentStatus.RUNNING;
        scope().events() /* global */ .notify(AgentEvents.STATUS, scope(), this, AgentStatus.RUNNING);
        driver = CompletableFuture.runAsync(
            () -> registry.withInitiator(this, this::drainLoop),
            agentScope.require(Runtime.KEY).virtualThreads());
    }

    /**
     * 排空循环。整段跑在 initiator 绑定内（R：因果归因，见 §10）。
     *
     * 唤醒竞态修复：driver 退出前在 finally 里重查 hasWork()——
     * send() 在"driver 决定退出但未置 IDLE"窗口内投递时，不会丢唤醒。
     */
    private void drainLoop() {
        try {
            while (hasWork()) {
                runTurn();
            }
        } finally {
            synchronized (this) {
                status = AgentStatus.IDLE;
                driver = null;
                if (hasWork()) {
                    ensureDriver();   // 退出窗口内有新 work → 立即重启 driver
                    return;
                }
            }
            notifyIdle();            // 真 idle：完成 whenIdle future
        }
    }

    private boolean hasWork() {
        return !inbox.nextTurn().isEmpty() || !inbox.nextStep().isEmpty();
    }
```

## 6. Turn — 编号、认领、落账位置

```java
    private void runTurn() {
        int turn = nextTurnNumber();               // = 日志中 TurnStart 计数 + 1（见下）
        activeAbort = new AbortController();
        AbortSignal signal = activeAbort.signal();

        // turn/start（时间来自注入的 clock，不直接调系统时钟——R1 可测）
        session.append(new TurnStart(clock.millis(), turn));

        // claim 输入：turn 边界优先普通排队，steering 兜底
        List<UserMessage> claimed = inbox.claim(InboxTarget.NEXT_TURN);
        if (claimed.isEmpty()) claimed = inbox.claim(InboxTarget.NEXT_STEP);

        // agent/pre-step（waterfall）：接受/拒绝/改写本批消息
        PreStepDecision decision = events().waterfall(
            AgentEvents.PRE_STEP, scope(), this,
            List.of(claimed, turn, signal),
            () -> new PreStepDecision.Enter(claimed));

        if (decision instanceof PreStepDecision.Reject) {
            session.append(new TurnEnd(clock.millis(), turn, TurnEndReason.completed()));
            return;
        }
        List<UserMessage> admitted =
            decision instanceof PreStepDecision.Enter e ? e.messages() : claimed;

        // user/message 在 TURN 层落账一次（pre-step 之后、第一个 step 之前）。
        // 不随 step 重复——后续 step 只落 steering（在认领边界落账）。
        for (UserMessage m : admitted) {
            session.append(toUserMessageEvent(m));
        }

        runStepLoop(turn, signal);

        // agent/turn-stopping（notifyOrdered）：listener 可 steer() → hasWork 复真 → 再排一轮
        events().notifyOrdered(AgentEvents.TURN_STOPPING, scope(), this,
            new TurnStoppingPayload(turn, signal));

        TurnEndReason endReason = activeAbort.isAborted()
            ? TurnEndReason.aborted(activeAbort.cause())
            : TurnEndReason.completed();
        session.append(new TurnEnd(clock.millis(), turn, endReason));
    }

    /** turn 号从日志派生：TurnStart 个数 + 1。resume 时初始化一次，之后 loop 内自增。 */
    private int nextTurnNumber() { /* 构造时 1 + countTurnStarts(session.events())，此后 ++ */ }
```

**turn ≠ seq**（修正前版缺陷）：turn 号是 TurnStart 事件的计数语义，与日志序号无关——中间穿插的 chunk/tool 事件不会推高 turn 号。step 号同理由 loop 在 turn 内自增（从 0 起）。

## 7. Step loop — 请求指纹（R1）与流式消费

```java
    private void runStepLoop(int turn, AbortSignal signal) {
        int step = 0;
        while (true) {
            signal.checkAbort();
            guard.checkBudget(session, turn, step);   // R4：每步先查停止条件（超限抛 GuardReject）

            session.append(new StepStart(clock.millis(), turn, step));

            // 组装请求内容（R1：只读日志事实——时间读 event.time，配置读组合清单）
            String systemPrompt = prompts.assemble(session);
            String toolsSchema = tools.schemaJson();
            LlmCallConfig callConfig = events().waterfall(
                AgentEvents.REQUEST, scope(), this,
                List.of(turn, step, signal),
                () -> new LlmCallConfig(options.provider(), options.model(), options.params()));

            // R1 锚点：请求指纹落账（哈希 + 消息窗口区间 + 参数）
            session.append(new LlmRequestEvent(
                clock.millis(), turn, step,
                sha256(systemPrompt), sha256(toolsSchema),
                0, session.seq() - 1,                  // 消息窗口 = 当前日志前缀
                callConfig.params()));

            // 模型流式：阻塞 Stream，跑在 driver 虚拟线程上（05 §LLM seam）
            AssistantMessage assistantMsg;
            try (Stream<StreamChunk> chunks = llm.stream(callConfig, session.requestHeader()
                .map(h -> buildRequest(systemPrompt, toolsSchema, session.deriveMessages(), h))
                .orElseGet(() -> buildRequest(systemPrompt, toolsSchema, session.deriveMessages(), null)),
                signal)) {
                assistantMsg = accumulate(chunks, turn, step, signal);
                // chunks 逐个：signal.checkAbort()；可选 append AssistantChunkEvent（遥测）
                // 结束：append AssistantMessageEvent（surface，带 usage）
            }

            List<ToolCallEvent> calls = extractToolCalls(assistantMsg, turn, step);
            if (calls.isEmpty()) {
                session.append(new StepEnd(clock.millis(), turn, step));
                return;
            }

            // 工具执行：唯一路径经 executor；tool/call 与 tool/result 均由 executor 落账（R2，05）
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(calls, signal);

            session.append(new StepEnd(clock.millis(), turn, step));

            if (signal.isAborted()) return;
            if (results.stream().anyMatch(r -> r.event().concludesTurn())) return;

            // steering 在此认领并落账（step 边界）
            List<UserMessage> steering = inbox.claim(InboxTarget.NEXT_STEP);
            for (UserMessage m : steering) session.append(toUserMessageEvent(m));
            if (steering.isEmpty() && !shouldContinue(calls, results)) return;
            step++;
        }
    }
```

**user/message 的落账规则**（统一前版两处不一致）：admitted 批在 **turn 层**落账一次（pre-step 之后）；steering 在**认领它的 step 边界**落账。`UserMessageEvent` 无 turn/step 字段——它的位置由日志顺序表达，投影按顺序取。

## 8. cancel — 实现语义

```java
    @Override
    public void cancel(AgentCancelCause cause, CancelOptions options) {
        AbortController abort = activeAbort;
        if (abort != null) abort.cancel(cause);       // first-cause-wins；传播给流式/工具
        if (!options.keepInbox()) inbox.clear();      // 默认清空 pending；true 则保留供 resume
    }
```

- 取消的**传播**靠 `AbortSignal.checkAbort()`（§9）：模型流式消费循环、工具执行、waterfall listener 在关键点自查，快速失败为 `AbortedException`。
- 取消的**收敛**：drainLoop 捕获 `AbortedException` → turn 以 `aborted(cause)` 关闭（`TurnEnd` 落账）→ hasWork() 决定是否继续下一个 turn。
- `keepInbox=true` 是 resume 场景：取消驱动但保留 pending 输入。

## 9. AbortController — 取消传播

```java
// io.javanatic.harness.agentloop.AbortController
/**
 * 取消控制器：把一个 AgentCancelCause 传播给所有协作者。
 * first-cause-wins：第一次 cause 生效，后续 cancel no-op。
 */
public final class AbortController {
    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public AbortSignal signal() { return new AbortSignal(this); }
    public void cancel(AgentCancelCause c) {
        if (cancelled.compareAndSet(false, true)) {
            cause.set(c);
            listeners.forEach(Runnable::run);
        }
    }
    public boolean isAborted() { return cancelled.get(); }
    public AgentCancelCause cause() { return cause.get(); }
    void addListener(Runnable r) { if (isAborted()) r.run(); else listeners.add(r); }
}

public record AbortSignal(AbortController controller) {
    public boolean isAborted() { return controller.isAborted(); }
    public AgentCancelCause cause() { return controller.cause(); }
    /** 关键点自查：已取消则抛 AbortedException（含 cause）。 */
    public void checkAbort() { if (isAborted()) throw new AbortedException(controller.cause()); }
}
```

虚拟线程 + `checkAbort()` 是 JH 的取消机制：不用 `Thread.interrupt()`（不会在任意安全点抛 `InterruptedException`，传播点显式可控）。

## 10. AgentRegistry 与 initiator（ScopedValue 绑定点）

```java
// io.javanatic.harness.agent.AgentRegistry
/** Agent 服务：跟踪活跃 agent，携带 process-local initiator，提供 create/resume 工厂。 */
public final class AgentRegistry {

    private final ConcurrentHashMap<SessionId, Agent> agents = new ConcurrentHashMap<>();
    private final AtomicReference<AgentFactory> factory = new AtomicReference<>();

    /** loop 插件注册自己的工厂。重复注册 fail loud。 */
    public Disposable setFactory(AgentFactory f) { /* CAS + Disposable */ }

    public AgentHandle create(Scope owner, CreateAgentOptions opts)  { /* 工厂 + 注册表 */ }
    public AgentHandle resume(Scope owner, ResumeAgentOptions opts)  { /* load + 工厂 */ }
    public Agent get(SessionId id) { return agents.get(id); }
    public List<Agent> list() { return List.copyOf(agents.values()); }

    // ── initiator（process-local 因果归因，JEP 506 ScopedValue）──

    private static final ScopedValue<Agent> INITIATOR = ScopedValue.newInstance();

    public Agent currentInitiator() { return INITIATOR.isBound() ? INITIATOR.get() : null; }

    public <T> T withInitiator(Agent a, Supplier<T> op) {
        return ScopedValue.where(INITIATOR, a).get(op);
    }
}
```

**绑定规则**（不变式化，09 §并发细述）：

1. **绑定点唯一**：`drainLoop` 整段跑在 `withInitiator(this, ...)` 内。agent 生命周期内的一切模型调用、工具执行、事件派发都发生在绑定内。
2. **继承发生在创建时**：`ScopedValue` 按线程创建时刻快照继承——绑定后 caller 再绑新值，已派生的子虚拟线程看不到。因此**禁止 pooled-executor 提交**（池化线程的绑定属于别人）；需要并发就 fork 虚拟线程（工具执行、notify 派发都如此）。
3. **不可变**：绑定期内无人能改写 initiator，杜绝 ThreadLocal 的 set/forget 泄漏。

## 11. AgentHandle — 所有权与 dispose

```java
// io.javanatic.harness.agent.AgentHandle
/**
 * 一个被拥有的 agent + 其 disposer。对应 dsh 的 AgentHandle。
 * dispose 是一个 capability：只有持有 handle 的消费者能 teardown agent。
 * dispose 流程：cancel(Disposed) → 等 driver 退出 → 注销 agent → 回收 agent scope。
 */
public record AgentHandle(Agent agent, CompletableFuture<Void> dispose) {

    /** dispose 并阻塞等待完成（虚拟线程上调用）。 */
    public void disposeAndAwait() { dispose.join(); }
}
```

record 自动生成 `dispose()` 访问器（返回 `CompletableFuture<Void>`）；便捷方法命名 `disposeAndAwait()` 避免与访问器冲突（修正前版同签名重复的编译错误）。dispose future 由工厂用 `CompletableFuture` 组合构建（cancel → whenIdle → scope.close 链），不是裸 lambda。

## 12. 不变式落点

| 不变式 | 本篇机制 |
|---|---|
| R1 可重建 | loop 落账 LlmRequestEvent（§7）；时间来自注入 Clock；提示词组装只读日志 |
| R2 执行一致 | loop 只从 ToolRegistry 取 schema；toolCalls 只交 ToolExecutor（全库唯一调用点，架构测试断言）；tool/result 由 executor 落账（05） |
| R4 治理完备 | AgentLoopImpl 构造器强制 LoopGuard/Session/ToolExecutor；executor 构造器强制 ApprovalService（05）；装配期 fail loud（07） |

## 13. 一轮 Turn 的完整时序（伪代码）

```
[ensureDriver]
  status = RUNNING; notify agent/status(RUNNING)
  drainLoop（在 withInitiator 内，虚拟线程）:
    while hasWork():
      runTurn():
        turn = nextTurnNumber(); abort = new AbortController()
        append turn/start(clock)
        claimed = claim(NEXT_TURN) ∪ fallback claim(NEXT_STEP)
        decision = waterfall(agent/pre-step, default=Enter(claimed))
        Reject → append turn/end(completed); continue
        append user/message × admitted          ← TURN 层一次
        runStepLoop(turn, signal):
          step = 0; loop:
            signal.checkAbort(); guard.checkBudget(...)
            append step/start
            prompt = prompts.assemble(session)  ← 只读日志（R1）
            schemas = tools.schemaJson()        ← 唯一来源（R2）
            callConfig = waterfall(agent/request, default=options)
            append llm/request(sha256(prompt), sha256(schemas), [0, seq-1], params)   ← R1
            try (Stream<chunk> = llm.stream(callConfig, request, signal)):
              consume → append assistant/chunk?（遥测）→ append assistant/message
            calls = extractToolCalls(...)
            append tool/call × N（executor 落账）
            empty → append step/end; break
            results = executor.execute(calls, signal)   ← 唯一执行路径（R2）
            append step/end
            aborted → break
            any result.concludesTurn → break
            steering = claim(NEXT_STEP); append user/message × steering   ← step 边界
            无 steering 且不继续 → break; step++
        notifyOrdered(agent/turn-stopping)      ← listener 可 steer() 复活循环
        append turn/end(aborted ? cause : completed)
    finally: status = IDLE; driver = null
             if hasWork() → ensureDriver()      ← 唤醒竞态修复
             else → notifyIdle()
```

## 14. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Turn / Step / Round 三层 | 同 | Round 留接口不实现 |
| `Agent` interface | `Agent` interface | `Context ctx()` → `Scope scope()` |
| `AgentHandle.dispose()` capability | record + disposeAndAwait() | 修正编译冲突 |
| inbox `next-turn` / `next-step` | `InboxTarget` | 同 |
| `agent/pre-step` waterfall | PRE_STEP waterfall，default=Enter | 消息在决策后落账 |
| `agent/request` waterfall | REQUEST waterfall，default=options | |
| `agent/request-error` waterfall | REQUEST_ERROR **firstOf** | 返回 retry 或 null（=不拦截） |
| `agent/turn-stopping` serial | TURN_STOPPING notify + **notifyOrdered** | 两模式下形态工具 |
| turn 号 | TurnStart 计数派生 | 修正 turn=seq 缺陷 |
| user/message 落账 | turn 层一次 + steering 在认领 step 边界 | 统一两处不一致 |
| `agent.cancel(cause, {keepInbox})` | cancel + AbortController | first-cause-wins |
| `whenIdle()` | driver future 链 + 退出窗口重查 | 修复唤醒竞态 |
| `ctx.agents.currentInitiator()` | ScopedValue，绑定点=drainLoop | 创建时继承，禁池化提交 |
| 工具 `concludesTurn` | ToolResultEvent.concludesTurn | 数据驱动停 turn |
| model-visible ⟺ logged | append 先于消费；请求指纹落账 | R1 |
