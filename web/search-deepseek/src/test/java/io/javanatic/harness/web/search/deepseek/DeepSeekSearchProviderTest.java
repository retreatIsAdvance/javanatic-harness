package io.javanatic.harness.web.search.deepseek;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebSearchRequest;
import io.javanatic.harness.web.WebSearchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider 线上路径：请求头/体、结构化映射、错误脱敏、Redirect.NEVER、超时/取消/上限。 */
class DeepSeekSearchProviderTest {

    private static final String KEY = "sk-test-secret-1234567890";

    private static final String ONE_SOURCE = """
        {"content": [{"type": "web_search_tool_result", "content": [
          {"type": "web_search_result", "url": "https://example.test/a", "title": "A", "page_age": "1 day"}
        ]}]}
        """;

    @TempDir
    Path tmp;

    @Test
    void sendsDshRequestBodyAndDualAuthHeadersWithoutKeyInBody() throws IOException {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.json(200, ONE_SOURCE));
            DeepSeekSearchProvider provider = provider(fixture, 4096, 5, 30, client);
            WebSearchResult result = provider.search(
                new WebSearchRequest("java 25", 8), AbortSignal.never());

            assertThat(result.sources()).singleElement()
                .satisfies(source -> assertThat(source.url()).isEqualTo("https://example.test/a"));
            assertThat(provider.id()).isEqualTo("deepseek");
            assertThat(provider.displayName()).contains("deepseek-v4-flash");
            assertThat(provider.available()).isTrue();

            SearchFixture.RecordedRequest request = fixture.lastRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/anthropic/v1/messages");
            assertThat(request.header("x-api-key")).isEqualTo(KEY);
            assertThat(request.header("authorization")).isEqualTo("Bearer " + KEY);
            assertThat(request.header("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(request.header("content-type")).isEqualTo("application/json");
            assertThat(request.header("accept")).isEqualTo("application/json");
            assertThat(request.header("user-agent")).isEqualTo("javanatic-harness/0.3");
            assertThat(request.bodyText()).doesNotContain(KEY);

            JsonNode body = request.json();
            assertThat(body.path("model").asText()).isEqualTo("deepseek-v4-flash");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
            assertThat(body.path("messages")).hasSize(1);
            assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(body.path("messages").get(0).path("content")).hasSize(1);
            assertThat(body.path("messages").get(0).path("content").get(0).path("type").asText())
                .isEqualTo("text");
            assertThat(body.path("messages").get(0).path("content").get(0).path("text").asText())
                .isEqualTo("Perform a web search for the query: java 25");
            assertThat(body.path("tools")).hasSize(1);
            assertThat(body.path("tools").get(0).path("type").asText())
                .isEqualTo("web_search_20250305");
            assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("web_search");
            assertThat(body.path("tools").get(0).path("max_uses").asInt()).isEqualTo(5);
        }
    }

