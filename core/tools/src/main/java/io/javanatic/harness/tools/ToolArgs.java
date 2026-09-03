package io.javanatic.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * 已校验的工具实参（模型 JSON 经 {@link #parse} 按 schema 校验后封装）。
 * 模型/tool JSON 是持久化与信任边界（08 §6），这是仓库里 Jackson 的第一个落点。
 */
public final class ToolArgs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode node;

    private ToolArgs(JsonNode node) {
        this.node = node;
    }

    /**
     * @throws IllegalArgumentException 不是 JSON 对象、必填字段缺失或类型不符
     */
    public static ToolArgs parse(String argumentsJson, ValueSchema schema) {
        Objects.requireNonNull(schema, "schema");
        JsonNode root;
        try {
            root = MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("tool arguments are not valid JSON", e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("tool arguments must be a JSON object");
        }
        validate(root, schema, "");
        return new ToolArgs(root);
    }

    private static void validate(JsonNode node, ValueSchema schema, String path) {
        switch (schema) {
            case ValueSchema.Object o -> {
                for (var entry : o.properties().entrySet()) {
                    JsonNode child = node.get(entry.getKey());
                    if (child == null) {
                        throw new IllegalArgumentException("missing required argument: " + path + entry.getKey());
                    }
                    validate(child, entry.getValue(), path + entry.getKey() + ".");
                }
            }
            case ValueSchema.Str s -> requireType(node.isTextual(), path, "a string");
            case ValueSchema.Num n -> requireType(node.isNumber(), path, "a number");
            case ValueSchema.Bool b -> requireType(node.isBoolean(), path, "a boolean");
        }
    }

    private static void requireType(boolean ok, String path, String expected) {
        if (!ok) {
            String field = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
            throw new IllegalArgumentException("argument '" + field + "' must be " + expected);
        }
    }

    /** 读必填字符串字段（parse 已保证存在且类型正确）。 */
    public String readString(String field) {
        return node.get(field).asText();
    }

    /** 读必填数值字段。 */
    public long readLong(String field) {
        return node.get(field).asLong();
    }

    /** 读必填布尔字段。 */
    public boolean readBoolean(String field) {
        return node.get(field).asBoolean();
    }
}
