package io.javanatic.harness.web.search.deepseek;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebSearchRequest;
import io.javanatic.harness.web.WebSearchResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停 2 真实探针（默认跳过）：只有同时给出 {@code DEEPSEEK_API_KEY} 与
 * {@code -Djh.web.probe=true} 才消耗配额。走实际 {@link DeepSeekSearchProvider}
 * 线上路径，成功判据是结构化 {@code web_search_tool_result} 至少来源一条。
 *
 * <p>失败即停：不换 provider、不抓正文 prose、不把探测改写成别的请求；异常原文
 * （provider 文本已脱敏）与观察到的状态/体积直接作为取证。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "jh.web.probe", matches = "true")
class DeepSeekSearchProbeTest {

    private static final String QUERY = "Javanatic Harness Java agent framework";

    @Test
    void realNativeWebSearchReturnsStructuredSources() {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(Map.of(), "probe");
        String apiKey = config.resolveApiKey(System::getenv);
        AtomicReference<String> observed = new AtomicReference<>("unobserved");
        String requestBytes = String.valueOf(DeepSeekWire.requestBody(config, QUERY).length);
        long started = System.nanoTime();
        WebSearchResult result;
        try (HttpClient client = WebSearchDeepSeekPlugin.newHttpClient()) {
            result = new DeepSeekSearchProvider(config, apiKey, client,
                (status, bytes) -> observed.set(status + ":" + bytes))
                .search(new WebSearchRequest(QUERY, config.maxUses()), AbortSignal.never());
        } catch (WebException error) {
            System.out.println("PROBE_FAILED code=" + error.code()
                + " endpoint=" + config.endpoint().getHost() + config.endpoint().getPath()
                + " model=" + config.model()
                + " request_bytes=" + requestBytes
                + " observed=" + observed.get()
                + " detail=" + error.getMessage());
            throw error;
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.println("PROBE_OK endpoint=" + config.endpoint().getHost() + config.endpoint().getPath()
            + " model=" + config.model()
            + " max_tokens=" + config.maxTokens()
            + " max_uses=" + config.maxUses()
            + " request_bytes=" + requestBytes
            + " observed=" + observed.get()
            + " sources=" + result.sources().size()
            + " truncated=" + result.truncated()
            + " elapsed_ms=" + elapsedMs);

        assertThat(observed.get()).startsWith("200:");
        assertThat(result.sources())
            .as("probe requires at least one structured web_search_result source")
            .isNotEmpty();
        assertThat(result.sources()).allSatisfy(source ->
            assertThat(source.url()).startsWith("http"));
    }
}
