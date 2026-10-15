package io.javanatic.harness.mcp;

/**
 * 协议级畸形响应（非法 JSON / 错 id / 缺必填字段 / 非 JSON-RPC 报文，it27）：
 * 消费面转为工具错误结果 fail loud（不静默、不假装成功），turn 不炸。
 */
public final class McpProtocolException extends McpConnectionException {

    /** @param message 畸形说明（含期望与实际摘要） */
    public McpProtocolException(String message) {
        super(message);
    }

    /**
     * @param message 畸形说明
     * @param cause 底层原因
     */
    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
