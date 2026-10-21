package io.javanatic.harness.web;

import java.util.Objects;

/**
 * 一次抓取请求。URL 的完整安全策略由 Provider 在解析时执行（https、长度、
 * userinfo、IDN、地址闸）；本记录只保证非 null，空串同样交由 Provider 的
 * URL 策略拒绝（{@code WEB_INVALID_URL}）。
 *
 * @param url 目标 URL
 */
public record WebFetchRequest(String url) {

    /** @throws NullPointerException url 为 null 时 */
    public WebFetchRequest {
        Objects.requireNonNull(url, "url");
    }
}
