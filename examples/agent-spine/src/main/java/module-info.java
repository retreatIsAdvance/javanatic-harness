/**
 * harness-examples-agent-spine — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.examples.agent.spine {
    requires io.deepseek.harness.core;    exports io.dsh.examples.agent.spine;
}
