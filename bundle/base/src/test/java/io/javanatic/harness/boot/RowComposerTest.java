package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.ConfigRowSpec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 行合并语义:Include 宽松/Replace 严格/Remove/Insert 锚点/重复与缺失 fail loud。 */
class RowComposerTest {

    @Test
    void includeAppendsOrReplacesInPlace() {
        List<ConfigRowSpec> rows = RowComposer.apply(List.of(),
            List.of(ConfigRowSpec.Include.of("a", Map.of("v", 1))));
        rows = RowComposer.apply(rows, List.of(ConfigRowSpec.Include.of("a", Map.of("v", 2)),
            ConfigRowSpec.Include.of("b", Map.of())));
        assertThat(rows.stream().map(ConfigRowSpec::plugin).toList()).containsExactly("a", "b");
        assertThat(rows.getFirst().config()).containsEntry("v", 2);
    }

    @Test
    void replaceRequiresTargetAndRemovesIt() {
        List<ConfigRowSpec> rows = List.of(ConfigRowSpec.Include.of("a", Map.of()));
        List<ConfigRowSpec> replaced = RowComposer.apply(rows,
            List.of(new ConfigRowSpec.Replace("a", Map.of("v", 9), null)));
        assertThat(replaced.getFirst().config()).containsEntry("v", 9);
        assertThatThrownBy(() -> RowComposer.apply(List.of(),
            List.of(new ConfigRowSpec.Replace("ghost", Map.of(), null))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Replace target not present");
        List<ConfigRowSpec> removed = RowComposer.apply(rows,
            List.of(new ConfigRowSpec.Remove("a")));
        assertThat(removed).isEmpty();
        assertThatThrownBy(() -> RowComposer.apply(List.of(),
            List.of(new ConfigRowSpec.Remove("ghost"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Remove target not present");
    }

    @Test
    void insertHonorsAnchorsAndRejectsMissing() {
        List<ConfigRowSpec> rows = List.of(
            ConfigRowSpec.Include.of("a", Map.of()), ConfigRowSpec.Include.of("b", Map.of()));
        List<ConfigRowSpec> after = RowComposer.apply(rows,
            List.of(new ConfigRowSpec.Insert("x", Map.of(), null, ConfigRowSpec.Anchor.after("a"))));
        assertThat(after.stream().map(ConfigRowSpec::plugin).toList()).containsExactly("a", "x", "b");
        List<ConfigRowSpec> before = RowComposer.apply(rows,
            List.of(new ConfigRowSpec.Insert("y", Map.of(), null, ConfigRowSpec.Anchor.before("b"))));
        assertThat(before.stream().map(ConfigRowSpec::plugin).toList()).containsExactly("a", "y", "b");
        assertThatThrownBy(() -> RowComposer.apply(rows,
            List.of(new ConfigRowSpec.Insert("z", Map.of(), null, ConfigRowSpec.Anchor.after("nope")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("anchor not present");
    }

    @Test
    void duplicateResultFailsLoud() {
        List<ConfigRowSpec> base = List.of(
            ConfigRowSpec.Include.of("a", Map.of()), ConfigRowSpec.Include.of("b", Map.of()));
        // after 锚点插入到 a 后,再插 b 前 → x 出现两次?不会;构造双 a:include 已替换。
        // 直接构造重复:patch 把 a 插到 a 之后 → 同 id 双行
        assertThatThrownBy(() -> RowComposer.apply(base,
            List.of(new ConfigRowSpec.Insert("a", Map.of(), null, ConfigRowSpec.Anchor.after("a")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("duplicate plugin row");
    }
}
