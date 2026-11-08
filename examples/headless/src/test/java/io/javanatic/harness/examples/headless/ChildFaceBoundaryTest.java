package io.javanatic.harness.examples.headless;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 委派子代工具面（it31 停 3 模型在环裁决的实证测试，真 AppBoot 装配）：
 * ask_user 随全量继承在场——问态（NeedsParentInput）路径的结构前提。
 * delegate 影子封禁的语义在 delegation/local 同包 DelegationChildSetupTest
 * （封禁只在委派服务建子时经 setup 附加，直建 agent 不含——本测试的直建子代
 * 正好同时实证了这一点）。
 */
class ChildFaceBoundaryTest {

    @TempDir
    Path dir;

    @Test
    void childFaceHasAskUserAndShadowedDelegation(@TempDir Path unused) throws Exception {
        Path ws = dir.resolve("ws");
        Files.createDirectories(ws);
        Path profile = Files.createTempFile(dir, "jh-face", ".yml");
        Files.writeString(profile, "name: jh-face\nbundles: [base]\n");
        List<ConfigRowSpec> overlays = List.of(
                new ConfigRowSpec.Replace("fs-local", Map.of("root", ws.toString()), null),
                new ConfigRowSpec.Replace("shell-tool",
                        Map.of("workspace", ws.toString(), "timeoutSeconds", 30), null),
                new ConfigRowSpec.Replace("sandbox-policy",
                        Map.of("mode", "workspace-write", "workspace", ws.toString()), null),
                new ConfigRowSpec.Replace("agent-loop", Map.of("cwd", ws.toString()), null),
                new ConfigRowSpec.Replace("skill-local", Map.of("cwd", ws.toString()), null),
                new ConfigRowSpec.Replace("persistence-jsonl",
                        Map.of("root", dir.resolve("sessions").toString()), null));
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, overlays, false, null))) {
            ToolRegistry tools = rt.root().require(ToolRegistry.KEY);
            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle child = agents.create(rt.root(), CreateAgentOptions.of(
                    Session.newId("face-child"), new AgentOptions("replay", "m")));
            List<String> childTools = tools.definitions(child.agent().scope()).stream()
                    .map(ToolDefinition::name).sorted().toList();
            assertThat(childTools).as("ask_user 随全量继承在场").contains("ask_user");
        }
    }
}
