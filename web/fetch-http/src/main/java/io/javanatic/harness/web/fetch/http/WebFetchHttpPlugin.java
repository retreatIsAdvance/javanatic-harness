package io.javanatic.harness.web.fetch.http;

import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.web.WebFetchService;

/**
 * 网页抓取 provider（id "web-fetch-http"，it28）：解析 {@code timeoutSeconds} /
 * {@code maxBodyBytes}（键白名单，未知键/坏值 fail loud），装配钉地址客户端并
 * provide {@link WebFetchService}。本插件不 require 任何其他插件——地址闸、代理、
 * 凭据都不经外部配置；无 server/无连接池，装配期零网络。
 */
public final class WebFetchHttpPlugin implements Plugin {

    @Override
    public String id() {
        return "web-fetch-http";
    }

    @Override
    public void apply(Scope scope) throws Exception {
        FetchLimits limits = FetchLimits.parse(
            scope.require(ConfigService.KEY).configFor(id()), id());
        SSLContext context;
        try {
            context = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("web-fetch-http: default TLS context unavailable", e);
        }
        PinnedHttpClient client = new PinnedHttpClient(limits, AddressResolver.system(),
            PublicAddressGate.INSTANCE, context);
        scope.provide(WebFetchService.KEY, new PinnedFetchService(client));
    }
}
