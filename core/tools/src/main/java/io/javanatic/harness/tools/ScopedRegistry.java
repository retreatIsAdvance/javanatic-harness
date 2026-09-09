package io.javanatic.harness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            schemas.add(new ToolSchema(tool.name(), tool.description(), toJson(tool.parameters())));
        }
        return List.copyOf(schemas);
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

    /** ValueSchema → JSON Schema 文本(wire 侧唯一序列化点)。 */
    private static String toJson(ValueSchema schema) {
        try {
            return MAPPER.writeValueAsString(toNode(schema));
        } catch (Exception e) {
            throw new IllegalStateException("tool schema serialization failed", e);
        }
    }

    private static ObjectNode toNode(ValueSchema schema) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (schema) {
            case ValueSchema.Object o -> {
                node.put("type", "object");
                ObjectNode props = node.putObject("properties");
                for (var entry : o.properties().entrySet()) {
                    props.set(entry.getKey(), toNode(entry.getValue()));
                }
                node.put("description", o.description());
            }
            case ValueSchema.Str s -> {
                node.put("type", "string");
                node.put("description", s.description());
            }
            case ValueSchema.Num n -> {
                node.put("type", "number");
                node.put("description", n.description());
            }
            case ValueSchema.Bool b -> {
                node.put("type", "boolean");
                node.put("description", b.description());
            }
        }
        return node;
    }
}
