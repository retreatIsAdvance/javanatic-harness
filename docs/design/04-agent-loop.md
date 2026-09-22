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
     * 词表是 Supplier（不带 AbortSignal）：core/agent 不为维护取消引入 llm 依赖边，
     * 任务自限时；维护取消语义随第一个真实维护消费者（compaction）再定形。
     * @throws IllegalStateException 当 turn 驱动或另一维护任务已占用 agent。
     */
    <T> CompletableFuture<T> runMaintenance(java.util.function.Supplier<T> task);
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

    /** 追加到指定列表尾部（消息身份去重随 durable inbox / 消息 id 词表再进）。 */
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

        // request-context：工作区快照落账，提示词组装读它（R1：值在事件里）。
        // cwd 来自组合行配置 agent-loop.cwd（it21：与 fs/shell/sandbox 围栏同源）
        session.append(new RequestHeader(clock.millis(), cwd,
            clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString()));

        // 项目说明装载（it21）：轮首读 cwd 下说明文件（行配置 agent-loop.instructionsFile，
        // 缺省 AGENTS.md），内容+指纹落 project/instructions——装载是 loop 的动作，
        // 但值仍只在事件里：提示词组装的说明段读日志（R1 不破）。
        appendProjectInstructions();

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

**工作区单源（it21）**：`request/header.cwd` 的值来自组合行配置 `agent-loop.cwd`（缺省 `user.dir`），不是 loop 自己读进程状态——与 `fs-local.root` / `shell-tool.workspace` / `sandbox-policy.workspace` 四处同源，四处漂移由 AppBoot 装配期断言拒绝（[07 §5](07-profile-bundle.md)）。提示词组装的上下文段读最新 `request/header`（R1：值在事件里，同日志必同提示词）。

**项目说明装载（it21，P3 改判）**：装载职责在 **agent-loop**（初稿一度考虑放 system-prompt/组合层；改判理由：轮首时机只有 loop 有，装配层无法在「每次 turn 前」动作，且事件落账需要 turn 上下文）。轮首、`request/header` 之后读 `cwd` 下的说明文件：行配置 `agent-loop.instructionsFile`（缺省 `AGENTS.md`，`DEFAULT_INSTRUCTIONS_FILE`）——相对名按 `cwd` 解析，也可写绝对路径。**装载通道**是**直接 NIO**（`InstructionsFile.read` → `Files.newInputStream`）：不经 `FsService`/realpath 围栏、不设 `NOFOLLOW`（符号链接被跟随），绝对路径可绕出 `cwd`——这是对四确认 ②「经 `java.base` 读」通道粒度的**实施改判**，理由与两条边界见 [plan/iteration-21.md](../plan/iteration-21.md) 设计偏离表第 5 行（loop 环境快照先例 / 组合定向输入非模型定向 / 读平权——`bash` 本可 `cat` 任意路径，符号链接通道无新读能力；与 `fs_search` 的 `NOFOLLOW` 姿态有意不一致）。装载**有界**：超 64 KiB（`InstructionsFile.MAX_BYTES`）截断到最后一个换行、置 `truncated` 位（通篇无换行则保留字节前缀）。**sha 去重增强**：事件带内容 SHA-256，loop 比「最新 `project/instructions` 的 path+sha256」与本次装载——相同则不追加（长会话逐轮装载同一文件只有一条事件，日志有界）；不同或不存在则追加新事件（文件变了，提示词随之变，指纹进 R1 链）。失败面：文件不存在静默跳过（无说明文件是常态）；其余 `IOException` WARN 降级、不炸 turn——说明文件读不到不该让会话起不来。提示词组装的说明段由 `SystemPromptImpl` 硬编码在**上下文段之后、priority 排序的注册段之前**（说明段不参与 priority 排序），读**最新事件**（`instructionsSection`），渲染为 `Project instructions (<path>):\n<content>`，截断时尾附 `\n… (truncated)`。

## 7. Step loop — 请求指纹（R1）与流式消费

