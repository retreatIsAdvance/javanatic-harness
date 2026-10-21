package io.javanatic.harness.web.fetch.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP/1.1 线路矩阵：头区边界、smuggling 面、chunked 解码（T2 条件 ①）。 */
class FetchProtocolTest {

    @Test
    void parsesStatusAndHeadersWithOwsAndDuplicates() {
        FetchProtocol.Head head = headOf("HTTP/1.1 200 OK\r\n"
            + "Content-Type:  text/plain \r\n"
            + "Set-Cookie: a=1\r\n"
            + "Set-Cookie: b=2\r\n"
            + "\r\n");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.first("content-type")).isEqualTo("text/plain");
        assertThat(head.all("set-cookie")).containsExactly("a=1", "b=2");
        assertThat(head.first("missing")).isNull();
    }

    @Test
    void acceptsNon2xxStatusAsAResult() {
        FetchProtocol.Head head = headOf("HTTP/1.1 503 Service Unavailable\r\n\r\n");
        assertThat(head.statusCode()).isEqualTo(503);
    }

    @Test
    void rejectsMalformedStatusLines() {
        assertProtocolError("HTTP/2 200 OK\r\n\r\n", "status");
        assertProtocolError("ICY 200 OK\r\n\r\n", "status");
        assertProtocolError("HTTP/1.1 100 Continue\r\n\r\n", "status");
        // 101 不得作为结果透传：客户端从未请求升级，升级响应属传输层拒绝路径
        assertProtocolError("HTTP/1.1 101 Switching Protocols\r\n\r\n", "status");
        assertProtocolError("HTTP/1.1 abc\r\n\r\n", "status");
        assertProtocolError("HTTP/1.1 999 Nope\r\n\r\n", "status");
        assertProtocolError("", "empty");
    }

    @Test
    void rejectsObsFoldAndBadHeaderShapes() {
        assertProtocolError("HTTP/1.1 200 OK\r\nX-Long: a\r\n b\r\n\r\n", "folding");
        assertProtocolError("HTTP/1.1 200 OK\r\nBad Header: x\r\n\r\n", "header name");
        assertProtocolError("HTTP/1.1 200 OK\r\nX-Bad: a\u0001b\r\n\r\n", "control");
        assertProtocolError("HTTP/1.1 200 OK\r\nNoColon\r\n\r\n", "malformed header");
    }

    @Test
    void enforcesHeaderCountAndSizeBounds() {
        StringBuilder many = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 101; i++) {
            many.append("X-").append(i).append(": v\r\n");
        }
        many.append("\r\n");
        assertProtocolError(many.toString(), "more than");

        StringBuilder big = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 9; i++) {
            big.append("X-Big-").append(i).append(": ").append("a".repeat(8000)).append("\r\n");
        }
        big.append("\r\n");
        assertProtocolError(big.toString(), "exceeds 65536");

        String longLine = "HTTP/1.1 200 OK\r\nX-Long: " + "a".repeat(8200) + "\r\n\r\n";
        assertProtocolError(longLine, "exceeds 8192");
    }

    @Test
    void requiresStrictCrlf() {
        assertProtocolError("HTTP/1.1 200 OK\n\n", "CRLF");
        assertProtocolError("HTTP/1.1 200 OK\r\nX: a\rb\r\n\r\n", "bare CR");
    }

    @Test
    void rejectsSmugglingShapesAndInconsistentLengths() {
        assertProtocolError("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n"
            + "Transfer-Encoding: chunked\r\n\r\nabcd", "both Content-Length");
        assertProtocolError("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\nabc", "Transfer-Encoding");
        assertProtocolError("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Length: 5\r\n\r\nabcd5",
            "inconsistent Content-Length");
        assertProtocolError("HTTP/1.1 200 OK\r\nContent-Length: +4\r\n\r\nabcd", "Content-Length");
    }

    @Test
    void acceptsIdenticalRepeatedContentLength() {
        FetchProtocol.Body body = read("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n"
            + "Content-Length: 4\r\n\r\nabcd", 100);
        assertThat(new String(body.bytes(), StandardCharsets.UTF_8)).isEqualTo("abcd");
        assertThat(body.truncated()).isFalse();
    }

    @Test
    void noBodyStatusesIgnoreFramingHeaders() {
        FetchProtocol.Body body = read("HTTP/1.1 204 No Content\r\nContent-Length: 10\r\n\r\n", 100);
        assertThat(body.bytes()).isEmpty();
        assertThat(body.truncated()).isFalse();
        FetchProtocol.Body notModified = read("HTTP/1.1 304 Not Modified\r\n"
            + "Transfer-Encoding: chunked\r\n\r\n", 100);
        assertThat(notModified.bytes()).isEmpty();
    }

    @Test
    void readsContentLengthBodyAndRejectsShortBody() {
        FetchProtocol.Body body = read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello", 100);
        assertThat(new String(body.bytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertProtocolError("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", "truncated");
    }

    @Test
    void declaresTooLargeBeforeReadingBody() {
        assertThatThrownBy(() -> read("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n", 10))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_TOO_LARGE));
    }

    @Test
    void decodesChunkedWithExtensionsAndTrailers() {
        FetchProtocol.Body body = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "4;foo=bar\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trailer: v\r\n\r\n", 100);
        assertThat(new String(body.bytes(), StandardCharsets.UTF_8)).isEqualTo("Wikipedia");
        assertThat(body.truncated()).isFalse();
    }

    @Test
    void decodesUppercaseHexChunkSize() {
        FetchProtocol.Body body = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "A\r\n0123456789\r\n0\r\n\r\n", 100);
        assertThat(new String(body.bytes(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
    }

    @Test
    void rejectsMalformedChunkFraming() {
        assertProtocolError(chunked("ZZ\r\nabc\r\n0\r\n\r\n"), "chunk size");
        assertProtocolError(chunked("\r\n"), "chunk size");
        assertProtocolError(chunked("4\r\nabc"), "truncated");
        assertProtocolError(chunked("4\r\nabcdX\r\n0\r\n\r\n"), "chunk data is not CRLF-terminated");
        assertProtocolError(chunked("4\r\nabcd\n\n0\r\n\r\n"), "chunk data is not CRLF-terminated");
        assertProtocolError(chunked("4\r\nabcd\r\n"), "truncated");
        assertProtocolError(chunked("0\r\n bad-fold\r\n\r\n"), "folding");
        assertProtocolError(chunked("0\r\nX: v"), "truncated");
        assertProtocolError(chunked("11111111111111111\r\n"), "chunk size");
        assertProtocolError(chunked("a".repeat(8200) + "\r\n"), "exceeds 8192");
    }

    @Test
    void chunkedOverCapStopsAndMarksTruncated() {
        FetchProtocol.Body body = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "4\r\nabcd\r\n4\r\nefgh\r\n0\r\n\r\n", 4);
        assertThat(new String(body.bytes(), StandardCharsets.UTF_8)).isEqualTo("abcd");
        assertThat(body.truncated()).isTrue();
    }

    @Test
    void unknownLengthBodyIsBoundedAndMarked() {
        FetchProtocol.Body truncated = read("HTTP/1.1 200 OK\r\n\r\nabcdef", 4);
        assertThat(new String(truncated.bytes(), StandardCharsets.UTF_8)).isEqualTo("abcd");
        assertThat(truncated.truncated()).isTrue();

        FetchProtocol.Body exact = read("HTTP/1.1 200 OK\r\n\r\nabcd", 4);
        assertThat(new String(exact.bytes(), StandardCharsets.UTF_8)).isEqualTo("abcd");
        assertThat(exact.truncated()).isFalse();
    }

    @Test
    void chunkedExactlyAtCapIsNotMarkedTruncated() {
        FetchProtocol.Body body = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "4\r\nabcd\r\n0\r\n\r\n", 4);
        assertThat(body.truncated()).isFalse();
    }

    @Test
    void readHeadReturnsHeadBeforeBodyStarts() throws IOException {
        InputStream in = new ByteArrayInputStream(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
            .getBytes(StandardCharsets.ISO_8859_1));
        FetchProtocol.Head head = FetchProtocol.readHead(in);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)).isEqualTo("ok");
    }

    private static FetchProtocol.Head headOf(String raw) {
        try {
            return FetchProtocol.readHead(stream(raw));
        } catch (IOException e) {
            throw new AssertionError("unexpected I/O", e);
        }
    }

    private static FetchProtocol.Body read(String raw, int cap) {
        try {
            InputStream in = stream(raw);
            return FetchProtocol.readBody(in, FetchProtocol.readHead(in), cap);
        } catch (IOException e) {
            throw new AssertionError("unexpected I/O", e);
        }
    }

    private static InputStream stream(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String chunked(String body) {
        return "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + body;
    }

    private static void assertProtocolError(String raw, String message) {
        assertThatThrownBy(() -> {
            InputStream in = stream(raw);
            FetchProtocol.readBody(in, FetchProtocol.readHead(in), 100);
        })
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_NETWORK_ERROR))
            .hasMessageContaining(message);
    }
}
