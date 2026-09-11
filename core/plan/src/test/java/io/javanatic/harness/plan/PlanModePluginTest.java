package io.javanatic.harness.plan;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolsPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划模式行为：fold 末值胜、exit 校验、直接落账翻转（下一步 assemble 生效）、
 * 同批并行 exit 幂等、动态段三态、jsonl codec 往返、真实 loop 下的事件序。
 */
class PlanModePluginTest {

    private static final String SECTION = "Plan mode is active. Research only; present the plan with exit_plan_mode.";
    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");
    private static final String PLAN_ARGS =
        "{\"plan\":\"# Migration\\n1. read the code\\n2. write tests\\n3. ship\"}";

    @TempDir
    Path root;

    @Test
    void foldIsLastWinsAndDefaultsInactive() {
        Session session = Session.create(Session.newId("fold"), null, null);
        assertThat(PlanModeService.foldActive(session.events())).isFalse();
        session.append(new PlanModeEvent(1, true));
        assertThat(PlanModeService.foldActive(session.events())).isTrue();
        session.append(new PlanModeEvent(2, false));
        assertThat(PlanModeService.foldActive(session.events())).isFalse();
    }

    @Test
    void exitValidationsRejectWithoutFlipping() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(), new SystemPromptPlugin(),
                new PlanModePlugin(SECTION)));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("reject"), null, null);

            // 未激活 + 无标题：都拒,不落 plan/mode
            List<String> bad = List.of(PLAN_ARGS, "{\"plan\":\"no heading plan\"}");
            int call = 0;
            for (String arguments : bad) {
                var results = executor.execute(List.of(
                    new ToolUseBlock(
                        CallId.of("b" + call), PlanModePlugin.EXIT_PLAN_MODE, arguments)),
                    session, 0, call, rt.root(), AbortSignal.never());
                assertThat(results.getFirst().event().block().isError()).isTrue();
                call++;
            }
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsOnly("tool/call", "tool/result");
        }
    }

    @Test
    void exitFlipsModeAtOnceAndSectionFollowsFold() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(), new PlanModePlugin(SECTION)));
            PlanModeService service = rt.root().require(PlanModeService.KEY);
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            Session session = Session.create(Session.newId("exit"), null, null);
            session.append(new PlanModeEvent(1, true));
            assertThat(prompts.assemble(session)).contains(SECTION);

            var results = executor.execute(List.of(
                new ToolUseBlock(
                    CallId.of("x1"), PlanModePlugin.EXIT_PLAN_MODE, PLAN_ARGS)),
                session, 0, 0, rt.root(), AbortSignal.never());

            assertThat(results.getFirst().event().block().isError()).isFalse();
            // 翻转夹在审计对之间（todo 同款),下一次 assemble 即读到 inactive
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsExactly("plan/mode", "tool/call", "plan/mode", "tool/result");
            assertThat(service.active(session)).isFalse();
            assertThat(prompts.assemble(session)).doesNotContain(SECTION);
        }
    }

    /** 同批并行 exit：重复落账 false 对 fold 幂等;审计对完好。 */
    @Test
    void parallelDoubleExitStaysConsistent() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(), new SystemPromptPlugin(),
                new PlanModePlugin(SECTION)));
            PlanModeService service = rt.root().require(PlanModeService.KEY);
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            Session session = Session.create(Session.newId("double"), null, null);
            session.append(new PlanModeEvent(1, true));
            executor.execute(List.of(
                new ToolUseBlock(
                    CallId.of("e1"), PlanModePlugin.EXIT_PLAN_MODE, PLAN_ARGS),
                new ToolUseBlock(
                    CallId.of("e2"), PlanModePlugin.EXIT_PLAN_MODE, PLAN_ARGS)),
                session, 0, 0, rt.root(), AbortSignal.never());

            assertThat(service.active(session)).isFalse();
            long flips = session.events().stream()
                .map(LoggedEvent::event).filter(PlanModeEvent.class::isInstance).count();
            assertThat(flips).isGreaterThanOrEqualTo(2); // seed(true) + ≥1 exit(false)
            assertThat(session.events().stream()
                .filter(e -> e.event() instanceof ToolCallEvent).count()).isEqualTo(2);
        }
    }

    @Test
    void planModeEventRoundTripsThroughJsonl() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root),
                new ApprovalAutoPlugin(), new ToolsPlugin(), new SystemPromptPlugin(),
                new PlanModePlugin(SECTION)));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("durable"),
                CreateOptions.empty());
            session.append(new PlanModeEvent(11, true));
            store.flush(rt.root(), session);

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(session.id());
            assertThat(loaded.events()).containsExactly(new PlanModeEvent(11, true));
        }
    }

    /** 真实 loop:激活期请求带 plan:policy;exit 落账后下一步请求不带。 */
    @Test
    void loopExitsPlanModeFromNextStep() {
        List<List<StreamChunk>> scripts = List.of(
            List.of(new StreamChunk.Delta("planning"),
                new StreamChunk.DeltaToolUse(CallId.of("x1"),
                    PlanModePlugin.EXIT_PLAN_MODE, PLAN_ARGS),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(new StreamChunk.Delta("executing"),
                new StreamChunk.Finish(FinishReason.STOP)));
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new AgentLoopPlugin(Clock.systemUTC()), new PlanModePlugin(SECTION)));
            PlanModeService service = rt.root().require(PlanModeService.KEY);
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("loop"), OPTIONS));
            Session session = handle.agent().session();
            // 轮前激活:并发契约保证测试线程 append 合法
            session.append(new PlanModeEvent(1, true));
            assertThat(prompts.assemble(session)).contains(SECTION);

            handle.agent().followup(UserMessage.of("plan the migration", new MessageSource.User()));
            handle.agent().whenIdle().join();

            // 事件序:exit 的翻转夹在审计对之间,turn 正常收口
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsSubsequence("turn/start", "user/message", "step/start",
                    "llm/request", "assistant/message", "tool/call", "plan/mode",
                    "tool/result", "step/end", "step/start", "llm/request", "step/end",
                    "turn/end");
            assertThat(service.active(session)).isFalse();
            assertThat(prompts.assemble(session)).doesNotContain(SECTION);
        }
    }
}
