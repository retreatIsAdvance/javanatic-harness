package io.javanatic.harness.mcp.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** servers[] 行配置解析矩阵：形状/缺键/坏值/重名/URL/headers 全部 fail loud。 */
class McpHttpServerConfigTest {

    private static final String PLUGIN = "mcp-http";

    @Test
    void absentServersKeyYieldsEmptyList() {
        assertThat(McpHttpServerConfig.parseAll(Map.of(), PLUGIN)).isEmpty();
    }

    @Test
    void fullEntryParsesAllFields() {
        List<McpHttpServerConfig> servers = McpHttpServerConfig.parseAll(Map.of("servers", List.of(Map.of(
            "id", "alpha",
            "url", "https://mcp.example.com/rpc?tenant=a",
            "headers", Map.of("Authorization", "Bearer secret", "X-Tenant", "acme"),
            "timeoutSeconds", 90))), PLUGIN);
        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.id()).isEqualTo("alpha");
            assertThat(server.url().toString()).isEqualTo("https://mcp.example.com/rpc?tenant=a");
            assertThat(server.headers()).containsEntry("Authorization", "Bearer secret")
                .containsEntry("X-Tenant", "acme");
            assertThat(server.timeout()).isEqualTo(Duration.ofSeconds(90));
        });
    }

    @Test
    void defaultsApplyWhenOptionalFieldsAbsent() {
        McpHttpServerConfig server = parseOne(Map.of("id", "alpha", "url", "http://127.0.0.1:8080/mcp"));
        assertThat(server.headers()).isEmpty();
        assertThat(server.timeout())
            .isEqualTo(Duration.ofSeconds(McpHttpServerConfig.DEFAULT_TIMEOUT_SECONDS));
    }

    @Test
    void timeoutAcceptsNumericString() {
        assertThat(parseOne(Map.of("id", "alpha", "url", "http://h/mcp", "timeoutSeconds", " 7 ")).timeout())
            .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void serversMustBeList() {
        assertRejected(Map.of("servers", "nope"), "'servers' must be a list");
    }

    @Test
    void entryMustBeMap() {
        assertRejected(Map.of("servers", List.of("nope")), "server entry must be a map, got: String");
    }

    @Test
    void unknownKeyRejected() {
        assertRejected(servers(Map.of("id", "alpha", "url", "http://h/mcp", "cwd", "/tmp")),
            "unknown key 'cwd'");
    }

    @Test
    void missingOrEmptyIdRejected() {
        assertRejected(servers(Map.of("url", "http://h/mcp")), "missing required 'id'");
        assertRejected(servers(Map.of("id", "", "url", "http://h/mcp")), "missing required 'id'");
    }

    @Test
    void missingUrlRejected() {
        assertRejected(servers(Map.of("id", "alpha")), "missing required 'url'");
    }

    /** T2 歧义防护与 stdio 同口径。 */
    @Test
    void doubleUnderscoreIdRejectedWithAmbiguityReason() {
        assertRejected(servers(Map.of("id", "bad__id", "url", "http://h/mcp")),
            "must not contain '__'");
    }

    @Test
    void nonKebabIdRejected() {
        assertRejected(servers(Map.of("id", "Bad_Id", "url", "http://h/mcp")), "must be kebab-case");
        assertRejected(servers(Map.of("id", "9lives", "url", "http://h/mcp")), "must be kebab-case");
        assertRejected(servers(Map.of("id", "alpha-", "url", "http://h/mcp")), "must be kebab-case");
    }

    @Test
    void duplicateServerIdRejected() {
        assertRejected(Map.of("servers", List.of(
            Map.of("id", "alpha", "url", "http://h/a"),
            Map.of("id", "alpha", "url", "http://h/b"))), "duplicate server id 'alpha'");
    }

    /** T6：URL 只接受绝对 http/https；userinfo 与 fragment 拒收（凭据只走 headers）。 */
    @Test
    void urlMustBeAbsoluteHttpOrHttps() {
        assertRejected(servers(Map.of("id", "alpha", "url", "/mcp")),
            "must be absolute http/https URI");
        assertRejected(servers(Map.of("id", "alpha", "url", "ftp://h/mcp")),
            "must be absolute http/https URI");
        assertRejected(servers(Map.of("id", "alpha", "url", "http:///mcp")), "missing host");
        assertRejected(servers(Map.of("id", "alpha", "url", "http://user:pw@h/mcp")),
            "userinfo is not allowed");
        assertRejected(servers(Map.of("id", "alpha", "url", "http://h/mcp#frag")),
            "fragment is not allowed");
    }

    @Test
    void malformedUriRejected() {
        assertRejected(servers(Map.of("id", "alpha", "url", "http://h/ mcp")), "invalid URI");
    }

    /** T6：query 参数名命中敏感词表（与脱敏单源）即拒；凭据只走 headers。 */
    @Test
    void sensitiveQueryNamesRejected() {
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?access_token=abc")), "looks sensitive");
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?tenant=a&api_key=k")), "looks sensitive");
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?Cookie=sid")), "looks sensitive");
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?X-Auth=1")), "looks sensitive");
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?author=bob")), "looks sensitive");
    }

    @Test
    void percentEncodedSensitiveQueryNameStillRejected() {
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?%61uth=1")), "looks sensitive");
    }

    @Test
    void nonSensitiveQueryAllowed() {
        assertThat(parseOne(Map.of("id", "alpha",
            "url", "https://h/mcp?tenant=acme&page=2")).url())
            .hasToString("https://h/mcp?tenant=acme&page=2");
    }

    @Test
    void malformedPercentEncodingInQueryNameRejected() {
        // URI 构造期即拒坏转义（URLDecoder 的 catch 是廉价防御，不可达面不伪测）
        assertRejected(servers(Map.of("id", "alpha",
            "url", "https://h/mcp?bad%ZZ=1")), "invalid URI");
    }

    @Test
    void headersMustBeMap() {
        assertRejected(servers(Map.of("id", "alpha", "url", "http://h/mcp", "headers", List.of())),
            ".headers: must be a map");
    }

    @Test
    void headerValuesMustBeStrings() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", "alpha");
        entry.put("url", "http://h/mcp");
        entry.put("headers", Map.of("X-Num", 42));
        assertRejected(servers(entry), ".headers[X-Num]: value must be a string");
    }

    @Test
    void reservedTransportHeadersRejected() {
        assertRejected(header("Host", "evil"), "reserved by the transport");
        assertRejected(header("content-type", "text/plain"), "reserved by the transport");
        assertRejected(header("Mcp-Session-Id", "forged"), "reserved by the transport");
        assertRejected(header("MCP-Protocol-Version", "2025-06-18"), "reserved by the transport");
    }

    @Test
    void invalidHeaderTokenRejected() {
        assertRejected(header("X Bad", "v"), "is not a valid HTTP token");
    }

    @Test
    void controlCharactersInHeaderValueRejected() {
        assertRejected(header("X-Trace", "a\r\nInjected: x"),
            "contains a control character");
        assertRejected(header("X-Trace", "a\u0000b"), "contains a control character");
    }

    @Test
    void duplicateHeaderNamesCaseInsensitiveRejected() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", "alpha");
        entry.put("url", "http://h/mcp");
        Map<String, Object> headers = new HashMap<>();
        headers.put("X-Tenant", "a");
        headers.put("x-tenant", "b");
        entry.put("headers", headers);
        assertRejected(servers(entry), "duplicate header");
    }

    @Test
    void timeoutRejectsBadValues() {
        assertRejected(serverWithTimeout(0), ".timeoutSeconds: must be positive, got: 0");
        assertRejected(serverWithTimeout(-5), ".timeoutSeconds: must be positive, got: -5");
        assertRejected(serverWithTimeout("abc"), ".timeoutSeconds: not numeric: abc");
        assertRejected(serverWithTimeout(List.of(1)), ".timeoutSeconds: must be numeric");
    }

    @Test
    void errorLocationIncludesEntryIndex() {
        assertRejected(Map.of("servers", List.of(
            Map.of("id", "alpha", "url", "http://h/a"),
            Map.of("url", "http://h/b"))), "mcp-http.servers[1]: missing required 'id'");
    }

    private static McpHttpServerConfig parseOne(Map<String, Object> entry) {
        return McpHttpServerConfig.parseAll(Map.of("servers", List.of(entry)), PLUGIN).getFirst();
    }

    private static Map<String, Object> servers(Map<String, Object> entry) {
        return Map.of("servers", List.of(entry));
    }

    private static Map<String, Object> header(String name, String value) {
        return servers(Map.of("id", "alpha", "url", "http://h/mcp", "headers", Map.of(name, value)));
    }

    private static Map<String, Object> serverWithTimeout(Object timeout) {
        return servers(Map.of("id", "alpha", "url", "http://h/mcp", "timeoutSeconds", timeout));
    }

    private static void assertRejected(Map<String, Object> config, String messagePart) {
        assertThatThrownBy(() -> McpHttpServerConfig.parseAll(config, PLUGIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(messagePart);
    }
}
