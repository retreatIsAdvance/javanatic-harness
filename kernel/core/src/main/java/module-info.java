/**
 * harness-kernel-core — the unified kernel: Scope / Events / Plugin in one JPMS
 * module (design: docs/design/01-kernel.md). Only java.base is required.
 */
module io.javanatic.harness.kernel {
    // ServiceLoader 发现插件(AppBoot 双向显式组合的发现侧)由本模块发起
    uses io.javanatic.harness.kernel.plugin.Plugin;
    exports io.javanatic.harness.kernel.scope;
    exports io.javanatic.harness.kernel.events;
    exports io.javanatic.harness.kernel.plugin;
}
