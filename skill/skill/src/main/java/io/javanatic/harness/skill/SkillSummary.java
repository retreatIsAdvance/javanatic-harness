package io.javanatic.harness.skill;

import java.util.Objects;

/** 技能摘要：目录面条目（name / 描述 / 来源根）。来源根模型可见、可解释（it26 内容 4）。 */
public record SkillSummary(String name, String description, String source) {

    public SkillSummary {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(source, "source");
        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name must not be blank");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("skill source must not be blank: '" + name + "'");
        }
    }
}
