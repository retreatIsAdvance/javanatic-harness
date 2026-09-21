package io.javanatic.harness.session.persistence;

import java.io.IOException;

/**
 * 会话写者锁冲突：同一会话目录已被另一写者占用（第二进程或同 JVM 第二实例）。
 * 单写者保护是**拒绝**第二写者，不是合并——占用即 fail loud，不做静默让位。
 * 属 {@code load} 的 IOException 家族（占用检查在撕裂尾修复之前）；
 * CLI 层将其映射为退出码 3（12 §6 词表「写者锁冲突」）。
 */
public final class WriterLockException extends IOException {

    /** @param message 失败说明（含会话目录路径或会话 id） */
    public WriterLockException(String message) {
        super(message);
    }

    /** @param message 失败说明 @param cause 同 JVM 重叠锁等底层原因 */
    public WriterLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
