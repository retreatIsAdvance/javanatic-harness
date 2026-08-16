/**
 * harness-examples-agent-spine — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.examples.agent.spine {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.llm.llm;
    exports io.javanatic.harness.examples.agent.spine;
}
