/**
 * harness-shell-tool — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.shell.tool {
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.core.tools;    exports io.javanatic.harness.shell.tool;
}
