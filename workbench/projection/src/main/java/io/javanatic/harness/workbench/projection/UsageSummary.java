package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.message.TokenUsage;

import java.util.List;

/**
 * usage 视图:token 计量 rollup(适配器报告什么就记什么,未报告字段为 0)。
 *
 * @param total   全部 assistant/message 的计量合计;null = 全日志无任何报告
 * @param perTurn 按轮 rollup(与 turns 视图同序,且只含至少一条
 *                assistant/message 的轮);TurnUsage.usage=null 表示该轮未报告
 */
public record UsageSummary(TokenUsage total, List<TurnUsage> perTurn) {

    /** 构造时冻结列表。 */
    public UsageSummary {
        perTurn = List.copyOf(perTurn);
    }

    /** 一轮的计量;usage=null 表示该轮无任何 assistant/message 报告计量。 */
    public record TurnUsage(int turn, TokenUsage usage) {
    }
}
