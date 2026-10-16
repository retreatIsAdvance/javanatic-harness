package io.javanatic.harness.mcp.stdio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** servers[] 行配置解析矩阵：形状/缺键/坏值/重名/命名规则（T2 歧义防护）全部 fail loud。 */
class McpServerConfigTest {

    private static final String PLUGIN = "mcp-stdio";

    @Test
    void absentServersKeyYieldsEmptyList() {
        assertThat(McpServerConfig.parseAll(Map.of(), PLUGIN)).isEmpty();
    }

    @Test
    void fullEntryParsesAllFields() {
        List<McpServerConfig> servers = McpServerConfig.parseAll(Map.of("servers", List.of(Map.of(
            "id", "alpha",
            "command", "/usr/bin/java",
            "args", List.of("srv.java", "--x", 7),
            "env", Map.of("TOKEN", 42),
            "timeoutSeconds", 90))), PLUGIN);
        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.id()).isEqualTo("alpha");
            assertThat(server.command()).isEqualTo("/usr/bin/java");
            assertThat(server.args()).containsExactly("srv.java", "--x", "7");
            assertThat(server.env()).containsEntry("TOKEN", "42");
            assertThat(server.timeout()).isEqualTo(Duration.ofSeconds(90));
        });
    }

    @Test
    void defaultsApplyWhenOptionalFieldsAbsent() {
        McpServerConfig server = parseOne(Map.of("id", "alpha", "command", "java"));
        assertThat(server.args()).isEmpty();
        assertThat(server.env()).isEmpty();
        assertThat(server.timeout()).isEqualTo(Duration.ofSeconds(McpServerConfig.DEFAULT_TIMEOUT_SECONDS));
    }

    @Test
    void timeoutAcceptsNumericString() {
        assertThat(parseOne(Map.of("id", "alpha", "command", "java", "timeoutSeconds", " 7 ")).timeout())
            .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void serversMustBeList() {
        assertRejected(Map.of("servers", "nope"), "'servers' must be a list");
    }

    @Test
    void entryMustBeMap() {
        assertRejected(Map.of("servers", Collections.singletonList(null)), "server entry must be a map, got: null");
        assertRejected(Map.of("servers", List.of("nope")), "server entry must be a map, got: String");
    }

    @Test
    void unknownKeyRejected() {
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "cwd", "/tmp")), "unknown key 'cwd'");
    }

    @Test
    void missingOrEmptyIdRejected() {
        assertRejected(servers(Map.of("command", "java")), "missing required 'id'");
        assertRejected(servers(Map.of("id", "", "command", "java")), "missing required 'id'");
    }

    @Test
    void missingOrEmptyCommandRejected() {
        assertRejected(servers(Map.of("id", "alpha")), "missing required 'command'");
        assertRejected(servers(Map.of("id", "alpha", "command", "")), "missing required 'command'");
    }

    /** T2 歧义防护：'__' 与 kebab-case 两条规则分开报错。 */
    @Test
    void doubleUnderscoreIdRejectedWithAmbiguityReason() {
        assertRejected(servers(Map.of("id", "bad__id", "command", "java")), "must not contain '__'");
    }

    @Test
    void nonKebabIdRejected() {
        assertRejected(servers(Map.of("id", "Bad_Id", "command", "java")), "must be kebab-case");
        assertRejected(servers(Map.of("id", "9lives", "command", "java")), "must be kebab-case");
        assertRejected(servers(Map.of("id", "alpha-", "command", "java")), "must be kebab-case");
    }

    @Test
    void duplicateServerIdRejected() {
        assertRejected(Map.of("servers", List.of(
            Map.of("id", "alpha", "command", "java"),
            Map.of("id", "alpha", "command", "java2"))), "duplicate server id 'alpha'");
    }

    @Test
    void argsMustBeListAndScalarsOnly() {
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "args", "x")), ".args: must be a list");
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", "alpha");
        entry.put("command", "java");
        entry.put("args", Arrays.asList("ok", null));
        assertRejected(servers(entry), ".args: entries must be scalar, got: null");
        Map<String, Object> nested = new HashMap<>();
        nested.put("id", "alpha");
        nested.put("command", "java");
        nested.put("args", List.of(List.of("x")));
        assertRejected(servers(nested), ".args: entries must be scalar");
    }

    @Test
    void envMustBeMapWithScalarValues() {
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "env", List.of())), ".env: must be a map");
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", "alpha");
        entry.put("command", "java");
        entry.put("env", Map.of("NESTED", Map.of("x", 1)));
        assertRejected(servers(entry), ".env: entries must be scalar");
    }

    @Test
    void timeoutRejectsBadValues() {
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "timeoutSeconds", 0)),
            ".timeoutSeconds: must be positive, got: 0");
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "timeoutSeconds", -5)),
            ".timeoutSeconds: must be positive, got: -5");
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "timeoutSeconds", "abc")),
            ".timeoutSeconds: not numeric: abc");
        assertRejected(servers(Map.of("id", "alpha", "command", "java", "timeoutSeconds", List.of(1))),
            ".timeoutSeconds: must be numeric");
    }

    /** 错误定位含条目序（多 server 时指向具体条目）。 */
    @Test
    void errorLocationIncludesEntryIndex() {
        assertRejected(Map.of("servers", List.of(
            Map.of("id", "alpha", "command", "java"),
            Map.of("command", "java"))), "mcp-stdio.servers[1]: missing required 'id'");
    }

    private static McpServerConfig parseOne(Map<String, Object> entry) {
        return McpServerConfig.parseAll(Map.of("servers", List.of(entry)), PLUGIN).getFirst();
    }

    private static Map<String, Object> servers(Map<String, Object> entry) {
        return Map.of("servers", List.of(entry));
    }

    private static void assertRejected(Map<String, Object> config, String messagePart) {
        assertThatThrownBy(() -> McpServerConfig.parseAll(config, PLUGIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(messagePart);
    }
}
