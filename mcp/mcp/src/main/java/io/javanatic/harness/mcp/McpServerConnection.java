package io.javanatic.harness.mcp;

import io.javanatic.harness.llm.AbortSignal;

import java.util.List;

/**
 * 单个 MCP server 的连接面（协议会话的只读视图，it27.1）。
 *
 * <p>清单语义：{@link #tools()} 是「当前已知清单」，随远端通知 / 重连对账
 * 异步变化；{@link #catalogGeneration()} 标出代际（内容变化才递增）。
 * 消费面只在轮屏障（TURN_STOPPING / PRE_STEP）采纳新代——轮内注册面稳定，
 * 请求 schema 与账本不错位（R1/T4）。
 *
 * <p>call() 阻塞（虚拟线程下安全）、取消经 {@link AbortSignal} 传播（checkAbort
 * 轮询 + onCancel 中止等待并尽力通知远端；取消是正常路径，
 * {@link io.javanatic.harness.llm.AbortedException} 向上传播，不落工具错误结果）。
 */
public interface McpServerConnection {

    /** server id（kebab-case；配置面拒含 "__"）。 */
    String serverId();

    /** 当前连接状态（诊断面；清单换代不改变状态）。 */
    McpServerState state();

    /**
     * 当前已知工具清单（顺序稳定；刷新异步发布，消费面在轮屏障采纳）。
     */
    List<McpToolDescriptor> tools();

    /**
     * 工具清单代际：装配完成时 1；清单内容（名称 / description / schema）变化才
     * 递增（仅顺序变化不算）。消费面比较代际决定是否在轮屏障换代。
     */
    long catalogGeneration();

    /**
     * 调用远端工具（tools/call）。
     *
     * @param toolName raw 工具名（非 {@code mcp__} 前缀名——拼接与命名校验在消费面）
     * @param argumentsJson 实参 JSON 对象文本（消费面已按映射后 schema 校验，wire 原文）
     * @param signal 取消信号（取消沿 AbortedException 语义传播，正常路径不落结果）
     * @return 调用结果（工具级错误 = 数据，isError 标记，不抛出）
     * @throws McpTimeoutException 调用超时（server 级 timeoutSeconds，缺省 60s）
     * @throws McpProtocolException 协议级畸形（非法 JSON / 错 id / 缺必填字段）
     * @throws McpConnectionException 其余连接面失败（进程死亡且重连耗尽、重连中、已关闭等）
     */
    McpCallResult call(String toolName, String argumentsJson, AbortSignal signal);
}
