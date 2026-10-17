package io.javanatic.harness.kernel.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 脱敏单点：任意深度、敏感键子串、键保留、键序保留、非字符串键 fail loud。 */
class ConfigValuesTest {

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void masksNestedEnvSecretsAndKeepsNonSensitiveValues() {
        Map<String, Object> config = linked(
            "apiKey", "sk-top",
            "plain", "visible",
            "servers", List.of(linked(
                "id", "fixture",
                "env", linked(
                    "GITHUB_TOKEN", "ghp-secret",
                    "MODE", "dev",
                    "db_password", "hunter2"))),
            "nested", linked("clientSecret", "s3cr3t", "label", "ok"),
            "maxTokens", 4096,
            "PUBLIC_KEY", "not-a-secret");

        Map<String, Object> masked = ConfigValues.masked(config);

        assertThat(masked).containsEntry("apiKey", "****")
            .containsEntry("plain", "visible")
            .containsEntry("maxTokens", "****")
            .containsEntry("PUBLIC_KEY", "****");
        Map<String, Object> server = ((List<Map<String, Object>>) masked.get("servers")).getFirst();
        assertThat((Map<String, Object>) server.get("env"))
            .containsEntry("GITHUB_TOKEN", "****")
            .containsEntry("MODE", "dev")
            .containsEntry("db_password", "****");
        assertThat((Map<String, Object>) masked.get("nested"))
            .containsEntry("clientSecret", "****")
            .containsEntry("label", "ok");
        // 返回副本：调用方配置不被改写
        assertThat(config).containsEntry("apiKey", "sk-top");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesKeyOrderAtEveryLevel() {
        // HashMap 对单字符键按桶序(a<b<c)迭代;插入序刻意反向,键序丢失必红
        Map<String, Object> config = linked(
            "c", "3",
            "apiKey", "sk-top",
            "b", "2",
            "a", linked("c", "3", "b", "2", "a", "1"));

        Map<String, Object> masked = ConfigValues.masked(config);

        assertThat(masked.keySet()).containsExactly("c", "apiKey", "b", "a");
        assertThat(((Map<String, Object>) masked.get("a")).keySet())
            .containsExactly("c", "b", "a");
    }

    @Test
    void sensitiveKeyWithNullValueIsMasked() {
        Map<String, Object> masked = ConfigValues.masked(linked(
            "token", null,
            "optional", null));

        assertThat(masked).containsEntry("token", "****").containsEntry("optional", null);
    }

    @Test
    void nonStringNestedMapKeyFailsLoud() {
        Map<Object, Object> broken = new LinkedHashMap<>();
        broken.put(42, "value");
        Map<String, Object> config = linked("servers", List.of(broken));

        assertThatThrownBy(() -> ConfigValues.masked(config))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non-string key");
    }
}
