package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.Approvals;
import io.javanatic.harness.tools.ApprovalService;

/** 全拒 Provider（id "approval-deny"，DENY_ALL）。组合需先于 "tools" 装载。 */
public final class ApprovalDenyPlugin implements Plugin {

    @Override
    public String id() {
        return "approval-deny";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(ApprovalService.KEY, Approvals.deny());
    }
}
