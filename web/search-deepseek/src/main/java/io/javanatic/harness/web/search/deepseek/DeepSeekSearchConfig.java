package io.javanatic.harness.web.search.deepseek;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import io.javanatic.harness.kernel.config.ConfigValues;

/**
 * web-search-deepseek 行配置（07）：baseUrl / model / apiKey / apiKeyEnv /
 * maxTokens / maxUses / timeoutSeconds。键白名单，未知键与坏值 fail loud。
 *
 * <p>凭据来源按序：字面 {@code apiKey} 优先，否则读 {@code apiKeyEnv}
 * （缺省 {@value #DEFAULT_API_KEY_ENV}）。只存字面值；解析后的 key 由 provider
 * 持有，本记录的 {@link #toString()} 恒遮蔽字面 key。
 *
 * @param baseUrl Messages API 基址（https，无 userinfo/query/fragment；末端斜杠已归一）
 * @param model Anthropic 格式模型名
 * @param apiKeyEnv 环境变量名（字面 key 缺席时使用）
 * @param apiKey 字面 key（可为 null；设置时必须非空）
 * @param maxTokens 单次生成上限
 * @param maxUses 单次请求允许的原生搜索次数
 * @param timeoutSeconds 单次请求截止时间（秒）
 */
record DeepSeekSearchConfig(String baseUrl, String model, String apiKeyEnv, String apiKey,
                            int maxTokens, int maxUses, int timeoutSeconds) {

    /** 缺省 Messages API 基址（含 {@code /v1}；{@code /messages} 由 {@link #endpoint()} 追加）。 */
    static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic/v1";

    /** 缺省 Anthropic 格式模型名。 */
    static final String DEFAULT_MODEL = "deepseek-v4-flash";

    /** 缺省凭据环境变量。 */
    static final String DEFAULT_API_KEY_ENV = "DEEPSEEK_API_KEY";

    /** 缺省生成上限。 */
    static final int DEFAULT_MAX_TOKENS = 4096;

    /** 缺省单次请求搜索次数上限。 */
    static final int DEFAULT_MAX_USES = 5;

    /** 缺省请求截止时间（秒）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 生成上限防护值（防误配把单次调用放大到不可控成本）。 */
    static final int MAX_MAX_TOKENS = 100_000;

    /** 搜索次数防护值。 */
    static final int MAX_MAX_USES = 100;

    /** 截止时间防护值（秒）。 */
    static final int MAX_TIMEOUT_SECONDS = 600;

    private static final Set<String> KEYS = Set.of(
        "baseUrl", "model", "apiKey", "apiKeyEnv", "maxTokens", "maxUses", "timeoutSeconds");

    /** @throws NullPointerException 任一分量为 null 时 */
    DeepSeekSearchConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(apiKeyEnv, "apiKeyEnv");
    }

    /**
     * 解析插件行配置（键白名单；未知键/坏值 fail loud 带定位）。
     *
     * @param config 插件行配置（可为空 Map）
     * @param pluginId 插件 id（错误信息定位用）
     * @return 解析后的配置
     * @throws IllegalStateException 未知键或坏值时
     */
    static DeepSeekSearchConfig parse(Map<String, Object> config, String pluginId) {
        Objects.requireNonNull(config, "config");
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(pluginId + ": unknown key '" + key
                    + "' (allowed: " + KEYS + ")");
            }
        }
        String baseUrl = normalizeBaseUrl(
            ConfigValues.stringValue(config, pluginId, "baseUrl", DEFAULT_BASE_URL),
            pluginId + ".baseUrl");
        String model = ConfigValues.stringValue(config, pluginId, "model", DEFAULT_MODEL);
        if (model.isBlank()) {
            throw new IllegalStateException(pluginId + ".model: must not be blank");
        }
        String apiKeyEnv = ConfigValues.stringValue(config, pluginId, "apiKeyEnv", DEFAULT_API_KEY_ENV);
        if (apiKeyEnv.isBlank()) {
            throw new IllegalStateException(pluginId + ".apiKeyEnv: must not be blank");
        }
        String apiKey = ConfigValues.stringValue(config, pluginId, "apiKey", null);
        if (apiKey != null && apiKey.isEmpty()) {
            throw new IllegalStateException(pluginId + ".apiKey: must not be empty when set");
        }
        int maxTokens = intValue(config.get("maxTokens"), DEFAULT_MAX_TOKENS,
            pluginId + ".maxTokens", MAX_MAX_TOKENS);
        int maxUses = intValue(config.get("maxUses"), DEFAULT_MAX_USES,
            pluginId + ".maxUses", MAX_MAX_USES);
        int timeoutSeconds = intValue(config.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS,
            pluginId + ".timeoutSeconds", MAX_TIMEOUT_SECONDS);
        return new DeepSeekSearchConfig(baseUrl, model, apiKeyEnv, apiKey,
            maxTokens, maxUses, timeoutSeconds);
    }

    /**
     * @return Messages 端点（基址 + {@code /messages}）
     */
    URI endpoint() {
        return URI.create(baseUrl + "/messages");
    }

    /**
     * 解析一次搜索使用的 key；字面值优先于环境变量。
     *
     * @param environment 环境变量读取器（生产为 {@code System::getenv}；测试可注入）
     * @return key；两处都没有时 null
     */
    String resolveApiKey(Function<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return apiKey != null ? apiKey : environment.apply(apiKeyEnv);
    }

    /** 凭据脱敏：字面 key 永不出现在日志/断言输出。 */
    @Override
    public String toString() {
        return "DeepSeekSearchConfig[baseUrl=" + baseUrl + ", model=" + model
            + ", apiKeyEnv=" + apiKeyEnv + ", apiKey=" + (apiKey == null ? "<unset>" : "****")
            + ", maxTokens=" + maxTokens + ", maxUses=" + maxUses
            + ", timeoutSeconds=" + timeoutSeconds + "]";
    }

    private static String normalizeBaseUrl(String text, String where) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(where + ": invalid URI: " + e.getMessage(), e);
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            throw new IllegalStateException(where + ": must be an absolute https URI");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalStateException(where + ": missing host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException(where + ": userinfo is not allowed"
                + " (the API key is sent in headers)");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalStateException(where + ": query is not allowed");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalStateException(where + ": fragment is not allowed");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalStateException(where + ": port out of range: " + uri.getPort());
        }
        String normalized = text;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static int intValue(Object raw, int fallback, String where, int max) {
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
        if (value <= 0 || value > max) {
            throw new IllegalStateException(where + ": must be within 1.." + max
                + ", got: " + value);
        }
        return (int) value;
    }
}
