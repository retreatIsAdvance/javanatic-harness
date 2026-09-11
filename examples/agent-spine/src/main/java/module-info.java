/**
 * harness-examples-agent-spine — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.examples.agent.spine {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.llm.replay;
    requires io.javanatic.harness.fs.fs;
    requires io.javanatic.harness.fs.local;
    requires io.javanatic.harness.fs.tool;
    requires io.javanatic.harness.core.plan;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.sandbox.local;
    requires io.javanatic.harness.sandbox.policy;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.session.persistence.jsonl;
    exports io.javanatic.harness.examples.agent.spine;
}
