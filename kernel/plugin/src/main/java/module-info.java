/**
 * harness-kernel-plugin — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.kernel.plugin {
    requires io.deepseek.harness.kernel.context;
    requires io.deepseek.harness.kernel.fiber;    exports io.dsh.kernel.plugin;
}
