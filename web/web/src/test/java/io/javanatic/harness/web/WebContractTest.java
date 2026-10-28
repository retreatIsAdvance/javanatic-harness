package io.javanatic.harness.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 承载契约的形状与 fail-loud 面：错误词表稳定、请求/结果不变量、来源
 * 归一工厂与不可变集合。消费者与 Provider 都以这里为共同词表（it28 停 1）。
 */
class WebContractTest {

    @Test
    void errorVocabularyIsStable() {
        assertThat(WebErrorCode.values()).extracting(Enum::name).containsExactly(
            "WEB_INVALID_URL", "WEB_BLOCKED_ADDRESS", "WEB_REDIRECT_BLOCKED",
            "WEB_UNSUPPORTED_CONTENT_TYPE", "WEB_TOO_LARGE", "WEB_TIMEOUT",
            "WEB_NETWORK_ERROR", "WEB_PROVIDER_UNAVAILABLE", "WEB_PROVIDER_ERROR");
    }

    @Test
    void fetchRequestRequiresUrlButDefersPolicyToProvider() {
        assertThatNullPointerException().isThrownBy(() -> new WebFetchRequest(null));
        assertThat(new WebFetchRequest("").url()).isEmpty();
    }

    @Test
    void fetchResultValidatesStatusAndReferences() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebFetchResult(
            "https://example.com", 99, "text/plain", new WebFetchBody.Text("x"), false));
        assertThatIllegalArgumentException().isThrownBy(() -> new WebFetchResult(
            "https://example.com", 600, "text/plain", new WebFetchBody.Text("x"), false));
        assertThatNullPointerException().isThrownBy(() -> new WebFetchResult(
            null, 200, "text/plain", new WebFetchBody.Text("x"), false));
        assertThatNullPointerException().isThrownBy(() -> new WebFetchResult(
            "https://example.com", 200, "text/plain", null, false));
        assertThatNullPointerException().isThrownBy(() -> new WebFetchResult(
            "https://example.com", 200, null, new WebFetchBody.Text("x"), false));
    }

    @Test
    void fetchBodyIsASealedTextUnion() {
        WebFetchBody html = new WebFetchBody.Html("<p>x</p>");
        WebFetchBody text = new WebFetchBody.Text("x");
        assertThat(html.content()).isEqualTo("<p>x</p>");
        assertThat(text.content()).isEqualTo("x");
        assertThat(WebFetchBody.class.isSealed()).isTrue();
        assertThatNullPointerException().isThrownBy(() -> new WebFetchBody.Html(null));
        assertThatNullPointerException().isThrownBy(() -> new WebFetchBody.Text(null));
    }

    @Test
    void searchRequestRequiresQueryAndPositiveLimit() {
        assertThatNullPointerException().isThrownBy(() -> new WebSearchRequest(null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new WebSearchRequest("  ", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new WebSearchRequest("q", 0));
        assertThat(new WebSearchRequest("q", 3).maxResults()).isEqualTo(3);
    }

    @Test
    void searchResultCopiesSourcesAndKeepsOptionalSummary() {
        List<WebSearchSource> mutable = new ArrayList<>();
        mutable.add(new WebSearchSource("https://example.com/a"));
        WebSearchResult result = new WebSearchResult(mutable, false);
        mutable.clear();
        assertThat(result.sources()).hasSize(1);
        assertThat(result.summary()).isEmpty();
        assertThatThrownBy(() -> result.sources().add(new WebSearchSource("https://example.com/b")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new WebSearchResult(Optional.of("sum"), List.of(), true).truncated()).isTrue();
    }

    @Test
    void searchSourceNormalizesMissingFieldsWithoutInventingThem() {
        WebSearchSource source = WebSearchSource.of("https://example.com/a", " ", null, "");
        assertThat(source.title()).isEmpty();
        assertThat(source.snippet()).isEmpty();
        assertThat(source.publishedAt()).isEmpty();

        WebSearchSource full = WebSearchSource.of("https://example.com/a", "Title", "snippet", "2026-01-02");
        assertThat(full.title()).contains("Title");
        assertThat(full.snippet()).contains("snippet");
        assertThat(full.publishedAt()).contains("2026-01-02");
        assertThatNullPointerException().isThrownBy(() -> WebSearchSource.of(null, "t", "s", "d"));
    }

    @Test
    void exceptionCarriesStableCodeAndCause() {
        WebException plain = new WebException(WebErrorCode.WEB_TIMEOUT, "timed out");
        assertThat(plain.code()).isEqualTo(WebErrorCode.WEB_TIMEOUT);
        assertThat(plain).hasNoCause();

        IllegalStateException cause = new IllegalStateException("root");
        WebException wrapped = new WebException(WebErrorCode.WEB_NETWORK_ERROR, "net", cause);
        assertThat(wrapped.getCause()).isSameAs(cause);
        assertThatNullPointerException().isThrownBy(
            () -> new WebException(WebErrorCode.WEB_NETWORK_ERROR, "net", null));
    }
}
