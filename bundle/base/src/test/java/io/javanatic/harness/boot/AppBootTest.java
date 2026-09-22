package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.sandbox.sandbox.BackendStatus;
import io.javanatic.harness.sandbox.sandbox.ConfinedArgv;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.tools.ApprovalService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** boot 集成:三层叠加/表达式/双向显式/dump/verify 两档/manifest 进 header。 */
class AppBootTest {

    @TempDir
    Path dir;

    private Path profile(String extraRows) throws Exception {
        Path file = dir.resolve("profile.yml");
        Files.writeString(file, """
            name: test
            policy: standard
            bundles: [base]
            rows:
            """ + extraRows);
        return file;
    }

    private List<ConfigRowSpec> rootOverlays() {
        Path ws = dir.resolve("ws");
        Path sess = dir.resolve("sessions");
        ws.toFile().mkdir();
        return List.of(
            new ConfigRowSpec.Replace("fs-local", Map.of("root", ws.toString()), null),
            new ConfigRowSpec.Replace("shell-tool",
                Map.of("workspace", ws.toString(), "timeoutSeconds", 30), null),
            // it21 单源断言:四处 workspace 承载键须同值,独立 overlay 也须逐个 pin
            new ConfigRowSpec.Replace("sandbox-policy",
                Map.of("mode", "workspace-write", "workspace", ws.toString()), null),
            new ConfigRowSpec.Replace("agent-loop", Map.of("cwd", ws.toString()), null),
            new ConfigRowSpec.Replace("persistence-jsonl", Map.of("root", sess.toString()), null));
    }

    @Test
    void discoverFindsAllRegisteredPlugins() {
        Map<String, ?> discovered = new PluginLoader().discover();
        for (String id : List.of("session-store", "persistence-jsonl", "agents", "loop-guard",
            "system-prompt", "llm", "llm-openai-compat", "approval-auto", "approval-ask",
            "approval-deny", "tools", "fs-local", "fs-tool", "shell-bash-local",
            "shell-tool", "shell-docker", "todo", "plan", "commands", "sandbox-local", "sandbox-policy",
            "presets", "compaction", "agent-loop")) {
            assertThat(discovered).containsKey(id);
        }
    }

