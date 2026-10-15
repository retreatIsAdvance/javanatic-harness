/**
 * harness-mcp-tool — MCP tool consumer (plugin id "mcp-tool", requires "tools" +
 * "mcp-stdio", it27): 已连接 server 的工具清单 → 既有 ScopedRegistry 注册
 * （mcp__&lt;server&gt;__&lt;raw&gt; 命名，approvalExempt=false 恒真）+ 启动清单落
 * mcp/catalog 扩展事件 + JSON Schema → ValueSchema 白名单映射（不支持构造
 * 排除 + WARN）。
 */
module io.javanatic.harness.mcp.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.mcp;
    requires io.javanatic.harness.core.tools;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.tool.McpToolPlugin;

    exports io.javanatic.harness.mcp.tool;
}
