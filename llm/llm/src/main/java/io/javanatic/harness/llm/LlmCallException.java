package io.javanatic.harness.llm;

/**
 * LLM 调用失败（seam typed 失败,dsh LlmCall 错误形状）：厂商中立分类经
 * {@link Kind} 词表表达——消费方（交互面渲染、重试路由）按 kind 分流,
 * <b>不解析消息文本</b>（it12.5 原则）。消息保留厂商细节（如
 * {@code "deepseek http 401"}）供人读;程序判定读 {@link #kind()}。
 */
public final class LlmCallException extends RuntimeException {

    /** 失败分类词表（厂商细节映射到本词表是适配器的义务）。 */
    public enum Kind {

        /** 凭据被拒（401/403）——指引检查 API key,重试无意义。 */
        AUTH,

        /** 限流（429）——稍后重试可愈。 */
        RATE_LIMIT,

        /** 服务端故障（5xx）——重试可愈。 */
        SERVER,

        /** 连接/传输失败（连接被拒、读中断等）——重试可愈。 */
        NETWORK,

        /** 空闲看门狗掐断——流已部分消费,重试由上层决定（seam 不自作主张）。 */
        TIMEOUT,

        /** wire 解析失败（畸形 SSE、未知 finish_reason、请求被拒收等）——重试无意义。 */
        PROTOCOL;

        /** 传输层重试是否有意义（适配器重试判定的唯一依据）。 */
        public boolean retryable() {
            return switch (this) {
                case RATE_LIMIT, SERVER, NETWORK -> true;
                case AUTH, TIMEOUT, PROTOCOL -> false;
            };
        }
    }

    private final Kind kind;

    /** @param kind 失败分类 @param message 人读消息（保留厂商细节） */
    public LlmCallException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** @param cause 底层原因（IO/解析异常等） */
    public LlmCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** @return 失败分类 */
    public Kind kind() {
        return kind;
    }
}
