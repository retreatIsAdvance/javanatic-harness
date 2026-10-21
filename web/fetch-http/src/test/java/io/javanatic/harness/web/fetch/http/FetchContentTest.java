package io.javanatic.harness.web.fetch.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebFetchBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 内容白名单 / charset / identity 编码 / 非法字节替换矩阵（含 T2 条件 ②）。 */
class FetchContentTest {

    @Test
    void htmlAndXhtmlBecomeHtmlBody() {
        assertThat(FetchContent.decode(head("text/html; charset=utf-8"), "<p>x</p>".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(new WebFetchBody.Html("<p>x</p>"));
        assertThat(FetchContent.decode(head("application/xhtml+xml"), "<p>x</p>".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(new WebFetchBody.Html("<p>x</p>"));
    }

    @Test
    void textJsonAndXmlBecomeTextBody() {
        for (String type : List.of("text/plain", "text/csv", "application/json",
            "application/ld+json", "application/xml", "application/atom+xml")) {
            assertThat(FetchContent.decode(head(type), "ok".getBytes(StandardCharsets.UTF_8)))
                .as(type)
                .isEqualTo(new WebFetchBody.Text("ok"));
        }
    }

    @Test
    void missingOrNonTextContentTypeIsUnsupported() {
        assertUnsupported(head(404, "image/png"), new byte[0], "404");
        assertUnsupported(extraHeaders(Map.of(), 404), new byte[0], "missing Content-Type");
    }

    @Test
    void charsetDefaultsToUtf8AndHonorsDeclaredLatin1() {
        assertThat(FetchContent.decode(head("text/plain"),
            "中文".getBytes(StandardCharsets.UTF_8)).content()).isEqualTo("中文");
        assertThat(FetchContent.decode(head("text/plain; charset=ISO-8859-1"),
            new byte[] {'c', 'a', 'f', (byte) 0xE9})).isEqualTo(new WebFetchBody.Text("café"));
        assertThat(FetchContent.decode(head("text/plain; charset=\"utf-8\""),
            "ok".getBytes(StandardCharsets.UTF_8))).isEqualTo(new WebFetchBody.Text("ok"));
    }

    @Test
    void unsupportedOrDangerousCharsetIsRejected() {
        assertUnsupported(head("text/plain; charset=x-unknown-charset"),
            new byte[0], "x-unknown-charset");
        assertUnsupported(head("text/plain; charset=UTF-16"), new byte[0], "UTF-16");
    }

    @Test
    void invalidBytesAreReplacedNotGuessed() {
        WebFetchBody body = FetchContent.decode(head("text/plain; charset=utf-8"),
            new byte[] {'a', (byte) 0xC3, 'b'});
        assertThat(body.content()).isEqualTo("a\uFFFDb");
    }

    @Test
    void utf8BomIsStripped() {
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertThat(FetchContent.decode(head("text/plain; charset=utf-8"), bom).content())
            .isEqualTo("hi");
    }

    @Test
    void nonIdentityContentEncodingIsExplicitlyUnsupported() {
        for (String encoding : List.of("gzip", "br", "deflate", "gzip, identity")) {
            assertUnsupported(extraHeaders(Map.of("content-encoding", List.of(encoding)), 200),
                new byte[] {1, 2, 3}, "Content-Encoding");
        }
    }

    @Test
    void explicitIdentityEncodingIsAccepted() {
        assertThat(FetchContent.decode(extraHeaders(
            Map.of("content-type", List.of("text/plain"), "content-encoding", List.of("identity")), 200),
            "ok".getBytes(StandardCharsets.UTF_8))).isEqualTo(new WebFetchBody.Text("ok"));
    }

    private static FetchProtocol.Head head(String contentType) {
        return extraHeaders(Map.of("content-type", List.of(contentType)), 200);
    }

    private static FetchProtocol.Head head(int status, String contentType) {
        return extraHeaders(Map.of("content-type", List.of(contentType)), status);
    }

    private static FetchProtocol.Head extraHeaders(Map<String, List<String>> headers, int status) {
        Map<String, List<String>> map = new LinkedHashMap<>(headers);
        map.replaceAll((name, values) -> List.copyOf(values));
        return new FetchProtocol.Head(status, Map.copyOf(map));
    }

    private static void assertUnsupported(FetchProtocol.Head head, byte[] bytes, String message) {
        assertThatThrownBy(() -> FetchContent.decode(head, bytes))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_UNSUPPORTED_CONTENT_TYPE))
            .hasMessageContaining(message);
    }
}
