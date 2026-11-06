package io.javanatic.harness.delegation;

/** seam 级失败（子代执行细节归 Provider 实现，不在此降级或吞并）。 */
public class DelegationException extends RuntimeException {

    /** @param message 不可为空的失败事实描述 */
    public DelegationException(String message) {
        super(message);
    }

    /** @param message 失败事实描述 @param cause 根因（翻译上抛，不丢层） */
    public DelegationException(String message, Throwable cause) {
        super(message, cause);
    }
}
