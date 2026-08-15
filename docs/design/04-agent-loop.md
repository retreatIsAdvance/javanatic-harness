# 04 · Agent Loop — Turn / Step 状态机

Agent Loop 是整个系统的驱动核心。它消费输入、开 Turn、跑 Step、调模型、跑工具、关 Turn，全程把**模型可见的每个事实都写进 Session 日志**。

> **关键原则（移植自 dsh）**：新行为挂扩展点（事件），**不改 loop 本身**。Loop 只读日志、写日志、走 waterfall。

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
// io.dsh.core.agent.Agent
package io.dsh.core.agent;

import java.util.concurrent.CompletableFuture;

/**
 * 活跃 Agent 的公开句柄。
 *
 * 对应 dsh 的 Agent interface。
 * UI、编排器、插件都通过这个接口操作 agent。
 * 具体实现是 agent-loop 包内部的，不暴露。
 */
public interface Agent {

    /** 唯一身份（与 session 共享）。 */
    SessionId id();

    /** provider 路由 + model。 */
    AgentOptions options();

    /** 驱动的 live session；其日志是真相源。 */
    Session session();

    /** agent 拥有的 pending work 投影。 */
    Inbox inbox();

    /** 当前生命周期状态（idle / running）。 */
    AgentStatus status();

    /** agent-scoped context（注册是 agent-local，dispose 时回收）。 */
    Context ctx();

    // ────────── 输入投递 ──────────

    /**
     * 把输入路由到 inbox 边界，可选唤醒 driver。
     * @param message 用户消息
     * @param target 下一个 turn 或下一个 step 边界
     * @param wakeup 是否唤醒 driver
     */
    void send(UserMessage message, InboxTarget target, boolean wakeup);

    /** 排一个普通 follow-up turn 并唤醒。该消息成为自己 turn 的唯一 ordinary 消息。 */
    void followup(UserMessage message);

    /** 提交 steering 给最近的 step。idle driver 开 turn；running driver 下一步消费。 */
    void steer(UserMessage message);

    /**
     * 排模型可见上下文给下一个 pre-step，不唤醒。
     * running driver 在最近的 step 边界消费；idle driver 留 pending 直到 followup/steer 唤醒。
     */
    void inject(UserMessage message);

    // ────────── 控制 ──────────

    /** 取消活跃 turn 或 between-turn 任务。cause 是稳定的调用方意图。 */
    void cancel(AgentCancelCause cause, CancelOptions options);

    /** 等当前整个 agent 活动达到静止。 */
    CompletableFuture<Void> whenIdle();

    /**
     * 在 true idle 阶段跑一个非 turn 维护任务。
     * 任务同步启动占住 idle 阶段；后来的 waking input 留 inbox 等任务 settle。
     * @throws IllegalStateException 当 turn 驱动或另一维护任务已占用 agent。
     */
    <T> CompletableFuture<T> runMaintenance(java.util.function.Function<java.util.concurrent.Flow.Subscription, CompletableFuture<T>> task);
}

enum AgentStatus { IDLE, RUNNING }
```

### InboxTarget

```java
// io.dsh.core.agent.InboxTarget
package io.dsh.core.agent;

/**
 * agent 拥有的两条有序 pending 消息列表。
 * 对应 dsh 的 InboxTarget。
 */
public enum InboxTarget {
    /** 下一个 turn 边界：开新 turn 时消费。 */
    NEXT_TURN,
    /** 下一个 step 边界：插队当前 turn 的下一步。 */
    NEXT_STEP
}
```

### AgentCancelCause — 判别式

```java
public sealed interface AgentCancelCause {
    record User() implements AgentCancelCause {}
    record Parent() implements AgentCancelCause {}
    record Hook(String reason) implements AgentCancelCause {}
    record Disposed() implements AgentCancelCause {}
}
```

## 3. Inbox — 投递与排空

```java
// io.dsh.core.agent.Inbox
package io.dsh.core.agent;

import java.util.*;

/**
 * Agent 拥有的 pending 消息投影。
 * 两条有序列表：nextTurn（普通排队）、nextStep（steering + injected）。
 *
 * 修改操作记录为 durable 的 agent/inbox/spliced 事件（插件扩展事件）。
 * 对应 dsh 的 Inbox。
 */
public final class Inbox {

