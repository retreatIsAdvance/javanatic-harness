package io.javanatic.harness.web.search.deepseek;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 行配置：默认值、键白名单、URL 规则、数值边界与凭据来源。 */
class DeepSeekSearchConfigTest {

    private static final String PLUGIN = "web-search-deepseek";

    @Test
    void defaultsMatchDocumentedValues() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(), PLUGIN);
        assertThat(config.baseUrl()).isEqualTo("https://api.deepseek.com/anthropic/v1");
        assertThat(config.endpoint().toString())
            .isEqualTo("https://api.deepseek.com/anthropic/v1/messages");
        assertThat(config.model()).isEqualTo("deepseek-v4-flash");
        assertThat(config.apiKeyEnv()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(config.apiKey()).isNull();
        assertThat(config.maxTokens()).isEqualTo(4096);
        assertThat(config.maxUses()).isEqualTo(5);
        assertThat(config.timeoutSeconds()).isEqualTo(30);
    }

    @Test
    void parsesAllKeysAndNormalizesTrailingSlashes() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(
            "baseUrl", "https://example.test/anthropic/v1/",
            "model", "custom-model",
            "apiKey", "literal-key",
            "apiKeyEnv", "CUSTOM_KEY",
            "maxTokens", 1024,
            "maxUses", 2,
            "timeoutSeconds", 5), PLUGIN);
        assertThat(config.baseUrl()).isEqualTo("https://example.test/anthropic/v1");
        assertThat(config.endpoint().toString()).isEqualTo("https://example.test/anthropic/v1/messages");
        assertThat(config.model()).isEqualTo("custom-model");
        assertThat(config.apiKey()).isEqualTo("literal-key");
        assertThat(config.apiKeyEnv()).isEqualTo("CUSTOM_KEY");
        assertThat(config.maxTokens()).isEqualTo(1024);
        assertThat(config.maxUses()).isEqualTo(2);
        assertThat(config.timeoutSeconds()).isEqualTo(5);
    }

    @Test
    void numericStringsAreAccepted() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(
            "maxTokens", "2048", "maxUses", "3", "timeoutSeconds", "12"), PLUGIN);
        assertThat(config.maxTokens()).isEqualTo(2048);
        assertThat(config.maxUses()).isEqualTo(3);
        assertThat(config.timeoutSeconds()).isEqualTo(12);
    }

    @Test
    void literalKeyWinsOverEnvironment() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(
            "apiKey", "literal", "apiKeyEnv", "CUSTOM_KEY"), PLUGIN);
        assertThat(config.resolveApiKey(name -> "from-env")).isEqualTo("literal");
    }

    @Test
    void keyFallsBackToNamedEnvironment() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(
            "apiKeyEnv", "CUSTOM_KEY"), PLUGIN);
        assertThat(config.resolveApiKey(name -> {
            assertThat(name).isEqualTo("CUSTOM_KEY");
            return "from-env";
        })).isEqualTo("from-env");
    }

    @Test
    void missingKeyResolvesNull() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(), PLUGIN);
        assertThat(config.resolveApiKey(name -> null)).isNull();
    }

    @Test
    void toStringMasksLiteralKey() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(
            "apiKey", "super-secret-key"), PLUGIN);
        assertThat(config.toString()).contains("****").doesNotContain("super-secret-key");
    }

    @Test
    void rejectsUnknownKeys() {
        assertConfigFails(Map.of("unknown", 1), "unknown key");
    }

    @Test
    void rejectsNonHttpsBaseUrl() {
        assertConfigFails(Map.of("baseUrl", "http://example.test/v1"), "https");
        assertConfigFails(Map.of("baseUrl", "ftp://example.test/v1"), "https");
        assertConfigFails(Map.of("baseUrl", "example.test/v1"), "https");
    }

    @Test
    void rejectsUserInfoQueryFragmentAndBadPort() {
        assertConfigFails(Map.of("baseUrl", "https://user:pass@example.test/v1"), "userinfo");
        assertConfigFails(Map.of("baseUrl", "https://example.test/v1?x=1"), "query");
        assertConfigFails(Map.of("baseUrl", "https://example.test/v1#frag"), "fragment");
        assertConfigFails(Map.of("baseUrl", "https:///v1"), "missing host");
        assertConfigFails(Map.of("baseUrl", "https://example.test:0/v1"), "port");
    }

    @Test
    void rejectsBadModelAndCredentialShape() {
        assertConfigFails(Map.of("model", "  "), "model");
        assertConfigFails(Map.of("apiKeyEnv", " "), "apiKeyEnv");
        assertConfigFails(Map.of("apiKey", ""), "apiKey");
    }

    @Test
    void rejectsOutOfRangeNumbers() {
        assertConfigFails(Map.of("maxTokens", 0), "maxTokens");
        assertConfigFails(Map.of("maxTokens", 100_001), "maxTokens");
        assertConfigFails(Map.of("maxUses", 0), "maxUses");
        assertConfigFails(Map.of("maxUses", 101), "maxUses");
        assertConfigFails(Map.of("timeoutSeconds", 0), "timeoutSeconds");
        assertConfigFails(Map.of("timeoutSeconds", 601), "timeoutSeconds");
        assertConfigFails(Map.of("timeoutSeconds", "soon"), "not numeric");
        assertConfigFails(Map.of("maxTokens", true), "must be numeric");
    }

    private static void assertConfigFails(Map<String, Object> config, String message) {
        assertThatThrownBy(() -> DeepSeekSearchConfig.parse(config, PLUGIN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(message);
    }
}
