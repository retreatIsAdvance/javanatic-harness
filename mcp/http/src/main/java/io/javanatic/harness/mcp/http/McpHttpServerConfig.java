package io.javanatic.harness.mcp.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * mcp-http 行配置的一个 server 条目（07）：id / url / headers / timeoutSeconds。
 * 默认值在插件侧 resolve（08 §7）：timeout 缺省 {@link #DEFAULT_TIMEOUT_SECONDS}，
 * headers 缺省空。全部校验 fail loud 带定位（插件 id + 条目序）。
 *
 * <p>URL 只接受绝对 {@code http}/{@code https}，拒绝 userinfo 与 fragment
 * （凭据只走 headers；fragment 不上线，写了就是误配）。headers 拒绝保留头、
 * CR/LF/控制字符、大小写不敏感重名——这些头由 transport 自己产生，用户再写
 * 一份会与协议面打架。敏感 query/头的词表校验在消费面配置硬化批次落地
 * （同一份 {@code ConfigValues} 词表），本类不另起词表。
 *
 * @param id server id（kebab-case；拒含 "__"）
 * @param url Streamable HTTP 端点（绝对 URI，无 userinfo/fragment）
 * @param headers 附加请求头（保留头/控制字符/大小写重复拒绝；按原样发送）
 * @param timeout 该 server 全部请求（initialize / tools/list / tools/call）的超时
 */
record McpHttpServerConfig(String id, URI url, Map<String, String> headers, Duration timeout) {

    /** 文档化默认（与 stdio 同口径 60s）。 */
    static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private static final Set<String> KEYS = Set.of("id", "url", "headers", "timeoutSeconds");
    private static final Pattern KEBAB = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> RESERVED_HEADERS = Set.of(
        "host", "content-length", "content-type", "accept",
        "mcp-session-id", "mcp-protocol-version", "connection", "transfer-encoding");

    /** @throws NullPointerException 任一分量为 null 时 */
    McpHttpServerConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * 解析行配置的 servers[]（缺省空列表——零 server 即零噪声：无网络、无事件、无工具）。
     *
     * @param config 插件行配置
     * @param pluginId 插件 id（错误信息定位用）
     * @return 按配置序的 server 条目
     * @throws IllegalStateException 形状/缺键/坏值/重名 server id 时
     */
    static List<McpHttpServerConfig> parseAll(Map<String, Object> config, String pluginId) {
        Object raw = config.get("servers");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(pluginId + ": 'servers' must be a list, got: "
                + raw.getClass().getSimpleName());
        }
        List<McpHttpServerConfig> parsed = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String where = pluginId + ".servers[" + index + "]";
            McpHttpServerConfig server = parseOne(entryMap(list.get(index), where), where);
            if (!ids.add(server.id())) {
                throw new IllegalStateException(pluginId + ": duplicate server id '" + server.id() + "'");
            }
            parsed.add(server);
        }
        return List.copyOf(parsed);
    }

    private static McpHttpServerConfig parseOne(Map<String, Object> entry, String where) {
        for (String key : entry.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(where + ": unknown key '" + key + "' (allowed: " + KEYS + ")");
            }
        }
        String id = requiredString(entry, "id", where);
        validateId(id, where);
        URI url = parseUrl(requiredString(entry, "url", where), where);
        Map<String, String> headers = parseHeaders(entry.get("headers"), where + ".headers");
        long timeoutSeconds = timeoutSeconds(entry.get("timeoutSeconds"), where);
        return new McpHttpServerConfig(id, url, headers, Duration.ofSeconds(timeoutSeconds));
    }

    /** T2 歧义防护 + kebab-case（与 stdio 同规则；两条分开报错，各自可行动）。 */
    private static void validateId(String id, String where) {
        if (id.contains("__")) {
            throw new IllegalStateException(where + ".id: '" + id + "' must not contain '__' (tool names"
                + " are built as mcp__<server>__<raw> — '__' would make the name ambiguous)");
        }
        if (!KEBAB.matcher(id).matches()) {
            throw new IllegalStateException(where + ".id: '" + id + "' must be kebab-case ([a-z][a-z0-9]*(-[a-z0-9]+)*)");
        }
    }

    private static URI parseUrl(String text, String where) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(where + ".url: invalid URI: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException(where + ".url: must be absolute http/https URI");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalStateException(where + ".url: missing host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException(where + ".url: userinfo is not allowed"
                + " (put credentials in headers)");
        }
        if (uri.getFragment() != null) {
            throw new IllegalStateException(where + ".url: fragment is not allowed");
        }
        return uri;
    }

    private static Map<String, String> parseHeaders(Object raw, String where) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": must be a map, got: " + raw.getClass().getSimpleName());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String key = name.toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalStateException(where + ": header name '" + name
                    + "' is not a valid HTTP token");
            }
            if (RESERVED_HEADERS.contains(key)) {
                throw new IllegalStateException(where + ": header '" + name
                    + "' is reserved by the transport");
            }
            if (values.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                throw new IllegalStateException(where + ": duplicate header '" + name
                    + "' (names are case-insensitive; previously configured differently)");
            }
            Object value = entry.getValue();
            if (!(value instanceof String text)) {
                throw new IllegalStateException(where + ".headers[" + name
                    + "]: value must be a string");
            }
            validateHeaderValue(name, text, where);
            values.put(name, text);
        }
        return values;
    }

    private static void validateHeaderValue(String name, String value, String where) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\r' || ch == '\n' || ch == 0x7f || (ch < 0x20 && ch != '\t')) {
                throw new IllegalStateException(where + ": header '" + name
                    + "' contains a control character (CR/LF/control rejected)");
            }
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
