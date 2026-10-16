/**
 * harness-mcp-mcp — MCP capability Definition (design: docs/design/05-capability-seam.md §4).
 * Providers implement; consumers see only this contract. 零第三方依赖（契约自持，it27）；
 * 取消信号词表复用 llm.llm（AbortSignal——shell/shell 先例，取消语义全能力面同形）。
 */
module io.javanatic.harness.mcp {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.llm.llm;

    exports io.javanatic.harness.mcp;
}
