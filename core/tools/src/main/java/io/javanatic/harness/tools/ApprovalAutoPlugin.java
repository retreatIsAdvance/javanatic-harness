package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/** 提供 auto 审批服务（id "approval-auto"）。组合需先于 "tools" 装载。 */
public final class ApprovalAutoPlugin implements Plugin {

    @Override
    public String id() {
        return "approval-auto";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(ApprovalService.KEY, Approvals.auto());
    }
}
