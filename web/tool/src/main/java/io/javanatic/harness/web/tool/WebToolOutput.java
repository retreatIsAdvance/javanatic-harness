package io.javanatic.harness.web.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.javanatic.harness.web.WebFetchResult;
import io.javanatic.harness.web.WebSearchResult;
import io.javanatic.harness.web.WebSearchSource;

/**
 * 工具结果渲染 + 输出上限（T5/T7）。
 *
 * <p>成功结果首行恒为 {@link WebContentTrust#EXTERNAL_WEB_CONTENT_NOTICE}。
 * 抓取头部外显最终 URL、状态码、原始 Content-Type 与三层截断事实；搜索头部
 * 外显 provider（含模型名）、query 与来源字段实况（{@code Sources: N (titles: x,
 * snippets: y)}，只陈述实际存在的字段），来源保留标题/URL/snippet，截断尾行
 * {@value #SEARCH_TRUNCATED_LINE}，零来源明说「No sources returned」。
 *
 * <p>输出按 UTF-8 完整字符边界截断到 {@code maxOutputBytes}，尾附
 * {@value #TRUNCATED_MARKER}（标记不计入上限，与 fs/mcp 同口径）；输出帽切到
 * 抓取正文时把头部 {@code Truncated} 改真后重渲染。元数据（URL/标题/snippet）
 * 折叠换行——外部来源不能伪造结果头部行。
 */
final class WebToolOutput {

    /** 输出截断标记（与 fs/mcp 同文本）。 */
    static final String TRUNCATED_MARKER = " (output truncated)";

    /** 搜索结果条数截断尾行（provider 侧 maxResults 截断时出现）。 */
    static final String SEARCH_TRUNCATED_LINE = "… (search truncated)";

    /** 输出上限处理结果。 */
    record Bounded(String text, boolean truncated) {
    }

    private WebToolOutput() {
    }

    /**
     * 渲染抓取结果并施加输出上限。
     *
     * @param result seam 结果
     * @param bodyText 已提取/归一后的正文
     * @param truncated 上游（正文/源码）是否已截断
     * @param config 上限
     * @return 有界输出
     */
    static String fetch(WebFetchResult result, String bodyText, boolean truncated,
                        WebToolConfig config) {
        Bounded bounded = cap(renderFetch(result, bodyText, truncated), config.maxOutputBytes());
        if (bounded.truncated() && !truncated) {
            // 输出帽切到正文：头部须外显真实截断事实（重渲染后标记由 cap 追加）
            bounded = cap(renderFetch(result, bodyText, true), config.maxOutputBytes());
        }
        return bounded.text();
    }

    /**
     * 渲染搜索结果并施加输出上限。
     *
     * @param result seam 结果
     * @param provider provider displayName（含模型名）
     * @param query 原始查询
     * @param config 上限
     * @return 有界输出
     */
    static String search(WebSearchResult result, String provider, String query,
                         WebToolConfig config) {
        return cap(renderSearch(result, provider, query), config.maxOutputBytes()).text();
    }

    private static String renderFetch(WebFetchResult result, String bodyText, boolean truncated) {
        return WebContentTrust.EXTERNAL_WEB_CONTENT_NOTICE + "\n"
            + "URL: " + oneLine(result.url()) + "\n"
            + "Status: " + result.statusCode() + "\n"
            + "Content-Type: " + oneLine(result.contentType()) + "\n"
            + "Truncated: " + truncated + "\n\n"
            + bodyText;
    }

    private static String renderSearch(WebSearchResult result, String provider, String query) {
        StringBuilder out = new StringBuilder();
        out.append(WebContentTrust.EXTERNAL_WEB_CONTENT_NOTICE).append('\n');
        out.append("Provider: ").append(oneLine(provider)).append('\n');
        out.append("Query: ").append(oneLine(query)).append('\n');
        List<WebSearchSource> sources = result.sources();
        if (sources.isEmpty()) {
            out.append("No sources returned\n");
        } else {
            int titles = 0;
            int snippets = 0;
            for (WebSearchSource source : sources) {
                if (source.title().isPresent()) {
                    titles++;
                }
                if (source.snippet().isPresent()) {
                    snippets++;
                }
            }
            out.append("Sources: ").append(sources.size())
                .append(" (titles: ").append(titles)
                .append(", snippets: ").append(snippets).append(")\n");
            for (int i = 0; i < sources.size(); i++) {
                WebSearchSource source = sources.get(i);
                out.append(i + 1).append(". ");
                if (source.title().isPresent()) {
                    out.append(oneLine(source.title().get())).append('\n').append("   ");
                }
                out.append(oneLine(source.url())).append('\n');
                if (source.snippet().isPresent()) {
                    out.append("   ").append(oneLine(source.snippet().get())).append('\n');
                }
            }
            if (result.truncated()) {
                out.append(SEARCH_TRUNCATED_LINE).append('\n');
            }
        }
        Optional<String> summary = result.summary().map(String::strip).filter(text -> !text.isEmpty());
        if (summary.isPresent()) {
            out.append('\n').append("Summary:\n").append(summary.get()).append('\n');
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.substring(0, end);
    }

    /** 元数据单行化：任意空白折叠为单个空格并去首尾（防伪造头部行）。 */
    static String oneLine(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder out = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (isSpace(codePoint)) {
                pendingSpace = !out.isEmpty();
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /**
     * UTF-8 完整字符边界截断：按码点累计字节，不整串编码；不截断时原文返回。
     * 未配对代理按 3 字节计（宁早截，不越上限）；标记不计入上限。
     */
    static Bounded cap(String content, int maxBytes) {
        Objects.requireNonNull(content, "content");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        int bytes = 0;
        int index = 0;
        while (index < content.length()) {
            int codePoint = content.codePointAt(index);
            int size = utf8Bytes(codePoint);
            if (bytes + size > maxBytes) {
                return new Bounded(content.substring(0, index) + TRUNCATED_MARKER, true);
            }
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return new Bounded(content, false);
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
