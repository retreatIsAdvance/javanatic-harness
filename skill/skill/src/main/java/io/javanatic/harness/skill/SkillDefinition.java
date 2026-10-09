package io.javanatic.harness.skill;

import java.util.Objects;

/**
 * 技能全文：摘要 + 指示正文。正文 = 纯指示文本（it26 治理边界：不改变任何权限位，
 * 正文指示的命令仍走既有工具治理），装载即返回，无独立执行语义。
 */
public record SkillDefinition(SkillSummary summary, String body) {

    public SkillDefinition {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(body, "body");
        if (body.isBlank()) {
            throw new IllegalArgumentException("skill body must not be blank: '" + summary.name() + "'");
        }
    }

    /** @see SkillSummary#name() */
    public String name() {
        return summary.name();
    }
}
