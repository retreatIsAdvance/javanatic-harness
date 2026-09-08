package io.javanatic.harness.kernel.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 行动作联合:变体校验与锚点工厂;互斥动作为类型级,非法组合不可表达。 */
class ConfigRowSpecTest {

    @Test
    void includeNormalizesConfig() {
        ConfigRowSpec.Include row = new ConfigRowSpec.Include("fs-local", null, null);
        assertThat(row.config()).isEmpty();
        assertThat(row.disabled()).isNull();
    }

    @Test
    void invalidPluginFailsLoud() {
        assertThatThrownBy(() -> new ConfigRowSpec.Include("", Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigRowSpec.Remove(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void insertRequiresAnchor() {
        assertThatThrownBy(() -> new ConfigRowSpec.Insert("x", Map.of(), null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("anchor");
        ConfigRowSpec.Anchor after = ConfigRowSpec.Anchor.after("tools");
        assertThat(after.target()).isEqualTo("tools");
        assertThat(after.before()).isFalse();
        assertThat(ConfigRowSpec.Anchor.before("tools").before()).isTrue();
    }
}
