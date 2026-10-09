package io.javanatic.harness.skill.tool;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.skill.SkillCandidate;
import io.javanatic.harness.skill.SkillPlugin;
import io.javanatic.harness.skill.SkillDefinition;
import io.javanatic.harness.skill.SkillProvider;
import io.javanatic.harness.skill.SkillService;
import io.javanatic.harness.skill.SkillSummary;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 停 2 装配与治理：工具注册、装载闭环、审批档、jsonl 往返、真实 loop 轮首事件序。 */
class SkillToolPluginTest {

    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");

    private static SkillProvider fixed(SkillCandidate... candidates) {
        return new SkillProvider() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public List<SkillCandidate> catalog() {
                return List.of(candidates);
            }

            @Override
            public Optional<SkillDefinition> load(String name) {
                return catalog().stream()
                    .filter(c -> c.summary().name().equals(name))
                    .findFirst()
                    .map(c -> new SkillDefinition(c.summary(), "正文:" + c.summary().source()));
            }
        };
    }

    private static SkillCandidate deploy() {
        return new SkillCandidate(new SkillSummary("deploy", "部署指南", "project"), 100);
    }

    @TempDir
    Path root;

    @Test
    void pluginWiresServiceToolAndSection() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new SkillPlugin(),
                new SkillToolPlugin()));
            assertThat(rt.root().require(SkillService.KEY)).isNotNull();
            // 装载工具已注册（模型可见目录含 skill）
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("wire"), null, null);
            var results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("w1"), SkillToolPlugin.SKILL_TOOL, "{\"name\":\"deploy\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());
            // 未挂 provider：目录空 → 未知名报错（不抛）
            assertThat(results.getFirst().event().block().isError()).isTrue();
        }
    }

    @Test
    void loadReturnsBodyWithSourceHeaderAndUnknownFails() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new SkillPlugin(),
                new SkillToolPlugin()));
            rt.root().require(SkillService.KEY).register(fixed(deploy()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("load"), null, null);

            var hit = executor.execute(List.of(
                new ToolUseBlock(CallId.of("l1"), SkillToolPlugin.SKILL_TOOL, "{\"name\":\"deploy\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());
            assertThat(hit.getFirst().event().block().isError()).isFalse();
            assertThat(hit.getFirst().event().block().content()).contains("source: project").contains("正文:project");

            var miss = executor.execute(List.of(
                new ToolUseBlock(CallId.of("l2"), SkillToolPlugin.SKILL_TOOL, "{\"name\":\"absent\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());
            assertThat(miss.getFirst().event().block().isError()).isTrue();
            assertThat(miss.getFirst().event().block().content()).contains("skill not found");
        }
    }

    @Test
    void skillCatalogRoundTripsThroughJsonl() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root),
                new ApprovalAutoPlugin(), new ToolsPlugin(), new SystemPromptPlugin(),
                new SkillPlugin(),
                new SkillToolPlugin()));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("durable"), CreateOptions.empty());
            session.append(new SkillCatalogEvent(7, List.of(
                new SkillCatalogEvent.Entry("deploy", "部署指南", "project"))));
            store.flush(rt.root(), session);

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(session.id());
            assertThat(loaded.events()).containsExactly(new SkillCatalogEvent(7, List.of(
                new SkillCatalogEvent.Entry("deploy", "部署指南", "project"))));
        }
    }

    /** 真实 loop：轮首 PRE_STEP 先于首个 assemble——目录事件在 step 前落账、提示段在请求里。 */
    @Test
    void loopRefreshesCatalogAtTurnStartBeforeFirstAssemble() {
        List<List<StreamChunk>> scripts = List.of(
            List.of(new StreamChunk.Delta("loading skill"),
                new StreamChunk.DeltaToolUse(CallId.of("s1"), SkillToolPlugin.SKILL_TOOL, "{\"name\":\"deploy\"}"),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(new StreamChunk.Delta("done"),
                new StreamChunk.Finish(FinishReason.STOP)));
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new AgentLoopPlugin(Clock.systemUTC()),
                new SkillPlugin(),
                new SkillToolPlugin()));
            SkillService skills = rt.root().require(SkillService.KEY);
            skills.register(fixed(deploy()));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("loop"), OPTIONS));
            Session session = handle.agent().session();

            handle.agent().followup(UserMessage.of("use the deploy skill", new MessageSource.User()));
            handle.agent().whenIdle().join();

            // 轮首装载先于首个请求：catalog 事件在 user/message 之前
            List<String> types = session.events().stream().map(LoggedEvent::type).toList();
            assertThat(types.indexOf("skill/catalog"))
                .isLessThan(types.indexOf("user/message"));
            assertThat(prompts.assemble(session))
                .contains("- deploy — 部署指南（来源: project）");

            // 装载工具真跑进账：正文入模型历史（tool/result 对）
            assertThat(types).contains("tool/call", "tool/result");
        }
    }
}
