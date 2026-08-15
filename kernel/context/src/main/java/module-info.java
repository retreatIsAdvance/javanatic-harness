/**
 * harness-kernel-context — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.kernel.context {
    requires io.deepseek.harness.kernel.brand;    exports io.dsh.kernel.context;
}
