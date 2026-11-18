package io.javanatic.harness.workbench.rpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * it37 停 1:B36/B37 纯函数围栏矩阵(设计稿 §4)。Host/Origin 白名单
 * 精确比对(port = 实际绑定面)、Origin 缺失放行/存在即同源、Content-Type
 * 媒体类型精确 application/json(参数放行)。拒绝理由字面量钉死,
 * 不与被测共享魔法值。
 */
class RequestFenceTest {

    private static final int PORT = 8787;
    private static final String GOOD_HOST = "127.0.0.1:8787";

    // ────────── Host 矩阵(8 例) ──────────

    @Test
    void loopbackIpHostPasses() {
        assertThat(RequestFence.hostOriginRejection("127.0.0.1:8787", null, PORT)).isEmpty();
    }

    @Test
    void localhostHostPasses() {
        assertThat(RequestFence.hostOriginRejection("localhost:8787", null, PORT)).isEmpty();
    }

    @Test
    void uppercaseLocalhostHostPasses() {
        assertThat(RequestFence.hostOriginRejection("LOCALHOST:8787", null, PORT)).isEmpty();
    }

    @Test
    void missingHostRejected() {
        assertThat(RequestFence.hostOriginRejection(null, null, PORT))
            .hasValue("host not allowed");
    }

    @Test
    void emptyHostRejected() {
        assertThat(RequestFence.hostOriginRejection("", null, PORT))
            .hasValue("host not allowed");
    }

    @Test
    void foreignHostRejected() {
        assertThat(RequestFence.hostOriginRejection("evil.com:8787", null, PORT))
            .hasValue("host not allowed");
    }

    @Test
    void wrongPortHostRejected() {
        assertThat(RequestFence.hostOriginRejection("127.0.0.1:9999", null, PORT))
            .hasValue("host not allowed");
    }

    @Test
    void portlessHostRejected() {
        assertThat(RequestFence.hostOriginRejection("127.0.0.1", null, PORT))
            .hasValue("host not allowed");
    }

    // ────────── Origin 矩阵(10 例,Host 恒合法) ──────────

    @Test
    void missingOriginPasses() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, null, PORT)).isEmpty();
    }

    @Test
    void loopbackIpOriginPasses() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "http://127.0.0.1:8787", PORT))
            .isEmpty();
    }

    @Test
    void localhostOriginPasses() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "http://localhost:8787", PORT))
            .isEmpty();
    }

    @Test
    void uppercaseOriginPasses() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "HTTP://LOCALHOST:8787", PORT))
            .isEmpty();
    }

    @Test
    void httpsOriginRejected() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "https://127.0.0.1:8787", PORT))
            .hasValue("origin not allowed");
    }

    @Test
    void foreignOriginRejected() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "http://evil.com", PORT))
            .hasValue("origin not allowed");
    }

    @Test
    void wrongPortOriginRejected() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "http://127.0.0.1:9999", PORT))
            .hasValue("origin not allowed");
    }

    @Test
    void nullLiteralOriginRejected() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "null", PORT))
            .hasValue("origin not allowed");
    }

    @Test
    void trailingSlashOriginRejected() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "http://127.0.0.1:8787/", PORT))
            .hasValue("origin not allowed");
    }

    @Test
    void emptyOriginRejected() {
        assertThat(RequestFence.hostOriginRejection(GOOD_HOST, "", PORT))
            .hasValue("origin not allowed");
    }

    // ────────── Content-Type 矩阵(11 例) ──────────

    @Test
    void plainJsonContentTypePasses() {
        assertThat(RequestFence.jsonContentType("application/json")).isTrue();
    }

    @Test
    void jsonWithCharsetParameterPasses() {
        assertThat(RequestFence.jsonContentType("application/json; charset=utf-8")).isTrue();
    }

    @Test
    void uppercaseJsonContentTypePasses() {
        assertThat(RequestFence.jsonContentType("APPLICATION/JSON")).isTrue();
    }

    @Test
    void uppercaseJsonWithParameterPasses() {
        assertThat(RequestFence.jsonContentType("APPLICATION/JSON; charset=UTF-8")).isTrue();
    }

    @Test
    void surroundingWhitespaceJsonPasses() {
        assertThat(RequestFence.jsonContentType("  application/json  ")).isTrue();
    }

    @Test
    void formUrlencodedRejected() {
        assertThat(RequestFence.jsonContentType("application/x-www-form-urlencoded")).isFalse();
    }

    @Test
    void textPlainRejected() {
        assertThat(RequestFence.jsonContentType("text/plain")).isFalse();
    }

    @Test
    void multipartRejected() {
        assertThat(RequestFence.jsonContentType("multipart/form-data; boundary=x")).isFalse();
    }

    @Test
    void missingContentTypeRejected() {
        assertThat(RequestFence.jsonContentType(null)).isFalse();
    }

    @Test
    void emptyContentTypeRejected() {
        assertThat(RequestFence.jsonContentType("")).isFalse();
    }

    @Test
    void jsonPatchMediaTypeRejected() {
        assertThat(RequestFence.jsonContentType("application/json-patch+json")).isFalse();
    }
}
