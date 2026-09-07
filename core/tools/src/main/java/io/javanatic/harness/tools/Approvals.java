package io.javanatic.harness.tools;

/** 内置审批实现：全放行（测试/headless）与全拒绝（策略验证）。 */
public final class Approvals {

    private Approvals() {
    }

    /** 全放行。 */
    public static ApprovalService auto() {
        return new ApprovalService() {
            @Override public ApprovalService.Mode mode() {
                return Mode.AUTO;
            }

            @Override public void require(ApprovalRequest request) {
                // AUTO:放行
            }
        };
    }

    /** 全拒绝。 */
    public static ApprovalService deny() {
        return new ApprovalService() {
            @Override public ApprovalService.Mode mode() {
                return Mode.DENY_ALL;
            }

            @Override public void require(ApprovalRequest request) {
                throw new ApprovalDeniedException("denied by policy: " + request.toolName());
            }
        };
    }
}
