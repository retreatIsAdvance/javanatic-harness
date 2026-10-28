package io.javanatic.harness.web.tool;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * web-tool 行配置（07）：maxSourceChars / maxOutputBytes / maxResults。键白名单，
 * 未知键与坏值 fail loud。三层上限各司其职：源码字符上限先于提取（防超长文档
 * 吃内存），输出字节上限作用于最终工具结果（UTF-8 完整边界），结果条数上限
 * 透传给搜索 seam。
 *
 * @param maxSourceChars 单次抓取的源码字符上限（HTML 提取或文本截取前）
 * @param maxOutputBytes 工具结果输出上限（字节）
 * @param maxResults 单次搜索返回来源上限
 */
record WebToolConfig(int maxSourceChars, int maxOutputBytes, int maxResults) {

    /** 缺省源码字符上限（512k，与 plan 同口径）。 */
    static final int DEFAULT_MAX_SOURCE_CHARS = 512 * 1024;

    /** 缺省输出上限（256 KiB，与 fs/mcp 同口径）。 */
    static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    /** 缺省来源条数上限。 */
    static final int DEFAULT_MAX_RESULTS = 8;

    /** 源码上限防护值（16M 字符）。 */
    static final int MAX_SOURCE_CHARS_LIMIT = 16 * 1024 * 1024;

    /** 输出上限防护值（64 MiB）。 */
    static final int MAX_OUTPUT_BYTES_LIMIT = 64 * 1024 * 1024;

    /** 来源条数防护值。 */
    static final int MAX_RESULTS_LIMIT = 50;

    private static final Set<String> KEYS = Set.of("maxSourceChars", "maxOutputBytes", "maxResults");

    WebToolConfig {
        requireWithin("maxSourceChars", maxSourceChars, MAX_SOURCE_CHARS_LIMIT);
        requireWithin("maxOutputBytes", maxOutputBytes, MAX_OUTPUT_BYTES_LIMIT);
        requireWithin("maxResults", maxResults, MAX_RESULTS_LIMIT);
    }

    /** 缺省配置（无 ConfigService 的直装组合）。 */
    static WebToolConfig defaults() {
        return new WebToolConfig(DEFAULT_MAX_SOURCE_CHARS, DEFAULT_MAX_OUTPUT_BYTES,
            DEFAULT_MAX_RESULTS);
    }

    /**
     * 解析插件行配置（键白名单；未知键/坏值 fail loud 带定位）。
     *
     * @param config 插件行配置（可为空 Map）
     * @param pluginId 插件 id（错误信息定位用）
     * @return 解析后的配置
     * @throws IllegalStateException 未知键或坏值时
     */
    static WebToolConfig parse(Map<String, Object> config, String pluginId) {
        Objects.requireNonNull(config, "config");
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(pluginId + ": unknown key '" + key
                    + "' (allowed: " + KEYS + ")");
            }
        }
        int maxSourceChars = intValue(config.get("maxSourceChars"), DEFAULT_MAX_SOURCE_CHARS,
            pluginId + ".maxSourceChars", MAX_SOURCE_CHARS_LIMIT);
        int maxOutputBytes = intValue(config.get("maxOutputBytes"), DEFAULT_MAX_OUTPUT_BYTES,
            pluginId + ".maxOutputBytes", MAX_OUTPUT_BYTES_LIMIT);
        int maxResults = intValue(config.get("maxResults"), DEFAULT_MAX_RESULTS,
            pluginId + ".maxResults", MAX_RESULTS_LIMIT);
        return new WebToolConfig(maxSourceChars, maxOutputBytes, maxResults);
    }

    private static void requireWithin(String name, int value, int max) {
        if (value <= 0 || value > max) {
            throw new IllegalArgumentException(name + " must be within 1.." + max
                + ", got: " + value);
        }
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