```java
    private void runStepLoop(int turn, AbortSignal signal) {
        int step = 0;
        int retriesLeft = 0;
        int overflowRetries = 0;
        while (true) {
            signal.checkAbort();
            guard.checkBudget(session, turn, step);   // R4：每步先查停止条件（超限抛 GuardReject）

            // 压力压缩（it10；口径见 §7.1）：末次 inputTokens 超阈 → 维护事务盖写前缀；
            // 无可压缩区间 → 跳过 + WARN（不判死本 step，真空由请求侧溢出显形）
            if (compaction != null && compaction.shouldCompact(session)) {
                compaction.compact(session, turn, config0(turn, step, signal), signal);
            }

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

            // 派发前耐久屏障（it19）：请求锚落盘确认后才许调用（工具批同形，见 §13）
            store.flush(agentScope, session);

            // 模型流式：阻塞 Stream，跑在 driver 虚拟线程上（05 §LLM seam）
            ChunkAssembly.Assembled assembled;
            try (Stream<StreamChunk> chunks = llm.stream(callConfig, new LlmRequest(
                    systemPrompt.isEmpty() ? null : systemPrompt,
                    session.deriveMessages(), schemas, Map.of()), signal)) {
                // 边消费边落 AssistantChunkEvent（流式事实）；装配失败也不抹已到分块
                assembled = ChunkAssembly.fold(chunks.toList());
            } catch (RuntimeException e) {
                // 溢出恢复（it14；规格见 §7.1）：Kind.OVERFLOW 且本 turn 未恢复过
                // → 强制压缩（compactNow）+ 同 step 重试一次
                if (overflowRetries == 0 && compaction != null && isContextOverflow(e)
                        && compaction.compactNow(session, turn, config0(turn, step, signal), signal) != null) {
                    overflowRetries++;
                    continue;
                }
                // REQUEST_ERROR 首决策（firstOf）：maxRetries 内同 step 重试；否则重抛
                if (decision.isPresent() && retriesLeft < decision.orElseThrow().maxRetries()) {
                    retriesLeft++;
                    continue;
                }
                throw e;
            }
            retriesLeft = 0;

            List<ToolCallEvent> calls = extractToolCalls(assembled, turn, step);
            if (calls.isEmpty()) {
                session.append(new StepEnd(clock.millis(), turn, step));
                return;
            }

            // 工具执行：唯一路径经 executor；tool/call 与 tool/result 均由 executor 落账（R2，05）
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(calls, session, turn,
                step, agentScope, signal);

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

### 7.1 压缩与溢出恢复（it10 落地；it14/it20 口径修正）

两个触发路径共用同一维护事务（`compaction/start` → 摘要维护调用 → `user/message` + `Replace` + `MessageSource.Compaction` → `compaction/summary` 审计 → `compaction/end`，词表见 03 §6）：

| 路径 | 触发 | 边界/失败语义 |
|---|---|---|
| 压力（pre-step） | step 顶 `shouldCompact`：**末次** assistant 消息的 `inputTokens > 阈值`（阈值 = `maxContextTokens` 覆盖 > `contextWindow × thresholdRatio`，皆缺则 apply 期 fail loud——不猜窗口大小） | 保留边界（`retainTokens` 估价累计，切点回退 tool 配对）落在 0 = 无可压缩区间 → **跳过 + WARN**，本 step 继续 |
| 溢出（request-error catch） | `LlmCallException.Kind.OVERFLOW` 且本 turn 尚未恢复过 → `compactNow`（无视阈值强制） | `compactNow` 返回 null（无可压缩区间）即不重试，落回 `REQUEST_ERROR` 决策 / 重抛——溢出以 `turn/end(Error, FailureKind.OVERFLOW)` 诚实收口 |

- **为什么压力路径跳过而不抛**（it20 修正）：`keepFrom<=0` 说明「阈值判据与可压区间判据打架」（保留预算覆盖整个 surface）。此时 step 的真实需要是继续跑；若随后请求真超窗，溢出 catch 以强制路径接管并给出诚实终局（OVERFLOW），而非在 step 顶以 UNKNOWN 判死一个本可能完成的轮。
- **为什么触发读末次而非 max()**（it20 修正）：max() 是高位水位——一次跨阈后判据永久为真，每个 step 顶复发压缩（it15 首步核对已在案）；末次读「最近一次真实请求的上下文规模」，压缩后新请求实测值自然回落，跨 resume 仍读最近一次请求（03 §6「触发用末次 inputTokens 实数」即此口径——此前实现偏离，it20 对齐）。
- **估算边界**：保留切点用估价（chars/2.5 + 结构开销，agentscope 校准）——无 tokenizer 的诚实近似，只用于「切在哪里」，不用于判定当前占用；触发判定用模型回报的实数。`ProductionScenarioTest` 以 replay 脚本钉住两个触发点与 R1 折叠（it15 建立、it20 随口径重写脚本）。

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
- **执行收敛（it18）**：工具批的取消/失败在 executor 内 **join 全部再传播**——传播发生时本批全部工具线程已停止（AbortedException 按输入序首个优先，其余失败按输入序首个）。因此 `whenIdle()` 完成 ⇒ 无工具线程在跑（含取消路径）。
- **不合作边界**：纯 Java 工具若无视 `checkAbort()` 且不返回，静止永不达成——无上界、无强制手段（Java 不能强杀线程）；子进程击杀已由 `onCancel` 钩子覆盖（it6 shell）。若整批工具无视取消并正常返回，turn 以 `completed` 关轮——取消只保证不推进后续 step。
- **超时 / 取消 / idle 的关系**（it18 钉死，互不代偿）：

| 面 | 触发源 | 落账 / 返回 | 后续 |
|---|---|---|---|
| 工具超时（shell 60s 等） | 工具自身 | 该工具 error result | turn 继续——失败是数据 |
| LLM 空闲看门狗 | 传输停滞 | `LlmCallException(TIMEOUT)` | 按重试词表重试或 Error 关轮 |
| 取消 | `cancel(cause)`（CLI SIGINT 走 `User`） | `AbortedException` 传播 → `aborted(cause)` 关轮 | 不推进后续 step |
| idle | 驱动静止 | `whenIdle()` 完成 | **含全部工具线程已停**（本页上一条） |

  超时不是取消（不停轮）；idle 只说明静止、不说明为什么停。

## 9. AbortController — 取消传播

```java
// io.javanatic.harness.agentloop.AbortController
/**
 * 取消控制器：把一个 AgentCancelCause 传播给流式消费与工具执行。
 * first-cause-wins：第一次 cause 生效，后续 cancel no-op。
 * signal() 返回 llm.AbortSignal 的无状态视图（seam 词表在 llm，executor/stream
 * 消费它）；cause 经 describe() 转稳定 String 进 TurnEndReason.Aborted——
 * session 不反向依赖 agent。
 */