    private final Deque<UserMessage> nextTurn = new ArrayDeque<>();
    private final Deque<UserMessage> nextStep = new ArrayDeque<>();
    private final Set<String> pendingIds = new HashSet<>();  // 去重

    /** 追加到指定列表尾部。重复 id 抛错。 */
    public synchronized void append(InboxTarget target, UserMessage msg) {
        if (!pendingIds.add(msg.id().value())) {
            throw new IllegalStateException("Duplicate pending message: " + msg.id());
        }
        queue(target).addLast(msg);
    }

    /**
     * 认领 step 批次：全部 nextStep + （turn 边界时）一条 nextTurn。
     * 纯删除 splice（不发 discarded 通知），loop 单独发 claimed 通知。
     */
    public synchronized List<UserMessage> claim(InboxTarget context) {
        List<UserMessage> batch = new ArrayList<>();
        batch.addAll(nextStep);
        nextStep.clear();
        if (context == InboxTarget.NEXT_TURN && !nextTurn.isEmpty()) {
            batch.add(nextTurn.pollFirst());
        }
        batch.forEach(m -> pendingIds.remove(m.id().value()));
        return batch;
    }

    public synchronized void clear() {
        nextTurn.clear();
        nextStep.clear();
        pendingIds.clear();
    }

    public synchronized List<UserMessage> nextTurn() { return List.copyOf(nextTurn); }
    public synchronized List<UserMessage> nextStep() { return List.copyOf(nextStep); }

    private Deque<UserMessage> queue(InboxTarget t) {
        return t == InboxTarget.NEXT_TURN ? nextTurn : nextStep;
    }
}
```

## 4. Agent Loop — 具体驱动

这是 `harness.core.agent-loop` 模块的核心。一个 fiber 内跑的循环：

```java
// io.dsh.core.agentloop.AgentLoopImpl
package io.dsh.core.agentloop;

class AgentLoopImpl implements Agent {

    private final Context agentCtx;
    private final Session session;
    private final SessionStore sessions;
    private final Events events;
    private final LlmService llm;
    private final ToolRegistry tools;
    private final SystemPrompt systemPrompt;
    private final Inbox inbox = new Inbox();
    private final AgentOptions options;

    private volatile AgentStatus status = AgentStatus.IDLE;
    private volatile CompletableFuture<Void> driver = null;
    private volatile AbortController activeAbort = null;

    // ────────── 输入投递（线程安全，可从任意线程调用）──────────

    @Override
    public void send(UserMessage message, InboxTarget target, boolean wakeup) {
        inbox.append(target, message);
        if (wakeup && status == AgentStatus.IDLE) {
            ensureDriver();
        }
    }

    @Override
    public void followup(UserMessage m) {
        send(m, InboxTarget.NEXT_TURN, true);
    }

    @Override
    public void steer(UserMessage m) {
        send(m, InboxTarget.NEXT_STEP, true);
    }

    @Override
    public void inject(UserMessage m) {
        send(m, InboxTarget.NEXT_STEP, false);  // 不唤醒
    }

    // ────────── driver（虚拟线程上的排空循环）──────────

    /**
     * 确保 driver 在跑。幂等：已在跑则 no-op。
     * driver 在虚拟线程上排空 inbox，直到 idle。
     */
    private synchronized void ensureDriver() {
        if (driver != null && !driver.isDone()) return;
        status = AgentStatus.RUNNING;
        events.emit(AgentEvents.STATUS, this, AgentStatus.RUNNING);
        Executor exec = agentCtx.fiber().runtime().virtualThreadExecutor();
        driver = CompletableFuture.runAsync(this::drainLoop, exec);
    }

    /**
     * 排空循环：反复开 turn、跑 step，直到没有更多 work。
     */
    private void drainLoop() {
        try {
            while (hasWork()) {
                runTurn();
            }
        } finally {
            status = AgentStatus.IDLE;
            events.emit(AgentEvents.STATUS, this, AgentStatus.IDLE);
            driver = null;
        }
    }

    private boolean hasWork() {
        return !inbox.nextTurn().isEmpty() || !inbox.nextStep().isEmpty();
    }

    // ────────── Turn ──────────

