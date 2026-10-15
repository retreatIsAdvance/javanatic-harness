/**
 * harness-mcp-mcp — MCP capability Definition (design: docs/design/05-capability-seam.md §4).
 * Providers implement; consumers see only this contract. 零第三方依赖（契约自持，it27）。
 */
module io.javanatic.harness.mcp {
    requires io.javanatic.harness.kernel;

    exports io.javanatic.harness.mcp;
}
