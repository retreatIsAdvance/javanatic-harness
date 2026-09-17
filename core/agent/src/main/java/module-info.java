/**
 * harness-core-agent — agent 公开契约：Agent 句柄、Inbox 双队列、AgentRegistry
 * （ScopedValue initiator）与 AgentHandle（design: docs/design/04-agent-loop.md）。
 * 事件键（pre-step/request/turn-stopping）在 core/agent-loop——其负载含 llm 词表。
 */
module io.javanatic.harness.core.agent {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.agent.AgentPlugin;

    exports io.javanatic.harness.agent;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.agent;
}
