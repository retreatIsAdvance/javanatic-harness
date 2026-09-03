/**
 * harness-core-tools — R2 landing: ToolRegistry (single schema source) and
 * ToolExecutor (single dispatch pipeline: audit → dedup → pre-execute →
 * approval → execute → post-execute → audit). First boundary module to
 * carry Jackson: model/tool JSON is a validated boundary (08 §6).
 */
module io.javanatic.harness.core.tools {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;

    exports io.javanatic.harness.tools;
}
