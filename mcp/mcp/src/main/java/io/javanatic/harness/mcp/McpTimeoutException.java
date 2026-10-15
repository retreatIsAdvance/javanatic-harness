package io.javanatic.harness.mcp;

/**
 * tools/call 超时（server 级 timeoutSeconds，缺省 60s——dsh 同口径，it27）：
 * 超时即失败（消费面转工具错误结果）——不静默续等。
 */
public final class McpTimeoutException extends McpConnectionException {

    /** @param message 超时说明（含 server id 与时限） */
    public McpTimeoutException(String message) {
        super(message);
    }

    /**
     * @param message 超时说明
     * @param cause 底层原因
     */
    public McpTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
