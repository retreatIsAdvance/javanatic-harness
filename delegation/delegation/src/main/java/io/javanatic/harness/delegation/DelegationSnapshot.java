package io.javanatic.harness.delegation;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;

/**
 * 委派全量快照：delegation/snapshot 事件与查询面共用的只读事实。
 * childSessionId 是会话树链接（评审重点②）——子代 durable 会话的 id；
 * budgetTokens 随快照入账（it31 停 2 增补：预算进载荷 → 回放期聚合树总账，零运行期隐藏数——
 * R1 依据见停 2 设计 §2）。
 * `--sessions` 呈现与 R1 树回放经它导航；父快照不内嵌子事件（账本各归各会话，
 * 树结构经 id 关联，不复制账面）。事件消费方按 id fold 末值胜（无 generation，
 * 日志序即代，job/snapshot 同精神）。
 */
public record DelegationSnapshot(Id<Delegation> id, String taskSummary, Id<Session> childSessionId,
                                 long budgetTokens, DelegationState state) {

    /** 快照即校验：id / 摘要 / 子会话 / 状态在场。 */
    public DelegationSnapshot {
        if (id == null) {
            throw new IllegalArgumentException("id must be present");
        }
        if (taskSummary == null || taskSummary.isBlank()) {
            throw new IllegalArgumentException("taskSummary must be non-blank");
        }
        if (childSessionId == null) {
            throw new IllegalArgumentException("childSessionId must be present");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must be present");
        }
    }
}
