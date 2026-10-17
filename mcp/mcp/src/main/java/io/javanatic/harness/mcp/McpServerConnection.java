package io.javanatic.harness.mcp;

import io.javanatic.harness.llm.AbortSignal;

import java.util.List;

/**
 * 单个 MCP server 的连接面（子进程 + 协议会话的只读视图，it27）。
 * 清单快照会话启动即冻结（R1/T4）：重连只做续命 + 对账，绝不等价于热变更——
 * 对账不一致抛 {@link McpCatalogDriftException}，server 转 DRIFTED。
 * call() 阻塞（虚拟线程下安全）、取消经 {@link AbortSignal} 传播（checkAbort
 * 轮询 + onCancel 中止等待并尽力通知远端；取消是正常路径，
 * {@link io.javanatic.harness.llm.AbortedException} 向上传播，不落工具错误结果）。
 */
public interface McpServerConnection {

    /** server id（kebab-case；配置校验与 tools/list 校验两处拒含 "__"）。 */
    String serverId();

    /** 当前连接状态（诊断面；工具面不因状态变化而增删——清单冻结）。 */
    McpServerState state();

    /** 会话启动时的工具清单快照（冻结，不随重连变化；顺序稳定）。 */
    List<McpToolDescriptor> tools();

    /**
     * 调用远端工具（tools/call）。
     *
     * @param toolName raw 工具名（非 {@code mcp__} 前缀名——拼接与命名校验在消费面）
     * @param argumentsJson 实参 JSON 对象文本（消费面已按映射后 schema 校验，wire 原文）
     * @param signal 取消信号（取消沿 AbortedException 语义传播，正常路径不落结果）
     * @return 调用结果（工具级错误 = 数据，isError 标记，不抛出）
     * @throws McpTimeoutException 调用超时（server 级 timeoutSeconds，缺省 60s）
     * @throws McpProtocolException 协议级畸形（非法 JSON / 错 id / 缺必填字段）
     * @throws McpCatalogDriftException 重连后清单与会话启动不一致（T4：消费面落轮终，不静默降级）
     * @throws McpConnectionException 其余连接面失败（进程死亡且重连耗尽、重连中、已关闭等）
     */
    McpCallResult call(String toolName, String argumentsJson, AbortSignal signal);
}