    @Test
    void bootsBaseProfileAndManifestFlowsIntoHeader() throws Exception {
        Path profile = profile("");
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, rootOverlays(), false, null))) {
            assertThat(rt.root().resolve(ApprovalService.KEY)).isPresent();
            CompositionManifest manifest = rt.root().require(CompositionManifest.KEY);
            assertThat(manifest.rows().stream().map(CompositionManifest.Row::plugin))
                .contains("agent-loop").doesNotContain("approval-ask");

            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("boot-1"),
                CreateOptions.empty());
            assertThat(session.header().manifest()).isNotNull();
            assertThat(session.header().manifest().rows()).isEqualTo(manifest.rows());
        }
    }

    @Test
    void verifyStandardPassesWithoutKey() throws Exception {
        Path profile = profile("");
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, rootOverlays(),
                true, Policy.STANDARD))) {
            assertThat(rt).isNotNull();
        }
    }

    @Test
    void verifyProductionRejectsAutoApproval() throws Exception {
        Path profile = profile("");
        AppBoot.BootOptions options = new AppBoot.BootOptions(profile, rootOverlays(),
            true, Policy.PRODUCTION);
        assertThatThrownBy(() -> AppBoot.boot(options))
            .isInstanceOf(AppBoot.VerifyFailedException.class)
            .hasMessageContaining("AUTO");
    }

    @Test
    void unknownRowReferenceFailsLoud() throws Exception {
        Path profile = profile("  - plugin: no-such-plugin\n");
        assertThatThrownBy(() -> AppBoot.boot(new AppBoot.BootOptions(profile, rootOverlays(), false, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("行引用的插件未发现: no-such-plugin");
    }

    @Test
    void unreferencedDiscoveredPluginFailsLoud() throws Exception {
        // 不含 base 的 profile 只引用一个插件 → 其余发现项未被引用
        Path file = dir.resolve("tiny.yml");
        Files.writeString(file, """
            name: tiny
            bundles: []
            rows:
              - plugin: agents
            """);
        assertThatThrownBy(() -> AppBoot.boot(new AppBoot.BootOptions(file, List.of(), false, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未被任何行引用");
    }

    @Test
    void workspaceDriftFailsLoudNamingEveryDeclaredValue() throws Exception {
        Path profile = profile("");
        Path other = dir.resolve("other-ws");
        other.toFile().mkdir();
        List<ConfigRowSpec> drifting = new ArrayList<>(rootOverlays());
        // 只把 shell 围栏挪走:四处承载键不再同源
        drifting.add(new ConfigRowSpec.Replace("shell-tool",
            Map.of("workspace", other.toString(), "timeoutSeconds", 30), null));
        assertThatThrownBy(() -> AppBoot.boot(new AppBoot.BootOptions(profile, drifting, false, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("workspace drift")
            .hasMessageContaining("fs-local.root=" + dir.resolve("ws"))
            .hasMessageContaining("shell-tool.workspace=" + other);
    }

    @Test
    void workspaceTrailingSlashIsNotDrift() throws Exception {
        Path profile = profile("");
        Path ws = dir.resolve("ws");
        List<ConfigRowSpec> equivalent = new ArrayList<>(rootOverlays());
        equivalent.add(new ConfigRowSpec.Replace("agent-loop",
            Map.of("cwd", ws + "/"), null));
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, equivalent, false, null))) {
            assertThat(rt.root().require(ConfigService.KEY).configFor("agent-loop"))
                .containsEntry("cwd", ws + "/");
        }
    }

    @Test
    void dumpShowsDisabledAndMasksApiKey() throws Exception {
        Path profile = profile("");
        List<ConfigRowSpec> rows = AppBoot.compose(new AppBoot.BootOptions(
            profile, List.of(new ConfigRowSpec.Replace("llm-openai-compat",
                Map.of("name", "v", "apiKey", "sk-secret"), null)), false, null));
        String dump = AppBoot.dump(rows);
        assertThat(dump).contains("approval-ask").contains("(disabled: true)");
        assertThat(dump).doesNotContain("sk-secret").contains("****");
    }

    @Test
    void expressionDisablesProviderWithoutKey() throws Exception {
        Path profile = profile("");
        List<ConfigRowSpec> enabled = AppBoot.resolve(AppBoot.compose(
            new AppBoot.BootOptions(profile, rootOverlays(), false, null)));
        // 测试环境无 DEEPSEEK_API_KEY → provider 行禁用
        assertThat(enabled.stream().map(ConfigRowSpec::plugin))
            .doesNotContain("llm-openai-compat");
    }

    // ---- --verify 沙箱预警（观测面：注伪 provider 驱动三态） ----

    private static SandboxProvider statusOnlyProvider(BackendStatus status) {
        return new SandboxProvider() {
            @Override
            public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
                throw new UnsupportedOperationException("warning path never confines");
            }

            @Override
            public BackendStatus backendStatus() {
                return status;
            }
        };
    }

    @Test
    void sandboxWarningNamesNoBackendPlatformAndWaysOut() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxPolicyService.KEY,
                session -> new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir));
            rt.root().provide(SandboxProvider.KEY,
                statusOnlyProvider(new BackendStatus.NoBackend("win32")));
            assertThat(AppBoot.sandboxWarning(rt.root())).hasValueSatisfying(warning ->
                assertThat(warning).contains("workspace-write").contains("win32")
                    .contains("fail closed").contains("danger-full-access")
                    .contains("windows-acl"));
        }
    }

    @Test
    void sandboxWarningNamesProbeFailureAndWaysOut() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxPolicyService.KEY,
                session -> new SandboxPolicy(SandboxMode.READ_ONLY, dir));
            rt.root().provide(SandboxProvider.KEY, statusOnlyProvider(
                new BackendStatus.ProbeFailed("linux", "bwrap probe failed (binary: bwrap)")));
            assertThat(AppBoot.sandboxWarning(rt.root())).hasValueSatisfying(warning ->
                assertThat(warning).contains("read-only").contains("linux")
                    .contains("bwrap probe failed").contains("fail closed")
                    .contains("bubblewrap"));
        }
    }

    @Test
    void sandboxWarningSilentWithoutPolicyOrWithoutProvider() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxProvider.KEY,
                statusOnlyProvider(new BackendStatus.NoBackend("win32")));
            assertThat(AppBoot.sandboxWarning(rt.root())).isEmpty();
        }
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxPolicyService.KEY,
                session -> new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir));
            assertThat(AppBoot.sandboxWarning(rt.root())).isEmpty();
        }
    }

    @Test
    void sandboxWarningSilentForPassthroughPolicyAndReadyBackend() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxPolicyService.KEY,
                session -> new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, dir));
            rt.root().provide(SandboxProvider.KEY,
                statusOnlyProvider(new BackendStatus.NoBackend("win32")));
            assertThat(AppBoot.sandboxWarning(rt.root())).isEmpty();
        }
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxPolicyService.KEY,
                session -> new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir));
            rt.root().provide(SandboxProvider.KEY,
                statusOnlyProvider(new BackendStatus.Ready("seatbelt")));
            assertThat(AppBoot.sandboxWarning(rt.root())).isEmpty();
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void sandboxWarningSilentOnRealDarwinComposition() throws Exception {
        Path profile = profile("");
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, rootOverlays(),
                true, Policy.STANDARD))) {
            assertThat(AppBoot.sandboxWarning(rt.root())).isEmpty();
        }
    }

    @Test
    void verifyWarnsOnStderrWhenPlatformChainIsEmptyAndStillPasses() throws Exception {
        String originalOs = System.getProperty("os.name");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            // 注伪平台：真组合（含 sandbox-local/sandbox-policy 行）在 win32 链上
            // 触发 NoBackend 预警——stderr 捕获即 verify 的观测面证据
            System.setProperty("os.name", "Windows 11");
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Path profile = profile("");
            try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, rootOverlays(),
                    true, Policy.STANDARD))) {
                assertThat(rt).isNotNull();
            } finally {
                System.setErr(originalErr);
            }
        } finally {
            System.setErr(originalErr);
            System.setProperty("os.name", originalOs);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8))
            .contains("sandbox warning").contains("win32").contains("fail closed");
    }
}
