/**
 * harness-mcp-stdio — MCP stdio provider (plugin id "mcp-stdio", it27):
 * 手写最小 JSON-RPC 2.0 over stdio 客户端（换行分帧 / initialize 协商 /
 * tools/list / tools/call / 错误分档）+ 子进程生命周期（装配期启动、
 * 进程树随 scope close 回收、stderr 转日志、有限重连 500ms→30s ×10）。
 * Jackson 仅此边界模块；env 清洗 = 缺省最小环境（PATH/HOME/TMPDIR）+
 * 行配置白名单叠加（server 以用户全权运行、不入沙箱——治理边界见迭代文档）。
 */
module io.javanatic.harness.mcp.stdio {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.mcp;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.stdio.McpStdioPlugin;

    exports io.javanatic.harness.mcp.stdio;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.mcp.stdio;
}
