package io.javanatic.harness.mcp;

/**
 * MCP 连接面失败基类（分档密封，it27）：进程/管道/重连耗尽等传输侧失败。
 * 工具级错误不走异常（{@link McpCallResult} isError 数据面）。
 */
public sealed class McpConnectionException extends RuntimeException
    permits McpProtocolException, McpTimeoutException, McpCatalogDriftException {

    /** @param message 失败说明 */
    public McpConnectionException(String message) {
        super(message);
    }

    /**
     * @param message 失败说明
     * @param cause 底层原因
     */
    public McpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
