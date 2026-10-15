package io.javanatic.harness.mcp;

/**
 * 重连后 tools/list 与会话启动清单不一致（T4 落点，it27）：server 转 FAILED，
 * 消费面落轮终——当前轮以 TurnEndReason.Error 收口 + 诊断「server 清单与会话
 * 启动不一致，请重开会话」；不静默降级为不可用。论据：schema 进请求指纹，
 * 热变更清单一律破 R1（与「不做 server 配置热重载」互为表里）。
 */
public final class McpCatalogDriftException extends McpConnectionException {

    /** @param message 不一致说明（含 server id 与差异摘要） */
    public McpCatalogDriftException(String message) {
        super(message);
    }

    /**
     * @param message 不一致说明
     * @param cause 底层原因
     */
    public McpCatalogDriftException(String message, Throwable cause) {
        super(message, cause);
    }
}
