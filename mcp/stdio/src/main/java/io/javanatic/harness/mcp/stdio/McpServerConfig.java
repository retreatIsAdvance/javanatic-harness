package io.javanatic.harness.mcp.stdio;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * mcp-stdio 行配置的一个 server 条目（07）：id / command / args / env 白名单 /
 * timeoutSeconds。默认值在插件侧 resolve（08 §7）：timeout 缺省
 * {@link #DEFAULT_TIMEOUT_SECONDS}，args/env 缺省空。全部校验 fail loud 带定位
 * （插件 id + 条目序）——含 T2 歧义防护：server id 拒含 "__"（kebab-case 同规），
 * 消掉 {@code mcp__<server>__<raw>} 拼接的二义。
 *
 * @param id server id（kebab-case；拒含 "__"）
 * @param command 启动命令（用户配置的受信命令，以用户全权运行——不入沙箱）
 * @param args 命令参数（逐元素透传，不过 shell 解释）
 * @param env 追加环境变量（白名单叠加在缺省最小环境 PATH/HOME/TMPDIR 之上；不继承全量）
 * @param timeout 该 server 全部请求（initialize / tools/list / tools/call）的超时
 */
record McpServerConfig(String id, String command, List<String> args, Map<String, String> env,
                       Duration timeout) {

    /** 文档化默认（dsh 同口径 60s）。 */
    static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private static final Set<String> KEYS = Set.of("id", "command", "args", "env", "timeoutSeconds");
    private static final Pattern KEBAB = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    /** @throws NullPointerException 任一分量为 null 时 */
    McpServerConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(command, "command");
        args = List.copyOf(args);
        env = Map.copyOf(env);
        Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * 解析行配置的 servers[]（缺省空列表——零 server 即零噪声：不拉进程、不落事件、不注册工具）。
     *
     * @param config 插件行配置
     * @param pluginId 插件 id（错误信息定位用）
     * @return 按配置序的 server 条目
     * @throws IllegalStateException 形状/缺键/坏值/重名 server id 时
     */
    static List<McpServerConfig> parseAll(Map<String, Object> config, String pluginId) {
        Object raw = config.get("servers");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(pluginId + ": 'servers' must be a list, got: "
                + raw.getClass().getSimpleName());
        }
        List<McpServerConfig> parsed = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String where = pluginId + ".servers[" + index + "]";
            McpServerConfig server = parseOne(entryMap(list.get(index), where), where);
            if (!ids.add(server.id())) {
                throw new IllegalStateException(pluginId + ": duplicate server id '" + server.id() + "'");
            }
            parsed.add(server);
        }
        return List.copyOf(parsed);
    }

    private static McpServerConfig parseOne(Map<String, Object> entry, String where) {
        for (String key : entry.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(where + ": unknown key '" + key + "' (allowed: " + KEYS + ")");
            }
        }
        String id = requiredString(entry, "id", where);
        validateId(id, where);
        String command = requiredString(entry, "command", where);
        List<String> args = stringList(entry.get("args"), where + ".args");
        Map<String, String> env = stringMap(entry.get("env"), where + ".env");
        long timeoutSeconds = timeoutSeconds(entry.get("timeoutSeconds"), where);
        return new McpServerConfig(id, command, args, env, Duration.ofSeconds(timeoutSeconds));
    }

    /** T2 歧义防护 + kebab-case：两条规则分开报错，各自可行动。 */
    private static void validateId(String id, String where) {
        if (id.contains("__")) {
            throw new IllegalStateException(where + ".id: '" + id + "' must not contain '__' (tool names"
                + " are built as mcp__<server>__<raw> — '__' would make the name ambiguous)");
        }
        if (!KEBAB.matcher(id).matches()) {
            throw new IllegalStateException(where + ".id: '" + id + "' must be kebab-case ([a-z][a-z0-9]*(-[a-z0-9]+)*)");
        }
    }

    private static Map<String, Object> entryMap(Object item, String where) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": server entry must be a map, got: "
                + (item == null ? "null" : item.getClass().getSimpleName()));
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            typed.put(String.valueOf(e.getKey()), e.getValue());
        }
        return typed;
    }

    private static String requiredString(Map<String, Object> entry, String key, String where) {
        Object value = entry.get(key);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalStateException(where + ": missing required '" + key + "'");
        }
        return text;
    }

    private static List<String> stringList(Object raw, String where) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(where + ": must be a list, got: " + raw.getClass().getSimpleName());
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            values.add(scalar(item, where));
        }
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(Object raw, String where) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": must be a map, got: " + raw.getClass().getSimpleName());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            values.put(String.valueOf(e.getKey()), scalar(e.getValue(), where));
        }
        return Map.copyOf(values);
    }

    private static String scalar(Object item, String where) {
        if (item instanceof String text) {
            return text;
        }
        if (item instanceof Number || item instanceof Boolean) {
            return item.toString();
        }
        throw new IllegalStateException(where + ": entries must be scalar, got: "
            + (item == null ? "null" : item.getClass().getSimpleName()));
    }

    private static long timeoutSeconds(Object raw, String where) {
        if (raw == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        long seconds;
        if (raw instanceof Number number) {
            seconds = number.longValue();
        } else if (raw instanceof String text) {
            try {
                seconds = Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(where + ".timeoutSeconds: not numeric: " + text, e);
            }
        } else {
            throw new IllegalStateException(where + ".timeoutSeconds: must be numeric, got: "
                + raw.getClass().getSimpleName());
        }
        if (seconds <= 0) {
            throw new IllegalStateException(where + ".timeoutSeconds: must be positive, got: " + seconds);
        }
        return seconds;
    }
}
