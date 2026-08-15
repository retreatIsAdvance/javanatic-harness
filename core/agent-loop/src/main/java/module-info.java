/**
 * harness-core-agent-loop — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.core.agent.loop {
    requires io.deepseek.harness.core.agent;
    requires io.deepseek.harness.core.tools;
    requires io.deepseek.harness.core.system.prompt;
    requires io.deepseek.harness.llm.llm;
    requires io.deepseek.harness.kernel;    exports io.dsh.core.agent.loop;
}
