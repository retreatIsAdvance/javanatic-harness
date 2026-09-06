package io.javanatic.harness.llm.deepseek;

import java.time.Duration;
import java.util.Objects;

/**
 * DeepSeek Provider 选项（组合期显式选择；未来 ConfigService 只是换来源——
 * 预留 {@code from(config)} 工厂位）。apiKey 在 {@link #toString()} 中遮蔽。
 *
 * @param baseUrl        API 根（默认 https://api.deepseek.com）
 * @param apiKey         Bearer 凭据（环境变量注入，永不入日志）
 * @param connectTimeout TCP 连接超时
 * @param idleTimeout    流式空闲看门狗：两个分块之间超过该时长即掐断（防挂死连接）
 * @param maxAttempts    传输重试总尝试次数（含首次；429/5xx/连接失败适用）
 * @param backoffBase    退避基值（指数增长，附抖动）
 * @param backoffMax     退避上限
 */
public record DeepSeekOptions(String baseUrl, String apiKey, Duration connectTimeout,
                              Duration idleTimeout, int maxAttempts,
                              Duration backoffBase, Duration backoffMax) {

    /** 默认 API 根。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** @throws NullPointerException/IllegalArgumentException 任一字段非法时 */
    public DeepSeekOptions {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must be non-empty");
        }
        baseUrl = baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl;
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

    /** 凭据脱敏：apiKey 永不出现在日志/断言输出。 */
    @Override
    public String toString() {
        return "DeepSeekOptions[baseUrl=" + baseUrl + ", apiKey=****, connectTimeout=" + connectTimeout
            + ", idleTimeout=" + idleTimeout + ", maxAttempts=" + maxAttempts
            + ", backoffBase=" + backoffBase + ", backoffMax=" + backoffMax + "]";
    }
}
