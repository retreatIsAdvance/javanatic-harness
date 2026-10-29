package io.javanatic.harness.lsp;

import java.util.Objects;

/** 定位请求复用同一个文档观察预算，不另开 deadline。 */
public record LspPositionQuery(LspQuery query, LspPosition position) {
    public LspPositionQuery {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(position, "position");
    }
}
