/**
 * harness-shell-bash-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.shell.bash.local {
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.llm.llm;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.shell.bash.local.BashLocalPlugin;

    exports io.javanatic.harness.shell.bash.local;
}
