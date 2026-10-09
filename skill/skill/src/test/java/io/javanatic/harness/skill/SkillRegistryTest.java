package io.javanatic.harness.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SkillRegistry 行为：priority 归并 / 遮蔽 / 同 rank fail loud / load 委托胜者 / provider id 唯一。 */
class SkillRegistryTest {

    private static SkillSummary summary(String name, String source) {
        return new SkillSummary(name, name + " 描述", source);
    }

    private static SkillProvider provider(String id, SkillCandidate... candidates) {
        return new SkillProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<SkillCandidate> catalog() {
                return List.of(candidates);
            }

            @Override
            public Optional<SkillDefinition> load(String name) {
                boolean offers = catalog().stream().anyMatch(c -> c.summary().name().equals(name));
                return offers
                    ? Optional.of(new SkillDefinition(
                        catalog().stream().filter(c -> c.summary().name().equals(name)).findFirst().orElseThrow().summary(),
                        "正文:" + id + ":" + name))
                    : Optional.empty();
            }
        };
    }

    @Test
    void lowerPriorityWinsAndCatalogIsSortedByName() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(provider("user", new SkillCandidate(summary("beta", "user"), 400),
            new SkillCandidate(summary("alpha", "user"), 400)));
        registry.register(provider("project", new SkillCandidate(summary("alpha", "project"), 100)));
        SkillCatalog catalog = registry.catalog();
        assertThat(catalog.skills()).extracting(SkillSummary::name).containsExactly("alpha", "beta");
        assertThat(catalog.skills().get(0).source()).isEqualTo("project");
    }

    @Test
    void loadDelegatesToWinningProviderNotRegistrationOrder() {
        SkillRegistry registry = new SkillRegistry();
        // user 先注册、priority 大——胜者必须是 project provider，正文来源可辨
        registry.register(provider("user", new SkillCandidate(summary("deploy", "user"), 400)));
        registry.register(provider("project", new SkillCandidate(summary("deploy", "project"), 100)));
        assertThat(registry.load("deploy")).hasValueSatisfying(
            d -> assertThat(d.body()).isEqualTo("正文:project:deploy"));
    }

    @Test
    void missingNameLoadsEmptyWithoutThrowing() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(provider("only", new SkillCandidate(summary("here", "project"), 100)));
        assertThat(registry.load("absent")).isEmpty();
    }

    @Test
    void duplicateRankAcrossRootsFailsLoud() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(provider("a", new SkillCandidate(summary("dup", "root-x"), 100)));
        registry.register(provider("b", new SkillCandidate(summary("dup", "root-y"), 100)));
        assertThatThrownBy(registry::catalog)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("duplicate skill rank")
            .hasMessageContaining("root-x")
            .hasMessageContaining("root-y");
    }

    @Test
    void duplicateProviderIdFailsLoud() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(provider("skill-local"));
        assertThatThrownBy(() -> registry.register(provider("skill-local")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("skill-local");
    }

    @Test
    void unregisterRemovesProviderEntries() {
        SkillRegistry registry = new SkillRegistry();
        var disposable = registry.register(provider("p", new SkillCandidate(summary("s", "project"), 100)));
        assertThat(registry.catalog().skills()).hasSize(1);
        disposable.close();
        assertThat(registry.catalog()).isEqualTo(SkillCatalog.empty());
        assertThat(registry.load("s")).isEmpty();
    }

    @Test
    void contractRejectsBlankNameAndBody() {
        assertThatThrownBy(() -> new SkillSummary(" ", "d", "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillDefinition(summary("a", "s"), " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("body");
    }
}
