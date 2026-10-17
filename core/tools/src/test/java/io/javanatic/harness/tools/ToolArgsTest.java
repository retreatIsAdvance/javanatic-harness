package io.javanatic.harness.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 可选参数（Opt）与 wire 保真：缺失放行/在场递归校验/序列化解包/rawJson 原文透传。 */
class ToolArgsTest {

    private static final ValueSchema SCHEMA = new ValueSchema.Object("参数", Map.of(
        "req", new ValueSchema.Str("必填"),
        "opt", new ValueSchema.Opt(new ValueSchema.Num("可选"))));

    @Test
    void optionalFieldMayBeAbsent() {
        assertThat(ToolArgs.parse("{\"req\":\"x\"}", SCHEMA).readString("req")).isEqualTo("x");
    }

    @Test
    void presentOptionalFieldIsValidatedRecursively() {
        assertThat(ToolArgs.parse("{\"req\":\"x\",\"opt\":7}", SCHEMA).readLong("opt")).isEqualTo(7);
        assertThatThrownBy(() -> ToolArgs.parse("{\"req\":\"x\",\"opt\":\"nan\"}", SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("argument 'opt' must be a number");
    }

    @Test
    void missingRequiredStillFails() {
        assertThatThrownBy(() -> ToolArgs.parse("{\"opt\":1}", SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required argument: req");
    }

    @Test
    void optionalNestedObjectValidatesInnerRequiredWhenPresent() {
        ValueSchema nested = new ValueSchema.Object("参数", Map.of(
            "box", new ValueSchema.Opt(new ValueSchema.Object("盒", Map.of(
                "inner", new ValueSchema.Str("内必填"))))));
        ToolArgs.parse("{}", nested); // 缺席：整层放行
        ToolArgs.parse("{\"box\":{\"inner\":\"v\"}}", nested);
        assertThatThrownBy(() -> ToolArgs.parse("{\"box\":{}}", nested))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required argument: box.inner");
    }

    @Test
    void rawJsonPreservesWireTextVerbatim() {
        String wire = "{ \"req\" : \"x\" }";
        assertThat(ToolArgs.parse(wire, SCHEMA).rawJson()).isEqualTo(wire);
    }

    @Test
    void optSerializesTransparentlyToWire() {
        ValueSchema plain = new ValueSchema.Object("d", Map.of("opt", new ValueSchema.Num("s")));
        ValueSchema wrapped = new ValueSchema.Object("d",
            Map.of("opt", new ValueSchema.Opt(new ValueSchema.Num("s"))));
        assertThat(ValueSchema.toJson(plain)).isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"opt\":{\"type\":\"number\",\"description\":\"s\"}},"
                + "\"description\":\"d\"}");
        assertThat(ValueSchema.toJson(wrapped)).isEqualTo(ValueSchema.toJson(plain));
    }
}
