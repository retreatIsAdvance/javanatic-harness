package io.javanatic.harness.llm.openai.compat;

import java.time.Duration;
import java.util.Objects;

/**
 * 传输韧性参数(厂商无关;与 {@link VendorProfile} 分离——换厂商只换 profile)。
 * apiKey 在 {@link #toString()} 中遮蔽。
 *
 * @param apiKey         Bearer 凭据(环境变量注入,永不入日志)
 * @param connectTimeout TCP 连接超时(null = 10s)
 * @param idleTimeout    流式空闲看门狗:两个分块之间超过该时长即掐断(null = 120s)
 * @param maxAttempts    传输重试总尝试次数(含首次)
 * @param backoffBase    退避基值(指数增长,附抖动;null = 500ms)
 * @param backoffMax     退避上限(null = 8s)
 */
public record TransportOptions(String apiKey, Duration connectTimeout, Duration idleTimeout,
                               int maxAttempts, Duration backoffBase, Duration backoffMax) {

    /** @throws NullPointerException/IllegalArgumentException 任一字段非法时 */
    public TransportOptions {
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must be non-empty");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        idleTimeout = idleTimeout == null ? Duration.ofSeconds(120) : idleTimeout;
        backoffBase = backoffBase == null ? Duration.ofMillis(500) : backoffBase;
        backoffMax = backoffMax == null ? Duration.ofSeconds(8) : backoffMax;
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive: " + maxAttempts);
        }
        if (backoffBase.isNegative() || backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("backoff bounds invalid: " + backoffBase + ".." + backoffMax);
        }
    }

    /** 凭据脱敏:apiKey 永不出现在日志/断言输出。 */
    @Override
    public String toString() {
        return "TransportOptions[apiKey=****, connectTimeout=" + connectTimeout
            + ", idleTimeout=" + idleTimeout + ", maxAttempts=" + maxAttempts
            + ", backoffBase=" + backoffBase + ", backoffMax=" + backoffMax + "]";
    }
}
