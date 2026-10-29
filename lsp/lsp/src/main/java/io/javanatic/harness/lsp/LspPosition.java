package io.javanatic.harness.lsp;

/** 零基位置；character 为 UTF-16 code units，不是字节或 Unicode code points。 */
public record LspPosition(int line, int character) {
    public LspPosition {
        if (line < 0 || character < 0) {
            throw new IllegalArgumentException("position must be nonnegative");
        }
    }
}
