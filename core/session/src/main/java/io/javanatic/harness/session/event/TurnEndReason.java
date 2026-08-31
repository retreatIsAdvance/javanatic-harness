package io.javanatic.harness.session.event;

/**
 * turn 结束原因。开放接口（对应 dsh 的 merge-extensible TurnEndReasonMap）：
 * 核心变体在此，agent-loop 等切片追加自己的变体，消费方 switch 用文档化默认分支。
 */
public interface TurnEndReason {

    /** 正常完成。 */
    record Completed() implements TurnEndReason {}

    /** 被取消（用户 / 父会话 / 钩子）。cause 的词表随消费者演进。 */
    record Aborted(String cause) implements TurnEndReason {}

    /** 出错结束。message 携带结构化错误的摘要。 */
    record Error(String message) implements TurnEndReason {}
}
