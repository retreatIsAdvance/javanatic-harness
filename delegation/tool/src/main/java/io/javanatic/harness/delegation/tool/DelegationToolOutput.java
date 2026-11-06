package io.javanatic.harness.delegation.tool;

import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.DelegationState;
import java.util.List;

/**
 * 委派面到结果文本的逐字映射（承接 JobToolOutput 纪律）：问与败逐字分界——
 * NeedsParentInput 恒称「等待父输入（待答问题逐字）」，不写成「失败」；Lost 恒称
 * 「失联（前宿主生命期，不冒充可续接）」。树链接（childSessionId）随每行可见，
 * 模型可据此解释会话树。模板由测试钉死，禁词突变必红。
 */
final class DelegationToolOutput {

    private DelegationToolOutput() {
    }

    /** 单条快照行：id / 摘要 / 状态词 / 预算 / 子会话链接。 */
    static String line(DelegationSnapshot snapshot) {
        return "delegation " + snapshot.id().value() + " " + stateWord(snapshot.state())
                + " budget=" + snapshot.budgetTokens()
                + " childSession=" + snapshot.childSessionId().value()
                + " task=" + snapshot.taskSummary();
    }

    /** 列表渲染（按启动序，全状态含终局与 Lost）。 */
    static String list(List<DelegationSnapshot> snapshots) {
        StringBuilder text = new StringBuilder("本会话委派（" + snapshots.size() + " 条，按启动序）：\n");
        for (DelegationSnapshot snapshot : snapshots) {
            text.append("- ").append(line(snapshot)).append('\n');
        }
        return text.toString();
    }

    /** delegate 启动返回：立即返名语义 + 观察指引（异步完成，不在此等待）。 */
    static String started(DelegationSnapshot snapshot) {
        return "已派出（异步）：" + line(snapshot)
                + "\n终局/暂停经 delegate_list 观察；子代提问时状态为 needs_parent_input，"
                + "用 delegate_answer 答复（答复 = 子代下一轮 user message）。";
    }

    /** 状态词：六态判别联合的稳定文案。 */
    static String stateWord(DelegationState state) {
        return switch (state) {
            case DelegationState.Running running -> "running";
            case DelegationState.NeedsParentInput needs ->
                "needs_parent_input(等待父输入——问题逐字：「" + needs.question() + "」)";
            case DelegationState.Succeeded succeeded ->
                "succeeded(结果：" + succeeded.resultSummary() + ")";
            case DelegationState.Failed failed -> "failed(" + failed.reason().name().toLowerCase() + ")";
            case DelegationState.Cancelled cancelled -> "cancelled";
            case DelegationState.Lost lost -> "lost(失联——前宿主生命期委派，不冒充可续接)";
        };
    }

    /** 工具层错误文本（稳定前缀 + 事实，不猜测原因）。 */
    static String error(String message) {
        return "委派错误：" + message;
    }
}
