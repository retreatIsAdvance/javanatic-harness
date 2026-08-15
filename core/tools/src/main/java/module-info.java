/**
 * harness-core-tools — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.core.tools {
    requires io.deepseek.harness.kernel;    exports io.dsh.core.tools;
}
