package io.javanatic.harness.web.search.deepseek;

import java.net.http.HttpClient;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.web.WebSearchService;

/**
 * 搜索 provider 插件（id "web-search-deepseek"，it28）：解析行配置（键白名单、
 * 坏值 fail loud），解析 key（字面 `apiKey` 优先于 `apiKeyEnv`），装配固定
 * {@link HttpClient.Redirect#NEVER} 的客户端并 provide {@link WebSearchService}。
 *
 * <p>无 key 时 fail loud——keyless 组合应由基础行的
 * {@code disabled: ${env:DEEPSEEK_API_KEY} == null} 关闭本行（与 llm-openai-compat
 * 同口径）。装配期零网络：只解析配置与凭据，不发请求。
 */
public final class WebSearchDeepSeekPlugin implements Plugin {

    @Override
    public String id() {
        return "web-search-deepseek";
    }

    @Override
    public void apply(Scope scope) {
        DeepSeekSearchConfig config = DeepSeekSearchConfig.parse(
            scope.require(ConfigService.KEY).configFor(id()), id());
        String apiKey = config.resolveApiKey(System::getenv);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("web-search-deepseek: no API key (config 'apiKey' or env "
                + config.apiKeyEnv() + "); keyless compositions should disable this row");
        }
        HttpClient client = newHttpClient();
        scope.onClose(client::close);
        scope.provide(WebSearchService.KEY, new DeepSeekSearchProvider(config, apiKey, client));
    }

    /** 装配面固定策略：不跟随重定向（端点由配置钉死），连接超时 10s。 */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(DeepSeekSearchProvider.CONNECT_TIMEOUT)
            .build();
    }
}
