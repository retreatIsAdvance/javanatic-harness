package io.javanatic.harness.mcp;

/**
 * MCP server 连接状态（it27.1）。
 *
 * <p>清单变化不是状态：正常换代经 {@link McpServerConnection#catalogGeneration()}
 * 表达，由消费面在轮屏障采纳；不可用事实才落本枚举。
 */
public enum McpServerState {

    /** 会话活跃：initialize 完成、当前清单在案。 */
    CONNECTED,

    /** 断线退避重连中（500ms→30s ×10）；工具面保留当前清单，call 快速失败（不静默挂起）。 */
    RECONNECTING,

    /** 终态：重连耗尽或提供者已关闭；会话内不再恢复（call 以连接错误结果降级）。 */
    FAILED
}
