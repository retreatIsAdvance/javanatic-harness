package io.javanatic.harness.llm;

import io.javanatic.harness.kernel.scope.Disposable;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 路由默认实现：provider 名 → adapter 注册表。注册与注销是运行时操作
 * （Disposable 自管生命周期，无 effect 栈），服务实例随提供它的插件回滚而消亡。
 */
final class RoutingLlmService implements LlmService {

    private final Map<String, LlmAdapter> adapters = new ConcurrentHashMap<>();

    @Override
    public Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(signal, "signal");
        LlmAdapter adapter = adapters.get(config.provider());
        if (adapter == null) {
            // fail loud 并列出可选项：装配缺口的诊断信息就地给出
            throw new IllegalStateException(
                "no llm adapter registered for provider '" + config.provider()
                    + "' (registered: " + String.join(", ", new TreeMap<>(adapters).keySet()) + ")");
        }
        return adapter.stream(config, request, signal);
    }

    @Override
    public Disposable registerAdapter(String provider, LlmAdapter adapter) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(adapter, "adapter");
        if (provider.isEmpty()) {
            throw new IllegalArgumentException("provider name must be non-empty");
        }
        if (adapters.putIfAbsent(provider, adapter) != null) {
            throw new IllegalStateException("llm adapter already registered for provider '" + provider + "'");
        }
        return Disposable.of(() -> adapters.remove(provider, adapter));
    }
}
