package io.javanatic.harness.lsp.jdtls;

/** 单次 LSP 请求超时（该调用失败；会话本身不因此断裂）。 */
final class LspRequestTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LspRequestTimeoutException(String message) {
        super(message);
    }
}
