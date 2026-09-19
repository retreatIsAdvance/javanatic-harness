package io.javanatic.harness.sandbox.policy;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.plan.PlanModeEvent;
import io.javanatic.harness.plan.PlanModePlugin;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolsPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略解析：部署默认档、plan/mode fold 压只读（透传弃权不覆盖）、
 * config 缺失 fail loud（受限档的强制在场归执行器运行期 fail-closed,it12.5 修订）。
 */
class SandboxPolicyTest {

    @TempDir
    Path workspace;

    private SandboxPolicyService boot(SandboxPolicyPlugin policyPlugin) {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."), policyPlugin));
            return rt.root().require(SandboxPolicyService.KEY);
        }
    }

    @Test
    void defaultModeResolvesAndPlanFoldForcesReadOnly() {
        SandboxPolicyService policies = boot(new SandboxPolicyPlugin(
            new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace)));
        Session session = Session.create(Session.newId("p"), null, null);

        assertThat(policies.resolve(session).mode()).isEqualTo(SandboxMode.WORKSPACE_WRITE);

        session.append(new PlanModeEvent(1, true));
        assertThat(policies.resolve(session).mode()).isEqualTo(SandboxMode.READ_ONLY);

        session.append(new PlanModeEvent(2, false));
        assertThat(policies.resolve(session).mode()).isEqualTo(SandboxMode.WORKSPACE_WRITE);
    }

    @Test
    void dangerOptOutIsNotOverriddenByPlan() {
        SandboxPolicyService policies = boot(new SandboxPolicyPlugin(
            new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace)));
        Session session = Session.create(Session.newId("d"), null, null);
        session.append(new PlanModeEvent(1, true));
        // 透传档是部署显式弃权——计划模式不覆盖
        assertThat(policies.resolve(session).mode()).isEqualTo(SandboxMode.DANGER_FULL_ACCESS);
    }

    @Test
    void missingConfigFailsLoud() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."),
                new SandboxPolicyPlugin())))
                .hasMessageContaining("Plugin failed and rolled back: sandbox-policy")
                .cause()
                .hasMessageContaining("mode");
        }
    }
}
