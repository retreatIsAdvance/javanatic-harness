package io.javanatic.harness.lsp.jdtls;

import java.util.Objects;
import java.util.function.Function;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.lsp.LspService;

/**
 * JDT LS provider 插件（id "lsp-jdtls"，it29 停 3）：解析行配置（键白名单、
 * 坏值 fail loud，边界 B27）→ 构造启动档案 → 启动 {@link JdtlsProvider} →
 * provide {@link LspService} → scope 关闭时收敛进程（onClose）。
 *
 * <p>本行以禁用行（`disabled: true`）进 base bundle：serverDir / configurationDir /
 * projectRoot 是组合者显式配置的信任面、无有意义缺省，缺省组合零 LSP 进程
 * （与 web-search 的 keyless 禁用同口径）；组合者用 `replace: true` 原地启用并给配置。
 * LS 以用户全权进程运行、不入沙箱（边界 B23），本插件只做装配不承诺隔离。
 */
public final class JdtlsPlugin implements Plugin {

    private final Function<JdtlsLaunch, LspService> providerFactory;

    public JdtlsPlugin() {
        this(JdtlsProvider::new);
    }

    /** 包内测试缝：工厂注入假服务，不起真实 LS 进程；生产构造器固定 {@link JdtlsProvider}。 */
    JdtlsPlugin(Function<JdtlsLaunch, LspService> providerFactory) {
        this.providerFactory = Objects.requireNonNull(providerFactory, "providerFactory");
    }

    @Override
    public String id() {
        return "lsp-jdtls";
    }

    @Override
    public void apply(Scope scope) throws Exception {
        JdtlsPluginConfig config = JdtlsPluginConfig.parse(
            scope.require(ConfigService.KEY).configFor(id()), id());
        LspService service = providerFactory.apply(config.toLaunch());
        scope.onClose(service::close);
        scope.provide(LspService.KEY, service);
    }
}
