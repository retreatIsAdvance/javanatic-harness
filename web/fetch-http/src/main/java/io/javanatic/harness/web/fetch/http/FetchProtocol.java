package io.javanatic.harness.web.fetch.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;

/**
 * HTTP/1.1 响应解析（T2 条件 ①）：状态行 + 头区有界、拒 obs-fold、CL/TE
 * 互斥与不一致 CL 拒收、TE 仅 {@code chunked}、chunked 十六进制 size（忽略
 * {@code ;} 扩展）、严格 CRLF、逐块读、0 块后读 trailer 至空行、无 body 状态
 * （204/304）不读体。正文上限语义：声明长度超限 → {@code WEB_TOO_LARGE}；
 * 流式超限 → 停收并置 {@code truncated=true}（内容事实，不报错）。
 *
 * <p>协议畸形统一映射 {@code WEB_NETWORK_ERROR}；底层 socket 异常（超时/取消）
 * 原样上抛，由客户端在 {@code checkAbort()} 之后翻译。
 */
final class FetchProtocol {

    /** 单行上限（状态行、头行、chunk size 行、trailer 行共用）。 */
    static final int MAX_LINE_BYTES = 8 * 1024;

    /** 头字段数量上限。 */
    static final int MAX_HEADER_COUNT = 100;

    /** 头区总字节上限。 */
    static final int MAX_HEAD_BYTES = 64 * 1024;

    /** trailer 行数上限。 */
    static final int MAX_TRAILER_COUNT = 100;

    private FetchProtocol() {
    }

