package io.javanatic.harness.skill;

import java.util.List;

/** 目录快照：合并后按名排序的条目（每名恰一条——同名已被优先级归并）。 */
public record SkillCatalog(List<SkillSummary> skills) {

    public SkillCatalog {
        skills = List.copyOf(skills);
    }

    /** 空目录（无技能或缺省态）。 */
    public static SkillCatalog empty() {
        return new SkillCatalog(List.of());
    }
}