public final class AbortController {
    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AbortSignal signal() { return this::throwIfCancelled; }
    public synchronized void cancel(AgentCancelCause c) { /* first-cause-wins */ }
    public boolean isAborted() { return cancelled.get(); }
    public AgentCancelCause cause() { return cause.get(); }
}
```

取消监听（onCancel 钩子）已随首个消费者落地（it6 shell 的 kill-tree、deepseek 看门狗旁路）；`llm.AbortSignal` 以 default 方法承载,轮询与监听双通道。等待型协作点同样收信号：it18 起 `ApprovalService.require` / `ApprovalPrompt.ask` 带 `AbortSignal` 参数（12 §3 迁移），审批等待中取消抛 `AbortedException` 按取消收敛（不落 error result）。虚拟线程 + `checkAbort()` 是 JH 的取消机制：不用 `Thread.interrupt()`（不会在任意安全点抛 `InterruptedException`，传播点显式可控）。

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
2. **跨线程共享仅限 StructuredTaskScope.fork**（JDK 25 终版 JEP 506 收紧；预览期的「线程创建时快照继承」已不成立——普通 `Thread.ofVirtual().start()` 不继承绑定，有契约测试钉住）。StructuredTaskScope 是 preview、项目禁用，因此 **initiator 归因只覆盖 driver 线程的同步执行段**（turn/step 编排、流式消费、executor 调用点）；工具线程与异步 notify listener 内不可见。STS 转 final 后把工具并行迁到 `fork` 即恢复跨线程归因。执行方法用终版 `Carrier.call`（预览期 `get(Supplier)` 已删）。
3. **不可变**：绑定期内无人能改写 initiator，杜绝 ThreadLocal 的 set/forget 泄漏。

## 11. AgentHandle — 所有权与 dispose

```java
// io.javanatic.harness.agent.AgentHandle
/**
 * 一个被拥有的 agent + 其 dispose 能力。dispose 是 capability：只有持有
 * handle 的消费者能 teardown agent，且必须显式触发——工厂绝不构造期启动
 * teardown（创建即 cancel 会清空 inbox，与首个 turn 竞态；实测复现）。
 * dispose 链：cancel(Disposed) → 等 driver 静止 → 回收 agent scope（注销由
 * Registry 组合在链尾）。
 */
public record AgentHandle(Agent agent, Disposer disposer) {

    /** teardown 能力：触发链并返回其 future。 */
    @FunctionalInterface
    public interface Disposer { CompletableFuture<Void> dispose(); }

    /** 触发（幂等：AgentHandle.once 包装后重复触发返回同一 future）。 */
    public CompletableFuture<Void> dispose() { return disposer.dispose(); }

