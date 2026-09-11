package io.javanatic.harness.tools;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;

import java.util.Objects;

/**
 * 一次工具执行的下文：取消信号（长操作轮询 checkAbort）与所属会话。
 *
 * <p>session 供工具追加<b>领域事件</b>（如 todo/write 的状态快照）——审计对
 * (tool/call + tool/result) 仍归 executor 无条件落账，工具写的是非审计事件；
 * Session.append 自身的同步与 surface 校验是既有防线。跨执行者的日志交错序
 * 任意，事件关联靠内容（callId），永不靠相邻性。
 */
public record ToolExecutionContext(AbortSignal signal, Session session) {

    /** @throws NullPointerException signal/session 为 null 时 */
    public ToolExecutionContext {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(session, "session");
    }
}
