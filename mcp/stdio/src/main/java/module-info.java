/**
 * harness-mcp-stdio — MCP stdio provider (plugin id "mcp-stdio", it27):
 * 手写最小 JSON-RPC 2.0 over stdio 客户端（换行分帧 / initialize 协商 /
 * tools/list / tools/call / 错误分档）+ 子进程生命周期（装配期启动、
 * 进程树随 scope close 回收、stderr 转日志）。Jackson 仅此边界模块。
 */
module io.javanatic.harness.mcp.stdio {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.mcp;
    requires com.fasterxml.jackson.databind;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.stdio.McpStdioPlugin;

    exports io.javanatic.harness.mcp.stdio;
}
