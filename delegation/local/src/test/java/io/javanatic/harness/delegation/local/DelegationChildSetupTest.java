package io.javanatic.harness.delegation.local;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 影子封禁语义（同包直达 DelegationChildSetup）：root 注册真 delegate 工具后，
 * 经 setup 建的子代解析到的是「递归委派已禁」桩（最近层同名覆盖）——maxDepth=1
 * 的结构性执法；封禁只在委派路径建子时附加（直建 agent 仍见真工具，headless 侧
 * ChildFaceBoundaryTest 实证）。
 */
class DelegationChildSetupTest {

    @Test
    void setupShadowsDelegationToolsForChild() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                    new SessionStorePlugin(), new AgentPlugin(),
                    new LoopGuardPlugin(new LoopGuard.Limits(20, 10, 0)),
                    new SystemPromptPlugin(), new LlmPlugin(),
                    new ReplayPlugin(List.of(List.of(new StreamChunk.Delta("ok"),
                            new StreamChunk.Finish(FinishReason.STOP)))),
                    new ApprovalAutoPlugin(), new ToolsPlugin(),
                    new AgentLoopPlugin(java.time.Clock.systemUTC(),
                            System.getProperty("user.dir"))));
            ToolRegistry tools = rt.root().require(ToolRegistry.KEY);
            // root 先放一把「真」delegate 工具（模拟装配面注册）
            rt.root().onClose(tools.register(rt.root(), ToolDefinition.of("delegate",
                    "真委派工具", new ValueSchema.Object("参数", Map.of()),
                    (args, context) -> ToolExecutionResult.success("real"))));
            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle child = agents.create(rt.root(), new CreateAgentOptions(
                    Session.newId("shadow-child"), new AgentOptions("replay", "m"),
                    null, null, new DelegationChildSetup()));
            for (String name : List.of("delegate", "delegate_list",
                    "delegate_answer", "delegate_interrupt")) {
                assertThat(tools.resolve(child.agent().scope(), name))
                        .as("子代解析 %s 命中影子封禁桩", name)
                        .hasValueSatisfying(tool ->
                                assertThat(tool.description()).contains("递归委派已禁"));
            }
            assertThat(tools.resolve(rt.root(), "delegate"))
                    .as("root 仍解析真工具（封禁只作用于子层）")
                    .hasValueSatisfying(tool ->
                            assertThat(tool.description()).isEqualTo("真委派工具"));
        }
    }
}
