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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 闭环:竖切会话落盘 → load 重建 → deriveMessages + 重组装提示词/schema →
 * 与每条 LlmRequestEvent 的双哈希比对(同代码库口径;CompositionManifest 随 07)。
 */
class R1ReplayHashTest {

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
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            String section = "You are the R1 replay verification agent.";
            prompts.register(new PromptSection.Static(0, section));

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
            prompts.register(new PromptSection.Static(0, "You are the R1 replay verification agent."));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            SessionPersistence.Loaded loaded = persistence.load(Session.newId("r1"));
            Session rebuilt = Session.create(Session.newId("r1"), loaded.events(), loaded.header());

            // 重组装提示词/schema,与每条 LlmRequestEvent 双哈希比对
            String prompt = prompts.assemble(rebuilt);
            String schemaFingerprint = RequestFingerprints.sha256(
                RequestFingerprints.toolSchemaFingerprint(
                    rt.root().require(ToolRegistry.KEY).schemas(rt.root())));
            List<LlmRequestEvent> anchors = loaded.events().stream()
                .filter(LlmRequestEvent.class::isInstance)
                .map(LlmRequestEvent.class::cast)
                .toList();
            assertThat(anchors).hasSize(2);
            for (LlmRequestEvent anchor : anchors) {
                assertThat(RequestFingerprints.sha256(prompt)).isEqualTo(anchor.systemPromptSha256());
                assertThat(schemaFingerprint).isEqualTo(anchor.toolsSchemaSha256());
            }
            assertThat(rebuilt.deriveMessages().size()).isEqualTo(4);
        }
    }
}
