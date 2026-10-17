package io.javanatic.harness.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javanatic.harness.tools.ToolArgs;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 白名单映射矩阵：支持构造 / Opt 语义 / enum・const 降级 / IGNORED 静默 / 两档排除 / 确定性。 */
class McpSchemaMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpSchemaMapping.Mapping map(String input, String label) {
        return McpSchemaMapping.map(input, label);
    }

    private static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("test fixture is not JSON", e);
        }
    }

    private static ValueSchema schema(McpSchemaMapping.Mapping mapping) {
        assertThat(mapping.schema()).isNotNull();
        return mapping.schema();
    }

    private static ValueSchema property(McpSchemaMapping.Mapping mapping, String name) {
        return ((ValueSchema.Object) schema(mapping)).properties().get(name);
    }

    @Test
    void mapsSupportedConstructsWithWireEquivalence() {
        String input = """
            {
              "type": "object",
              "description": "echo args",
              "properties": {
                "text": {"type": "string", "description": "the text"},
                "count": {"type": "integer", "description": "how many"},
                "ratio": {"type": "number", "description": "the ratio"},
                "loud": {"type": "boolean", "description": "be loud"},
                "tags": {"type": "array", "description": "labels", "items": {"type": "string", "description": "one tag"}},
                "nested": {"type": "object", "description": "wrapper", "properties": {
                  "inner": {"type": "string", "description": "the inner"}}}
              },
              "required": ["text"]
            }
            """;
        McpSchemaMapping.Mapping mapping = map(input, "mcp__alpha__echo");
        assertThat(mapping.warnings()).isEmpty();

        // 必填不包 Opt；未列者包 Opt（integer → Num：弱化面已登记偏离）
        assertThat(property(mapping, "text")).isInstanceOf(ValueSchema.Str.class);
        assertThat(property(mapping, "count")).isInstanceOf(ValueSchema.Opt.class);
        assertThat(((ValueSchema.Opt) property(mapping, "count")).schema())
            .isInstanceOf(ValueSchema.Num.class);
        assertThat(((ValueSchema.Opt) property(mapping, "ratio")).schema())
            .isInstanceOf(ValueSchema.Num.class);
        assertThat(((ValueSchema.Opt) property(mapping, "loud")).schema())
            .isInstanceOf(ValueSchema.Bool.class);
        assertThat(((ValueSchema.Opt) property(mapping, "tags")).schema())
            .isInstanceOf(ValueSchema.Arr.class);
        assertThat(((ValueSchema.Opt) property(mapping, "nested")).schema())
            .isInstanceOf(ValueSchema.Object.class);

        // wire 唯一序列化点（R1 前提）：树比较规避 Map.copyOf 序不定（integer 弱化为 number）
        JsonNode actual = tree(ValueSchema.toJson(schema(mapping)));
        JsonNode wanted = tree("""
            {
              "type": "object",
              "properties": {
                "text": {"type": "string", "description": "the text"},
                "count": {"type": "number", "description": "how many"},
                "ratio": {"type": "number", "description": "the ratio"},
                "loud": {"type": "boolean", "description": "be loud"},
                "tags": {"type": "array", "description": "labels",
                         "items": {"type": "string", "description": "one tag"}},
                "nested": {"type": "object", "description": "wrapper",
                           "properties": {"inner": {"type": "string", "description": "the inner"}}}
              },
              "description": "echo args"
            }
            """);
        assertThat(actual).isEqualTo(wanted);
    }

    /** Opt 校验语义：缺失放行、在场按内层递归（wire 零变化的校验面）。 */
    @Test
    void optionalFieldsValidateOnlyWhenPresent() {
        String input = """
            {"type": "object",
             "properties": {"text": {"type": "string"}, "count": {"type": "integer"}},
             "required": ["text"]}
            """;
        ValueSchema schema = schema(map(input, "t"));
        assertThat(ToolArgs.parse("{\"text\": \"hi\"}", schema)).isNotNull();
        assertThatThrownBy(() -> ToolArgs.parse("{}", schema))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required argument: text");
        assertThatThrownBy(() -> ToolArgs.parse("{\"text\": \"hi\", \"count\": \"nope\"}", schema))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("argument 'count' must be a number");
    }

    @Test
    void enumAndConstDegradeIntoDescription() {
        String input = """
            {"type": "object", "properties": {
              "color": {"type": "string", "description": "pick", "enum": ["red", "green"]},
              "plain": {"type": "string", "enum": [1, true, "x"]},
              "empty": {"type": "string", "enum": [], "description": "kept"},
              "version": {"type": "string", "const": "v2"}}}
            """;
        McpSchemaMapping.Mapping mapping = map(input, "t");
        assertThat(mapping.warnings()).isEmpty();
        assertThat(((ValueSchema.Str) ((ValueSchema.Opt) property(mapping, "color")).schema()).description())
            .isEqualTo("pick\nallowed values: red, green");
        assertThat(((ValueSchema.Str) ((ValueSchema.Opt) property(mapping, "plain")).schema()).description())
            .isEqualTo("allowed values: 1, true, x");
        assertThat(((ValueSchema.Str) ((ValueSchema.Opt) property(mapping, "empty")).schema()).description())
            .isEqualTo("kept"); // 空 enum：不降级、不告警
        assertThat(((ValueSchema.Str) ((ValueSchema.Opt) property(mapping, "version")).schema()).description())
            .isEqualTo("const: v2");
    }

    @Test
    void ignoredKeywordsStaySilentAndWireUnchanged() {
        String input = """
            {"type": "object", "title": "Echo", "$comment": "c", "examples": [{}], "default": {},
             "deprecated": false, "readOnly": false, "writeOnly": false,
             "$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "urn:x",
             "$defs": {}, "$anchor": "a", "definitions": {},
             "minProperties": 0, "maxProperties": 3, "propertyNames": {"pattern": "x"},
             "properties": {"text": {"type": "string", "pattern": "^a", "format": "email",
               "minLength": 1, "maxLength": 3, "minimum": 0, "maximum": 2,
               "exclusiveMinimum": 0, "exclusiveMaximum": 2, "multipleOf": 1,
               "uniqueItems": true, "additionalProperties": true}},
             "additionalProperties": false}
            """;
        McpSchemaMapping.Mapping mapping = map(input, "t");
        assertThat(mapping.warnings()).isEmpty();
        McpSchemaMapping.Mapping baseline = map("""
            {"type": "object", "properties": {"text": {"type": "string"}}}
            """, "t");
        assertThat(tree(ValueSchema.toJson(schema(mapping))))
            .isEqualTo(tree(ValueSchema.toJson(schema(baseline))));
    }

    /** 属性级结构失败：该属性排除 + WARN，工具仍注册、其余属性保留。 */
    @Test
    void propertyStructuralFailuresDropPropertyOnly() {
        String input = """
            {"type": "object", "properties": {
              "ok": {"type": "string"},
              "badType": {"type": ["string", "null"]},
              "noType": {},
              "badProps": {"type": "object", "properties": "nope"},
              "badItems": {"type": "array", "items": [{"type": "string"}]},
              "noItems": {"type": "array"},
              "notObject": 42}}
            """;
        McpSchemaMapping.Mapping mapping = map(input, "mcp__a__b");
        assertThat(((ValueSchema.Object) schema(mapping)).properties().keySet())
            .containsExactlyInAnyOrder("ok");
        assertThat(mapping.warnings())
            .hasSize(6)
            .allMatch(w -> w.startsWith("mcp__a__b $."))
            .anyMatch(w -> w.contains("$.badType excluded: type must be a single supported string"))
            .anyMatch(w -> w.contains("$.noType excluded: type must be a single supported string"))
            .anyMatch(w -> w.contains("$.badProps excluded: properties must be a JSON object"))
            .anyMatch(w -> w.contains("$.badItems excluded: array items must be a single schema object"))
            .anyMatch(w -> w.contains("$.noItems excluded: array items must be a single schema object"))
            .anyMatch(w -> w.contains("$.notObject excluded: schema must be a JSON object"));
    }

    /** 嵌套路径记法（"$"/"$.x"/"$.x[]"）：父属性保留、叶失败只掉叶。 */
    @Test
    void nestedPropertyPathsAreTracked() {
        String input = """
            {"type": "object", "properties": {
              "obj": {"type": "object", "properties": {"bad": {"type": []}}},
              "arr": {"type": "array", "items": {"type": "object",
                "properties": {"bad": 7, "good": {"type": "string"}}}}}}
            """;
        McpSchemaMapping.Mapping mapping = map(input, "t");
        assertThat(mapping.warnings())
            .anyMatch(w -> w.contains("$.obj.bad excluded"))
            .anyMatch(w -> w.contains("$.arr[].bad excluded"));
        assertThat(((ValueSchema.Object) schema(mapping)).properties().keySet())
            .containsExactlyInAnyOrder("obj", "arr");
        ValueSchema.Arr arr = (ValueSchema.Arr)
            ((ValueSchema.Opt) property(mapping, "arr")).schema();
        assertThat(((ValueSchema.Object) arr.items()).properties().keySet())
            .containsExactly("good");
    }

    /** 工具级排除：不支持构造 / 未知关键字（任意位置，含属性位）。 */
    @Test
    void unsupportedConstructsExcludeWholeTool() {
        assertThat(map("{\"type\": \"object\", \"oneOf\": []}", "t").warnings())
            .containsExactly("t excluded: unsupported construct: oneOf");
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\","
                + " \"$ref\": \"#/$defs/x\"}}}", "t").warnings())
            .containsExactly("t excluded: unsupported construct: $ref");
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\","
                + " \"if\": {}, \"then\": {}, \"else\": {}}}}", "t").warnings())
            .containsExactly("t excluded: unsupported construct: if");
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\","
                + " \"additionalProperties\": {\"type\": \"string\"}}}}", "t").warnings())
            .containsExactly("t excluded: unsupported construct: additionalProperties (schema form)");
        assertThat(map("{\"type\": \"object\", \"somethingNew\": 1}", "t").warnings())
            .containsExactly("t excluded: unknown keyword: somethingNew");
        // 枚举全表：oneOf/anyOf/allOf/……逐一至少覆盖到点名诊断面
        for (String keyword : new String[] {
            "oneOf", "anyOf", "allOf", "not", "$dynamicRef", "$recursiveRef", "prefixItems",
            "contains", "dependencies", "dependentSchemas", "dependentRequired",
            "unevaluatedItems", "unevaluatedProperties"}) {
            String value = switch (keyword) {
                case "oneOf", "anyOf", "allOf", "prefixItems" -> "[]";
                case "dependencies", "dependentSchemas", "dependentRequired" -> "{}";
                default -> "false";
            };
            assertThat(map("{\"type\": \"object\", \"" + keyword + "\": " + value + "}", "t").warnings())
                .as("keyword: %s", keyword)
                .containsExactly("t excluded: unsupported construct: " + keyword);
        }
    }

    @Test
    void topLevelShapeFailuresExcludeTool() {
        assertThat(map("{not json", "t").warnings())
            .containsExactly("t excluded: input schema is not valid JSON");
        assertThat(map("[1, 2]", "t").warnings())
            .containsExactly("t excluded: input schema must be a JSON object");
        assertThat(map("{}", "t").warnings())
            .containsExactly("t excluded: top-level schema must be type \"object\"");
        assertThat(map("{\"type\": \"string\"}", "t").warnings())
            .containsExactly("t excluded: top-level schema must be type \"object\"");
        assertThat(map("{\"type\": \"object\", \"properties\": []}", "t").warnings())
            .containsExactly("t excluded: properties must be a JSON object");
    }

    /** enum/const 形态坏与 required 形态坏 = 工具级（不能诚实表达）。 */
    @Test
    void malformedEnumConstRequiredExcludeTool() {
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\","
                + " \"enum\": \"red\"}}}", "t").warnings())
            .containsExactly("t excluded: enum must be an array");
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\","
                + " \"enum\": [[1]]}}}", "t").warnings())
            .containsExactly("t excluded: enum values must be scalars");
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\","
                + " \"const\": {\"a\": 1}}}}", "t").warnings())
            .containsExactly("t excluded: const must be a scalar");
        assertThat(map("{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}},"
                + " \"required\": \"x\"}", "t").warnings())
            .containsExactly("t excluded: required must be an array of strings");
    }

    /** required 冗余名（不在 properties）：静默忽略、不告警。 */
    @Test
    void requiredNamesOutsidePropertiesAreIgnored() {
        McpSchemaMapping.Mapping mapping = map(
            "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}},"
            + " \"required\": [\"x\", \"ghost\"]}", "t");
        assertThat(mapping.warnings()).isEmpty();
        assertThat(property(mapping, "x")).isInstanceOf(ValueSchema.Str.class);
    }

    /** 确定性：同一输入两跑同结果（注册面与回放 fold 面共用的前提）。 */
    @Test
    void mappingIsDeterministicAcrossRuns() {
        String input = """
            {"type": "object", "description": "d",
             "properties": {
               "a": {"type": "string", "enum": ["x", "y"]},
               "b": {"type": "array", "items": {"type": "object", "properties": {
                 "c": {"type": "boolean"}}}},
               "bad": {"type": ["string"]}},
             "required": ["a"]}
            """;
        McpSchemaMapping.Mapping first = map(input, "mcp__s__t");
        McpSchemaMapping.Mapping second = map(input, "mcp__s__t");
        assertThat(first.schema()).isEqualTo(second.schema());
        assertThat(first.warnings()).isEqualTo(second.warnings());
    }
}
