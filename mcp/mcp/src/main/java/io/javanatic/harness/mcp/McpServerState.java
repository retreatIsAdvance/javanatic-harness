package io.javanatic.harness.mcp;

/**
 * MCP server 连接状态（it27）。
 */
public enum McpServerState {

    /** 会话活跃：initialize 完成、启动清单在案。 */
    CONNECTED,

    /** 断线退避重连中（500ms→30s ×10）；工具面保持冻结清单，call 快速失败（不静默挂起）。 */
    RECONNECTING,

    /** 终态：重连耗尽或提供者已关闭；会话内不再恢复。 */
    FAILED,

    /**
     * 终态：重连后 tools/list 与会话启动清单不一致（T4 落点，it27）；会话内不再恢复。
     * 与 FAILED 分档：消费面据此落轮终（TurnEndReason.Error +「请重开会话」）；
     * 普通不可用（重连耗尽/已关闭）仍按 error result 降级，不牵连当前轮。
     */
    DRIFTED
}
