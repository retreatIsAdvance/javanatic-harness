package io.javanatic.harness.lsp.tool;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** lsp-tool 行配置（边界 B28 输入面）：键白名单、坏值 fail loud、预算缺省。 */
class LspToolConfigTest {

    @Test
    void defaultsAreBoundedAndConsistent() {
        LspToolConfig config = LspToolConfig.parse(Map.of(), "lsp-tool");
        assertThat(config.timeoutSeconds()).isEqualTo(30);
        assertThat(config.waitSeconds()).isEqualTo(15);
        assertThat(config.maxDiagnostics()).isEqualTo(50);
        assertThat(config.maxLocations()).isEqualTo(50);
        assertThat(config.maxOutputBytes()).isEqualTo(262144);
    }

    @Test
    void unknownKeyFailsLoud() {
        assertThatThrownBy(() -> LspToolConfig.parse(Map.of("bogus", 1), "lsp-tool"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lsp-tool")
            .hasMessageContaining("bogus");
    }

    @Test
    void nonPositiveBudgetFailsLoud() {
        assertThatThrownBy(() -> LspToolConfig.parse(Map.of("timeoutSeconds", 0), "lsp-tool"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("timeoutSeconds");
    }

    @Test
    void nonNumericBudgetFailsLoud() {
        assertThatThrownBy(() -> LspToolConfig.parse(Map.of("maxDiagnostics", true), "lsp-tool"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("maxDiagnostics");
    }

    @Test
    void waitWindowExceedingTimeoutFailsLoud() {
        assertThatThrownBy(() -> LspToolConfig.parse(
            Map.of("timeoutSeconds", 10, "waitSeconds", 20), "lsp-tool"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("waitSeconds");
    }

    @Test
    void explicitValuesOverrideDefaults() {
        LspToolConfig config = LspToolConfig.parse(Map.of(
            "timeoutSeconds", 45, "waitSeconds", 30, "maxDiagnostics", 3,
            "maxLocations", 4, "maxOutputBytes", 512), "lsp-tool");
        assertThat(config.timeoutSeconds()).isEqualTo(45);
        assertThat(config.waitSeconds()).isEqualTo(30);
        assertThat(config.maxDiagnostics()).isEqualTo(3);
        assertThat(config.maxLocations()).isEqualTo(4);
        assertThat(config.maxOutputBytes()).isEqualTo(512);
    }
}
