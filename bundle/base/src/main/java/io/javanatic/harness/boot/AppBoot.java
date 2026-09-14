package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ExpressionResolver;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.sandbox.sandbox.BackendStatus;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.session.Session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组合装配入口（07 §5）：profile bundles → profile rows → CLI overlay 三层叠加
 * → 表达式求值（合并后、加载前）→ 双向显式校验（行引用必须存在 + 发现必须被
 * 引用）→ Runtime + ConfigService + CompositionManifest → 按行序加载。
 * --dump-config 与 --verify 是纯组合期操作（verify 无 key 可跑）。
 */
public final class AppBoot {

    private static final System.Logger LOG = System.getLogger(AppBoot.class.getName());

    private AppBoot() {
    }

    /** 装配选项。 */
    public record BootOptions(Path profilePath, List<ConfigRowSpec> overlays,
                              boolean verify, Policy verifyPolicy) {

        /** @param profilePath profile YAML 路径 */
        public BootOptions {
            java.util.Objects.requireNonNull(profilePath, "profilePath");
            overlays = List.copyOf(overlays == null ? List.of() : overlays);
            verifyPolicy = verifyPolicy == null ? Policy.STANDARD : verifyPolicy;
        }
    }

    /** --verify 违规(逐项列出,exit 1 由调用方决定)。 */
    public static final class VerifyFailedException extends RuntimeException {
        private final List<String> violations;

        /** @param violations 违规项 */
        public VerifyFailedException(List<String> violations) {
            super("policy violations: " + violations);
            this.violations = List.copyOf(violations);
        }

        /** @return 违规项 */
        public List<String> violations() {
            return violations;
        }
    }

    /**
     * @param options 装配选项
     * @return 三层叠加后的行序(表达式未求值;dump/校验共用)
     * @throws IOException profile/bundle 读取失败
     */
    public static List<ConfigRowSpec> compose(BootOptions options) throws IOException {
        YamlRows.Profile profile = YamlRows.parseProfile(options.profilePath());
        Map<String, YamlRows.Bundle> bundles = new LinkedHashMap<>();
        for (YamlRows.Bundle bundle : YamlRows.discoverBundles()) {
            bundles.put(bundle.name(), bundle);
        }
        List<ConfigRowSpec> rows = List.of();
        for (String name : profile.bundles()) {
            YamlRows.Bundle bundle = bundles.get(name);
            if (bundle == null) {
                throw new IllegalStateException("profile references unknown bundle: " + name);
            }
            rows = RowComposer.apply(rows, bundle.rows());
        }
        rows = RowComposer.apply(rows, profile.rows());
        rows = RowComposer.apply(rows, options.overlays());
        return rows;
    }

    /**
     * 表达式求值(disabled/config)并过滤禁用行。
     *
     * @return 已解析启用行(config 值完成插值)
     */
    public static List<ConfigRowSpec> resolve(List<ConfigRowSpec> rows) {
        ExpressionResolver resolver = ExpressionResolver.standard();
        List<ConfigRowSpec> enabled = new ArrayList<>();
        for (ConfigRowSpec row : rows) {
            switch (row) {
                case ConfigRowSpec.Remove ignored -> throw new IllegalStateException("unreachable");
                case ConfigRowSpec.Include include -> {
                    if (include.disabled() == null || !resolver.evaluate(include.disabled())) {
                        enabled.add(new ConfigRowSpec.Include(include.plugin(),
                            resolver.interpolate(include.config()), null));
                    }
                }
                case ConfigRowSpec.Replace replace -> {
                    if (replace.disabled() == null || !resolver.evaluate(replace.disabled())) {
                        enabled.add(new ConfigRowSpec.Replace(replace.plugin(),
                            resolver.interpolate(replace.config()), null));
                    }
                }
                case ConfigRowSpec.Insert insert -> {
                    if (insert.disabled() == null || !resolver.evaluate(insert.disabled())) {
                        enabled.add(new ConfigRowSpec.Insert(insert.plugin(),
                            resolver.interpolate(insert.config()), null, insert.anchor()));
                    }
                }
            }
        }
        return enabled;
    }

