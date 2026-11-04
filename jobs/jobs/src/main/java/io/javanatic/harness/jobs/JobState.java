package io.javanatic.harness.jobs;

import java.time.Instant;

/**
 * 任务状态判别联合。终态 = Succeeded / Failed / Cancelled / Lost；Running 是唯一非终态。
 * Lost 只由重启后首次观测产生：前宿主生命期的 Running 任务无法证明仍在运行，
 * 如实标记失联而非冒充可续接——R1 对不可完成事实的诚实语义。
 */
public sealed interface JobState {

    /** 终态判定：Running 唯一非终态，其余四态皆终态（穷尽 switch，新增变体即编译期强制补齐）。 */
    default boolean terminal() {
        return switch (this) {
            case Running running -> false;
            case Succeeded succeeded -> true;
            case Failed failed -> true;
            case Cancelled cancelled -> true;
            case Lost lost -> true;
        };
    }

    /** 运行中；startedAt 为进程启动时刻。 */
    record Running(Instant startedAt) implements JobState {
        /** startedAt 必须在场。 */
        public Running {
            if (startedAt == null) {
                throw new IllegalArgumentException("startedAt must be present");
            }
        }
    }

    /** 成功退出。 */
    record Succeeded(int exitCode, Instant startedAt, Instant finishedAt) implements JobState {
        /** 时间序校验：结束不早于开始。 */
        public Succeeded {
            requireRange(startedAt, finishedAt);
        }
    }

    /** 失败退出；无有效退出码时 exitCode = -1（见 JobFailureReason）。 */
    record Failed(JobFailureReason reason, int exitCode, Instant startedAt, Instant finishedAt)
            implements JobState {
        /** reason 必须在场；时间序校验同上。 */
        public Failed {
            if (reason == null) {
                throw new IllegalArgumentException("reason must be present");
            }
            requireRange(startedAt, finishedAt);
        }
    }

    /** 被 cancel 收敛的任务；进程终止语义（TERM→KILL）归 Provider。 */
    record Cancelled(Instant startedAt, Instant finishedAt) implements JobState {
        /** 时间序校验同上。 */
        public Cancelled {
            requireRange(startedAt, finishedAt);
        }
    }

    /** 前宿主生命期的运行中任务在重启后标记失联；markedAt 为标记时刻。 */
    record Lost(Instant startedAt, Instant markedAt) implements JobState {
        /** 时间序校验：标记不早于开始。 */
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