    private void runTurn() {
        int turn = session.seq();  // turn 号从日志派生（实际是 nextTurnNumber）
        activeAbort = new AbortController();

        // turn/start
        session.append(new TurnStart(session.seq(), System.currentTimeMillis(), turn));

        // claim 输入
        List<UserMessage> claimed = inbox.claim(InboxTarget.NEXT_TURN);
        if (claimed.isEmpty()) {
            claimed = inbox.claim(InboxTarget.NEXT_STEP);
        }
        if (claimed.isEmpty()) {
            // 空 claim：关 turn 无 step
            session.append(new TurnEnd(session.seq(), System.currentTimeMillis(),
                turn, TurnEndReason.completed()));
            return;
        }

        // agent/pre-step (waterfall)：决定是否进入 step、可改写消息
        PreStepDecision decision = runPreStep(claimed, turn, activeAbort.signal()).join();
        switch (decision) {
            case PreStepDecision.Reject r -> {
                session.append(new TurnEnd(session.seq(), System.currentTimeMillis(),
                    turn, TurnEndReason.completed()));
                return;
            }
            case PreStepDecision.Enter e -> {
                claimed = e.messages();
            }
        }

        // 进入第一个 step
        runStepLoop(turn, claimed, activeAbort.signal());

        // agent/turn-stopping (serial)：是否有 steering 要继续
        runTurnStopping(turn, activeAbort.signal()).join();

        // turn/end
        TurnEndReason endReason = activeAbort.isAborted()
            ? TurnEndReason.aborted(activeAbort.cause())
            : TurnEndReason.completed();
        session.append(new TurnEnd(session.seq(), System.currentTimeMillis(), turn, endReason));
    }

    // ────────── Step loop（一个 turn 内的多次模型请求）──────────

    private void runStepLoop(int turn, List<UserMessage> admitted, AbortSignal signal) {
        int step = 0;
        List<UserMessage> batch = admitted;

        while (true) {
            if (signal.isAborted()) return;

            // step/start
            session.append(new StepStart(session.seq(), System.currentTimeMillis(), turn, step));
            for (UserMessage m : batch) {
                session.append(toUserMessageEvent(m, turn, step));
            }

            // 组装 system prompt + tool schemas
            EpochHeader header = buildHeader(batch);
            session.append(new RequestHeaderEvent(session.seq(), System.currentTimeMillis(),
                header, RequestHeaderReason.INITIAL));

            // agent/request (waterfall)：可改写 call config
            LlmCallConfig callConfig = runRequest(turn, step, signal).join();

            // 模型流式
            AssistantMessage assistantMsg = streamModel(callConfig, turn, step, signal);

            // 工具调用
            List<ToolCallEvent> toolCalls = extractToolCalls(assistantMsg, turn, step);
            if (toolCalls.isEmpty()) {
                // 无工具调用 → step 结束，turn 即将结束
                session.append(new StepEnd(session.seq(), System.currentTimeMillis(), turn, step));
                return;
            }

            // 执行工具
            List<ToolResultEvent> results = executeTools(toolCalls, signal);

            // step/end
            session.append(new StepEnd(session.seq(), System.currentTimeMillis(), turn, step));

            // 判断是否继续：有 tool result 要继续 → 下一个 step
            if (signal.isAborted()) return;
            // 检查是否有 concludesTurn 的 result 或 steering
            if (results.stream().anyMatch(r -> r.concludesTurn())) return;
            // 检查 inbox 是否有新 steering
            List<UserMessage> steering = inbox.claim(InboxTarget.NEXT_STEP);
            if (steering.isEmpty() && !shouldContinue(toolCalls, results)) return;

            // 准备下一个 step 的输入
            batch = steering;
            step++;
        }
    }

    // ────────── waterfall 扩展点 ──────────

    private CompletableFuture<PreStepDecision> runPreStep(
            List<UserMessage> messages, int turn, AbortSignal signal) {
        return agentCtx.waterfall(
            AgentEvents.PRE_STEP,
            this,  // carrier（带 scope）
            List.of(messages, turn, 0, signal),
            () -> new PreStepDecision.Enter(messages));  // default: enter
    }

    private CompletableFuture<LlmCallConfig> runRequest(
            int turn, int step, AbortSignal signal) {
        LlmCallConfig defaultConfig = new LlmCallConfig(options.provider(), options.model());
        return agentCtx.waterfall(
            AgentEvents.REQUEST,
            this,
            List.of(turn, step, signal),
            () -> defaultConfig);
    }

