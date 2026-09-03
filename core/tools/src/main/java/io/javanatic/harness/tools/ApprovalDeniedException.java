package io.javanatic.harness.tools;

/** 审批拒绝（unchecked：executor 捕获转 error result）。 */
public final class ApprovalDeniedException extends RuntimeException {

    /** @param message 拒绝原因 */
    public ApprovalDeniedException(String message) {
        super(message);
    }
}
