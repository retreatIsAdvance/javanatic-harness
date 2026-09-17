package io.javanatic.harness.examples.agent.spine;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.agentloop.RequestFingerprints;
import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.fs.tool.FsToolPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.plan.PlanModePlugin;
import io.javanatic.harness.sandbox.local.SandboxLocalPlugin;
import io.javanatic.harness.sandbox.policy.SandboxPolicyPlugin;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 闭环:竖切会话落盘 → load 重建 → 逐锚点前缀折叠重放(与生产场景同口径)——
 * 每个 LlmRequestEvent 的提示词/schema 双哈希由「该锚点之前的日志前缀」重建;
 * 动态段使锚点间提示词互异(证重建,非静态恒常;CompositionManifest 随 07)。
 */
class R1ReplayHashTest {

    private static final String SECTION = "You are the R1 replay verification agent.";

    @TempDir
    Path workspace;

    @Test
    void persistedSessionRebuildsIdenticalRequests() throws Exception {
        Path note = workspace.resolve("note.txt");
        Files.writeString(note, "R1 anchor");
        List<List<StreamChunk>> scripts = List.of(
            List.of(
                new StreamChunk.Delta("读"),
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read",
                    "{\"path\":\"" + note + "\"}"),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(new StreamChunk.Delta("done"), new StreamChunk.Finish(FinishReason.STOP)));

        Path sessionsRoot = workspace.resolve("sessions");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."),
                new SandboxLocalPlugin(),
                new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace)),
                new FsLocalPlugin(workspace),
                new FsToolPlugin(),
                new JsonlPersistencePlugin(sessionsRoot),
                new AgentLoopPlugin(Clock.systemUTC())));
            registerSections(rt.root().require(SystemPromptService.KEY));

            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId("r1"), new AgentOptions("replay", "m")));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("读笔记", new MessageSource.User()));
            agent.whenIdle().join();
            handle.disposeAndAwait();
        }

        // 独立进程语义:新 Runtime、从盘重建
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."),
                new SandboxLocalPlugin(),
                new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace)),
                new FsLocalPlugin(workspace), new FsToolPlugin(),
                new JsonlPersistencePlugin(sessionsRoot),
                new AgentLoopPlugin(Clock.systemUTC())));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            registerSections(prompts);
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            SessionPersistence.Loaded loaded = persistence.load(Session.newId("r1"));
            Session rebuilt = Session.create(Session.newId("r1"), loaded.events(), loaded.header());

            // 逐锚点前缀折叠:每个 LlmRequestEvent 都按其 messagesToSeq 之前的日志重建
            String schemaFp = RequestFingerprints.sha256(RequestFingerprints.toolSchemaFingerprint(
                rt.root().require(ToolRegistry.KEY).schemas(rt.root())));
            List<SessionEvent> events = loaded.events();
            List<Integer> anchorIndexes = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i) instanceof LlmRequestEvent) {
                    anchorIndexes.add(i);
                }
            }
            assertThat(anchorIndexes).hasSize(2);
            // 非平凡化:工具结果计数段逐锚点变化——折叠必须真正重建日志前缀
            assertThat(anchorIndexes.stream()
                .map(index -> ((LlmRequestEvent) events.get(index)).systemPromptSha256())
                .distinct().count()).isGreaterThan(1L);
            for (int index : anchorIndexes) {
                LlmRequestEvent anchor = (LlmRequestEvent) events.get(index);
                assertThat(anchor.messagesFromSeq()).isZero();
                assertThat(anchor.messagesToSeq()).isEqualTo(index - 1L);
                Session folded = Session.create(Session.newId("fold"),
                    events.subList(0, index), loaded.header());
                assertThat(RequestFingerprints.sha256(prompts.assemble(folded)))
                    .as("anchor seq %s 提示词重建", index)
                    .isEqualTo(anchor.systemPromptSha256());
                assertThat(schemaFp)
                    .as("anchor seq %s schema 指纹", index)
                    .isEqualTo(anchor.toolsSchemaSha256());
            }
            assertThat(rebuilt.deriveMessages().size()).isEqualTo(4);
        }
    }

    /** 两腿同段集:静态 + 动态(工具结果计数,自日志派生)——同日志必同提示词(R1)。 */
    private static void registerSections(SystemPromptService prompts) {
        prompts.register(new PromptSection.Static(0, SECTION));
        prompts.register(new PromptSection.Dynamic(10, session -> "tool results so far: "
            + session.events().stream().map(LoggedEvent::event)
                .filter(ToolResultEvent.class::isInstance).count()));
    }
}
