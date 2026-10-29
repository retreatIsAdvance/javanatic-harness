package io.javanatic.harness.lsp;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 观察结果四态；只有 Observed 能携结果（包括空集合）。
 * 空诊断只称“拉取时点无诊断”，不能称“项目无错误”。取消原样抛 AbortedException。
 */
public sealed interface LspResult<T> {
    /** 不可变快照；诊断含警告，不以无 ERROR 冒充空集合。 */
    record Observed<T>(LspObservation observation, List<T> items) implements LspResult<T> {
        public Observed {
            Objects.requireNonNull(observation, "observation");
            items = List.copyOf(items);
            if (observation.evidence().origin() == LspEvidence.Origin.QUIET_WINDOW && !items.isEmpty()) {
                throw new IllegalArgumentException("quiet observation cannot contain results");
            }
        }
    }

    /** 未建立可用观察，不携空列表以免调用方误判成功。 */
    record NotReady<T>(NotReadyReason reason) implements LspResult<T> {
        public NotReady {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 同一查询目标的观察锚变化；不返回可被误用的旧条目。 */
    record Stale<T>(LspAnchor before, LspAnchor after) implements LspResult<T> {
        public Stale {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            if (!before.projectRoot().equals(after.projectRoot())
                || !before.document().uri().equals(after.document().uri())) {
                throw new IllegalArgumentException("stale anchors must identify the same query target");
            }
            if (before.equals(after)) {
                throw new IllegalArgumentException("stale anchors must differ");
            }
        }
    }

    /** 总期限耗尽，不是无诊断；进程死亡或协议错误另走异常。 */
    record TimedOut<T>(Duration timeout) implements LspResult<T> {
        public TimedOut {
            LspValues.positive(timeout);
        }
    }

    /** 就绪描述客户端状态，不是 LS 分析完成屏障。 */
    enum NotReadyReason {
        INITIALIZING, SYNCHRONIZING, CLOSED
    }
}
