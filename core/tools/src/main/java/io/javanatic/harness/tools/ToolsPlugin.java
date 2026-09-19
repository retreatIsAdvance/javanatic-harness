package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.SessionStore;

/**
 * 提供 ToolRegistry 与 ToolExecutor（id "tools"）。审批与会话存储是 executor 的
 * 构造强制依赖（R4）：组合必须先装载某个 ApprovalService 提供者
 * （如 {@link ApprovalAutoPlugin}）与 SessionStore——缺失在 apply 时 fail loud。
 * 依赖解析序即 fail loud 的归因序：审批在前（缺审批的组合先报审批）。
 */
public final class ToolsPlugin implements Plugin {

    @Override
    public String id() {
        return "tools";
    }

    @Override
    public void apply(Scope scope) {
        ScopedRegistry registry = new ScopedRegistry();
        scope.provide(ToolRegistry.KEY, registry);
        ApprovalService approval = scope.require(ApprovalService.KEY);
        SessionStore store = scope.require(SessionStore.KEY);
        Events events = scope.require(Runtime.KEY).events();
        scope.provide(ToolExecutor.KEY, new ToolExecutorImpl(registry, approval, store, events, scope));
    }
}
