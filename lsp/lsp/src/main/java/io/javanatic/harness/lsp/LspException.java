package io.javanatic.harness.lsp;

import java.util.Objects;

/** Provider 失败分档，不把传输故障或回收失败转成成功空结果。 */
public final class LspException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final LspErrorCode code;

    public LspException(LspErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public LspException(LspErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public LspErrorCode code() {
        return code;
    }
}
