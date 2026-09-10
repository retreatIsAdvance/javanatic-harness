/**
 * harness-core-agent-loop — Turn/Step 状态机驱动（R1 落账侧 / R2 分发点 / R4
 * 构造器强制治理依赖）。事件键（pre-step/request/request-error/turn-stopping）
 * 在本模块——负载含 llm 词表（design: docs/design/04-agent-loop.md）。
 */
module io.javanatic.harness.core.agent.loop {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.llm.llm;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.agentloop.LoopGuardPlugin, io.javanatic.harness.agentloop.AgentLoopPlugin, io.javanatic.harness.agentloop.CompactionPlugin;

    exports io.javanatic.harness.agentloop;
}