    /**
     * 读状态行与头区。
     *
     * @param in 响应流
     * @return 已解析头
     * @throws IOException 底层读失败（由调用方翻译）
     * @throws WebException 协议畸形（{@code WEB_NETWORK_ERROR}）
     */
    static Head readHead(InputStream in) throws IOException {
        String statusLine = readLine(in, MAX_LINE_BYTES, "status line");
        if (statusLine == null) {
            throw protocol("empty response");
        }
        int status = parseStatus(statusLine);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        int total = statusLine.length() + 2;
        while (true) {
            String line = readLine(in, MAX_LINE_BYTES, "header line");
            if (line == null) {
                throw protocol("response head truncated before blank line");
            }
            total += line.length() + 2;
            if (total > MAX_HEAD_BYTES) {
                throw protocol("response head exceeds " + MAX_HEAD_BYTES + " bytes");
            }
            if (line.isEmpty()) {
                break;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw protocol("obsolete line folding is not accepted");
            }
            count++;
            if (count > MAX_HEADER_COUNT) {
                throw protocol("response has more than " + MAX_HEADER_COUNT + " header fields");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw protocol("malformed header line: " + preview(line));
            }
            String name = line.substring(0, colon);
            if (!isToken(name)) {
                throw protocol("invalid header name: " + preview(name));
            }
            String value = trimOws(line.substring(colon + 1));
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 && c != '\t' || c == 0x7F) {
                    throw protocol("control character in header '" + name + "'");
                }
            }
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(value);
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        headers.forEach((name, values) -> frozen.put(name, List.copyOf(values)));
        return new Head(status, Collections.unmodifiableMap(frozen));
    }

    /**
     * 按头语义读正文。
     *
     * @param in 响应流（头区之后）
     * @param head 已解析头
     * @param cap 正文上限（字节）
     * @return 正文与截断事实
     * @throws IOException 底层读失败
     * @throws WebException 协议畸形或声明超限
     */
    static Body readBody(InputStream in, Head head, int cap) throws IOException {
        if (head.statusCode() == 204 || head.statusCode() == 304) {
            return new Body(new byte[0], false);
        }
        List<String> transferEncoding = head.all("transfer-encoding");
        List<String> contentLength = head.all("content-length");
        if (!transferEncoding.isEmpty() && !contentLength.isEmpty()) {
            throw protocol("both Content-Length and Transfer-Encoding present");
        }
        if (!transferEncoding.isEmpty()) {
            if (transferEncoding.size() != 1
                || !transferEncoding.get(0).trim().equalsIgnoreCase("chunked")) {
                throw protocol("unsupported Transfer-Encoding: " + transferEncoding);
            }
            return readChunked(in, cap);
        }
        if (!contentLength.isEmpty()) {
            long length = consistentContentLength(contentLength);
            if (length > cap) {
                throw new WebException(WebErrorCode.WEB_TOO_LARGE,
                    "declared Content-Length " + length + " exceeds limit " + cap);
            }
            return new Body(readExactly(in, (int) length), false);
        }
        return readToEof(in, cap);
    }

    private static int parseStatus(String line) {
        if (!line.startsWith("HTTP/1.0 ") && !line.startsWith("HTTP/1.1 ")) {
            throw protocol("unsupported status line: " + preview(line));
        }
        if (line.length() < 12) {
            throw protocol("malformed status line: " + preview(line));
        }
        for (int i = 9; i < 12; i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                throw protocol("malformed status code: " + preview(line));
            }
        }
        if (line.length() > 12 && line.charAt(12) != ' ') {
            throw protocol("malformed status line: " + preview(line));
        }
        int status = Integer.parseInt(line.substring(9, 12));
        if (status < 200 || status > 599) {
            throw protocol("unsupported response status: " + status);
        }
        return status;
    }

    private static long consistentContentLength(List<String> values) {
        long result = -1;
        for (String value : values) {
            for (String token : value.split(",", -1)) {
                String text = token.trim();
                if (text.isEmpty()) {
                    throw protocol("empty Content-Length");
                }
                long parsed = 0;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c < '0' || c > '9') {
                        throw protocol("non-numeric Content-Length: " + preview(text));
                    }
                    if (parsed > (Long.MAX_VALUE - (c - '0')) / 10) {
                        throw protocol("Content-Length overflow");
                    }
                    parsed = parsed * 10 + (c - '0');
                }
                if (result < 0) {
                    result = parsed;
                } else if (result != parsed) {
                    throw protocol("inconsistent Content-Length values");
                }
            }
        }
        if (result < 0) {
            throw protocol("empty Content-Length");
        }
        return result;
    }

    private static Body readChunked(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
        while (true) {
            String line = readLine(in, MAX_LINE_BYTES, "chunk size line");
            if (line == null) {
                throw protocol("chunked body truncated before size line");
            }
            int semicolon = line.indexOf(';');
            String sizeText = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            long size = parseChunkSize(sizeText);
            if (size == 0) {
                readTrailers(in);
                return new Body(out.toByteArray(), false);
            }
            long remaining = size;
            while (remaining > 0) {
                int room = cap - out.size();
                if (room <= 0) {
                    return new Body(out.toByteArray(), true);
                }
                int want = (int) Math.min(remaining, room);
                byte[] chunk = in.readNBytes(want);
                if (chunk.length < want) {
                    throw protocol("chunked body truncated inside a chunk");
                }
                out.write(chunk, 0, chunk.length);
                remaining -= want;
            }
            expectCrlf(in, "chunk data");
        }
    }

    private static long parseChunkSize(String text) {
        if (text.isEmpty() || text.length() > 16) {
            throw protocol("invalid chunk size: '" + preview(text) + "'");
        }
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.digit(c, 16);
            if (digit < 0) {
                throw protocol("invalid chunk size: '" + preview(text) + "'");
            }
            if (value > (Long.MAX_VALUE - digit) / 16) {
                throw protocol("chunk size overflow");
            }
            value = value * 16 + digit;
        }
        return value;
    }

    private static void readTrailers(InputStream in) throws IOException {
        int count = 0;
        int total = 0;
        while (true) {
            String line = readLine(in, MAX_LINE_BYTES, "trailer line");
            if (line == null) {
                throw protocol("chunked body truncated inside trailers");
            }
            total += line.length() + 2;
            if (total > MAX_HEAD_BYTES) {
                throw protocol("trailers exceed " + MAX_HEAD_BYTES + " bytes");
            }
            if (line.isEmpty()) {
                return;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw protocol("obsolete line folding in trailers");
            }
            count++;
            if (count > MAX_TRAILER_COUNT) {
                throw protocol("too many trailer fields");
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || !isToken(line.substring(0, colon))) {
                throw protocol("malformed trailer line: " + preview(line));
            }
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw protocol("body truncated: expected " + length + " bytes, got " + bytes.length);
        }
        return bytes;
    }

    private static Body readToEof(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
        byte[] buffer = new byte[8192];
        while (out.size() < cap) {
            int want = Math.min(buffer.length, cap - out.size());
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                return new Body(out.toByteArray(), false);
            }
            out.write(buffer, 0, read);
        }
        int extra = in.read();
        return new Body(out.toByteArray(), extra >= 0);
    }

    private static void expectCrlf(InputStream in, String what) throws IOException {
        int first = in.read();
        int second = in.read();
        if (first != '\r' || second != '\n') {
            throw protocol(what + " is not CRLF-terminated");
        }
    }

    /** 读一行；EOF 出现在行首返回 null，行内 EOF/裸 LF/裸 CR/超长均协议错。 */
    private static String readLine(InputStream in, int max, String what) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        while (true) {
            int read = in.read();
            if (read < 0) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw protocol(what + " truncated before CRLF");
            }
            if (read == '\n') {
                byte[] bytes = buffer.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') {
                    throw protocol(what + " is not CRLF-terminated");
                }
                for (int i = 0; i < bytes.length - 1; i++) {
                    if (bytes[i] == '\r') {
                        throw protocol(what + " contains a bare CR");
                    }
                }
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            buffer.write(read);
            if (buffer.size() > max) {
                throw protocol(what + " exceeds " + max + " bytes");
            }
        }
    }

    private static boolean isToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean okay = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!okay) {
                return false;
            }
        }
        return true;
    }

    private static String trimOws(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(start, end);
    }

    private static String preview(String text) {
        String safe = text.length() > 80 ? text.substring(0, 80) + "..." : text;
        return "'" + safe + "'";
    }

    private static WebException protocol(String message) {
        return new WebException(WebErrorCode.WEB_NETWORK_ERROR, "malformed HTTP response: " + message);
    }

    /** 已解析响应头（名字小写；值去 OWS）。 */
    record Head(int statusCode, Map<String, List<String>> headers) {

        /** @param name 小写头名 @return 全部值（无则空列表） */
        List<String> all(String name) {
            return headers.getOrDefault(name, List.of());
        }

        /** @param name 小写头名 @return 首个值（无则 null） */
        String first(String name) {
            List<String> values = all(name);
            return values.isEmpty() ? null : values.get(0);
        }
    }

    /** 正文与流式截断事实。 */
    record Body(byte[] bytes, boolean truncated) {
    }
}
