/**
 * harness-llm-llm — LLM capability seam: vendor-neutral service, adapter
 * contract, stream vocabulary, and chunk assembly (design: docs/design/05-capability-seam.md).
 * Consumers require only this module; vendor adapters live in provider modules.
 */
module io.javanatic.harness.llm.llm {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.llm.LlmPlugin;

    exports io.javanatic.harness.llm;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.llm;
}
