package io.javanatic.harness.delegation;

import java.time.Instant;

/**
 * 委派状态判别联合。终态 = Succeeded / Failed / Cancelled / Lost；**非终态有两个**——
 * Running 与 NeedsParentInput（暂停、可经 resume 继续）。问与败严格分界（it30
 * Lost-vs-Failed 教训的前置应用）：需要父输入不是失败，暂停态由 resume(answer)
 * 回到 Running，绝不落入 Failed。Lost 与 jobs 同义：前宿主生命期的运行中委派在
 * 重启后首次观测时如实标记失联，不冒充可续接（续接语义留停 2 裁决——与 jobs 不同，
 * 子代有 durable 会话，「失联」可能是可恢复的，恢复与重标的关系是停 2 议题）。
 */
public sealed interface DelegationState {

    /** 终态判定：Running 与 NeedsParentInput 非终态（穷尽 switch，新增变体编译期强制补齐）。 */
    default boolean terminal() {
        return switch (this) {
            case Running running -> false;
            case NeedsParentInput needsParentInput -> false;
            case Succeeded succeeded -> true;
            case Failed failed -> true;
            case Cancelled cancelled -> true;
            case Lost lost -> true;
        };
    }

    /** 暂停判定：仅 NeedsParentInput——resume 的唯一合法前态。 */
    default boolean paused() {
        return this instanceof NeedsParentInput;
    }

    /** 运行中；startedAt 为子代启动时刻。 */
    record Running(Instant startedAt) implements DelegationState {
        /** startedAt 必须在场。 */
        public Running {
            if (startedAt == null) {
                throw new IllegalArgumentException("startedAt must be present");
            }
        }
    }

    /** 暂停等待父输入；question 为子代待答问题原文（非空，逐字入账）。 */
    record NeedsParentInput(String question, Instant startedAt, Instant pausedAt) implements DelegationState {
        /** question 非空 + 时间序：暂停不早于启动。 */
        public NeedsParentInput {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must be non-blank");
            }
            requireRange(startedAt, pausedAt);
        }
    }

    /** 成功终局；resultSummary 为子代结果摘要（截断口径归 Provider）。 */
    record Succeeded(String resultSummary, Instant startedAt, Instant finishedAt) implements DelegationState {
        /** 摘要非空 + 时间序。 */
        public Succeeded {
            if (resultSummary == null || resultSummary.isBlank()) {
                throw new IllegalArgumentException("resultSummary must be non-blank");
            }
            requireRange(startedAt, finishedAt);
        }
    }

    /** 失败终局；问与败分界见 {@link DelegationFailureReason}。 */
    record Failed(DelegationFailureReason reason, Instant startedAt, Instant finishedAt)
            implements DelegationState {
        /** reason 在场 + 时间序。 */
        public Failed {
            if (reason == null) {
                throw new IllegalArgumentException("reason must be present");
            }
            requireRange(startedAt, finishedAt);
        }
    }

    /** 父发起的取消收敛终局（父取消子收敛——路线原词）。 */
    record Cancelled(Instant startedAt, Instant finishedAt) implements DelegationState {
        /** 时间序。 */
        public Cancelled {
            requireRange(startedAt, finishedAt);
        }
    }

    /** 前宿主生命期的运行中/暂停委派在重启后标记失联。 */
    record Lost(Instant startedAt, Instant markedAt) implements DelegationState {
        /** 时间序：标记不早于启动。 */
        public Lost {
            requireRange(startedAt, markedAt);
        }
    }

    private static void requireRange(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            throw new IllegalArgumentException("startedAt/finishedAt must be present");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
    }
}
