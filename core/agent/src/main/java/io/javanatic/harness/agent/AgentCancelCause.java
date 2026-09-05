package io.javanatic.harness.agent;

/** 取消原因（稳定的调用方意图，first-cause-wins 语义由 AbortController 保证）。 */
public sealed interface AgentCancelCause {

    /** 用户主动取消。 */
    record User() implements AgentCancelCause {}

    /** 父编排者取消子 agent。 */
    record Parent() implements AgentCancelCause {}

    /** 钩子裁决取消。
     *
     * @param reason 钩子给出的原因 */
    record Hook(String reason) implements AgentCancelCause {}

    /** agent 被dispose（teardown 链的第一步）。 */
    record Disposed() implements AgentCancelCause {}
}
