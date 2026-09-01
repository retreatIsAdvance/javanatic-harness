package io.javanatic.harness.session;

import io.javanatic.harness.session.event.SessionEvent;

import java.util.List;

/**
 * 创建会话的输入：seed 重放/复刻既有日志；header 为 null 时生成最小头。
 *
 * @param seed   初始重放历史（null = 空会话）
 * @param header 存储元数据（null = fresh）
 */
public record CreateOptions(List<SessionEvent> seed, SessionHeader header) {

    /** 空会话。 */
    public static CreateOptions empty() {
        return new CreateOptions(null, null);
    }
}
