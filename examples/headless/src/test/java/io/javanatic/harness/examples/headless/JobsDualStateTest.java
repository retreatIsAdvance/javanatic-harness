package io.javanatic.harness.examples.headless;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jobs 双态·缺席态装配级负例（it30 停 3 评审裁决①）：profile Remove 两行 →
 * JobService 不在场、四工具零注册——与 web keyless / mcp 空 servers / lsp 禁用行
 * 同族的双态证据（呈现态见 HeadlessVerifyTest jobs 摘要行与敌意测试工具清单）。
 */
class JobsDualStateTest {

    @TempDir
    Path dir;

    @Test
    void removingJobsRowsYieldsNoServiceAndNoTools() throws Exception {
        Path ws = dir.resolve("ws");
        Path sess = dir.resolve("sessions");
        Files.createDirectories(ws);
        Path profile = Files.createTempFile(dir, "jh-dualstate", ".yml");
        Files.writeString(profile, "name: jh-dualstate\nbundles: [base]\n");
        List<ConfigRowSpec> overlays = List.of(
                // Remove 会触发双向校验（发现的插件必须被行引用）——缺席态 = 禁用行
                // （lsp-jdtls 先例）：引用在场、插件不装载
                new ConfigRowSpec.Replace("jobs-local", java.util.Map.of(), "true"),
                new ConfigRowSpec.Replace("jobs-tool", java.util.Map.of(), "true"),
                new ConfigRowSpec.Replace("fs-local", java.util.Map.of("root", ws.toString()), null),
                new ConfigRowSpec.Replace("shell-tool",
                        java.util.Map.of("workspace", ws.toString(), "timeoutSeconds", 30), null),
                new ConfigRowSpec.Replace("sandbox-policy",
                        java.util.Map.of("mode", "workspace-write", "workspace", ws.toString()), null),
                new ConfigRowSpec.Replace("agent-loop", java.util.Map.of("cwd", ws.toString()), null),
                new ConfigRowSpec.Replace("skill-local", java.util.Map.of("cwd", ws.toString()), null),
                new ConfigRowSpec.Replace("persistence-jsonl", java.util.Map.of("root", sess.toString()), null));
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, overlays, false, null))) {
            assertThat(rt.root().resolve(JobService.KEY)).as("JobService 缺席").isEmpty();
            List<String> jobTools = rt.root().resolve(ToolRegistry.KEY)
                    .map(registry -> registry.definitions(rt.root()).stream()
                            .map(ToolDefinition::name).filter(name -> name.startsWith("job_")).toList())
                    .orElseGet(List::of);
            assertThat(jobTools).as("缺席态零 job 工具").isEmpty();
        }
    }
}