    /** 触发并阻塞等待完成（虚拟线程上调用）。 */
    public void disposeAndAwait() { dispose().join(); }
}
```

热 future（构造期物化 `CompletableFuture` 并启动链）是错误形状：teardown 必须延迟到消费者显式调用。`once` 组合子保证单次执行、幂等返回。

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
            sessions.flush(scope, session)      ← 派发前屏障（it19）：对账+fsync 未确认即不派发
            try (Stream<chunk> = llm.stream(callConfig, request, signal)):
              consume → append assistant/chunk?（遥测）→ append assistant/message
            calls = extractToolCalls(...)
            append tool/call × N（executor 批前导落账；重复 callId 检测在此）
            sessions.flush(scope, session)      ← 工具批屏障（it19）：未确认不进入副作用
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

## 15. 实现落定（it5）

- **事件键归属**：`AgentEvents`（STATUS / PRE_STEP / REQUEST / REQUEST_ERROR / TURN_STOPPING）在 core/agent-loop——负载含 llm 词表；core/agent 保持纯契约（依赖 = kernel + brand + session，与 02 表一致）。
- **驱动线程**：每次唤醒 `Thread.ofVirtual().name("jh-agent-driver")` 直启（不经 Runtime executor，无池化语义）；driver 体异常经 whenComplete 记 ERROR 日志（fail loud，不静默吞 future）。
- **turn 隔离**：模型侧非取消的意外 `RuntimeException` 收敛为 `turn/end(Error)` 关轮，驱动继续排空后续 work；`REQUEST_ERROR`（firstOf）返回 `RequestErrorDecision(maxRetries)` 时同 step 重试——每次尝试是新的 `step/start` + `llm/request`（R1：每次请求各自留指纹），无人拦截即重抛收敛。
- **无条件续步**：`shouldContinue` 未实现——工具执行后只要未被取消、无 `concludesTurn`，一律进入下一步（失控由 LoopGuard 兜底；数据驱动停轮保留 `concludesTurn`）。
- **`concludesTurn` 生产者落定（it22）**：数据驱动停轮的首个实装是 `ask_user`（`interaction/ask` 模块）——`ToolExecutionResult.concluding(content)` → executor 取 `result.concludesTurn()` 随 `tool/result` 落账 → 本循环 §13 断轮；答复 = 下一轮 `user/message`（REPL 下一行 / `--resume=<id> "答复"`），问答两半都是日志事实，回放不重放提问。
- **LoopGuard 计数档先行**：max-turns / max-steps-per-turn（`LoopGuardPlugin` 构造注入 limits，组合期选择）；budget 档（token 计量）随 deepseek。guard 检查在关轮 try 内——超限以 `turn/end(Error)` 收口，不留下开着的 `turn/start`。
- **user/message 落账位置**：admitted 批在 turn 层一次；steering/注入在认领它的 step 边界（认领后、下一 `step/start` 前），与 §7 一致。
- **resume**：in-memory `SessionStore.get` 命中即恢复（turn 号从日志 TurnStart 计数派生）；缺失会话由 get 本身 fail loud（NoSuchElementException）。durable 重载路径 = `persistence.load` → `SessionStore.create(seed)` → `agents.resume`；挂载前执行恢复收口（03 §6「恢复收口」）——悬空 tool_use 不闭合会让首个请求违反 OpenAI 配对契约。
- **（it19）派发前屏障**：`llm/request` 落账后、`llm.stream` 前，与工具批（`tool/call` 全部落账后、fork 前）各落一次 `SessionStore.flush`（对账 + fsync）；屏障失败 ⇒ 该派发不进入、turn 以 `turn/end(Error)` 收口（`FailureKind.DISK`）。工具批的 `tool/call` 落账前移至 `ToolExecutorImpl` 批前导，重复 callId 检测随之搬家。
- **构造器取总线**：`AgentLoopImpl` 从 `agentScope.require(Runtime.KEY)` 解析事件总线与驱动；装配期缺 Runtime 即失败。
- **（it20）压缩口径修正与跳过语义**：`lastInputTokens` 由全日志 max() 改**末次** assistant 的 `inputTokens`（03 §6 措辞即此口径，此前实现偏离）；压力路径 `keepFrom<=0` 由抛错改**跳过 + WARN**（返回 null；真空由请求侧 OVERFLOW 显形）。规格与理由见 §7.1；it15 生产场景脚本随口径重写（`ProductionScenarioTest` 数值钉：跨阈在 C/E 两处、压缩 ×2、R1 全比对不变）。
- **（it20）预算口径**（README it20「明确 token 预算口径」）：预算 = **全日志**（含 resume 前轮）`AssistantMessageEvent.usage().outputTokens` 累计（`LoopGuardPlugin.checkBudget`），**只计输出**——输入 token 在压缩/重放/重试下会重复计，不具可比性，故不计；超限经 GuardReject 收敛 `turn/end(Error)` → exit 3（kind `UNKNOWN`，12 §6 已裁不设预算专属码）；PRODUCTION 档要求非零（07 §6）。这是可解释的**累计口径**，不是厂商计费对齐，也不宣称费用硬上限。
