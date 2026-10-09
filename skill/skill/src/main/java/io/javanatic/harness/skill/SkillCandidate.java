package io.javanatic.harness.skill;

import java.util.Objects;

/**
 * Provider 目录候选：摘要 + 优先级。合并规则单条（it26 内容 4 / T4 裁定 B）：
 * <b>priority 小者胜</b>（近者胜——项目覆盖用户、同层自有优先，rank 由 provider
 * 按来源根指派，如 100/200/400/500）；同名同 priority = 两根声明同一 rank，
 * 属配置错误，fail loud（对齐工具注册面「同层同名 fail loud」）。
 */
public record SkillCandidate(SkillSummary summary, int priority) {

    public SkillCandidate {
        Objects.requireNonNull(summary, "summary");
    }
}
