package io.javanatic.harness.web.search.deepseek;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.web.WebSearchService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件装配：配置白名单、无网络 provide、无 key fail loud（不静默缺席）。 */
class WebSearchDeepSeekPluginTest {

    @Test
    void pluginShapeIsASearchProvider() {
        WebSearchDeepSeekPlugin plugin = new WebSearchDeepSeekPlugin();
        assertThat(plugin.id()).isEqualTo("web-search-deepseek");
        assertThat(plugin.requires()).isEmpty();
    }

    /** 装配面客户端策略：重定向一律不跟随（端点钉死），连接超时 10s。 */
    @Test
    void assembledClientNeverFollowsRedirectsAndPinsConnectTimeout() {
        try (HttpClient client = WebSearchDeepSeekPlugin.newHttpClient()) {
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(client.connectTimeout()).contains(DeepSeekSearchProvider.CONNECT_TIMEOUT);
        }
    }

    @Test
    void applyProvidesSearchServiceWithoutNetwork() {
        try (Runtime rt = runtime(Map.of("apiKey", "literal-key"))) {
            new PluginLoader().loadAll(rt, List.of(new WebSearchDeepSeekPlugin()));
            WebSearchService service = rt.root().require(WebSearchService.KEY);
            assertThat(service.id()).isEqualTo("deepseek");
            assertThat(service.displayName()).contains("deepseek-v4-flash");
            assertThat(service.available()).isTrue();
        }
    }

    @Test
    void badConfigRollsBackAndLeavesNoService() {
        try (Runtime rt = runtime(Map.of("unknownKey", 1))) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new WebSearchDeepSeekPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-search-deepseek");
            assertThat(rt.root().resolve(WebSearchService.KEY)).isEmpty();
        }
    }

    @Test
    void missingKeyFailsLoudAndNamesOnlyTheEnvironmentVariable() {
        String envName = "JH_WEB_SEARCH_ABSENT_" + UUID.randomUUID();
        try (Runtime rt = runtime(Map.of("apiKeyEnv", envName))) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new WebSearchDeepSeekPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-search-deepseek")
                .satisfies(error -> {
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getCause().getMessage())
                        .contains("no API key")
                        .contains(envName)
                        .contains("disable this row");
                });
            assertThat(rt.root().resolve(WebSearchService.KEY)).isEmpty();
        }
    }

    private static Runtime runtime(Map<String, Object> config) {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> config);
        return rt;
    }
}
