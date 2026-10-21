package io.javanatic.harness.web.fetch.http;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** URL 策略矩阵：https/长度/userinfo/IDN/fragment/端口/IPv6/编码 + 重定向逐跳解析。 */
class FetchUrlPolicyTest {

    @Test
    void normalizesHostPortPathAndStripsFragment() {
        FetchTarget target = FetchUrlPolicy.parse("https://Example.COM:443/a/b?q=1#frag");
        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.hostHeader()).isEqualTo("example.com");
        assertThat(target.requestTarget()).isEqualTo("/a/b?q=1");
        assertThat(target.base().toString()).isEqualTo("https://example.com/a/b?q=1");
        assertThat(target.ipLiteral()).isFalse();
    }

    @Test
    void keepsNonDefaultPortInHostHeader() {
        FetchTarget target = FetchUrlPolicy.parse("https://example.com:8443/x");
        assertThat(target.hostHeader()).isEqualTo("example.com:8443");
        assertThat(target.port()).isEqualTo(8443);
    }

    @Test
    void convertsIdnToPunycodeAndEncodesNonAsciiPath() {
        FetchTarget target = FetchUrlPolicy.parse("https://例え.jp/路径?q=中文");
        assertThat(target.host()).isEqualTo("xn--r8jz45g.jp");
        assertThat(target.requestTarget())
            .isEqualTo("/%E8%B7%AF%E5%BE%84?q=%E4%B8%AD%E6%96%87");
    }

    @Test
    void stripsTrailingDotAndNormalizesMissingPath() {
        FetchTarget target = FetchUrlPolicy.parse("https://example.com.");
        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.requestTarget()).isEqualTo("/");
        assertThat(target.base().toString()).isEqualTo("https://example.com/");
    }

    @Test
    void ipv6LiteralRequiresBracketsAndKeepsThemInHostHeader() {
        FetchTarget target = FetchUrlPolicy.parse("https://[2001:4860:4860::8888]:8443/x");
        assertThat(target.ipLiteral()).isTrue();
        assertThat(target.port()).isEqualTo(8443);
        assertThat(target.hostHeader()).isEqualTo("[2001:4860:4860:0:0:0:0:8888]:8443");
        assertThat(target.requestTarget()).isEqualTo("/x");
    }

    @Test
    void literalIpv4IsMarkedAndCanonicalized() {
        FetchTarget target = FetchUrlPolicy.parse("https://127.1:8443/");
        assertThat(target.ipLiteral()).isTrue();
        assertThat(target.host()).isEqualTo("127.0.0.1");
        assertThat(target.hostHeader()).isEqualTo("127.0.0.1:8443");
    }

    @Test
    void preservesExistingPercentEncodingAndRejectsBrokenOne() {
        assertThat(FetchUrlPolicy.parse("https://example.com/a%20b").requestTarget())
            .isEqualTo("/a%20b");
        assertThatThrownBy(() -> FetchUrlPolicy.parse("https://example.com/a%2"))
            .isInstanceOf(WebException.class)
            .hasMessageContaining("percent-encoding");
    }

    @Test
    void rejectsNonHttpsAndMalformedShapes() {
        assertInvalid("http://example.com/");
        assertInvalid("ftp://example.com/");
        assertInvalid("example.com/path");
        assertInvalid("https://user:pass@example.com/");
        assertInvalid("https:///path");
        assertInvalid("https://bad_host.example/");
        assertInvalid("https://exa%6dple.com/");
        assertInvalid("https://999.1.1.1/");
        assertInvalid("https://example.com:0/");
        assertInvalid("https://example.com:65536/");
        assertInvalid("https://example.com:abc/");
        assertInvalid("https://example.com:/");
        assertInvalid("https://2001:db8::1/x");
        assertInvalid("https://example.com/a b");
        assertInvalid("https://example.com/a\\b");
        assertInvalid("https://example.com/" + "a".repeat(2060));
    }

    @Test
    void relativeRedirectResolvesAgainstCurrentUrl() {
        FetchTarget base = FetchUrlPolicy.parse("https://pinned.test:8443/a/b?q=1");
        assertThat(FetchUrlPolicy.resolveRedirect(base, "/next").base().toString())
            .isEqualTo("https://pinned.test:8443/next");
        assertThat(FetchUrlPolicy.resolveRedirect(base, "next").base().toString())
            .isEqualTo("https://pinned.test:8443/a/next");
        assertThat(FetchUrlPolicy.resolveRedirect(base, "../up").base().toString())
            .isEqualTo("https://pinned.test:8443/up");
    }

    @Test
    void schemeRelativeAndCrossOriginRedirectsStayHttps() {
        FetchTarget base = FetchUrlPolicy.parse("https://pinned.test/a");
        FetchTarget crossOrigin = FetchUrlPolicy.resolveRedirect(base, "//second.test/x");
        assertThat(crossOrigin.base().toString()).isEqualTo("https://second.test/x");
        assertThat(FetchUrlPolicy.resolveRedirect(base, "https://second.test/y#frag")
            .base().toString()).isEqualTo("https://second.test/y");
    }

    @Test
    void redirectToPlainHttpIsBlocked() {
        FetchTarget base = FetchUrlPolicy.parse("https://pinned.test/a");
        assertThatThrownBy(() -> FetchUrlPolicy.resolveRedirect(base, "http://second.test/x"))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_REDIRECT_BLOCKED))
            .hasMessageContaining("https");
    }

    @Test
    void badOrMissingLocationIsBlocked() {
        FetchTarget base = FetchUrlPolicy.parse("https://pinned.test/a");
        for (String location : new String[] {null, "", "   ", "http://[bad", "javascript:alert(1)"}) {
            assertThatThrownBy(() -> FetchUrlPolicy.resolveRedirect(base, location))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_REDIRECT_BLOCKED));
        }
    }

    @Test
    void overlongRedirectTargetIsBlocked() {
        FetchTarget base = FetchUrlPolicy.parse("https://pinned.test/a");
        assertThatThrownBy(() -> FetchUrlPolicy.resolveRedirect(base, "/" + "b".repeat(2048)))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_REDIRECT_BLOCKED));
    }

    private static void assertInvalid(String url) {
        assertThatThrownBy(() -> FetchUrlPolicy.parse(url))
            .isInstanceOf(WebException.class)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_INVALID_URL));
    }
}
