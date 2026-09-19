package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.AgentStatus;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallException;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.SessionEvents;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Turn/Step 状态机集成（replay 驱动，keyless）：序列落账、裁决、取消收敛、guard、错误重试。 */
class AgentLoopTest {

    private static final long FIXED_MILLIS = 1_000_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC);
    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");
    private static final LoopGuard.Limits DEFAULT_LIMITS = new LoopGuard.Limits(100, 50);
    private static final ValueSchema.Object NO_ARGS = new ValueSchema.Object("参数", Map.of());

    /** 九插件直装的 keyless 竖切 rig（与 examples/agent-spine 同构）。 */
    private static final class Rig implements AutoCloseable {
        final Runtime rt;
        final AgentRegistry agents;
        final ToolRegistry tools;

        Rig(List<List<StreamChunk>> scripts) {
            this(scripts, DEFAULT_LIMITS);
        }

        Rig(List<List<StreamChunk>> scripts, LoopGuard.Limits limits) {
            rt = new Runtime();
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(), new LoopGuardPlugin(limits),
                new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
                new ApprovalAutoPlugin(), new ToolsPlugin(), new AgentLoopPlugin(FIXED_CLOCK)));
            agents = rt.root().require(AgentRegistry.KEY);
            tools = rt.root().require(ToolRegistry.KEY);
        }

        AgentHandle agent(String rawId) {
            return agents.create(rt.root(), CreateAgentOptions.of(Session.newId(rawId), OPTIONS));
        }

        AgentHandle agent(String rawId, AgentOptions options) {
            return agents.create(rt.root(), CreateAgentOptions.of(Session.newId(rawId), options));
        }

        @Override
        public void close() {
            rt.close();
        }
    }

    private static UserMessage text(String body) {
        return UserMessage.of(body, new MessageSource.User());
    }

    private static List<StreamChunk> say(String body) {
        return List.of(new StreamChunk.Delta(body), new StreamChunk.Finish(FinishReason.STOP));
    }

    private static List<StreamChunk> useTool(String rawCallId, String tool, String arguments) {
        return List.of(
            new StreamChunk.Delta("checking"),
            new StreamChunk.DeltaToolUse(CallId.of(rawCallId), tool, arguments),
            new StreamChunk.Finish(FinishReason.TOOL_USE));
    }

    private static ToolDefinition echoTool() {
        return ToolDefinition.of("echo", "回显 path",
            new ValueSchema.Object("参数", Map.of("path", new ValueSchema.Str("文本"))),
            (args, ctx) -> ToolExecutionResult.success(args.readString("path")));
    }

    /** 阻塞直到取消的工具：started 置位后轮询 checkAbort。 */
    private static ToolDefinition blockingTool(CountDownLatch started) {
        return ToolDefinition.of("blocker", "阻塞直到取消", NO_ARGS, (args, ctx) -> {
            started.countDown();
            while (true) {
                ctx.signal().checkAbort();
                Thread.sleep(20);
            }
        });
    }

    private static List<String> types(Session session) {
        return session.events().stream().map(entry -> entry.event().type()).toList();
    }

    private static List<TurnEndReason> reasons(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(TurnEnd.class::isInstance)
            .map(event -> ((TurnEnd) event).reason())
            .toList();
    }

    private static List<Integer> turnNumbers(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(TurnStart.class::isInstance)
            .map(event -> ((TurnStart) event).turn())
            .toList();
    }

    /** 自旋等待条件成立（5s 截止），避免依赖时序的 sleep 断言。 */
    private static void spinUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as(what).isTrue();
            Thread.sleep(10);
        }
    }

    private static List<String> userTexts(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(UserMessageEvent.class::isInstance)
            .map(event -> ((UserMessageEvent) event).message())
            .flatMap(message -> message.content().stream())
            .filter(TextBlock.class::isInstance)
            .map(block -> ((TextBlock) block).text())
            .toList();
    }

    @Test
    void happyTurnLogsFullSequence() {
        try (Rig rig = new Rig(List.of(say("你好!")))) {
            Agent agent = rig.agent("a1").agent();
            agent.followup(text("hi"));
            agent.whenIdle().join();

            assertThat(types(agent.session())).containsExactly(
                "turn/start", "request/header", "user/message", "step/start", "llm/request",
                "assistant/chunk", "assistant/chunk",
                "assistant/message", "step/end", "turn/end");
            LoggedEvent<? extends SessionEvent> first = agent.session().events().getFirst();
            assertThat(first.event()).isEqualTo(new TurnStart(FIXED_MILLIS, 1));
            assertThat(agent.status()).isEqualTo(AgentStatus.IDLE);
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void toolCallGoesThroughExecutorAndFeedsNextStep() {
        try (Rig rig = new Rig(List.of(
                useTool("c1", "echo", "{\"path\":\"hello\"}"), say("done")))) {
            rig.tools.register(rig.rt.root(), echoTool());
            Agent agent = rig.agent("a2").agent();
            agent.followup(text("echo hello"));
            agent.whenIdle().join();

            assertThat(types(agent.session())).containsExactly(
                "turn/start", "request/header", "user/message", "step/start", "llm/request",
                "assistant/chunk", "assistant/chunk", "assistant/chunk",
                "assistant/message", "tool/call", "tool/result", "step/end",
                "step/start", "llm/request", "assistant/chunk", "assistant/chunk",
                "assistant/message", "step/end", "turn/end");
            // 工具结果投影进模型可见历史(source=Tool 的那条)
            UserMessage toolResult = (UserMessage) agent.session().deriveMessages().stream()
                .filter(message -> message.source() instanceof MessageSource.Tool)
                .findFirst().orElseThrow();
            assertThat(toolResult.source()).isEqualTo(new MessageSource.Tool(CallId.of("c1")));
            assertThat(toolResult.content().getFirst())
                .isEqualTo(new ToolResultBlock(CallId.of("c1"), "hello", false));
        }
    }

    @Test
    void preStepRejectClosesTurnWithNoStep() {
        try (Rig rig = new Rig(List.of())) {
            rig.rt.root().events().onWaterfall(AgentEvents.PRE_STEP, (carrier, args) ->
                new PreStepDecision.Reject("policy"));
            Agent agent = rig.agent("a3").agent();
            agent.followup(text("hi"));
            agent.whenIdle().join();

            // 拒绝：turn/start + turn/end(completed)，无 user/message、无 step
            assertThat(types(agent.session())).containsExactly(
                "turn/start", "request/header", "turn/end");
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void cancelDuringToolExecutionAbortsTurn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (Rig rig = new Rig(List.of(useTool("c1", "blocker", "{}")))) {
            rig.tools.register(rig.rt.root(), blockingTool(started));
            Agent agent = rig.agent("a4").agent();
            agent.followup(text("go"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            agent.cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
            agent.whenIdle().join();

            List<String> types = types(agent.session());
            assertThat(types).contains("tool/call");
            assertThat(types).doesNotContain("tool/result", "step/end");
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Aborted("user"));
            // idle 纪律(it18 ②):aborted 收敛完成,turn/end 已落账,状态才到 IDLE
            assertThat(agent.status()).isEqualTo(AgentStatus.IDLE);
        }
    }

    @Test
    void cancelJoinsParallelToolBatchBeforeIdle() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean writerStopped = new AtomicBoolean();
        ToolDefinition writer = ToolDefinition.of("writer", "慢写", NO_ARGS, (args, ctx) -> {
            releaseWriter.await();
            writerStopped.set(true);
            return ToolExecutionResult.success("written");
        });
        try (Rig rig = new Rig(List.of(List.of(
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "blocker", "{}"),
                new StreamChunk.DeltaToolUse(CallId.of("c2"), "writer", "{}"),
                new StreamChunk.Finish(FinishReason.TOOL_USE))))) {
            rig.tools.register(rig.rt.root(), blockingTool(blockerStarted));
            rig.tools.register(rig.rt.root(), writer);
            Agent agent = rig.agent("a16").agent();
            agent.followup(text("go"));
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            agent.cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
            Thread.sleep(100); // 首异常即传播的旧语义会在此窗口内关轮
            // 路线表原话的逆否形态：慢写工具未停，whenIdle 不得完成
            assertThat(writerStopped.get()).as("写手仍被压住").isFalse();
            assertThat(agent.whenIdle().isDone()).isFalse();

            releaseWriter.countDown();
            agent.whenIdle().join();
            assertThat(writerStopped.get()).isTrue();
            assertThat(types(agent.session())).doesNotContain("step/end");
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Aborted("user"));
        }
    }

    @Test
    void uncooperativeToolDelaysIdleUntilItReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolDefinition stubborn = ToolDefinition.of("stubborn", "无视取消", NO_ARGS, (args, ctx) -> {
            started.countDown();
            release.await();
            return ToolExecutionResult.success("done-anyway");
        });
        try (Rig rig = new Rig(List.of(useTool("c1", "stubborn", "{}")))) {
            rig.tools.register(rig.rt.root(), stubborn);
            Agent agent = rig.agent("a17").agent();
            agent.followup(text("go"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> idle = agent.whenIdle();

            agent.cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
            assertThat(idle.isDone()).isFalse(); // 无上界：取消不强制停不合作工具

            release.countDown();
            idle.join();
            // 批完整返回 → 数据面闭环、关轮 Completed（取消只阻止后续推进）
            assertThat(types(agent.session())).contains("tool/call", "tool/result");
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void injectedContextAppearsInNextStepNotCurrent() {
        try (Rig rig = new Rig(List.of(
                useTool("c1", "echo", "{\"path\":\"x\"}"), say("ok")))) {
            rig.tools.register(rig.rt.root(), echoTool());
            Agent agent = rig.agent("a5").agent();
            agent.inject(text("ctx-fact"));
            agent.followup(text("task"));
            agent.whenIdle().join();

            // 同一日志空间比较:task 在 step 0 内;ctx-fact 在 tool/result 之后、
            // step 1 之前被认领落账(下一个 step 才可见)
            List<LoggedEvent<? extends SessionEvent>> events = agent.session().events();
            int taskSeq = -1;
            int ctxSeq = -1;
            int toolResultSeq = -1;
            int lastStepStartSeq = -1;
            for (int i = 0; i < events.size(); i++) {
                SessionEvent event = events.get(i).event();
                if (event instanceof ToolResultEvent && toolResultSeq < 0) {
                    toolResultSeq = i;
                }
                if (event instanceof StepStart) {
                    lastStepStartSeq = i;
                }
                if (event instanceof UserMessageEvent message) {
                    String body = message.message().content().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(block -> ((TextBlock) block).text())
                        .findFirst().orElse("");
                    if ("task".equals(body)) {
                        taskSeq = i;
                    }
                    if ("ctx-fact".equals(body)) {
                        ctxSeq = i;
                    }
                }
            }
            assertThat(taskSeq).isLessThan(toolResultSeq);
            assertThat(ctxSeq).isGreaterThan(toolResultSeq);
            assertThat(ctxSeq).isLessThan(lastStepStartSeq);
        }
    }

    @Test
    void turnStoppingSteerRevivesDriver() throws Exception {
        try (Rig rig = new Rig(List.of(say("one"), say("two")))) {
            Agent agent = rig.agent("a6").agent();
            AtomicBoolean steeredOnce = new AtomicBoolean();
            rig.rt.root().events().on(AgentEvents.TURN_STOPPING, (carrier, payload) -> {
                if (steeredOnce.compareAndSet(false, true)) {
                    agent.steer(text("and-then"));
                }
            });
            agent.followup(text("first"));
            agent.whenIdle().join();
            spinUntil("steering 复活第二个 turn",
                () -> turnNumbers(agent.session()).size() == 2);

            assertThat(turnNumbers(agent.session())).containsExactly(1, 2);
            assertThat(reasons(agent.session())).hasSize(2);
            assertThat(userTexts(agent.session())).contains("first", "and-then");
        }
    }

    @Test
    void guardRejectEndsTurnWithError() throws Exception {
        try (Rig rig = new Rig(List.of(say("a"), say("b")), new LoopGuard.Limits(1, 50))) {
            Agent agent = rig.agent("a7").agent();
            agent.followup(text("t1"));
            agent.followup(text("t2"));
            spinUntil("两轮都关轮", () -> reasons(agent.session()).size() == 2);

            assertThat(reasons(agent.session()).getFirst()).isInstanceOf(TurnEndReason.Completed.class);
            assertThat(reasons(agent.session()).get(1))
                .isInstanceOf(TurnEndReason.Error.class);
            assertThat(reasons(agent.session()).get(1).toString()).contains("max turns");
            // 超限轮无 step
            assertThat(types(agent.session()).stream().filter("step/start"::equals).count()).isEqualTo(1);
        }
    }

    @Test
    void requestErrorRetriesThenCompletes() throws Exception {
        try (Rig rig = new Rig(List.of())) {
            AtomicInteger calls = new AtomicInteger();
            LlmService llm = rig.rt.root().require(LlmService.KEY);
            llm.registerAdapter("flaky", (config, request, signal) -> {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("boom");
                }
                return Stream.of(new StreamChunk.Delta("recovered"),
                    new StreamChunk.Finish(FinishReason.STOP));
            });
            rig.rt.root().events().onWaterfall(AgentEvents.REQUEST_ERROR, (carrier, args) ->
                new AgentEvents.RequestErrorDecision(1));

            Agent agent = rig.agent("a8", new AgentOptions("flaky", "m")).agent();
            agent.followup(text("go"));
            agent.whenIdle().join();

            assertThat(calls.get()).isEqualTo(2);
            assertThat(types(agent.session()).stream().filter("llm/request"::equals).count()).isEqualTo(2);
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void requestErrorRetryDoesNotReplayToolCalls() throws Exception {
        try (Rig rig = new Rig(List.of())) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger toolRuns = new AtomicInteger();
            LlmService llm = rig.rt.root().require(LlmService.KEY);
            llm.registerAdapter("flaky", (config, request, signal) -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return Stream.of(new StreamChunk.Delta("checking"),
                        new StreamChunk.DeltaToolUse(CallId.of("c1"), "echo", "{\"path\":\"x\"}"),
                        new StreamChunk.Finish(FinishReason.TOOL_USE));
                }
                if (n == 2) {
                    throw new IllegalStateException("boom"); // 工具批已执行;下一步请求失败一次
                }
                return Stream.of(new StreamChunk.Delta("done"),
                    new StreamChunk.Finish(FinishReason.STOP));
            });
            rig.rt.root().events().onWaterfall(AgentEvents.REQUEST_ERROR, (carrier, args) ->
                new AgentEvents.RequestErrorDecision(1));
            rig.tools.register(rig.rt.root(), ToolDefinition.of("echo", "回显 path",
                new ValueSchema.Object("参数", Map.of("path", new ValueSchema.Str("文本"))),
                (args, ctx) -> {
                    toolRuns.incrementAndGet();
                    return ToolExecutionResult.success(args.readString("path"));
                }));

            Agent agent = rig.agent("a18", new AgentOptions("flaky", "m")).agent();
            agent.followup(text("go"));
            agent.whenIdle().join();

            // 重试只重发请求:工具不重放(执行面 + 事件面双重计数)
            assertThat(toolRuns.get()).isEqualTo(1);
            assertThat(types(agent.session()).stream().filter("tool/call"::equals).count()).isEqualTo(1);
            // 重试 continue 不 step++:同号 step/start 重复是裁定语义(04 §7)
            assertThat(agent.session().events().stream()
                .map(LoggedEvent::event)
                .filter(StepStart.class::isInstance)
                .map(event -> ((StepStart) event).step()))
                .containsExactly(0, 1, 1);
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void requestErrorWithoutDecisionEndsTurnWithError() {
        try (Rig rig = new Rig(List.of())) {
            AtomicInteger calls = new AtomicInteger();
            LlmService llm = rig.rt.root().require(LlmService.KEY);
            llm.registerAdapter("flaky", (config, request, signal) -> {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            });

            Agent agent = rig.agent("a9", new AgentOptions("flaky", "m")).agent();
            agent.followup(text("go"));
            agent.whenIdle().join();

            assertThat(calls.get()).isEqualTo(1);
            // 非 llm 失败 → UNKNOWN（词表映射的缺省分支）
            assertThat(reasons(agent.session())).containsExactly(
                new TurnEndReason.Error("IllegalStateException: boom", FailureKind.UNKNOWN));
        }
    }

    @Test
    void llmFailureKindMapsToTurnEndReason() {
        try (Rig rig = new Rig(List.of())) {
            LlmService llm = rig.rt.root().require(LlmService.KEY);
            llm.registerAdapter("flaky", (config, request, signal) -> {
                throw new LlmCallException(LlmCallException.Kind.RATE_LIMIT, "deepseek http 429");
            });

            Agent agent = rig.agent("a13", new AgentOptions("flaky", "m")).agent();
            agent.followup(text("go"));
            agent.whenIdle().join();

            // llm 词表 → session 词表的映射在 turn 收敛点穷尽完成
            assertThat(reasons(agent.session())).containsExactly(
                new TurnEndReason.Error("LlmCallException: deepseek http 429",
                    FailureKind.RATE_LIMIT));
        }
    }

    @Test
    void dispatchBarrierFailureBlocksLlmCallWithDiskKind() {
        try (Rig rig = new Rig(List.of(say("never")))) {
            AtomicInteger flushes = new AtomicInteger();
            AtomicInteger calls = new AtomicInteger();
            rig.rt.root().events().onGlobal(SessionEvents.FLUSH, (carrier, session) -> {
                if (flushes.incrementAndGet() == 1) {
                    throw new IllegalStateException("disk full"); // 伪 writer:落盘不确认
                }
            });
            LlmService llm = rig.rt.root().require(LlmService.KEY);
            llm.registerAdapter("flaky", (config, request, signal) -> {
                calls.incrementAndGet();
                return Stream.of(new StreamChunk.Delta("x"),
                    new StreamChunk.Finish(FinishReason.STOP));
            });

            Agent agent = rig.agent("a19", new AgentOptions("flaky", "m")).agent();
            agent.followup(text("go"));
            agent.whenIdle().join();

            // 请求锚已落账,但屏障不确认 → 调用未发生(崩于此刻不存在"无据请求")
            assertThat(flushes.get()).isEqualTo(1);
            assertThat(calls.get()).isZero();
            assertThat(types(agent.session())).contains("llm/request")
                .doesNotContain("assistant/chunk", "step/end");
            TurnEndReason reason = reasons(agent.session()).getFirst();
            assertThat(reason).isInstanceOf(TurnEndReason.Error.class);
            assertThat(((TurnEndReason.Error) reason).kind()).isEqualTo(FailureKind.DISK);
            assertThat(reason.toString()).contains("DurabilityException");
        }
    }

    @Test
    void toolBatchBarrierFailureStopsExecutionAfterCallsLanded() {
        try (Rig rig = new Rig(List.of(useTool("c1", "echo", "{\"path\":\"x\"}")))) {
            AtomicInteger flushes = new AtomicInteger();
            AtomicInteger toolRuns = new AtomicInteger();
            rig.rt.root().events().onGlobal(SessionEvents.FLUSH, (carrier, session) -> {
                if (flushes.incrementAndGet() == 2) {
                    throw new IllegalStateException("disk full"); // 第 2 次 = 工具批屏障
                }
            });
            rig.tools.register(rig.rt.root(), ToolDefinition.of("echo", "回显 path",
                new ValueSchema.Object("参数", Map.of("path", new ValueSchema.Str("文本"))),
                (args, ctx) -> {
                    toolRuns.incrementAndGet();
                    return ToolExecutionResult.success(args.readString("path"));
                }));

            Agent agent = rig.agent("a20").agent();
            agent.followup(text("go"));
            agent.whenIdle().join();

            // 批前导已落账全部 tool/call,但屏障不确认 → 无一工具执行、无结果落账
            assertThat(flushes.get()).isEqualTo(2);
            assertThat(toolRuns.get()).isZero();
            assertThat(types(agent.session())).contains("tool/call")
                .doesNotContain("tool/result", "step/end");
            TurnEndReason reason = reasons(agent.session()).getFirst();
            assertThat(((TurnEndReason.Error) reason).kind()).isEqualTo(FailureKind.DISK);
        }
    }

    @Test
    void chunkEventsCarryTurnStepAndPayloadInOrder() {
        try (Rig rig = new Rig(List.of(say("你好!")))) {
            Agent agent = rig.agent("a14").agent();
            agent.followup(text("hi"));
            agent.whenIdle().join();

            List<AssistantChunkEvent> chunks = agent.session().events().stream()
                .map(LoggedEvent::event)
                .filter(AssistantChunkEvent.class::isInstance)
                .map(AssistantChunkEvent.class::cast)
                .toList();
            // 逐块留痕的原始载荷（不装配、不合并）
            assertThat(chunks).containsExactly(
                new AssistantChunkEvent(FIXED_MILLIS, 1, 0, new StreamChunk.Delta("你好!")),
                new AssistantChunkEvent(FIXED_MILLIS, 1, 0,
                    new StreamChunk.Finish(FinishReason.STOP)));
        }
    }

    @Test
    void appendedObserverReceivesChunksInOrder() {
        try (Rig rig = new Rig(List.of(say("one")))) {
            List<String> observed = new ArrayList<>();
            rig.rt.root().events().onGlobal(SessionEvents.APPENDED, (carrier, entry) ->
                observed.add(((LoggedEvent<?>) entry).event().type()));
            Agent agent = rig.agent("a15").agent();
            agent.followup(text("hi"));
            agent.whenIdle().join();

            // APPENDED 保序派发：chunk 风暴逐条按序到观察者（渲染面据此增量上屏）
            int request = observed.indexOf("llm/request");
            assertThat(observed.subList(request, request + 4)).containsExactly(
                "llm/request", "assistant/chunk", "assistant/chunk", "assistant/message");
        }
    }

    @Test
    void maintenanceRunsWhenIdleAndRejectsWhenBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (Rig rig = new Rig(List.of(useTool("c1", "blocker", "{}"), say("tail")))) {
            rig.tools.register(rig.rt.root(), blockingTool(started));
            Agent agent = rig.agent("a10").agent();

            assertThat(agent.runMaintenance(() -> "ok").join()).isEqualTo("ok");

            agent.followup(text("go"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> agent.runMaintenance(() -> "nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("busy");

            agent.cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
            agent.whenIdle().join();
        }
    }

    @Test
    void disposeCancelsDeregistersAndEndsTurnAsDisposed() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (Rig rig = new Rig(List.of(useTool("c1", "blocker", "{}")))) {
            rig.tools.register(rig.rt.root(), blockingTool(started));
            Id<Session> id = Session.newId("a11");
            AgentHandle handle = rig.agents.create(rig.rt.root(),
                CreateAgentOptions.of(id, OPTIONS));
            handle.agent().followup(text("go"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            handle.disposeAndAwait();
            assertThat(rig.agents.get(id)).isNull();
            assertThat(reasons(handle.agent().session()))
                .containsExactly(new TurnEndReason.Aborted("disposed"));
        }
    }

    @Test
    void resumeContinuesTurnNumbering() {
        try (Rig rig = new Rig(List.of(say("one"), say("two")))) {
            Id<Session> id = Session.newId("a12");
            AgentHandle first = rig.agents.create(rig.rt.root(), CreateAgentOptions.of(id, OPTIONS));
            first.agent().followup(text("t1"));
            first.agent().whenIdle().join();
            first.disposeAndAwait();

            AgentHandle resumed = rig.agents.resume(rig.rt.root(), new ResumeAgentOptions(id, OPTIONS));
            resumed.agent().followup(text("t2"));
            resumed.agent().whenIdle().join();

            assertThat(turnNumbers(resumed.agent().session())).containsExactly(1, 2);
        }
    }

    @Test
    void resumeMissingSessionFailsLoud() {
        try (Rig rig = new Rig(List.of())) {
            assertThatThrownBy(() -> rig.agents.resume(rig.rt.root(),
                    new ResumeAgentOptions(Session.newId("nope"), OPTIONS)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("nope");
        }
    }
}
