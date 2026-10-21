package io.javanatic.harness.web.fetch.http;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.web.WebFetchRequest;
import io.javanatic.harness.web.WebFetchResult;
import io.javanatic.harness.web.WebFetchService;

/**
 * 抓取服务实现：把 {@link PinnedHttpClient} 暴露为 seam 的 {@link WebFetchService}。
 * 无外部状态，{@link #available()} 恒真（TLS/解析失败属于单次调用错误，不是装配期不可用）。
 */
final class PinnedFetchService implements WebFetchService {

    private final PinnedHttpClient client;

    PinnedFetchService(PinnedHttpClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return "web-fetch-http";
    }

    @Override
    public String displayName() {
        return "HTTP/1.1 (pinned, public-only)";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public WebFetchResult fetch(WebFetchRequest request, AbortSignal signal) {
        return client.fetch(request, signal);
    }
}
