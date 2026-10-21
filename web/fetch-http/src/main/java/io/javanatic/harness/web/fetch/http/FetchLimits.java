package io.javanatic.harness.web.fetch.http;

import java.util.Map;
import java.util.Set;

/**
 * 抓取传输上限（插件配置解析后的不可变事实）。
 *
 * @param timeoutSeconds 总截止时间（秒）
 * @param maxBodyBytes 响应正文上限（字节；声明超限即错，流式超限截断）
 */
record FetchLimits(int timeoutSeconds, int maxBodyBytes) {

    /** 缺省总截止时间（秒）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 缺省正文上限（2 MiB）。 */
    static final int DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** 配置面允许的最大正文上限（64 MiB，防内存失控）。 */
    static final int MAX_BODY_BYTES_LIMIT = 64 * 1024 * 1024;

    /** 配置面允许的最大截止时间（秒）。 */
    static final int MAX_TIMEOUT_SECONDS = 600;

    private static final Set<String> KEYS = Set.of("timeoutSeconds", "maxBodyBytes");

    FetchLimits {
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeoutSeconds out of range: " + timeoutSeconds);
        }
        if (maxBodyBytes <= 0 || maxBodyBytes > MAX_BODY_BYTES_LIMIT) {
            throw new IllegalArgumentException("maxBodyBytes out of range: " + maxBodyBytes);
        }
    }

    /**
     * 解析插件行配置（键白名单；未知键/坏值 fail loud 带定位）。
     *
     * @param config 插件行配置（可为空 Map）
     * @param pluginId 插件 id（错误信息定位用）
     * @return 解析后的上限
     * @throws IllegalStateException 未知键或坏值时
     */
    static FetchLimits parse(Map<String, Object> config, String pluginId) {
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(pluginId + ": unknown key '" + key
                    + "' (allowed: " + KEYS + ")");
            }
        }
        int timeoutSeconds = intValue(config.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS,
            pluginId + ".timeoutSeconds");
        int maxBodyBytes = intValue(config.get("maxBodyBytes"), DEFAULT_MAX_BODY_BYTES,
            pluginId + ".maxBodyBytes");
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalStateException(pluginId + ".timeoutSeconds: must be within 1.."
                + MAX_TIMEOUT_SECONDS + ", got: " + timeoutSeconds);
        }
        if (maxBodyBytes <= 0 || maxBodyBytes > MAX_BODY_BYTES_LIMIT) {
            throw new IllegalStateException(pluginId + ".maxBodyBytes: must be within 1.."
                + MAX_BODY_BYTES_LIMIT + ", got: " + maxBodyBytes);
        }
        return new FetchLimits(timeoutSeconds, maxBodyBytes);
    }

    private static int intValue(Object raw, int fallback, String where) {
        if (raw == null) {
            return fallback;
        }
        long value;
        if (raw instanceof Number number) {
            value = number.longValue();
        } else if (raw instanceof String text) {
            try {
                value = Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(where + ": not numeric: " + text, e);
            }
        } else {
            throw new IllegalStateException(where + ": must be numeric, got: "
                + raw.getClass().getSimpleName());
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalStateException(where + ": out of range: " + value);
        }
        return (int) value;
    }
}
