package io.javanatic.harness.kernel.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * config Map 的取值器（插件侧 resolve 用的唯一入口，08 §7）：必填缺失、类型
 * 不符 fail loud 并带插件 id 与 key 定位；可选键带插件侧文档化默认值。
 * 数值宽容 Number/String（YAML 解析两态），非法数字串 fail loud。
 * <p>另收口敏感值的唯一脱敏规则：人读 dump 与会话 header 的 manifest 写入
 * 共用 {@link #masked(Map)}——凭据不得因序列化路径不同而漏盘。
 */
public final class ConfigValues {

    /** 敏感键子串（不区分大小写）；子串匹配宁多掩，不漏掩。 */
    private static final List<String> SENSITIVE_MARKERS =
        List.of("apikey", "token", "secret", "password", "key");

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

    /**
     * 敏感配置脱敏（dump 与会话 header 写入共用）：任意深度递归，键名（不区分
     * 大小写）含 {@code apikey|token|secret|password|key} 即把值替换为
     * {@code ****}，键保留、键序保留；列表按元素递归。非字符串 map 键 fail loud
     * （与 manifest 序列化的 JSON 对象键约束同源）。R1 无损：fold 只需键名，
     * 不需要环境变量值；header 在写入期脱敏，不构成派生失真。
     *
     * @param config 配置树（不可为 null）
     * @return 脱敏后的副本（不可变；敏感键的值恒为 {@code ****}）
     * @throws IllegalStateException 嵌套 map 出现非字符串键时
     */
    public static Map<String, Object> masked(Map<String, Object> config) {
        return maskMap(Objects.requireNonNull(config, "config"));
    }

    private static Map<String, Object> maskMap(Map<?, ?> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : config.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("config map has non-string key: " + entry.getKey());
            }
            out.put(key, sensitive(key) ? "****" : maskValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object maskValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return maskMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(maskValue(item));
            }
            return Collections.unmodifiableList(items);
        }
        return value;
    }

    /** 敏感键判定：子串匹配（dsh 同口径），不按词边界——宁多掩，不漏掩。 */
    private static boolean sensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
