package io.javanatic.harness.kernel.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 受限插值:白名单源/默认值/嵌入串/整体 null 语义/比较式/白名单外 fail loud。 */
class ExpressionResolverTest {

    private final ExpressionResolver resolver = new ExpressionResolver((source, name) -> switch (source) {
        case "env" -> "SET".equals(name) ? java.util.Optional.of("v1") : java.util.Optional.empty();
        case "props" -> "p.key".equals(name) ? java.util.Optional.of("pv") : java.util.Optional.empty();
        case "cwd" -> java.util.Optional.of("/work");
        case "home" -> java.util.Optional.of("/home/u");
        default -> null;
    });

    @Test
    void interpolatesWhitelistedSources() {
        assertThat(resolver.interpolate("${env:SET}")).isEqualTo("v1");
        assertThat(resolver.interpolate("${props:p.key}")).isEqualTo("pv");
        assertThat(resolver.interpolate("${cwd}/x")).isEqualTo("/work/x");
        assertThat(resolver.interpolate("${home}/.harness")).isEqualTo("/home/u/.harness");
        assertThat(resolver.interpolate("无表达式")).isEqualTo("无表达式");
    }

    @Test
    void defaultValueCoversUnset() {
        assertThat(resolver.interpolate("${env:UNSET:-https://api}")).isEqualTo("https://api");
        assertThat(resolver.interpolate("pre-${env:UNSET:-d}-post")).isEqualTo("pre-d-post");
    }

    @Test
    void wholeSingleUnsetYieldsNullButEmbeddedYieldsEmpty() {
        assertThat(resolver.interpolate("${env:UNSET}")).isNull();
        assertThat(resolver.interpolate("a${env:UNSET}b")).isEqualTo("ab");
    }

    @Test
    void nonStringValuesPassThrough() {
        Map<String, Object> config = new HashMap<>();
        config.put("timeout", 30L);
        config.put("url", "${env:SET}");
        assertThat(resolver.interpolate(config))
            .containsEntry("timeout", 30L)
            .containsEntry("url", "v1");
    }

    @Test
    void comparisonsAgainstNullAndLiterals() {
        assertThat(resolver.evaluate("${env:SET} != null")).isTrue();
        assertThat(resolver.evaluate("${env:UNSET} != null")).isFalse();
        assertThat(resolver.evaluate("${env:UNSET} == null")).isTrue();
        assertThat(resolver.evaluate("${env:SET} == 'v1'")).isTrue();
        assertThat(resolver.evaluate("${env:SET} != 'x'")).isTrue();
    }

    @Test
    void bareOperandIsTruthiness() {
        assertThat(resolver.evaluate("${env:SET}")).isTrue();
        assertThat(resolver.evaluate("${env:UNSET}")).isFalse();
    }

    @Test
    void unknownSourceFailsLoud() {
        assertThatThrownBy(() -> resolver.interpolate("${exec:rm -rf}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> resolver.interpolate("${env:}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void unsupportedOperatorsFailLoud() {
        assertThatThrownBy(() -> resolver.evaluate("${env:SET} > 5"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("operator not allowed");
        assertThatThrownBy(() -> resolver.evaluate("${env:SET} = 5"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("operator not allowed");
    }

    @Test
    void standardSourcesReadEnvironment() {
        Map<String, String> env = System.getenv();
        String anyKey = env.keySet().stream().findFirst().orElseThrow();
        assertThat(ExpressionResolver.standard().interpolate("${env:" + anyKey + "}"))
            .isEqualTo(env.get(anyKey));
    }
}
