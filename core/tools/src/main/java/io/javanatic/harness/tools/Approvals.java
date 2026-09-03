package io.javanatic.harness.tools;

/** 内置审批实现：全放行（测试/headless）与全拒绝（策略验证）。 */
public final class Approvals {

    private Approvals() {
    }

    /** 全放行。 */
    public static ApprovalService auto() {
        return request -> { };
    }

    /** 全拒绝。 */
    public static ApprovalService deny() {
        return request -> {
            throw new ApprovalDeniedException("denied by policy: " + request.toolName());
        };
    }
}
