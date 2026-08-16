/**
 * harness-kernel-core — the unified kernel: Scope / Events / Plugin in one JPMS
 * module (design: docs/design/01-kernel.md). Skeleton placeholder; the JPMS
 * dependency graph is enforced from day one.
 */
module io.javanatic.harness.kernel {
    requires io.javanatic.harness.kernel.brand;

    exports io.javanatic.harness.kernel.scope;
    exports io.javanatic.harness.kernel.events;
    exports io.javanatic.harness.kernel.plugin;
}
