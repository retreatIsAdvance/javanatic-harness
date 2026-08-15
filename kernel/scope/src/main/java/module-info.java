/**
 * harness-kernel-scope — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.kernel.scope {
    requires io.deepseek.harness.kernel.context;
    requires io.deepseek.harness.kernel.fiber;    exports io.dsh.kernel.scope;
}
