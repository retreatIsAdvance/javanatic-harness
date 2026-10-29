/**
 * Java LSP Definition：仅观察契约，无进程、网络、工具或第三方依赖。
 * 取消信号复用 llm.llm；设计见 docs/design/05-capability-seam.md。
 */
module io.javanatic.harness.lsp {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.llm.llm;

    exports io.javanatic.harness.lsp;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.lsp;
}
