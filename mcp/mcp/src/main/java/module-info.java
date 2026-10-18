/**
 * harness-mcp-mcp — MCP capability Definition (design: docs/design/05-capability-seam.md §4).
 * Providers implement; consumers see only this contract. 零第三方依赖（契约自持，it27）；
 * 取消信号词表复用 llm.llm（AbortSignal——shell/shell 先例，取消语义全能力面同形）。
 */
module io.javanatic.harness.mcp {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.llm.llm;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.McpRegistryPlugin;

    exports io.javanatic.harness.mcp;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.mcp;
}
