/**
 * harness-fs-fs — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.fs.fs {
    requires io.deepseek.harness.kernel;    exports io.dsh.fs.fs;
}
