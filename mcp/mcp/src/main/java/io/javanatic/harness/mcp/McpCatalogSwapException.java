package io.javanatic.harness.mcp;

/**
 * 新工具清单代际无法落地（it27.1，T4 重裁）：例如新代公开名与既有工具撞名、
 * 公开名归一不可表达。旧代原样保活，当前轮以 {@code TurnEndReason.Error}
 * 收口（fail loud，不静默少工具）；清单本身不是连接故障，server 不转 FAILED。
 */
public final class McpCatalogSwapException extends McpConnectionException {

    /** @param message 无法落地说明（含 server id 与冲突摘要） */
    public McpCatalogSwapException(String message) {
        super(message);
    }

    /**
     * @param message 无法落地说明
     * @param cause 底层原因
     */
    public McpCatalogSwapException(String message, Throwable cause) {
        super(message, cause);
    }
}
