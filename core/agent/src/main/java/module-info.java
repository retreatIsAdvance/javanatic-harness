/**
 * harness-core-agent — agent 公开契约：Agent 句柄、Inbox 双队列、AgentRegistry
 * （ScopedValue initiator）与 AgentHandle（design: docs/design/04-agent-loop.md）。
 * 事件键（pre-step/request/turn-stopping）在 core/agent-loop——其负载含 llm 词表。
 */
module io.javanatic.harness.core.agent {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.agent;
}
