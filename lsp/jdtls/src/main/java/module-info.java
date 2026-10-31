/**
 * harness-lsp-jdtls — it29 停 2 真实 JDT LS Provider（自有 LSP wire 边界）：
 * Content-Length 字节帧 + 最小 JSON-RPC 2.0 客户端（T5 已裁自有边界，不引 LSP4J），
 * 单项目长驻进程、观察串行区与有界生命周期（shutdown/exit 排水 → TERM → KILL → 复验）。
 * Jackson 仅此边界模块；LS 以用户全权进程运行、不入沙箱——治理边界见迭代文档与边界登记表。
 * 停 3 增 JdtlsPlugin 装配件（plugin id "lsp-jdtls"，双通道 SPI：本 provides + services 文件）。
 */
module io.javanatic.harness.lsp.jdtls {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.lsp;
    requires com.fasterxml.jackson.databind;

    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.lsp.jdtls.JdtlsPlugin;

    exports io.javanatic.harness.lsp.jdtls;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性。
    opens io.javanatic.harness.lsp.jdtls;
}
