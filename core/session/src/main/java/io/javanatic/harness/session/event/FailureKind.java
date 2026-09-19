package io.javanatic.harness.session.event;

/**
 * turn 失败的归因词表（厂商中立，家在本模块——{@link TurnEndReason.Error} 的
 * 分类组件）。词表家与依赖环：llm/llm requires core/session，TurnEndReason 直接
 * 引用 {@code LlmCallException.Kind} 会成环（JPMS 编译期即拒）——故 session 侧
 * 持有本词表，agent-loop 做 llm Kind → 本词表的穷尽映射，消费方（交互面渲染）
 * 按 kind 分流，不解析消息文本。旧日志缺分类 → UNKNOWN（渲染回退 message-only）。
 */
public enum FailureKind {

    /** 凭据被拒（401/403）——指引检查 API key。 */
    AUTH,

    /** 限流（429）——稍后重试可愈。 */
    RATE_LIMIT,

    /** 服务端故障（5xx）——重试可愈。 */
    SERVER,

    /** 连接/传输失败——重试可愈。 */
    NETWORK,

    /** 空闲看门狗掐断——流已部分消费。 */
    TIMEOUT,

    /** wire 解析失败（畸形 SSE、未知 finish_reason 等）——重试无意义。 */
    PROTOCOL,

    /** 输入超窗——由上层压缩恢复，传输重试无意义。 */
    OVERFLOW,

    /**
     * 耐久屏障失败（落盘/对账不确认，{@code DurabilityException}）——
     * 非-llm 通道：不经 {@code LlmCallException.Kind}，由 agent-loop 直接识别。
     */
    DISK,

    /** 未分类（非 llm 失败、旧日志缺分类、未知分类拼写）。 */
    UNKNOWN
}
