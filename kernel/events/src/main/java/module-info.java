/**
 * harness-kernel-events — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.kernel.events {
    requires io.deepseek.harness.kernel.context;
    requires io.deepseek.harness.kernel.scope;    exports io.dsh.kernel.events;
}
