package io.javanatic.harness.web.fetch.http;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;

/**
 * URL 策略（T2/T3）：https-only、无 userinfo、长度 ≤ {@value #MAX_URL_LENGTH}、
 * 主机 IDN→punycode + 小写 + 去尾点、IPv6 只接受方括号形式、缺省端口 443、
 * fragment 不入请求。规范化后产出 {@link FetchTarget}；重定向逐跳重跑本策略，
 * 相对 {@code Location} 以当前规范化 URL 为基准 resolve。
 *
 * <p>主机分两支：数字/点形式一律经 {@link InetAddress#ofLiteral(String)} 校验并
 * 规范化为字面 IP（非规范写法如 {@code 127.1} 也在此收口，闸门随后判定）；
 * 其余走 IDN。任何不合规都是 {@code WEB_INVALID_URL}（首跳）或
 * {@code WEB_REDIRECT_BLOCKED}（后续跳）。
 */
final class FetchUrlPolicy {

    /** URL 长度上限（安全常量，不可配置）。 */
    static final int MAX_URL_LENGTH = 2048;

    private static final int DEFAULT_PORT = 443;

    private FetchUrlPolicy() {
    }

    /**
     * 解析首跳 URL。
     *
     * @param rawUrl 调用方给出的 URL
     * @return 规范化目标
     * @throws WebException {@code WEB_INVALID_URL}
     */
    static FetchTarget parse(String rawUrl) {
        return parse(rawUrl, WebErrorCode.WEB_INVALID_URL);
    }

    /**
     * 解析一跳重定向：相对 Location 以当前 URL 为基准。
     *
     * @param current 当前跳目标
     * @param location 响应 Location（可相对）
     * @return 规范化后的下一跳
     * @throws WebException {@code WEB_REDIRECT_BLOCKED}（坏/缺 Location、降级、超长等）
     */
    static FetchTarget resolveRedirect(FetchTarget current, String location) {
        if (location == null || location.isBlank()) {
            throw new WebException(WebErrorCode.WEB_REDIRECT_BLOCKED,
                "redirect response has no usable Location header");
        }
        URI resolved;
        try {
            resolved = current.base().resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw new WebException(WebErrorCode.WEB_REDIRECT_BLOCKED,
                "invalid redirect Location: " + e.getMessage(), e);
        }
        return parse(resolved.toString(), WebErrorCode.WEB_REDIRECT_BLOCKED);
    }

