package io.javanatic.harness.web;

import java.util.Objects;

/**
 * 一次抓取的结果。非 2xx 也走这里（状态码是取回事实）；只有无法安全取回
 * 或表示时才抛 {@link WebException}。
 *
 * @param url 允许重定向后的最终 URL（去掉 fragment）
 * @param statusCode HTTP 状态码
 * @param contentType 原始 {@code Content-Type} 头（已通过白名单校验；停 3 增补：
 *     消费面需要向模型外显正文类型，避免把提取后的 HTML 文本误认成原文）
 * @param body 分类后的正文
 * @param truncated 是否因正文上限被截断（内容事实，不是错误）
 */
public record WebFetchResult(String url, int statusCode, String contentType,
                             WebFetchBody body, boolean truncated) {

    /** @throws IllegalArgumentException 状态码不在 100–599 时 */
    public WebFetchResult {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode out of range: " + statusCode);
        }
    }
}
