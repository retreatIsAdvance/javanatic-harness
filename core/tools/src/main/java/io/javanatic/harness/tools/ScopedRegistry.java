package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * scoped 注册表(06 §4):root(组合层)层 + 每个作用域一个 overlay 层。
 * 同层同名 fail loud(配置错误);跨层 shadowing(scoped 盖组合层,walk
 * 反序应用天然实现)。注册层键 = owner.registrationScope()——root 挂载落
 * root、agent 挂载落 agentScope;层随 owner 关闭回收(R3)。
 */
final class ScopedRegistry implements ToolRegistry {

    private final ScopedLayers<Map<String, ToolDefinition>> layers =
        new ScopedLayers<>(ConcurrentHashMap::new);

    @Override
    public Disposable register(Scope owner, ToolDefinition tool) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(tool, "tool");
        Map<String, ToolDefinition> layer = layers.of(owner.registrationScope());
        ToolDefinition prev = layer.putIfAbsent(tool.name(), tool);
        if (prev != null) {
            throw new IllegalStateException("tool already registered in scope '"
                + owner.registrationScope() + "': '" + tool.name() + "'");
        }
        return Disposable.of(() -> layer.remove(tool.name(), tool));
    }

    @Override
    public List<ToolSchema> schemas(Scope scope) {
        Map<String, ToolDefinition> merged = merged(scope);
        List<String> names = new ArrayList<>(merged.keySet());
        names.sort(String::compareTo);
        List<ToolSchema> schemas = new ArrayList<>(names.size());
        for (String name : names) {
            ToolDefinition tool = merged.get(name);
            schemas.add(new ToolSchema(tool.name(), tool.description(), ValueSchema.toJson(tool.parameters())));
        }
        return List.copyOf(schemas);
    }

    @Override
    public List<ToolDefinition> definitions(Scope scope) {
        Map<String, ToolDefinition> merged = merged(scope);
        List<String> names = new ArrayList<>(merged.keySet());
        names.sort(String::compareTo);
        List<ToolDefinition> definitions = new ArrayList<>(names.size());
        for (String name : names) {
            definitions.add(merged.get(name));
        }
        return List.copyOf(definitions);
    }

    @Override
    public Optional<ToolDefinition> resolve(Scope scope, String name) {
        return Optional.ofNullable(merged(scope).get(name));
    }

    /** root-first 合并(最近层同名覆盖 = shadowing)。 */
    private Map<String, ToolDefinition> merged(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        List<Map<String, ToolDefinition>> chain = layers.walk(scope);
        Map<String, ToolDefinition> merged = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            merged.putAll(chain.get(i));
        }
        return merged;
    }
}
