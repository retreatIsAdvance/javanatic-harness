/**
 * harness-shell-shell — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.shell.shell {
    requires io.deepseek.harness.kernel;    exports io.dsh.shell.shell;
}
