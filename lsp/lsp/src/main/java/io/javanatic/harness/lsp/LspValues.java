package io.javanatic.harness.lsp;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** 值形状校验，不做文件访问与授权判断。 */
final class LspValues {
    private LspValues() { }

    static void uri(URI value) {
        Objects.requireNonNull(value, "uri");
        if (!value.isAbsolute() || value.isOpaque()) {
            throw new IllegalArgumentException("LSP URI must be absolute and hierarchical");
        }
    }

    static void hash(String value) {
        Objects.requireNonNull(value, "sha256");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 must be 64 lowercase hex characters");
        }
    }

    static void positive(Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
