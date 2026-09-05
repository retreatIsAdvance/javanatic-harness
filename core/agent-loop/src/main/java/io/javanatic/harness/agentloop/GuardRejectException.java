package io.javanatic.harness.agentloop;

/** 停止条件超限（turn 以 TurnEndReason.Error 收口，消息含超限项）。 */
public final class GuardRejectException extends RuntimeException {

    /** @param message 超限说明 */
    public GuardRejectException(String message) {
        super(message);
    }
}
