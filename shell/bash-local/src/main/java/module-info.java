/**
 * harness-shell-bash-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.shell.bash.local {
    requires io.deepseek.harness.shell.shell;    exports io.dsh.shell.bash.local;
}
