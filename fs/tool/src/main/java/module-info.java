/**
 * harness-fs-tool — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.fs.tool {
    requires io.deepseek.harness.fs.fs;
    requires io.deepseek.harness.core.tools;    exports io.dsh.fs.tool;
}
