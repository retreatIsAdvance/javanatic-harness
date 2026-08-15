/**
 * JPMS aggregate: re-exports the kernel submodules so consumers can
 * `requires io.deepseek.harness.kernel` in one line (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.kernel {
    requires transitive io.deepseek.harness.kernel.brand;
    requires transitive io.deepseek.harness.kernel.context;
    requires transitive io.deepseek.harness.kernel.fiber;
    requires transitive io.deepseek.harness.kernel.scope;
    requires transitive io.deepseek.harness.kernel.events;
    requires transitive io.deepseek.harness.kernel.plugin;
    requires transitive io.deepseek.harness.kernel.config;}
