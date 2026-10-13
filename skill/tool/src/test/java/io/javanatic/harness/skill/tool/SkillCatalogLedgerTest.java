package io.javanatic.harness.skill.tool;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.skill.SkillCandidate;
import io.javanatic.harness.skill.SkillDefinition;
import io.javanatic.harness.skill.SkillPlugin;
import io.javanatic.harness.skill.SkillProvider;
import io.javanatic.harness.skill.SkillService;
import io.javanatic.harness.skill.SkillSummary;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.persistence.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 目录装载行为：变更才落账、无技能零噪声、空事件退役、截断、目录段三态、codec 往返。 */
class SkillCatalogLedgerTest {

    /** 可变目录桩：测试中改目录模拟轮间变化。 */
    private static final class MutableProvider implements SkillProvider {
        private List<SkillCandidate> catalog;

        private MutableProvider(SkillCandidate... candidates) {
            this.catalog = List.of(candidates);
        }

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public List<SkillCandidate> catalog() {
            return catalog;
        }

        @Override
        public Optional<SkillDefinition> load(String name) {
            return catalog().stream()
                .filter(c -> c.summary().name().equals(name))
                .findFirst()
                .map(c -> new SkillDefinition(c.summary(), "正文:" + c.summary().source()));
        }
    }

    private static SkillCandidate candidate(String name, String description, String source, int priority) {
        return new SkillCandidate(new SkillSummary(name, description, source), priority);
    }

    /** 服务经 SkillPlugin 装配（F5 公开入口的真实消费路径），provider 由测试挂载。 */
    private static SkillService serviceWith(SkillProvider provider) {
        Runtime rt = new Runtime();
        new PluginLoader().loadAll(rt, List.of(new SkillPlugin()));
        rt.root().require(SkillService.KEY).register(provider);
        return rt.root().require(SkillService.KEY);
    }

    private static List<SkillCatalogEvent> events(Session session) {
        return session.events().stream()
            .map(e -> e.event())
            .filter(SkillCatalogEvent.class::isInstance)
            .map(SkillCatalogEvent.class::cast)
            .toList();
    }

    @Test
    void appendsOnChangeOnlyAndRetiresOnEmpty() {
        MutableProvider provider = new MutableProvider(
            candidate("deploy", "部署指南", "project", 100));
        SkillService skills = serviceWith(provider);
        Session session = Session.create(Session.newId("ledger"), null, null);

        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(events(session)).hasSize(1);

        // 不变更：零新增（每轮刷新对 R1 的落账面稳定）
        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(events(session)).hasSize(1);

        // 目录变更：新事件
        provider.catalog = List.of(
            candidate("deploy", "部署指南", "project", 100),
            candidate("review", "评审清单", "user", 400));
        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(events(session)).hasSize(2);

        // 清空：落空事件退役（dsh 空目录语义）
        provider.catalog = List.of();
        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(events(session)).hasSize(3);
        assertThat(events(session).get(2).entries()).isEmpty();
    }

    @Test
    void emptyCatalogWithNoPriorEventsStaysSilent() {
        SkillService skills = serviceWith(new MutableProvider());
        Session session = Session.create(Session.newId("silent"), null, null);
        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(events(session)).isEmpty();
        assertThat(SkillToolPlugin.section(session)).isEmpty();
    }

    @Test
    void longDescriptionIsTruncatedAtLimit() {
        String longText = "x".repeat(600);
        SkillService skills = serviceWith(new MutableProvider(
            candidate("big", longText, "project", 100)));
        Session session = Session.create(Session.newId("trunc"), null, null);
        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(events(session).getFirst().entries().getFirst().description())
            .hasSize(SkillToolPlugin.DESCRIPTION_LIMIT)
            .isEqualTo("x".repeat(SkillToolPlugin.DESCRIPTION_LIMIT));
    }

    /** 非缺省 limit 参与截断（停 3 复核 f1：此前用例只走 DESCRIPTION_LIMIT，调用方口径无咬合）。 */
    @Test
    void truncationHonorsCallerSuppliedLimit() {
        SkillService skills = serviceWith(new MutableProvider(
            candidate("big", "x".repeat(600), "project", 100)));
        Session session = Session.create(Session.newId("trunc-explicit"), null, null);
        SkillToolPlugin.refresh(session, skills, 10);
        assertThat(events(session).getFirst().entries().getFirst().description())
            .hasSize(10)
            .isEqualTo("x".repeat(10));
    }

    @Test
    void sectionRendersHeaderEntriesAndSource() {
        MutableProvider provider = new MutableProvider(
            candidate("deploy", "部署指南", "project", 100));
        SkillService skills = serviceWith(provider);
        Session session = Session.create(Session.newId("section"), null, null);
        assertThat(SkillToolPlugin.section(session)).isEmpty();

        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        String section = SkillToolPlugin.section(session);
        assertThat(section)
            .contains("先装载再行动")
            .contains("不得仅凭摘要推断指令")
            .contains("- deploy — 部署指南（来源: project）");

        // 退役后段消失
        provider.catalog = List.of();
        SkillToolPlugin.refresh(session, skills, SkillToolPlugin.DESCRIPTION_LIMIT);
        assertThat(SkillToolPlugin.section(session)).isEmpty();
    }

    @Test
    void catalogEventRoundTripsThroughCodec() {
        SkillCatalogEvent original = new SkillCatalogEvent(42, List.of(
            new SkillCatalogEvent.Entry("deploy", "部署指南", "project"),
            new SkillCatalogEvent.Entry("review", "评审", "user")));
        SkillCatalogCodec codec = new SkillCatalogCodec();
        JsonValue.Obj wire = codec.write(original);
        assertThat(codec.read(wire)).isEqualTo(original);
        assertThat(codec.type()).isEqualTo("skill/catalog");
    }
}
