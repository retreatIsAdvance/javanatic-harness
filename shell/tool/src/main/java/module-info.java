/**
 * harness-shell-tool — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.shell.tool {
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.shell.tool.ShellToolPlugin;

    exports io.javanatic.harness.shell.tool;
}
