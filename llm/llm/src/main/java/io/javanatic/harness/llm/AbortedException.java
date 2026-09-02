package io.javanatic.harness.llm;

/** 流式调用被取消（经 {@link AbortSignal#checkAbort()} 抛出）。取消是正常路径，不是错误。 */
public final class AbortedException extends RuntimeException {

    /** @param message 取消原因 */
    public AbortedException(String message) {
        super(message);
    }
}
