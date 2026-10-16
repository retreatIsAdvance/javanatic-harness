package io.javanatic.harness.mcp;

/**
 * MCP server 连接状态（it27）。
 */
public enum McpServerState {

    /** 会话活跃：initialize 完成、启动清单在案。 */
    CONNECTED,

    /** 断线退避重连中（500ms→30s ×10）；工具面保持冻结清单，call 快速失败（不静默挂起）。 */
    RECONNECTING,

    /** 终态：重连耗尽、重连后清单不一致（T4 落点）或提供者已关闭；会话内不再恢复。 */
    FAILED
}
