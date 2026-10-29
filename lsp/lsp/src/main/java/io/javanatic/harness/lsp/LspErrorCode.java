package io.javanatic.harness.lsp;

/** 非观察态的失败；timeout 在 LspResult，取消保持 AbortedException。 */
public enum LspErrorCode {
    CONNECTION, PROTOCOL, UNSUPPORTED, IO, CLOSE_FAILED
}
