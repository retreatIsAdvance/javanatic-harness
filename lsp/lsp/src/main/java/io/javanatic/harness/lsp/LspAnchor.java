package io.javanatic.harness.lsp;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * JH 观察锚，不证明 LS 分析来源。projectHash 覆盖 Provider 明示的输入清单，非 LS 索引。
 * 每次启动使用新 incarnation；已观测输入变化时 generation 单调递增，不复用旧缓存代。
 */
public record LspAnchor(URI projectRoot, UUID incarnation, long generation,
                        String projectHash, LspDocument document) {
    public LspAnchor {
        LspValues.uri(projectRoot);
        Objects.requireNonNull(incarnation, "incarnation");
        Objects.requireNonNull(document, "document");
        LspValues.hash(projectHash);
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be nonnegative");
        }
    }
}
