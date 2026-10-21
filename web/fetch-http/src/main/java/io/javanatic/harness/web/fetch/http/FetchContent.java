package io.javanatic.harness.web.fetch.http;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebFetchBody;

/**
 * 内容策略：白名单（{@code text/html}、{@code application/xhtml+xml} → Html；
 * 其余 {@code text/*}、JSON/XML 与 {@code +json}/{@code +xml} → Text）、缺头即
 * 不支持、charset 缺省 UTF-8、非法字节替换 U+FFFD（内容事实，不猜测编码）。
 *
 * <p>T2 条件 ②：只接受 {@code Content-Encoding: identity}（或缺失）。服务器无视
 * {@code Accept-Encoding: identity} 返回 gzip/br/deflate → 显式
 * {@code WEB_UNSUPPORTED_CONTENT_TYPE}，不解码、不猜测、不重试。
 */
final class FetchContent {

    private FetchContent() {
    }

    /**
     * 按头分类并解码正文。
     *
     * @param head 响应头
     * @param bytes 正文（已受上限约束）
     * @return 分类后的文本正文
     * @throws WebException {@code WEB_UNSUPPORTED_CONTENT_TYPE}
     */
    static WebFetchBody decode(FetchProtocol.Head head, byte[] bytes) {
        requireIdentityEncoding(head);
        String contentType = head.first("content-type");
        if (contentType == null || contentType.isBlank()) {
            throw unsupported(head, "missing Content-Type");
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
            .trim().toLowerCase(Locale.ROOT);
        Map<String, String> parameters = semicolon < 0
            ? Map.of() : parseParameters(contentType.substring(semicolon + 1));
        boolean html = mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml");
        boolean text = html || mediaType.startsWith("text/")
            || mediaType.equals("application/json") || mediaType.equals("application/xml")
            || mediaType.endsWith("+json") || mediaType.endsWith("+xml");
        if (!text) {
            throw unsupported(head, "media type '" + mediaType + "' is outside the text whitelist");
        }
        Charset charset = charset(parameters.get("charset"), head);
        String content = decodeBytes(bytes, charset, head);
        return html ? new WebFetchBody.Html(content) : new WebFetchBody.Text(content);
    }

    private static void requireIdentityEncoding(FetchProtocol.Head head) {
        List<String> values = head.all("content-encoding");
        for (String value : values) {
            for (String token : value.split(",", -1)) {
                String encoding = token.trim();
                if (encoding.isEmpty()) {
                    continue;
                }
                if (!encoding.equalsIgnoreCase("identity")) {
                    throw unsupported(head, "Content-Encoding '" + encoding
                        + "' is not supported (identity only; no decoding attempted)");
                }
            }
        }
    }

    private static Charset charset(String label, FetchProtocol.Head head) {
        if (label == null || label.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        Charset charset;
        try {
            charset = Charset.forName(label.trim());
        } catch (IllegalArgumentException e) {
            throw unsupported(head, "unsupported charset label '" + label.trim() + "'");
        }
        String name = charset.name();
        if (name.startsWith("UTF-16") || name.startsWith("UTF-32")) {
            throw unsupported(head, "charset '" + name + "' is not supported");
        }
        return charset;
    }

    private static String decodeBytes(byte[] bytes, Charset charset, FetchProtocol.Head head) {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            String text = decoded.toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new WebException(WebErrorCode.WEB_UNSUPPORTED_CONTENT_TYPE,
                "cannot decode body as " + charset.name() + " (status " + head.statusCode() + ")", e);
        }
    }

    private static Map<String, String> parseParameters(String text) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : splitParameters(text)) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            parameters.putIfAbsent(name, value);
        }
        return parameters;
    }

    /** 按 ';' 切分，引号内的 ';' 不切（charset 值理论上可带引号与分号）。 */
    private static List<String> splitParameters(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static WebException unsupported(FetchProtocol.Head head, String reason) {
        return new WebException(WebErrorCode.WEB_UNSUPPORTED_CONTENT_TYPE,
            reason + " (status " + head.statusCode() + ")");
    }
}
