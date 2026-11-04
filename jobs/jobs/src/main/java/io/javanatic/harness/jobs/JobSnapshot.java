package io.javanatic.harness.jobs;

import io.javanatic.harness.kernel.brand.Id;

/**
 * 任务全量快照：job/snapshot 事件与查询面共用的只读事实。
 * commandSummary 由 Provider 从原命令生成（截断与脱敏口径归 Provider / Consumer）；
 * capturedBytes 是输出捕获的当前字节上界，也是 read 游标的合法区间上界。
 * 事件消费方按 id fold 末值胜（无 generation 字段，日志序即代）。
 */
public record JobSnapshot(Id<Job> id, String commandSummary, JobState state, long capturedBytes,
                          boolean outputTruncated) {

    /** 快照即校验：id / 摘要 / 状态在场，捕获字节数非负。 */
    public JobSnapshot {
        if (id == null) {
            throw new IllegalArgumentException("id must be present");
        }
        if (commandSummary == null || commandSummary.isBlank()) {
            throw new IllegalArgumentException("commandSummary must be non-blank");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must be present");
        }
        if (capturedBytes < 0) {
            throw new IllegalArgumentException("capturedBytes must be non-negative");
        }
    }
}