    private CompletableFuture<Void> runTurnStopping(int turn, AbortSignal signal) {
        return agentCtx.serial(AgentEvents.TURN_STOPPING, this,
            new TurnStoppingPayload(turn, signal));
    }
}
```

## 5. 事件 Key 定义

```java
// io.dsh.core.agent.AgentEvents
package io.dsh.core.agent;

public final class AgentEvents {
    /** agent 已创建并发布。emit。 */
    public static final EventKey<Agent> CREATED =
        EventKey.emit("agent/created", Agent.class);

    /** agent 离开注册表。emit。 */
    public static final EventKey<Agent> DISPOSED =
        EventKey.emit("agent/disposed", Agent.class);

    /** 状态翻转 idle↔running。emit。 */
    public static final EventKey<AgentStatus> STATUS =
        EventKey.emit("agent/status", AgentStatus.class);

    /** session 生命周期开始。emit。 */
    public static final EventKey<SessionStartPayload> SESSION_START =
        EventKey.emit("agent/session-start", SessionStartPayload.class);

    /** step/turn 出错。emit。 */
    public static final EventKey<AgentErrorPayload> ERROR =
        EventKey.emit("agent/error", AgentErrorPayload.class);

    // ── waterfall 扩展点 ──

    /** 接受或拒绝 proposed step。waterfall。 */
    public static final EventKey<PreStepDecision> PRE_STEP =
        EventKey.waterfall("agent/pre-step", PreStepDecision.class);

    /** 替换冻结的 call config。waterfall。 */
    public static final EventKey<LlmCallConfig> REQUEST =
        EventKey.waterfall("agent/request", LlmCallConfig.class);

    /** 处理失败的 model-request。waterfall，listener 返回 retry 或 undefined。 */
    public static final EventKey<RequestErrorAction> REQUEST_ERROR =
        EventKey.waterfall("agent/request-error", RequestErrorAction.class);

    // ── serial 扩展点 ──

    /** turn 即将关闭。serial，无 next()，数据决策。 */
    public static final EventKey<Void> TURN_STOPPING =
        EventKey.serial("agent/turn-stopping", Void.class);

    // ── inbox emit ──

    public static final EventKey<InboxMessagePayload> INBOX_INSERTED =
        EventKey.emit("agent/inbox/inserted", InboxMessagePayload.class);
    public static final EventKey<InboxMessagePayload> INBOX_CLAIMED =
        EventKey.emit("agent/inbox/claimed", InboxMessagePayload.class);
    public static final EventKey<InboxMessagePayload> INBOX_DISCARDED =
        EventKey.emit("agent/inbox/discarded", InboxMessagePayload.class);

    private AgentEvents() {}
}
```

## 6. PreStepDecision — 判别式

```java
public sealed interface PreStepDecision {
    /** 拒绝进入 step（turn 可能仍以 0 step 关闭）。 */
    record Reject() implements PreStepDecision {}

    /** 进入 step，用这批消息（可改写）。 */
    record Enter(List<UserMessage> messages) implements PreStepDecision {}
}
```

listener 用法：
```java
// 一个 hook 桥：拒绝包含敏感词的输入
ctx.onGlobal(AgentEvents.PRE_STEP, (carrier, payload) -> {
    WaterfallArgs<PreStepDecision> wp = (WaterfallArgs<PreStepDecision>) payload;
    List<UserMessage> msgs = (List<UserMessage>) wp.args().get(0);
    if (msgs.stream().anyMatch(m -> containsSecret(m))) {
        return new PreStepDecision.Reject();  // 不调 next() → veto
    }
    return wp.next().invoke();  // 委托
});
```

## 7. AbortController — 取消传播

```java
// io.dsh.core.agentloop.AbortController
package io.dsh.core.agentloop;

import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消控制器：把一个 AgentCancelCause 传播给所有协作者。
 *
 * 等价 dsh 的 turn abort signal + cause。
 * 第一次 cause 生效（first-cause-wins），后续 cancel no-op。
 */
public final class AbortController {

    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public AbortSignal signal() {
        return new AbortSignal(this);
    }

    public void cancel(AgentCancelCause c) {
        if (cancelled.compareAndSet(false, true)) {
            cause.set(c);
            listeners.forEach(Runnable::run);
        }
    }

