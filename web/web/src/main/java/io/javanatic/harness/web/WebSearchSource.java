package io.javanatic.harness.web;

import java.util.Objects;
import java.util.Optional;

/**
 * 一个可引用来源。只有 URL 是必填：provider 不编造缺失的标题/摘要/时间。
 *
 * @param url 来源 URL
 * @param title 标题（可能缺失）
 * @param snippet 摘要（可能缺失）
 * @param publishedAt provider 给出的 ISO-8601 时间（可能缺失）
 */
public record WebSearchSource(String url, Optional<String> title, Optional<String> snippet,
                              Optional<String> publishedAt) {

    /** @throws NullPointerException 参数为 null 时 */
    public WebSearchSource {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(snippet, "snippet");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /**
     * 仅 URL 的来源。
     *
     * @param url 来源 URL
     */
    public WebSearchSource(String url) {
        this(url, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * 归一工厂：null/空串按缺失处理。
     *
     * @param url 来源 URL
     * @param title 标题或 null
     * @param snippet 摘要或 null
     * @param publishedAt 时间或 null
     * @return 来源
     */
    public static WebSearchSource of(String url, String title, String snippet, String publishedAt) {
        return new WebSearchSource(url, optional(title), optional(snippet), optional(publishedAt));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
