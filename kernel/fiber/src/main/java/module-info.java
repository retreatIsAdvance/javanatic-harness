/**
 * harness-kernel-fiber — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.kernel.fiber {
    requires io.deepseek.harness.kernel.context;    exports io.dsh.kernel.fiber;
}
