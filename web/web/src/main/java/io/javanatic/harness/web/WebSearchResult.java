package io.javanatic.harness.web;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次搜索的结果。{@code summary} 是 provider 生成的答案/摘要（DeepSeek 原生
 * 搜索不返回，留空）；{@code sources} 已去重并截断到请求上限。
 *
 * @param summary provider 摘要（可能缺失）
 * @param sources 可引用来源
 * @param truncated 是否因 maxResults 截断
 */
public record WebSearchResult(Optional<String> summary, List<WebSearchSource> sources,
                              boolean truncated) {

    /** @throws NullPointerException 参数为 null 时 */
    public WebSearchResult {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(sources, "sources");
        sources = List.copyOf(sources);
    }

    /**
     * 无摘要结果的便捷构造。
     *
     * @param sources 来源
     * @param truncated 是否截断
     */
    public WebSearchResult(List<WebSearchSource> sources, boolean truncated) {
        this(Optional.empty(), sources, truncated);
    }
}
