package io.javanatic.harness.lsp;

import java.util.Objects;

/** 左闭右开范围；空范围合法，终点不能在起点之前。 */
public record LspRange(LspPosition start, LspPosition end) {
    public LspRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.line() < start.line() || (end.line() == start.line() && end.character() < start.character())) {
            throw new IllegalArgumentException("range end precedes start");
        }
    }
}
