package io.javanatic.harness.tools;

import java.util.Map;
import java.util.Objects;

/**
 * 工具参数 schema 的极简词表（fs 等 MVP 工具够用）。完整 JSON Schema 词表
 * 随需要扩展；properties 全部视为必填——可选字段进扩展。
 */
public sealed interface ValueSchema permits ValueSchema.Object, ValueSchema.Str, ValueSchema.Num, ValueSchema.Bool {

    /** 对象：properties 全必填。 */
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
}
