/**
 * harness-shell-shell — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.shell.shell {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.llm.llm;

    exports io.javanatic.harness.shell.shell;
}
