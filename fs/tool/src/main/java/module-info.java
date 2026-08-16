/**
 * harness-fs-tool — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.fs.tool {
    requires io.javanatic.harness.fs.fs;
    requires io.javanatic.harness.core.tools;    exports io.javanatic.harness.fs.tool;
}
