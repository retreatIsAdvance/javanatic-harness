/**
 * harness-fs-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.fs.local {
    requires io.deepseek.harness.fs.fs;    exports io.dsh.fs.local;
}
