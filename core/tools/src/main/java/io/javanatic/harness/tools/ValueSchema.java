package io.javanatic.harness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

/**
 * 工具参数 schema 的极简词表（fs 等 MVP 工具够用）。完整 JSON Schema 词表
 * 随需要扩展；properties 缺省全必填——可选字段以 {@link Opt} 包装
 * （校验语义：缺失放行；wire 侧零变化：解包直写内层 schema）。
 */
public sealed interface ValueSchema permits
    ValueSchema.Object, ValueSchema.Str, ValueSchema.Num, ValueSchema.Bool, ValueSchema.Arr,
    ValueSchema.Opt {

    /** 对象：未包 {@link Opt} 的 properties 全必填。 */
    record Object(String description, Map<String, ValueSchema> properties) implements ValueSchema {

        /** @throws NullPointerException description/properties 为 null 时 */
        public Object {
            Objects.requireNonNull(description, "description");
            properties = Map.copyOf(properties);
        }
    }

    /** 字符串。 */
    record Str(String description) implements ValueSchema {}

    /** 数值。 */
    record Num(String description) implements ValueSchema {}

    /** 布尔。 */
    record Bool(String description) implements ValueSchema {}

    /** @throws NullPointerException description/items 为 null 时 */
    record Arr(String description, ValueSchema items) implements ValueSchema {

        /** 数组：每个元素按 items 递归校验。 */
        public Arr {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(items, "items");
        }
    }

    /**
     * 可选字段包装：仅校验语义（缺失放行；在场按内层递归校验）。序列化解包
     * 直写内层——模型与 wire 看不到任何形态变化。
     */
    record Opt(ValueSchema schema) implements ValueSchema {

        /** @throws NullPointerException schema 为 null 时 */
        public Opt {
            Objects.requireNonNull(schema, "schema");
        }
    }

    /** ValueSchema → JSON Schema 文本（wire 侧唯一序列化点：注册面/重建面共用——字节一致是 R1 前提）。 */
    static String toJson(ValueSchema schema) {
        try {
            return Serialization.MAPPER.writeValueAsString(toNode(schema));
        } catch (Exception e) {
            throw new IllegalStateException("tool schema serialization failed", e);
        }
    }

    private static ObjectNode toNode(ValueSchema schema) {
        ObjectNode node = Serialization.MAPPER.createObjectNode();
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
            case ValueSchema.Arr a -> {
                node.put("type", "array");
                node.set("items", toNode(a.items()));
                node.put("description", a.description());
            }
            case ValueSchema.Opt o -> {
                return toNode(o.schema()); // 解包：可选是校验语义，wire 零变化
            }
        }
        return node;
    }

    /** 序列化承载：mapper 是私有 nest 状态（接口字段只能公有，故藏进嵌套类）。 */
    final class Serialization {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private Serialization() {
        }
    }
}
