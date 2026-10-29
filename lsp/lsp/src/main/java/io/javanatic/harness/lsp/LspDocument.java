package io.javanatic.harness.lsp;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** JH 采样源码字节的 hash；空值仅表示文件确认不存在，不表示读取失败或 LS 未分析。 */
public record LspDocument(URI uri, Optional<String> sha256) {
    public LspDocument {
        LspValues.uri(uri);
        Objects.requireNonNull(sha256, "sha256");
        sha256.ifPresent(LspValues::hash);
    }
}
