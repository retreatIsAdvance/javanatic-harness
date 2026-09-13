package io.javanatic.harness.sandbox.policy;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.plan.PlanModeService;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;

import java.nio.file.Path;
import java.util.Set;

/**
 * 策略解析插件（id "sandbox-policy"，requires "plan"——组合约束：计划耦合
 * 没有 plan 插件即无意义，fail loud 不静默失效）。解析规则：
 * 部署默认档；plan/mode fold 激活<b>且默认档受限</b>时压 READ_ONLY
 * （DANGER 是部署显式弃权，不覆盖）。
 */
public final class SandboxPolicyPlugin implements Plugin {

    private final SandboxPolicy explicit;

    /** 数据组合路径：mode/workspace 从行配置解析（均必配，缺失 fail loud）。 */
    public SandboxPolicyPlugin() {
        this.explicit = null;
    }

    /** @param explicit 程序化组合的显式策略 */
    public SandboxPolicyPlugin(SandboxPolicy explicit) {
        this.explicit = explicit;
    }

    @Override
    public String id() {
        return "sandbox-policy";
    }

    @Override
    public Set<String> requires() {
        return Set.of("plan");
    }

    @Override
    public void apply(Scope scope) {
        SandboxPolicy policy = explicit != null ? explicit : fromConfig(scope);
        // 不做「受限档需 SandboxProvider 在场」的装载期校验（it12.5 修订,对齐 dsh
        // 运行期 fail-closed）：docker 等执行器自身消费策略（挂载面即强制）,不装
        // 同机 provider 是合法组合；bash-local 的逐调用 fail-closed belt 兜底,
        // PRODUCTION 档位探针(≠DANGER)不变
        scope.provide(SandboxPolicyService.KEY, session ->
            policy.confining() && PlanModeService.foldActive(session.events())
                ? new SandboxPolicy(SandboxMode.READ_ONLY, policy.workspaceRoot())
                : policy);
    }

    private SandboxPolicy fromConfig(Scope scope) {
        var config = scope.require(ConfigService.KEY).configFor(id());
        return new SandboxPolicy(
            SandboxMode.fromWire(ConfigValues.requireString(config, id(), "mode")),
            Path.of(ConfigValues.requireString(config, id(), "workspace")));
    }
}
