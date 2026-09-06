package io.javanatic.harness.session.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化 seam 自有的 JSON 树(零 Jackson):codec 以它为载体成为纯函数,
 * 与 Jackson 的互译归具体后端。事件模型只含字符串/整数/布尔/数组/对象。
 */
public sealed interface JsonValue permits JsonValue.Obj, JsonValue.Arr, JsonValue.Str,
        JsonValue.Num, JsonValue.Bool, JsonValue.Null {

    /** 对象(字段序即写入序;不可变)。 */
    record Obj(Map<String, JsonValue> fields) implements JsonValue {
        public Obj {
            fields = Map.copyOf(fields);
        }

        /** @return 字段值;缺失为 {@link Null#INSTANCE} */
        public JsonValue get(String field) {
            return fields.getOrDefault(field, Null.INSTANCE);
        }
    }

    /** 数组。 */
    record Arr(List<JsonValue> items) implements JsonValue {
        public Arr {
            items = List.copyOf(items);
        }
    }

    /** 字符串。 */
    record Str(String value) implements JsonValue {
    }

    /** 整数(事件词表无浮点)。 */
    record Num(long value) implements JsonValue {
    }

    /** 布尔。 */
    record Bool(boolean value) implements JsonValue {
    }

    /** null。 */
    final class Null implements JsonValue {
        /** 单例。 */
        public static final Null INSTANCE = new Null();

        private Null() {
        }
    }

    /** @return 字符串值(Null → null) */
    default String asString() {
        return this instanceof Str str ? str.value() : null;
    }

    /** @return 整数值(Null/缺失 → 0) */
    default long asLong() {
        return this instanceof Num num ? num.value() : 0L;
    }

    /** @return 布尔值(Null/缺失 → false) */
    default boolean asBool() {
        return this instanceof Bool bool && bool.value();
    }

    /** @return 对象值(非对象/Null → null) */
    default Obj asObj() {
        return this instanceof Obj obj ? obj : null;
    }

    /** 对象便捷取值。 */
    default JsonValue get(String field) {
        return this instanceof Obj obj ? obj.get(field) : Null.INSTANCE;
    }

    /** 对象构建器(字段序保持)。 */
    static Builder object() {
        return new Builder();
    }

    /** 数组构建。 */
    static JsonValue array(JsonValue... items) {
        return new Arr(List.of(items));
    }

    /** 构建器:链式 set 后 build 冻结。 */
    final class Builder {
        private final Map<String, JsonValue> fields = new LinkedHashMap<>();

        public Builder set(String field, JsonValue value) {
            fields.put(field, value);
            return this;
        }

        public Builder set(String field, String value) {
            return set(field, value == null ? Null.INSTANCE : new Str(value));
        }

        public Builder set(String field, long value) {
            return set(field, new Num(value));
        }

        public Builder set(String field, boolean value) {
            return set(field, new Bool(value));
        }

        public Obj build() {
            return new Obj(fields);
        }
    }
}