    public boolean isAborted() { return cancelled.get(); }
    public AgentCancelCause cause() { return cause.get(); }

    void addListener(Runnable r) {
        if (isAborted()) r.run();
        else listeners.add(r);
    }
}

public record AbortSignal(AbortController controller) {
    public boolean isAborted() { return controller.isAborted(); }
    public AgentCancelCause cause() { return controller.cause(); }
    public void checkAbort() {
        if (isAborted()) throw new AbortedException(controller.cause());
    }
}
```

虚拟线程 + `checkAbort()` 是 JH 的取消机制。模型流式和工具执行在关键点调 `signal.checkAbort()`，让取消快速传播。比 `Thread.interrupt()` 更可控（不会在任意安全点抛 `InterruptedException`）。

## 8. AgentRegistry — agent 工厂 + 注册表

```java
// io.dsh.core.agent.AgentRegistry
package io.dsh.core.agent;

/**
 * Agent 服务（ctx.agents）。
 * 跟踪活跃 agent，携带 process-local initiator，提供 create/resume 工厂。
 */
public final class AgentRegistry {

    private final ConcurrentHashMap<SessionId, Agent> agents = new ConcurrentHashMap<>();
    private final AtomicReference<AgentFactory> factory = new AtomicReference<>();

    /** loop 插件注册自己的工厂。 */
    public Subscription setFactory(AgentFactory f) {
        if (!factory.compareAndSet(null, f)) {
            throw new IllegalStateException("Factory already registered");
        }
        return new Subscription(() -> factory.set(null));
    }

    /** 创建 agent + session（通过工厂）。 */
    public CompletableFuture<AgentHandle> create(Context ownerCtx, CreateAgentOptions opts) {
        AgentFactory f = requireFactory();
        return f.create(ownerCtx, opts).thenApply(handle -> {
            Agent prev = agents.putIfAbsent(handle.agent().id(), handle.agent());
            if (prev != null) {
                throw new IllegalStateException("Agent already exists: " + handle.agent().id());
            }
            return handle;
        });
    }

    /** resume 持久化的 session。 */
    public CompletableFuture<AgentHandle> resume(Context ownerCtx, ResumeAgentOptions opts) {
        AgentFactory f = requireFactory();
        return f.resume(ownerCtx, opts).thenApply(handle -> {
            agents.put(handle.agent().id(), handle.agent());
            return handle;
        });
    }

    public Agent get(SessionId id) { return agents.get(id); }
    public List<Agent> list() { return List.copyOf(agents.values()); }

    // ── initiator scope（process-local 因果归因）──
    //
    // 用 ScopedValue（JEP 506，Java 25 final）而非 ThreadLocal：
    // - 不可变：绑定后不可被任意代码改写，避免 ThreadLocal 的 set 泄漏
    // - 虚拟线程继承友好：子虚拟线程自动继承，无需 Thread.Builder.inherit()
    // - 作用域明确：ScopedValue.where(...).run(...) 退出即解绑，无需 try/finally 清理

    private static final ScopedValue<Agent> INITIATOR = ScopedValue.newInstance();

    public Agent currentInitiator() {
        return INITIATOR.isBound() ? INITIATOR.get() : null;
    }

    public <T> T withInitiator(Agent a, java.util.function.Supplier<T> op) {
        return ScopedValue.where(INITIATOR, a).get(op);
    }

    private AgentFactory requireFactory() {
        AgentFactory f = factory.get();
        if (f == null) throw new IllegalStateException("No agent factory registered");
        return f;
    }
}
```

**`ScopedValue<Agent>` initiator**：`ScopedValue`（JEP 506，Java 25 final）替代了原设计的 `ThreadLocal<Agent>`。绑定后该作用域（含其派生的子虚拟线程）内的所有调用能读到 `currentInitiator()`（用于日志/telemetry 归因）。相对 ThreadLocal 的三个优势：(1) 不可变，杜绝 set 后忘 clear 的泄漏；(2) 虚拟线程天然继承（ThreadLocal 默认不继承子虚拟线程的值，需 `Thread.Builder.inherit()`）；(3) `where(...).run(...)` 退出自动解绑，无需 try/finally。等价 dsh 的 `ctx.agents.currentInitiator()`。

## 9. AgentHandle — 所有权与 dispose

```java
// io.dsh.core.agent.AgentHandle
package io.dsh.core.agent;

