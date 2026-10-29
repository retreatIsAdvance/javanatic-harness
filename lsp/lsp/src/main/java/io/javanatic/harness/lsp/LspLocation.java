package io.javanatic.harness.lsp;

import java.net.URI;
import java.util.Objects;

/** LS 定位结果；URI 不代表读取授权，返回位置不是源码内容。 */
public record LspLocation(URI uri, LspRange range) {
    public LspLocation {
        LspValues.uri(uri);
        Objects.requireNonNull(range, "range");
    }
}
