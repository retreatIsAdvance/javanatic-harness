/**
 * harness-bundle-base — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.bundle.base {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.interaction.approval;
    requires io.javanatic.harness.llm.deepseek;
    requires io.javanatic.harness.llm.replay;
    requires io.javanatic.harness.fs.local;
    requires io.javanatic.harness.fs.tool;
    requires io.javanatic.harness.shell.bash.local;
    requires io.javanatic.harness.shell.tool;
    requires io.javanatic.harness.session.persistence.jsonl;
    exports io.javanatic.harness.bundle.base;
}
