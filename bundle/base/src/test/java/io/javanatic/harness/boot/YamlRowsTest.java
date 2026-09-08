package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.ConfigRowSpec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** YAML 装载边界:行解析、矛盾组合拦截、bundle 资源发现。 */
class YamlRowsTest {

    @Test
    void parsesVariantsFromFlatYaml() {
        List<ConfigRowSpec> rows = YamlRows.parseRows(new org.yaml.snakeyaml.Yaml()
            .<java.util.Map<String, Object>>load(new ByteArrayInputStream("""
                rows:
                  - plugin: a
                    config: {k: v}
                  - plugin: b
                    replace: true
                  - plugin: c
                    remove: true
                  - plugin: d
                    after: a
                  - plugin: e
                    disabled: ${env:X} != null
                """.getBytes(StandardCharsets.UTF_8))).get("rows"), "test");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).isInstanceOf(ConfigRowSpec.Include.class);
        assertThat(rows.get(1)).isInstanceOf(ConfigRowSpec.Replace.class);
        assertThat(rows.get(2)).isInstanceOf(ConfigRowSpec.Remove.class);
        assertThat(rows.get(3)).isInstanceOf(ConfigRowSpec.Insert.class);
        ConfigRowSpec.Include fifth = (ConfigRowSpec.Include) rows.get(4);
        assertThat(fifth.disabled()).isEqualTo("${env:X} != null");
    }

    @Test
    void contradictoryRowsFailLoud() {
        String yaml = """
            rows:
              - plugin: a
                replace: true
                remove: true
            """;
        Object raw = new org.yaml.snakeyaml.Yaml()
            .<java.util.Map<String, Object>>load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).get("rows");
        assertThatThrownBy(() -> YamlRows.parseRows(raw, "test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("同时 replace 与 remove");
    }

    @Test
    void baseBundleDiscoveredFromClasspath() throws Exception {
        List<YamlRows.Bundle> bundles = YamlRows.discoverBundles();
        assertThat(bundles.stream().map(YamlRows.Bundle::name)).contains("base");
        YamlRows.Bundle base = bundles.stream()
            .filter(b -> "base".equals(b.name())).findFirst().orElseThrow();
        assertThat(base.rows().stream().map(ConfigRowSpec::plugin)).contains("agent-loop");
    }
}
