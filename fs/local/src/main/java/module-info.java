/**
 * harness-fs-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.fs.local {
    requires io.javanatic.harness.fs.fs;    exports io.javanatic.harness.fs.local;
}
