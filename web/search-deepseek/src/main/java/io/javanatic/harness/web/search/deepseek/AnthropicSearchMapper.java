package io.javanatic.harness.web.search.deepseek;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebSearchResult;
import io.javanatic.harness.web.WebSearchSource;

/**
 * Anthropic Messages 响应 → seam 结果（dsh {@code mapAnthropicResponse} 同口径）。
 *
 * <p>只消费结构化事实：{@code web_search_tool_result} 块的 {@code web_search_result}
 * 项给出 URL/标题/页龄；摘要来自 {@code text} 块 {@code citations[].cited_text}
 * （按 URL 首次出现）。按 URL 去重后由本层按 {@code maxResults} 截断并置
 * {@code truncated}。没有结果块即 {@link WebErrorCode#WEB_PROVIDER_ERROR}——
 * 即使响应里有散文答案也不刮取；块在但零结果 = 合法空结果。
 */
final class AnthropicSearchMapper {

    private AnthropicSearchMapper() {
    }

    /**
     * @param root 解析后的响应 JSON
     * @param maxResults 来源上限（正数；由 {@code WebSearchRequest} 保证）
     * @return 去重、截断后的来源（无摘要）
     * @throws WebException {@link WebErrorCode#WEB_PROVIDER_ERROR} 形状不符或无结果块时
     */
    static WebSearchResult map(JsonNode root, int maxResults) {
        if (root == null || !root.isObject()) {
            throw malformed("response is not a JSON object");
        }
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            throw malformed("response is missing a 'content' array");
        }
        Map<String, String> snippets = citationSnippets(content);
        Set<String> seen = new HashSet<>();
        List<WebSearchSource> sources = new ArrayList<>();
        boolean sawResultBlock = false;
        for (JsonNode block : content) {
            if (!block.isObject()) {
                throw malformed("content contains a non-object block");
            }
            String type = text(block, "type");
            if (type == null) {
                throw malformed("content block has no string 'type'");
            }
            if (!"web_search_tool_result".equals(type)) {
                continue;
            }
            sawResultBlock = true;
            collectBlock(block.get("content"), snippets, seen, sources);
        }
        if (!sawResultBlock) {
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek returned no web_search_tool_result blocks;"
                    + " the request may not have triggered native web search");
        }
        boolean truncated = sources.size() > maxResults;
        List<WebSearchSource> limited = truncated
            ? new ArrayList<>(sources.subList(0, maxResults))
            : sources;
        return new WebSearchResult(limited, truncated);
    }

    private static void collectBlock(JsonNode payload, Map<String, String> snippets,
                                     Set<String> seen, List<WebSearchSource> sources) {
        if (payload == null || payload.isNull()) {
            return;
        }
        if (!payload.isArray()) {
            if (payload.isObject()) {
                String code = text(payload, "error_code");
                if (code == null) {
                    code = text(payload, "type");
                }
                throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                    "DeepSeek web search returned an error"
                        + (code == null ? "" : ": " + code));
            }
            throw malformed("web_search_tool_result 'content' is not an array");
        }
        for (JsonNode item : payload) {
            if (!item.isObject()) {
                throw malformed("web_search_result item is not an object");
            }
            String type = text(item, "type");
            if (type == null) {
                throw malformed("web_search_result item has no string 'type'");
            }
            if (!"web_search_result".equals(type)) {
                continue;
            }
            String url = text(item, "url");
            if (url == null || url.isBlank()) {
                throw malformed("web_search_result item has no usable 'url'");
            }
            if (!seen.add(url)) {
                continue;
            }
            sources.add(WebSearchSource.of(url, optionalText(item, "title"),
                snippets.get(url), optionalText(item, "page_age")));
        }
    }

    /**
     * 摘要只来自 text 块的引用；结构不完整的引用不构成来源，跳过（dsh 同口径）。
     */
    private static Map<String, String> citationSnippets(JsonNode content) {
        Map<String, String> snippets = new LinkedHashMap<>();
        for (JsonNode block : content) {
            if (!block.isObject() || !"text".equals(text(block, "type"))) {
                continue;
            }
            JsonNode citations = block.get("citations");
            if (citations == null || !citations.isArray()) {
                continue;
            }
            for (JsonNode citation : citations) {
                if (!citation.isObject()) {
                    continue;
                }
                String url = text(citation, "url");
                String citedText = text(citation, "cited_text");
                if (url != null && !url.isBlank() && citedText != null && !citedText.isBlank()) {
                    snippets.putIfAbsent(url, citedText);
                }
            }
        }
        return snippets;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw malformed("web_search_result item field '" + field + "' is not a string");
        }
        return value.asText();
    }

    private static WebException malformed(String detail) {
        return new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
            "DeepSeek returned an unprocessable response body: " + detail);
    }
}
