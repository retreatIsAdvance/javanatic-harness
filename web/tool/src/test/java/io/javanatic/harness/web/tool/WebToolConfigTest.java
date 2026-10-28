package io.javanatic.harness.web.tool;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** web-tool 配置：缺省值、数字字符串宽容、键白名单与边界 fail loud。 */
class WebToolConfigTest {

    @Test
    void defaultsMatchTheDocumentedPlanValues() {
        WebToolConfig config = WebToolConfig.defaults();
        assertThat(config.maxSourceChars()).isEqualTo(524_288);
        assertThat(config.maxOutputBytes()).isEqualTo(262_144);
        assertThat(config.maxResults()).isEqualTo(8);
    }

    @Test
    void parsesNumbersFromStringsAndNumbers() {
        WebToolConfig config = WebToolConfig.parse(Map.of(
            "maxSourceChars", "1024",
            "maxOutputBytes", 2048,
            "maxResults", "3"), "web-tool");
        assertThat(config.maxSourceChars()).isEqualTo(1024);
        assertThat(config.maxOutputBytes()).isEqualTo(2048);
        assertThat(config.maxResults()).isEqualTo(3);
    }

    @Test
    void unknownKeyFailsLoudWithPluginAndKey() {
        assertThatIllegalStateException()
            .isThrownBy(() -> WebToolConfig.parse(Map.of("maxBytes", 1), "web-tool"))
            .withMessageContaining("web-tool")
            .withMessageContaining("unknown key 'maxBytes'");
    }

    @Test
    void outOfRangeAndNonNumericValuesFailLoud() {
        assertThatIllegalStateException()
            .isThrownBy(() -> WebToolConfig.parse(Map.of("maxSourceChars", 0), "web-tool"))
            .withMessageContaining("maxSourceChars");
        assertThatIllegalStateException()
            .isThrownBy(() -> WebToolConfig.parse(
                Map.of("maxOutputBytes", 64 * 1024 * 1024 + 1), "web-tool"))
            .withMessageContaining("maxOutputBytes");
        assertThatIllegalStateException()
            .isThrownBy(() -> WebToolConfig.parse(Map.of("maxResults", 51), "web-tool"))
            .withMessageContaining("maxResults");
        assertThatIllegalStateException()
            .isThrownBy(() -> WebToolConfig.parse(Map.of("maxResults", "eight"), "web-tool"))
            .withMessageContaining("not numeric");
        assertThatIllegalStateException()
            .isThrownBy(() -> WebToolConfig.parse(Map.of("maxResults", true), "web-tool"))
            .withMessageContaining("must be numeric");
    }

    @Test
    void constructorRejectsOutOfRangeValues() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new WebToolConfig(-1, 1, 1));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new WebToolConfig(1, 0, 1));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new WebToolConfig(1, 1, -5));
    }
}
