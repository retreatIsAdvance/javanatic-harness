package io.javanatic.harness.kernel.config;

import java.util.Map;
import java.util.Objects;

/**
 * config Map 的取值器（插件侧 resolve 用的唯一入口，08 §7）：必填缺失、类型
 * 不符 fail loud 并带插件 id 与 key 定位；可选键带插件侧文档化默认值。
 * 数值宽容 Number/String（YAML 解析两态），非法数字串 fail loud。
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    /**
     * @param config   插件配置
     * @param pluginId 插件 id（错误信息定位用）
     * @param key      键
     * @return 必填字符串值
     * @throws IllegalStateException 缺失或为空时
     */
    public static String requireString(Map<String, Object> config, String pluginId, String key) {
        String value = stringValue(config, pluginId, key, null);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("config missing required '" + key + "' for plugin '" + pluginId + "'");
        }
        return value;
    }

    /**
     * @param config   插件配置
     * @param pluginId 插件 id（错误信息定位用）
     * @param key      键
     * @return 必填布尔值
     * @throws IllegalStateException 缺失或值非布尔/非 true|false 串时
     */
    public static boolean requireBool(Map<String, Object> config, String pluginId, String key) {
        if (!Objects.requireNonNull(config, "config").containsKey(key)) {
            throw new IllegalStateException("config missing required '" + key + "' for plugin '" + pluginId + "'");
        }
        return boolValue(config, pluginId, key, false);
    }

    /**
     * @return 可选字符串值；缺失返回 fallback
     * @throws IllegalStateException 值非字符串/非数值时
     */
    public static String stringValue(Map<String, Object> config, String pluginId, String key,
                                     String fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
            + "' must be a string, got: " + value.getClass().getSimpleName());
    }

    /**
     * @return 可选整数值；缺失返回 fallback
     * @throws IllegalStateException 值非数值或非法数字串时
     */
    public static long longValue(Map<String, Object> config, String pluginId, String key,
                                 long fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
                    + "' is not numeric: " + text, e);
            }
        }
        throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
            + "' must be numeric, got: " + value.getClass().getSimpleName());
    }

    /**
     * @return 可选浮点值；缺失返回 fallback
     * @throws IllegalStateException 值非数值或非法浮点串时
     */
    public static double doubleValue(Map<String, Object> config, String pluginId, String key,
                                    double fallback) {
        Object value = Objects.requireNonNull(config, "config").get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
                    + "' is not a number: " + text, e);
            }
        }
        throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
            + "' must be numeric, got: " + value.getClass().getSimpleName());
    }

    /**
     * @return 布尔值；缺失返回 fallback
     * @throws IllegalStateException 值非布尔/非 true|false 串时
     */
    public static boolean boolValue(Map<String, Object> config, String pluginId, String key,
                                    boolean fallback) {
        Object value = Objects.requireNonNull(config, "config").get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && ("true".equals(text) || "false".equals(text))) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
            + "' must be boolean, got: " + value);
    }
}
