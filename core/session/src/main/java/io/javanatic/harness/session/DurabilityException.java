package io.javanatic.harness.session;

/**
 * 耐久屏障失败：flush listener（持久化落盘 + 对账）未能确认会话已落盘。
 * 派发前屏障与 dispose 屏障都以本类型 fail loud——agent-loop 在
 * {@code failureKind} 的非-llm 分支把它归入 {@code FailureKind.DISK}，
 * 与 llm 词表分流（{@code LlmCallException.Kind} 不新增变体）。
 */
public final class DurabilityException extends RuntimeException {

    /** @param message 失败说明（含会话 id 与底层原因） */
    public DurabilityException(String message, Throwable cause) {
        super(message, cause);
    }
}