    @Test
    void truncatesSourcesToRequestLimitAtTheSeam() throws IOException {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.json(200, """
                {"content": [{"type": "web_search_tool_result", "content": [
                  {"type": "web_search_result", "url": "https://a.test"},
                  {"type": "web_search_result", "url": "https://b.test"},
                  {"type": "web_search_result", "url": "https://c.test"}
                ]}]}
                """));
            WebSearchResult result = provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("three", 2), AbortSignal.never());
            assertThat(result.sources()).extracting(source -> source.url())
                .containsExactly("https://a.test", "https://b.test");
            assertThat(result.truncated()).isTrue();
        }
    }

    @Test
    void non2xxIncludesStatusAndRedactedProviderText() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.json(401,
                "{\"error\":{\"message\":\"invalid key " + KEY + "\"}}"));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("****")
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain(KEY);
                    assertThat(error.getMessage()).doesNotContain("sk-test-secret");
                    assertThat(((WebException) error).code())
                        .isEqualTo(WebErrorCode.WEB_PROVIDER_ERROR);
                });
        }
    }

    @Test
    void nonJsonErrorBodyIsBoundedAndIncluded() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.text(429, "rate limited, slow down"));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("rate limited");
        }
    }

    @Test
    void providerJsonErrorMessageIsUsedForServerFailures() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.json(500,
                "{\"error\":{\"message\":\"upstream exploded\"}}"));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("upstream exploded");
        }
    }

    @Test
    void badJsonSuccessIsAProviderError() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.json(200, "not-json"));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("unprocessable response body")
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_PROVIDER_ERROR));
        }
    }

    @Test
    void proseWithoutResultBlockFailsInsteadOfScraping() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.json(200, """
                {"content": [{"type": "text", "text": "I found https://a.test for you"}]}
                """));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("no web_search_tool_result");
        }
    }

    @Test
    void redirectsAreNeverFollowed() throws IOException {
        Path secondDir = Files.createDirectory(tmp.resolve("second"));
        try (SearchFixture first = SearchFixture.start(tmp);
             SearchFixture second = SearchFixture.start(secondDir);
             HttpClient client = first.client()) {
            first.respond(exchange -> {
                exchange.getResponseHeaders().set("Location", second.baseUrl() + "/messages");
                exchange.sendResponseHeaders(302, -1);
            });
            assertThatThrownBy(() -> provider(first, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 302");
            assertThat(first.requests()).hasSize(1);
            assertThat(second.requests()).isEmpty();
        }
    }

    @Test
    void responseBodyOverOneMiBIsAProviderError() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            // 字面量而非常量：正文必须真的超过 1 MiB，常量被改大时本用例仍应红。
            String padding = "x".repeat(1024 * 1024 + 4096);
            String body = "{\"content\": [{\"type\": \"web_search_tool_result\", \"content\": ["
                + "{\"type\": \"web_search_result\", \"url\": \"https://a.test\"}]}],"
                + "\"padding\": \"" + padding + "\"}";
            fixture.respond(SearchFixture.json(200, body));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("exceeds 1048576 bytes");
        }
    }

    @Test
    void timeoutMapsToWebTimeout() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.delayed(Duration.ofSeconds(5),
                SearchFixture.json(200, ONE_SOURCE)));
            long started = System.nanoTime();
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 1, client)
                .search(new WebSearchRequest("slow", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("timed out")
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(4));
        }
    }

    @Test
    void cancellationSurfacesAbortedExceptionAndStopsQuickly() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.delayed(Duration.ofSeconds(10),
                SearchFixture.json(200, ONE_SOURCE)));
            SearchFixture.TestSignal signal = new SearchFixture.TestSignal();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            long started = System.nanoTime();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    provider(fixture, 4096, 5, 30, client)
                        .search(new WebSearchRequest("cancel me", 8), signal);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            assertThat(fixture.awaitFirstRequest(Duration.ofSeconds(5))).isTrue();
            signal.cancel("user");
            assertThat(join(worker, Duration.ofSeconds(5))).isTrue();
            assertThat(failure.get()).isInstanceOf(AbortedException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void responseObserverSeesStatusAndBodySize() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            DeepSeekSearchConfig config = config(fixture, 4096, 5, 30);
            AtomicReference<String> observed = new AtomicReference<>();
            new DeepSeekSearchProvider(config, KEY, client,
                (status, bytes) -> observed.set(status + ":" + bytes))
                .search(new WebSearchRequest("observe", 8), AbortSignal.never());
            assertThat(observed.get()).startsWith("200:").doesNotEndWith(":0");
        }
    }

    @Test
    void observerSeesErrorStatusAndBodySize() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            fixture.respond(SearchFixture.text(400, "bad"));
            AtomicReference<String> observed = new AtomicReference<>();
            assertThatThrownBy(() -> new DeepSeekSearchProvider(
                config(fixture, 4096, 5, 30), KEY, client,
                (status, bytes) -> observed.set(status + ":" + bytes))
                .search(new WebSearchRequest("observe error", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 400");
            assertThat(observed.get()).isEqualTo("400:3");
        }
    }

    /** provider 文本再长也只保留 512 字符 + 省略号；尾部不得泄漏。 */
    @Test
    void providerErrorTextIsTruncatedToTheLimit() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            String tail = "END-OF-TEXT";
            fixture.respond(SearchFixture.text(500, "a".repeat(4096) + tail));
            assertThatThrownBy(() -> provider(fixture, 4096, 5, 30, client)
                .search(new WebSearchRequest("x", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 500")
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain(tail).endsWith("…");
                    assertThat(error.getMessage().length()).isLessThan(600);
                });
        }
    }

    /** 超大错误体只取 16 KiB 诊断前缀：状态码保留，消息仍有界，尾部不泄漏。 */
    @Test
    void hugeErrorBodyIsBoundedAndKeepsStatus() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            String tail = "END-OF-ERROR";
            fixture.respond(SearchFixture.text(500, "x".repeat(1024 * 1024) + tail));
            AtomicReference<String> observed = new AtomicReference<>();
            assertThatThrownBy(() -> new DeepSeekSearchProvider(
                config(fixture, 4096, 5, 30), KEY, client,
                (status, bytes) -> observed.set(status + ":" + bytes))
                .search(new WebSearchRequest("huge error", 8), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .hasMessageContaining("HTTP 500")
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain(tail);
                    assertThat(error.getMessage().length()).isLessThan(600);
                });
            assertThat(observed.get()).isEqualTo("500:16384");
        }
    }

    @Test
    void emptyKeyAndControlCharactersAreRejectedWithoutEchoingTheValue() {
        try (SearchFixture fixture = SearchFixture.start(tmp);
             HttpClient client = fixture.client()) {
            DeepSeekSearchConfig config = config(fixture, 4096, 5, 30);
            assertThatThrownBy(() -> new DeepSeekSearchProvider(config, "", client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
            String secret = "secret\nvalue";
            assertThatThrownBy(() -> new DeepSeekSearchProvider(config, secret, client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("secret"));
        }
    }

    private static DeepSeekSearchProvider provider(SearchFixture fixture, int maxTokens, int maxUses,
                                                    int timeoutSeconds, HttpClient client) {
        return new DeepSeekSearchProvider(config(fixture, maxTokens, maxUses, timeoutSeconds),
            KEY, client);
    }

    private static DeepSeekSearchConfig config(SearchFixture fixture, int maxTokens, int maxUses,
                                               int timeoutSeconds) {
        return DeepSeekSearchConfig.parse(Map.of(
            "baseUrl", fixture.baseUrl(),
            "apiKey", KEY,
            "maxTokens", maxTokens,
            "maxUses", maxUses,
            "timeoutSeconds", timeoutSeconds), "web-search-deepseek");
    }

    private static boolean join(Thread thread, Duration timeout) {
        try {
            thread.join(timeout.toMillis());
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
