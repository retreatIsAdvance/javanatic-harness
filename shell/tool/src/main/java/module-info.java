/**
 * harness-shell-tool — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.shell.tool {
    requires io.deepseek.harness.shell.shell;
    requires io.deepseek.harness.core.tools;    exports io.dsh.shell.tool;
}
