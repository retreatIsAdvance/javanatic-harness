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
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.SessionStorePlugin;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
                "turn/start", "user/message", "step/start", "llm/request",
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
            rig.tools.register(echoTool());
            Agent agent = rig.agent("a2").agent();
            agent.followup(text("echo hello"));
            agent.whenIdle().join();

            assertThat(types(agent.session())).containsExactly(
                "turn/start", "user/message", "step/start", "llm/request", "assistant/message",
                "tool/call", "tool/result", "step/end",
                "step/start", "llm/request", "assistant/message", "step/end", "turn/end");
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
            assertThat(types(agent.session())).containsExactly("turn/start", "turn/end");
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void cancelDuringToolExecutionAbortsTurn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (Rig rig = new Rig(List.of(useTool("c1", "blocker", "{}")))) {
            rig.tools.register(blockingTool(started));
            Agent agent = rig.agent("a4").agent();
            agent.followup(text("go"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            agent.cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT);
            agent.whenIdle().join();

            List<String> types = types(agent.session());
            assertThat(types).contains("tool/call");
            assertThat(types).doesNotContain("tool/result", "step/end");
            assertThat(reasons(agent.session())).containsExactly(new TurnEndReason.Aborted("user"));
        }
    }

    @Test
    void injectedContextAppearsInNextStepNotCurrent() {
        try (Rig rig = new Rig(List.of(
                useTool("c1", "echo", "{\"path\":\"x\"}"), say("ok")))) {
            rig.tools.register(echoTool());
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
            assertThat(reasons(agent.session()).getFirst())
                .isInstanceOf(TurnEndReason.Error.class);
            assertThat(reasons(agent.session()).getFirst().toString()).contains("boom");
        }
    }

    @Test
    void maintenanceRunsWhenIdleAndRejectsWhenBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (Rig rig = new Rig(List.of(useTool("c1", "blocker", "{}"), say("tail")))) {
            rig.tools.register(blockingTool(started));
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
            rig.tools.register(blockingTool(started));
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
