/**
 * harness-mcp-http — MCP Streamable HTTP provider (plugin id "mcp-http", it27.1):
 * 手写最小 Streamable HTTP 客户端（POST JSON-RPC / JSON 与 SSE 响应分流 /
 * Mcp-Session-Id 捕获回传 / MCP-Protocol-Version / GET SSE 通知通道 / 404
 * 会话失效 / DELETE 收尾 / Redirect.NEVER / 超时、取消、有限重连）。
 * Jackson 仅此边界模块；T7 单帧 8 MiB 上限（响应体与单条 SSE 事件），与消费面
 * 256 KiB 结果上限分层。连接不提供 McpClientService——统一由 mcp-registry 聚合。
 */
module io.javanatic.harness.mcp.http {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.mcp;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.http.McpHttpPlugin;

    exports io.javanatic.harness.mcp.http;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.mcp.http;
}
