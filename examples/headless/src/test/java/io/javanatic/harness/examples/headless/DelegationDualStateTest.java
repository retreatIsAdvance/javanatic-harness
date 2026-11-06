package io.javanatic.harness.examples.headless;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.delegation.DelegationService;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * delegation 双态·缺席态装配级负例（it31 停 3，沿 JobsDualStateTest 先例）：
 * 两行禁用 → DelegationService 缺席 + 零 delegate 工具。
 */
class DelegationDualStateTest {

    @TempDir
    Path dir;

    @Test
    void disablingDelegationRowsYieldsNoServiceAndNoTools() throws Exception {
        Path ws = dir.resolve("ws");
        Files.createDirectories(ws);
        Path profile = Files.createTempFile(dir, "jh-del-dual", ".yml");
        Files.writeString(profile, "name: jh-del-dual\nbundles: [base]\n");
        List<ConfigRowSpec> overlays = List.of(
                new ConfigRowSpec.Replace("delegation-local", Map.of(), "true"),
                new ConfigRowSpec.Replace("delegation-tool", Map.of(), "true"),
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
            assertThat(rt.root().resolve(DelegationService.KEY)).as("DelegationService 缺席").isEmpty();
            List<String> tools = rt.root().resolve(ToolRegistry.KEY)
                    .map(registry -> registry.definitions(rt.root()).stream()
                            .map(ToolDefinition::name)
                            .filter(name -> name.startsWith("delegate")).toList())
                    .orElseGet(List::of);
            assertThat(tools).as("缺席态零委派工具").isEmpty();
        }
    }
}
