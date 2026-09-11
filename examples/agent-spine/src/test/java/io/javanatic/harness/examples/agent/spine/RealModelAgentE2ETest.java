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
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.deepseek.DeepSeekOptions;
import io.javanatic.harness.llm.deepseek.DeepSeekPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.shell.bash.local.BashLocalOptions;
import io.javanatic.harness.shell.bash.local.BashLocalPlugin;
import io.javanatic.harness.shell.tool.ShellToolPlugin;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolsPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实模型驱动完整竖切(无 key 自跳过):deepseek 真 tool_use → bash 工具
 * 经 executor 真执行 → 结果回填 → 终答关轮。R2 pipeline 的意义场景实测。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class RealModelAgentE2ETest {

    @TempDir
    Path workspace;

    @Test
    void realModelDrivesToolsThroughExecutor() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(5, 8)),
                new SystemPromptPlugin(), new LlmPlugin(),
                new DeepSeekPlugin(new DeepSeekOptions(DeepSeekOptions.DEFAULT_BASE_URL,
                    System.getenv("DEEPSEEK_API_KEY"), null, null, 2, null, null)),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new BashLocalPlugin(new BashLocalOptions(64 * 1024)),
                new ShellToolPlugin(workspace, Duration.ofSeconds(30)),
                new AgentLoopPlugin(Clock.systemUTC())));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            prompts.register(new PromptSection.Static(0,
                "You are a test agent. Use the bash tool when asked to touch the filesystem. Be terse."));

            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId("real-e2e"),
                    new AgentOptions("deepseek", "deepseek-chat")));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of(
                "用 bash 工具执行: echo real-e2e-ok > proof.txt,然后读回 proof.txt 确认内容并原样告诉我。",
                new MessageSource.User()));
            agent.whenIdle().join();

            List<String> types = agent.session().events().stream()
                .map(entry -> entry.event().type()).toList();
            assertThat(types).containsSubsequence("tool/call", "tool/result");
            assertThat(types.getLast()).isEqualTo("turn/end");
            // 真实副作用发生
            assertThat(Files.readString(workspace.resolve("proof.txt"))).contains("real-e2e-ok");
            handle.disposeAndAwait();
        }
    }
}
