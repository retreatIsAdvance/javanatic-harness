package io.javanatic.harness.web.fetch.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.web.WebFetchService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件装配与配置白名单；装配期零网络（只 provide，不建连接）。 */
class WebFetchHttpPluginTest {

    @Test
    void pluginShapeIsAFetchProvider() {
        WebFetchHttpPlugin plugin = new WebFetchHttpPlugin();
        assertThat(plugin.id()).isEqualTo("web-fetch-http");
        assertThat(plugin.requires()).isEmpty();
    }

    @Test
    void applyProvidesFetchServiceWithoutNetwork() {
        try (Runtime rt = runtime(Map.of())) {
            new PluginLoader().loadAll(rt, List.of(new WebFetchHttpPlugin()));
            WebFetchService service = rt.root().require(WebFetchService.KEY);
            assertThat(service.id()).isEqualTo("web-fetch-http");
            assertThat(service.displayName()).contains("pinned");
            assertThat(service.available()).isTrue();
        }
    }

    @Test
    void configKeysAreWhitelistedAndValidated() {
        assertThat(FetchLimits.parse(Map.of(), "web-fetch-http"))
            .isEqualTo(new FetchLimits(30, 2 * 1024 * 1024));
        assertThat(FetchLimits.parse(Map.of("timeoutSeconds", 5, "maxBodyBytes", 1024),
            "web-fetch-http")).isEqualTo(new FetchLimits(5, 1024));
        assertThat(FetchLimits.parse(Map.of("timeoutSeconds", "7"), "web-fetch-http")
            .timeoutSeconds()).isEqualTo(7);

        assertConfigFails(Map.of("unknown", 1), "unknown key");
        assertConfigFails(Map.of("timeoutSeconds", 0), "timeoutSeconds");
        assertConfigFails(Map.of("timeoutSeconds", 601), "timeoutSeconds");
        assertConfigFails(Map.of("timeoutSeconds", "soon"), "not numeric");
        assertConfigFails(Map.of("maxBodyBytes", 0), "maxBodyBytes");
        assertConfigFails(Map.of("maxBodyBytes", 64L * 1024 * 1024 + 1), "maxBodyBytes");
        assertConfigFails(Map.of("maxBodyBytes", true), "must be numeric");
    }

    @Test
    void badConfigRollsBackAndLeavesNoService() {
        try (Runtime rt = runtime(Map.of("unknownKey", 1))) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new WebFetchHttpPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-fetch-http");
            assertThat(rt.root().resolve(WebFetchService.KEY)).isEmpty();
        }
    }

    private static Runtime runtime(Map<String, Object> config) {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> config);
        return rt;
    }

    private static void assertConfigFails(Map<String, Object> config, String message) {
        assertThatThrownBy(() -> FetchLimits.parse(config, "web-fetch-http"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(message);
    }
}
