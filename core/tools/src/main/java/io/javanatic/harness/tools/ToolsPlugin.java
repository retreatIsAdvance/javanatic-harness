package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;

/**
 * 提供 ToolRegistry 与 ToolExecutor（id "tools"）。审批是 executor 的构造
 * 强制依赖（R4）：组合必须先装载某个 ApprovalService 提供者
 * （如 {@link ApprovalAutoPlugin}）——缺失在 apply 时 fail loud。
 */
public final class ToolsPlugin implements Plugin {

    @Override
    public String id() {
        return "tools";
    }

    @Override
    public void apply(Scope scope) {
        RegistryImpl registry = new RegistryImpl();
        scope.provide(ToolRegistry.KEY, registry);
        ApprovalService approval = scope.require(ApprovalService.KEY);
        Events events = scope.require(Runtime.KEY).events();
        scope.provide(ToolExecutor.KEY, new ToolExecutorImpl(registry, approval, events, scope));
    }
}
