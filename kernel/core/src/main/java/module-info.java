/**
 * harness-kernel-core — the unified kernel: Scope / Events / Plugin in one JPMS
 * module (design: docs/design/01-kernel.md). Only java.base is required.
 */
module io.javanatic.harness.kernel {
    exports io.javanatic.harness.kernel.scope;
    exports io.javanatic.harness.kernel.events;
    exports io.javanatic.harness.kernel.plugin;
}
