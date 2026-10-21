package io.javanatic.harness.web;

import java.util.Objects;

/**
 * 带稳定错误码的取回/搜索失败（unchecked：工具面统一翻译成错误结果）。
 *
 * <p>消息是对模型/用户可见的诊断，禁止回显凭据与完整请求头；需要定位时
 * 只给主机、状态码与内容类型。取消不包装为 WebException（穿透 AbortSignal
 * 的 {@code AbortedException}）。
 */
public final class WebException extends RuntimeException {

    private final WebErrorCode code;

    /**
     * @param code 稳定错误码
     * @param message 可诊断消息（不含凭据）
     */
    public WebException(WebErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * @param code 稳定错误码
     * @param message 可诊断消息（不含凭据）
     * @param cause 底层原因
     */
    public WebException(WebErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /** @return 稳定错误码 */
    public WebErrorCode code() {
        return code;
    }
}
