package io.javanatic.harness.lsp;

import java.util.Objects;
import java.util.Optional;

/** 查询文档的一条诊断；不丢警告。version 属批次 LspEvidence，空数组亦能保留。 */
public record LspDiagnostic(LspRange range, Severity severity, Optional<String> code,
                            Optional<String> source, String message) {
    public LspDiagnostic {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
    }

    /** 缺失严重级别不默认成 ERROR。 */
    public enum Severity {
        ERROR, WARNING, INFORMATION, HINT, UNSPECIFIED
    }
}
