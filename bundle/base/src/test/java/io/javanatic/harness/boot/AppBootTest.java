package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.tools.ApprovalService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
            new ConfigRowSpec.Replace("persistence-jsonl", Map.of("root", sess.toString()), null));
    }

    @Test
    void discoverFindsAllRegisteredPlugins() {
        Map<String, ?> discovered = new PluginLoader().discover();
        for (String id : List.of("session-store", "persistence-jsonl", "agents", "loop-guard",
            "system-prompt", "llm", "llm-openai-compat", "approval-auto", "approval-ask",
            "approval-deny", "tools", "fs-local", "fs-tool", "shell-bash-local",
            "shell-tool", "agent-loop")) {
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
}
