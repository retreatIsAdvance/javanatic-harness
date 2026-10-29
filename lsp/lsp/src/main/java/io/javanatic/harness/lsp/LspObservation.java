package io.javanatic.harness.lsp;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 合格观察的值证明：前后锚相同、文档存在、窗口完整；不证明 Provider 实际执行或 LS 完成分析。
 * elapsedWait 由单调时钟测量；capturedAt 仅供落账，不用于 deadline。
 */
public record LspObservation(LspAnchor before, LspAnchor after, Duration requiredWait,
                             Duration elapsedWait, Instant capturedAt, LspEvidence evidence) {
    public LspObservation {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(elapsedWait, "elapsedWait");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(evidence, "evidence");
        LspValues.positive(requiredWait);
        if (!before.equals(after)) {
            throw new IllegalArgumentException("observation anchors changed");
        }
        if (after.document().sha256().isEmpty()) {
            throw new IllegalArgumentException("observed document must exist");
        }
        if (elapsedWait.compareTo(requiredWait) < 0) {
            throw new IllegalArgumentException("observation window is incomplete");
        }
    }
}
