/**
 * harness-lsp-tool — LSP 三能力 Consumer（插件 id "lsp-tool"，it29 停 3）：
 * lsp_diagnostics（GENERIC）与 lsp_definition / lsp_references（LOCATIONS）。
 * 仅依赖 Definition 模块 lsp/lsp——不 import Provider（三角色编译期保障，05 §10）。
 * 双态：LspService 在场才注册三工具，缺省组合零工具零进程零网络；恒需审批。
 * 结果文本四态逐字措辞（停 2 §8 纪律），零新事件：审计 = tool/call + tool/result。
 */
module io.javanatic.harness.lsp.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.lsp;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.llm.llm;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.lsp.tool.LspToolPlugin;

    exports io.javanatic.harness.lsp.tool;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性。
    opens io.javanatic.harness.lsp.tool;
}
