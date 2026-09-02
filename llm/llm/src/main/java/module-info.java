/**
 * harness-llm-llm — LLM capability seam: vendor-neutral service, adapter
 * contract, stream vocabulary, and chunk assembly (design: docs/design/05-capability-seam.md).
 * Consumers require only this module; vendor adapters live in provider modules.
 */
module io.javanatic.harness.llm.llm {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.llm;
}
