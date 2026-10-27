package io.javanatic.harness.web.search.deepseek;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebSearchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 映射层：只取结构化结果与引用 snippet、URL 去重、空结果与无结果块负路径。 */
class AnthropicSearchMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsStructuredResultsAndCitationSnippetsInProviderOrder() throws IOException {
        WebSearchResult result = map("""
            {"content": [
              {"type": "text", "text": "prose answer", "citations": [
                {"type": "web_search_result_location", "url": "https://a.test", "cited_text": "snippet a"},
                {"type": "web_search_result_location", "url": "https://a.test", "cited_text": "later a"},
                {"type": "web_search_result_location", "url": "https://b.test", "cited_text": "snippet b"}
              ]},
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result", "url": "https://a.test", "title": "A", "page_age": "2 days"},
                {"type": "web_search_result", "url": "https://b.test", "title": "B"},
                {"type": "web_search_result", "url": "https://a.test", "title": "A duplicate"}
              ]}
            ]}
            """, 8);

        assertThat(result.summary()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.sources()).hasSize(2);
        assertThat(result.sources().get(0).url()).isEqualTo("https://a.test");
        assertThat(result.sources().get(0).title()).contains("A");
        assertThat(result.sources().get(0).snippet()).contains("snippet a");
        assertThat(result.sources().get(0).publishedAt()).contains("2 days");
        assertThat(result.sources().get(1).url()).isEqualTo("https://b.test");
        assertThat(result.sources().get(1).title()).contains("B");
        assertThat(result.sources().get(1).snippet()).contains("snippet b");
        assertThat(result.sources().get(1).publishedAt()).isEmpty();
    }

    @Test
    void dedupesAcrossResultBlocksAndFirstOccurrenceWins() throws IOException {
        WebSearchResult result = map("""
            {"content": [
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result", "url": "https://a.test", "title": "first"}
              ]},
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result", "url": "https://a.test", "title": "second"}
              ]}
            ]}
            """, 8);
        assertThat(result.sources()).singleElement()
            .satisfies(source -> assertThat(source.title()).contains("first"));
    }

    @Test
    void truncatesToMaxResultsAndFlags() throws IOException {
        WebSearchResult result = map("""
            {"content": [
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result", "url": "https://a.test"},
                {"type": "web_search_result", "url": "https://b.test"},
                {"type": "web_search_result", "url": "https://c.test"}
              ]}
            ]}
            """, 2);
        assertThat(result.sources()).extracting(source -> source.url())
            .containsExactly("https://a.test", "https://b.test");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void presentResultBlockWithZeroResultsIsAValidEmptyResult() throws IOException {
        WebSearchResult result = map("""
            {"content": [
              {"type": "text", "text": "no results found"},
              {"type": "web_search_tool_result", "content": []}
            ]}
            """, 8);
        assertThat(result.sources()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void resultBlockWithoutContentIsAValidEmptyResult() throws IOException {
        WebSearchResult result = map("""
            {"content": [{"type": "web_search_tool_result"}]}
            """, 8);
        assertThat(result.sources()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void proseWithoutResultBlockIsAProviderError() throws IOException {
        assertProviderError("""
            {"content": [
              {"type": "text", "text": "I searched the web and found https://a.test"}
            ]}
            """, "no web_search_tool_result");
    }

    @Test
    void resultBlockErrorObjectIsAProviderError() throws IOException {
        assertProviderError("""
            {"content": [
              {"type": "web_search_tool_result", "content": {
                "type": "web_search_tool_result_error", "error_code": "max_uses_exceeded"
              }}
            ]}
            """, "max_uses_exceeded");
    }

    @Test
    void malformedRootAndContentShapesAreProviderErrors() throws IOException {
        assertProviderError("[]", "not a JSON object");
        assertProviderError("{}", "missing a 'content' array");
        assertProviderError("{\"content\": {}}", "missing a 'content' array");
        assertProviderError("{\"content\": [1]}", "non-object block");
        assertProviderError("{\"content\": [{}]}", "no string 'type'");
    }

    @Test
    void malformedResultBlocksAndItemsAreProviderErrors() throws IOException {
        assertProviderError("""
            {"content": [
              {"type": "web_search_tool_result", "content": "text"}
            ]}
            """, "'content' is not an array");
        assertProviderError("""
            {"content": [
              {"type": "web_search_tool_result", "content": [1]}
            ]}
            """, "item is not an object");
        assertProviderError("""
            {"content": [
              {"type": "web_search_tool_result", "content": [{}]}
            ]}
            """, "no string 'type'");
        assertProviderError("""
            {"content": [
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result"}
              ]}
            ]}
            """, "no usable 'url'");
        assertProviderError("""
            {"content": [
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result", "url": "https://a.test", "title": 7}
              ]}
            ]}
            """, "'title' is not a string");
    }

    @Test
    void unknownItemTypesAreSkipped() throws IOException {
        WebSearchResult result = map("""
            {"content": [
              {"type": "web_search_tool_result", "content": [
                {"type": "future_result", "url": "https://ignored.test"},
                {"type": "web_search_result", "url": "https://kept.test"}
              ]}
            ]}
            """, 8);
        assertThat(result.sources()).extracting(source -> source.url())
            .containsExactly("https://kept.test");
    }

    @Test
    void malformedCitationsAreIgnoredButDoNotFabricateSnippets() throws IOException {
        WebSearchResult result = map("""
            {"content": [
              {"type": "text", "citations": "nope"},
              {"type": "text", "citations": [1, {"url": "https://a.test"}]},
              {"type": "web_search_tool_result", "content": [
                {"type": "web_search_result", "url": "https://a.test"}
              ]}
            ]}
            """, 8);
        assertThat(result.sources()).singleElement()
            .satisfies(source -> assertThat(source.snippet()).isEmpty());
    }

    private static WebSearchResult map(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        return AnthropicSearchMapper.map(root, maxResults);
    }

    private static void assertProviderError(String json, String message) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        assertThatThrownBy(() -> AnthropicSearchMapper.map(root, 8))
            .isInstanceOf(WebException.class)
            .hasMessageContaining(message)
            .satisfies(error -> assertThat(((WebException) error).code())
                .isEqualTo(WebErrorCode.WEB_PROVIDER_ERROR));
    }
}