    /**
     * @param rows 已解析行序 @return dump 文本(一行一插件:enable/disable + config)
     */
    public static String dump(List<ConfigRowSpec> rows) {
        StringBuilder out = new StringBuilder();
        for (ConfigRowSpec row : rows) {
            out.append(row.plugin());
            if (!row.config().isEmpty()) {
                out.append(' ').append(masked(row.config()));
            }
            if (disabledText(row) != null) {
                out.append("  (disabled: ").append(disabledText(row)).append(')');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String disabledText(ConfigRowSpec row) {
        return switch (row) {
            case ConfigRowSpec.Include include -> include.disabled();
            case ConfigRowSpec.Replace replace -> replace.disabled();
            case ConfigRowSpec.Insert insert -> insert.disabled();
            case ConfigRowSpec.Remove ignored -> null;
        };
    }

    /** 凭据键脱敏——dump 是人读输出,字面量 key 永不落盘。 */
    private static Map<String, Object> masked(Map<String, Object> config) {
        Map<String, Object> out = new HashMap<>();
        config.forEach((key, value) -> out.put(key,
            key.toLowerCase().contains("apikey") && value != null ? "****" : value));
        return out;
    }

    /**
     * 完整装配(组合 → 求值 → 双向校验 → Runtime + 服务 + 加载 → verify)。
     *
     * @throws IOException 读取失败
     * @throws IllegalStateException 双向校验或加载失败
     * @throws VerifyFailedException verify 违规
     */
    public static Runtime boot(BootOptions options) throws IOException {
        List<ConfigRowSpec> rows = compose(options);
        PluginLoader loader = new PluginLoader();
        Map<String, Plugin> discovered = loader.discover();
        // 双向校验对照全量行(禁用行也算引用——它在组合里,只是不加载)
        verifyComposition(discovered, rows);
        List<ConfigRowSpec> enabled = resolve(rows);

        Runtime runtime = new Runtime();
        Scope root = runtime.root();
        root.provide(ConfigService.KEY, configServiceFrom(enabled));
        root.provide(CompositionManifest.KEY, manifestFrom(enabled));
        loader.loadAll(runtime, enabled.stream().map(row -> discovered.get(row.plugin())).toList());

        if (options.verify()) {
            List<String> violations = options.verifyPolicy().check(root);
            if (!violations.isEmpty()) {
                throw new VerifyFailedException(violations);
            }
            sandboxWarning(root).ifPresent(warning -> LOG.log(System.Logger.Level.WARNING, warning));
        }
        return runtime;
    }

    /**
     * --verify 的沙箱预警（观测面，非违规——exit 码不变）：组合含受限档且本宿主
     * 受限执行会在首调用 fail-closed 时，点名平台、后果与出路。无策略行或无同机
     * provider（docker 等执行器自身消费策略）不预警。
     *
     * @param root 装配完成的 root scope
     * @return 预警文本；无需预警时 empty
     */
    static Optional<String> sandboxWarning(Scope root) {
        Optional<SandboxPolicyService> policies = root.resolve(SandboxPolicyService.KEY);
        Optional<SandboxProvider> providers = root.resolve(SandboxProvider.KEY);
        if (policies.isEmpty() || providers.isEmpty()) {
            return Optional.empty();
        }
        Session probe = Session.create(Session.newId("verify-sandbox-probe"), null, null);
        SandboxPolicy policy = policies.get().resolve(probe);
        if (!policy.mode().confining()) {
            return Optional.empty();
        }
        String mode = policy.mode().wire();
        return switch (providers.get().backendStatus()) {
            case BackendStatus.Ready ignored -> Optional.empty();
            case BackendStatus.NoBackend noBackend -> Optional.of(
                "sandbox warning: confining policy \"" + mode + "\" is configured but platform \""
                + noBackend.platform() + "\" has no same-host sandbox backend yet; the first"
                + " confined shell call will fail closed. Options: overlay mode:"
                + " danger-full-access (explicit bypass), or wait for windows-acl (0.2.0).");
            case BackendStatus.ProbeFailed failed -> Optional.of(
                "sandbox warning: confining policy \"" + mode + "\" is configured but no sandbox"
                + " backend is usable on platform \"" + failed.platform() + "\" ("
                + failed.detail() + "); the first confined shell call will fail closed."
                + " Options: install bubblewrap, or overlay mode: danger-full-access"
                + " (explicit bypass).");
        };
    }

    /** 双向显式:行引用必须存在;发现的插件必须被引用(disabled 行也算引用)。 */
    static void verifyComposition(Map<String, Plugin> discovered, List<ConfigRowSpec> rows) {
        List<String> violations = new ArrayList<>();
        for (ConfigRowSpec row : rows) {
            if (!discovered.containsKey(row.plugin())) {
                violations.add("行引用的插件未发现: " + row.plugin());
            }
        }
        java.util.Set<String> referenced = new java.util.HashSet<>();
        rows.forEach(row -> referenced.add(row.plugin()));
        for (String id : discovered.keySet()) {
            if (!referenced.contains(id)) {
                violations.add("发现的插件未被任何行引用: " + id);
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.join("; ", violations));
        }
    }

    private static ConfigService configServiceFrom(List<ConfigRowSpec> rows) {
        Map<String, Map<String, Object>> byPlugin = new HashMap<>();
        rows.forEach(row -> byPlugin.put(row.plugin(), row.config()));
        return pluginId -> byPlugin.getOrDefault(pluginId, Map.of());
    }

    private static CompositionManifest manifestFrom(List<ConfigRowSpec> rows) {
        return new CompositionManifest(rows.stream()
            .map(row -> new CompositionManifest.Row(row.plugin(), row.config()))
            .toList());
    }
}
