package io.javanatic.harness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.llm.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 注册表默认实现：名称 → 定义，注销凭据自管生命周期（同 RoutingLlmService 模式）。 */
final class RegistryImpl implements ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    @Override
    public Disposable register(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool");
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalStateException("tool already registered: '" + tool.name() + "'");
        }
        return io.javanatic.harness.kernel.scope.Disposable.of(
            () -> tools.remove(tool.name(), tool));
    }

    @Override
    public List<ToolSchema> schemas() {
        List<String> names = new ArrayList<>(tools.keySet());
        names.sort(String::compareTo);
        List<ToolSchema> schemas = new ArrayList<>(names.size());
        for (String name : names) {
            ToolDefinition tool = tools.get(name);
            schemas.add(new ToolSchema(tool.name(), tool.description(), toJson(tool.parameters())));
        }
        return List.copyOf(schemas);
    }

    @Override
    public Optional<ToolDefinition> resolve(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** ValueSchema → JSON Schema 文本（wire 侧唯一序列化点）。 */
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
