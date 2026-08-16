/**
 * harness-shell-bash-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.shell.bash.local {
    requires io.javanatic.harness.shell.shell;    exports io.javanatic.harness.shell.bash.local;
}