    private static FetchTarget parse(String rawUrl, WebErrorCode code) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            throw fail(code, "url must be non-empty");
        }
        String text = rawUrl.strip();
        if (text.length() > MAX_URL_LENGTH) {
            throw fail(code, "url exceeds " + MAX_URL_LENGTH + " characters");
        }
        if (text.indexOf('\\') >= 0) {
            throw fail(code, "backslash is not allowed in url");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            String reason = e.getReason() == null ? "malformed" : e.getReason();
            if (reason.contains("escape pair")) {
                throw fail(code, "invalid percent-encoding in url");
            }
            throw fail(code, "invalid url: " + reason);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw fail(code, "url must use https (got: " + (scheme == null ? "none" : scheme) + ")");
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw fail(code, "url must be absolute and include a host");
        }
        if (authority.indexOf('@') >= 0) {
            throw fail(code, "userinfo is not allowed in url (credentials would leak into logs)");
        }
        HostPort parsed = parseAuthority(authority, code);
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            rawPath = "/";
        }
        if (!rawPath.startsWith("/")) {
            throw fail(code, "url path must start with '/'");
        }
        String requestTarget = encode(rawPath, false, code);
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            requestTarget = requestTarget + "?" + encode(rawQuery, true, code);
        }
        String hostHeader = parsed.ipLiteral() && parsed.host().indexOf(':') >= 0
            ? "[" + parsed.host() + "]" : parsed.host();
        if (parsed.port() != DEFAULT_PORT) {
            hostHeader = hostHeader + ":" + parsed.port();
        }
        String baseText = "https://" + hostHeader + requestTarget;
        if (baseText.length() > MAX_URL_LENGTH) {
            throw fail(code, "normalized url exceeds " + MAX_URL_LENGTH + " characters");
        }
        return new FetchTarget(URI.create(baseText), parsed.host(), parsed.port(), hostHeader,
            requestTarget, parsed.ipLiteral());
    }

    private static HostPort parseAuthority(String authority, WebErrorCode code) {
        String hostPart;
        String portPart = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw fail(code, "unterminated IPv6 literal in url");
            }
            hostPart = authority.substring(1, close);
            String rest = authority.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':') {
                    throw fail(code, "unexpected characters after IPv6 literal: " + rest);
                }
                portPart = rest.substring(1);
            }
            if (hostPart.isEmpty() || hostPart.indexOf('%') >= 0) {
                throw fail(code, "invalid IPv6 literal in url");
            }
            InetAddress address = literal(hostPart);
            if (address == null) {
                throw fail(code, "invalid IPv6 literal: " + hostPart);
            }
            String normalized = address.getHostAddress().toLowerCase(Locale.ROOT);
            int port = portPart == null ? DEFAULT_PORT : parsePort(portPart, code);
            return new HostPort(normalized, port, true);
        }
        int firstColon = authority.indexOf(':');
        int lastColon = authority.lastIndexOf(':');
        if (firstColon != lastColon) {
            throw fail(code, "IPv6 literals must be bracketed in url");
        }
        if (lastColon >= 0) {
            hostPart = authority.substring(0, lastColon);
            portPart = authority.substring(lastColon + 1);
        } else {
            hostPart = authority;
        }
        if (hostPart.isEmpty()) {
            throw fail(code, "url is missing a host");
        }
        if (hostPart.indexOf('%') >= 0) {
            throw fail(code, "percent-encoding is not allowed in url host");
        }
        int port = portPart == null ? DEFAULT_PORT : parsePort(portPart, code);
        if (isNumeric(hostPart)) {
            InetAddress address = literal(hostPart);
            if (address == null) {
                throw fail(code, "not a valid IPv4 literal: " + hostPart);
            }
            return new HostPort(address.getHostAddress(), port, true);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(hostPart, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw fail(code, "invalid internationalized host name: " + e.getMessage());
        }
        if (ascii.endsWith(".")) {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        if (ascii.isEmpty() || ascii.endsWith(".") || ascii.indexOf(':') >= 0) {
            throw fail(code, "invalid host name");
        }
        return new HostPort(ascii, port, false);
    }

    private static int parsePort(String text, WebErrorCode code) {
        if (text.isEmpty() || text.length() > 5) {
            throw fail(code, "invalid port: '" + text + "'");
        }
        int port = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw fail(code, "invalid port: '" + text + "'");
            }
            port = port * 10 + (c - '0');
        }
        if (port < 1 || port > 65535) {
            throw fail(code, "port out of range: " + port);
        }
        return port;
    }

    private static boolean isNumeric(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress literal(String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 请求行目标编码：保留既有合法百分号转义；控制字符/空格拒绝；非 ASCII 按
     * UTF-8 百分号编码；RFC 3986 保留分隔符之外的 ASCII 也编码（不猜测服务器宽容度）。
     */
    private static String encode(String raw, boolean query, WebErrorCode code) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length() || !isHex(raw.charAt(i + 1)) || !isHex(raw.charAt(i + 2))) {
                    throw fail(code, "invalid percent-encoding in url");
                }
                out.append('%').append(Character.toUpperCase(raw.charAt(i + 1)))
                    .append(Character.toUpperCase(raw.charAt(i + 2)));
                i += 2;
            } else if (c <= 0x20 || c == 0x7F) {
                throw fail(code, "control character or space is not allowed in url");
            } else if (c < 0x7F) {
                if (allowedAscii(c, query)) {
                    out.append(c);
                } else {
                    appendEscaped(out, c);
                }
            } else {
                int codePoint = raw.codePointAt(i);
                String text = new String(Character.toChars(codePoint));
                for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
                    appendEscaped(out, b & 0xFF);
                }
                i += Character.charCount(codePoint) - 1;
            }
        }
        return out.toString();
    }

    private static boolean allowedAscii(char c, boolean query) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return true;
        }
        switch (c) {
            case '-':
            case '.':
            case '_':
            case '~':
            case '!':
            case '$':
            case '&':
            case '\'':
            case '(':
            case ')':
            case '*':
            case '+':
            case ',':
            case ';':
            case '=':
            case ':':
            case '@':
            case '/':
                return true;
            case '?':
                return query;
            default:
                return false;
        }
    }

    private static void appendEscaped(StringBuilder out, int value) {
        out.append('%');
        out.append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xF, 16)));
        out.append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
    }

    private static boolean isHex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static WebException fail(WebErrorCode code, String message) {
        return new WebException(code, message);
    }

    /** 权威段解析结果（host 为 ASCII/punycode 或规范 IP 字面量，IPv6 无括号）。 */
    private record HostPort(String host, int port, boolean ipLiteral) {
    }
}