/**
 * 一个被拥有的 agent + 其 disposer。
 *
 * 对应 dsh 的 AgentHandle。
 * dispose 是一个 capability：只有持有 handle 的消费者能 teardown agent。
 * dispose 停 loop、等退出、注销 agent、从 store 移除 session、回收 scoped world。
 */
public record AgentHandle(Agent agent, java.util.concurrent.CompletableFuture<Void> dispose) {

    /** 便捷：dispose 并等待。 */
    public CompletableFuture<Void> dispose() { return dispose; }
}
```

## 10. 一轮 Turn 的完整时序（伪代码）

```
[ensureDriver]
  status = RUNNING; emit agent/status(RUNNING)
  drainLoop on virtual thread:
    while hasWork():
      runTurn():
        abortController = new AbortController()
        append turn/start
        claimed = inbox.claim(NEXT_TURN)
        if claimed.isEmpty() && nextStep.isEmpty():
          append turn/end(completed)   ← 0-step turn
          continue

        decision = waterfall(agent/pre-step, claimed, default=Enter(claimed))
        switch decision:
          case Reject → append turn/end(completed); continue
          case Enter(messages) → admitted = messages

        runStepLoop(turn, admitted, abortController.signal):
          step = 0
          loop:
            abortSignal.checkAbort()
            append step/start
            append user/message × admitted

            header = systemPrompt.assemble(tools.schemas())
            append request/header(header)

            callConfig = waterfall(agent/request, default=agentOptions)
            assistantMsg = llm.stream(callConfig, abortSignal)
              → emit assistant/chunk × N（log 保留流式保真）
              → append assistant/message

            toolCalls = extractToolCalls(assistantMsg)
            append tool/call × toolCalls
            if toolCalls.isEmpty():
              append step/end
              break

            results = executeTools(toolCalls):
              for each call:
                waterfall(tools/pre-execute, default=proceed)
                result = tools.execute(call, abortSignal)
                  → dispatch to ToolRegistry → capability seam provider
                waterfall(tools/post-execute, default=observe)
                append tool/result
              return results
            append step/end

            if abortSignal.isAborted() → break
            if any result.concludesTurn() → break
            steering = inbox.claim(NEXT_STEP)
            if steering.isEmpty() && !shouldContinue(toolCalls, results) → break
            admitted = steering; step++

        serial(agent/turn-stopping):
          if listener calls agent.steer() → inbox 有新 steering → 再跑一轮 step loop
          else → turn 关闭

        endReason = aborted ? TurnEndReason.aborted(cause) : completed
        append turn/end(endReason)

    status = IDLE; emit agent/status(IDLE)
    driver = null
```

## 11. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Turn / Step / Round 三层 | 同 | Round 留接口不实现 |
| `Agent` interface | `Agent` interface | 1:1 方法签名 |
| `AgentHandle.dispose()` capability | `AgentHandle.dispose()` | 同 |
| inbox `next-turn` / `next-step` | `InboxTarget.NEXT_TURN` / `NEXT_STEP` | 同 |
| `agent/pre-step` waterfall | `AgentEvents.PRE_STEP` waterfall | default=Enter |
| `agent/request` waterfall | `AgentEvents.REQUEST` waterfall | default=options |
| `agent/turn-stopping` serial | `AgentEvents.TURN_STOPPING` serial | 数据决策 |
| `agent/request-error` waterfall | `AgentEvents.REQUEST_ERROR` waterfall | retry/undefined |
| AbortSignal.reason | `AbortSignal.controller().cause()` | first-cause-wins |
| `agent.cancel(cause, {keepInbox})` | `Agent.cancel(cause, CancelOptions)` | 同 |
| `whenIdle()` | `whenIdle()` CompletableFuture | 跟踪 driver + replacement |
| `runMaintenance(task)` | `runMaintenance(task)` | true idle 阶段独占 |
| `ctx.agents.currentInitiator()` | `ScopedValue<Agent> INITIATOR`（JEP 506）| 虚拟线程继承，不可变 |
| 工具 `concludesTurn` | `ToolResultEvent.concludesTurn()` | 数据驱动停 turn |
| model-visible ⟺ logged | append 前不变式 | 每个 assistant/tool 事实先写日志 |
