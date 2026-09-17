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
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.tools.ApprovalAutoPlugin, io.javanatic.harness.tools.ToolsPlugin;

    exports io.javanatic.harness.tools;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.tools;
}
