package io.javanatic.harness.skill;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构性质：合并结果与 provider 注册顺序无关（快照确定性的 R1 侧依据）。 */
class SkillCatalogMergePropertyTest {

    private record FixedProvider(String id, List<SkillCandidate> catalog) implements SkillProvider {
        @Override
        public String id() {
            return id;
        }

        @Override
        public List<SkillCandidate> catalog() {
            return catalog;
        }

        @Override
        public java.util.Optional<SkillDefinition> load(String name) {
            return java.util.Optional.empty();
        }
    }

    private static SkillCandidate candidate(String name, String source, int priority) {
        return new SkillCandidate(new SkillSummary(name, name + " 描述", source), priority);
    }

    @Property
    void mergeResultIndependentOfRegistrationOrder(
        @ForAll @Size(min = 2, max = 6) List<Integer> seed) {
        List<SkillProvider> providers = new ArrayList<>();
        for (int i = 0; i < seed.size(); i++) {
            int rank = 100 * (i + 1);
            providers.add(new FixedProvider("p" + i,
                List.of(candidate("shared-" + seed.get(i), "root-" + i, rank),
                    candidate("own-" + i, "root-" + i, rank))));
        }
        List<String> entriesFromFirstOrder = null;
        // 逐个轮转注册序：任一排列下目录的 (名字, 来源) 对一致——「取错胜出源」变体在此变红
        for (int rotation = 0; rotation < providers.size(); rotation++) {
            SkillRegistry registry = new SkillRegistry();
            for (int i = 0; i < providers.size(); i++) {
                registry.register(providers.get((rotation + i) % providers.size()));
            }
            List<String> entries = registry.catalog().skills().stream()
                .map(s -> s.name() + "@" + s.source())
                .toList();
            if (entriesFromFirstOrder == null) {
                entriesFromFirstOrder = entries;
            } else {
                assertThat(entries).isEqualTo(entriesFromFirstOrder);
            }
        }
        assertThat(entriesFromFirstOrder).isNotEmpty();
    }
}
