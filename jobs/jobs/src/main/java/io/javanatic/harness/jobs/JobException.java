package io.javanatic.harness.jobs;

/** seam 级失败（进程与管道细节归 Provider 实现，不在此降级或吞并）。 */
public class JobException extends RuntimeException {

    /** @param message 不可为空的失败事实描述 */
    public JobException(String message) {
        super(message);
    }

    /** @param message 失败事实描述 @param cause 根因（翻译上抛，不丢层） */
    public JobException(String message, Throwable cause) {
        super(message, cause);
    }
}
