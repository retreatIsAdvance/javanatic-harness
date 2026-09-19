package io.javanatic.harness.preset;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.fs.local.LocalFs;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** preset 组合(06 §6):mount 可见性/隔离/回收/未知插件 fail loud/动作拒绝。 */
class PresetServiceTest {

    @TempDir
    Path root;

    private void writePreset(String name, String rows) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("preset.yml"), "name: " + name + "\ndescription: 测试\n" + rows);
    }

    private Runtime presetRuntime() {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> Map.of());
        // fs-tool 逐调用解析策略——作用域测试用显式透传策略即可
        rt.root().provide(SandboxPolicyService.KEY,
            session -> new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, root));
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin()));
        rt.root().provide(AgentPresets.KEY, new PresetService(root));
        return rt;
    }

    @Test
    void mountUnderAgentScopeVisibleOnlyThere() throws Exception {
        writePreset("readonly", """
            rows:
              - plugin: fs-tool
            """);
        // fs-tool 需要 tools+fs-local;装进同一 agent scope 供 end-to-end
        try (Runtime rt = presetRuntime()) {
            rt.root().provide(FsService.KEY,
                new LocalFs(root));
            AgentPresets presets = rt.root().require(AgentPresets.KEY);
            Scope agentScope = rt.root().child();
            presets.mount(agentScope, "readonly");
            ToolRegistry tools = rt.root().require(ToolRegistry.KEY);
            assertThat(tools.schemas(agentScope)).isNotEmpty();   // 本 agent 可见
            assertThat(tools.schemas(rt.root())).isEmpty();       // 组合层不可见
            Scope sibling = rt.root().child();
            assertThat(tools.schemas(sibling)).isEmpty();         // 兄弟不可见
            agentScope.close();
            assertThat(tools.schemas(rt.root())).isEmpty();       // 关闭后回收(不再泄漏)
        }
    }

    @Test
    void unknownPluginFailsLoudListingAll() throws Exception {
        writePreset("broken", """
            rows:
              - plugin: no-such
              - plugin: also-missing
            """);
        try (Runtime rt = presetRuntime()) {
            AgentPresets presets = rt.root().require(AgentPresets.KEY);
            assertThatThrownBy(() -> presets.mount(rt.root().child(), "broken"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such")
                .hasMessageContaining("also-missing");
        }
    }

    @Test
    void patchActionsRejectedInPreset() throws Exception {
        writePreset("actioned", """
            rows:
              - plugin: fs-tool
                replace: true
            """);
        try (Runtime rt = presetRuntime()) {
            AgentPresets presets = rt.root().require(AgentPresets.KEY);
            assertThatThrownBy(() -> presets.resolve("actioned"))
                .hasMessageContaining("patch action 'replace'");
        }
    }

    @Test
    void listAndResolve() throws Exception {
        writePreset("alpha", "rows: []");
        writePreset("beta", "rows: []");
        try (Runtime rt = presetRuntime()) {
            AgentPresets presets = rt.root().require(AgentPresets.KEY);
            assertThat(presets.list()).extracting(AgentPreset::name).containsExactly("alpha", "beta");
            assertThat(presets.resolve("alpha").description()).isEqualTo("测试");
            assertThatThrownBy(() -> presets.resolve("ghost"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        }
    }
}
