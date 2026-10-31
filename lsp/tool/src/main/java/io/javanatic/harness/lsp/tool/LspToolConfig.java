package io.javanatic.harness.lsp.tool;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.javanatic.harness.kernel.config.ConfigValues;

/**
 * lsp-tool 行配置（边界 B28 输入面）：键白名单，未知键 / 坏值 fail loud。
 * 预算缺省有界：timeoutSeconds 30 / waitSeconds 15 / 条数帽 50 / 输出字节帽 256 KiB；
 * waitSeconds ≤ timeoutSeconds（LspQuery 构造契约的配置面预校验）。
 */
record LspToolConfig(int timeoutSeconds, int waitSeconds, int maxDiagnostics,
                     int maxLocations, int maxOutputBytes) {

    private static final Set<String> KEYS = Set.of(
        "timeoutSeconds", "waitSeconds", "maxDiagnostics", "maxLocations", "maxOutputBytes");

    static LspToolConfig parse(Map<String, Object> config, String pluginId) {
        Objects.requireNonNull(config, "config");
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(pluginId + ": unknown key '" + key
                    + "' (allowed: " + KEYS + ")");
            }
        }
        int timeoutSeconds = positive(config, pluginId, "timeoutSeconds", 30);
        int waitSeconds = positive(config, pluginId, "waitSeconds", 15);
        if (waitSeconds > timeoutSeconds) {
            throw new IllegalStateException("config 'waitSeconds' for plugin '" + pluginId
                + "' must not exceed timeoutSeconds (" + waitSeconds + " > " + timeoutSeconds + ")");
        }
        int maxDiagnostics = positive(config, pluginId, "maxDiagnostics", 50);
        int maxLocations = positive(config, pluginId, "maxLocations", 50);
        int maxOutputBytes = positive(config, pluginId, "maxOutputBytes", 262144);
        return new LspToolConfig(timeoutSeconds, waitSeconds, maxDiagnostics,
            maxLocations, maxOutputBytes);
    }

    static LspToolConfig defaults() {
        return parse(Map.of(), "lsp-tool");
    }

    /** 读正整数预算：缺失用缺省；非数值由 ConfigValues fail loud；非正 fail loud。 */
    private static int positive(Map<String, Object> config, String pluginId, String key,
                                int fallback) {
        long value = ConfigValues.longValue(config, pluginId, key, fallback);
        if (value < 1) {
            throw new IllegalStateException("config '" + key + "' for plugin '" + pluginId
                + "' must be positive, got: " + value);
        }
        return (int) value;
    }
}
