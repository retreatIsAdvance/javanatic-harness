package io.javanatic.harness.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javanatic.harness.tools.ValueSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema → {@link ValueSchema} 白名单映射（05 schema 边界，it27 内容 2）。
 *
 * <p>支持构造 = {@code type}（object/string/number/integer/boolean/array，递归）
 * + {@code description} + {@code properties}/{@code required}（未列者包
 * {@link ValueSchema.Opt}）+ {@code items}（单对象形）；{@code enum}/{@code const}
 * 降级拼接进 description（模型可读、校验不误伤——Opt 的 wire 与语义先例）。
 *
 * <p>分层容错（工具级 fail loud × 属性级跳过）：不能诚实表达者
 * （oneOf/anyOf/allOf/not/$ref/$dynamicRef/$recursiveRef/if-then-else/
 * prefixItems/contains/dependencies/dependentSchemas/dependentRequired/
 * unevaluatedItems/unevaluatedProperties 与 additionalProperties 模式形、
 * 白名单外未知关键字）→ <b>该工具排除 + WARN</b>；顶层非 object →
 * 工具排除（{@code ToolArgs.parse} 恒要求 JSON 对象）；属性级结构失败
 * （子 schema 非对象/缺或坏 type/array items 缺或非单对象/嵌套 properties
 * 非对象）→ <b>该属性排除 + WARN，工具仍注册</b>。
 *
 * <p>{@link #map} 是纯函数（不落日志：同一输入恒同输出——注册面与回放 fold
 * 面共用，确定性是 R1 重建的前提）；WARN 由调用侧（apply）单点落账，避免
 * 每 REQUEST 检查点重复告警。
 */
final class McpSchemaMapping {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 直接消费的关键字（结构检查与递归在下方按 type 分派）。 */
    private static final Set<String> CONSUMED = Set.of(
        "type", "description", "properties", "required", "items", "enum", "const");

    /** 静默忽略的关键字：不参与映射、不告警（wire 语义无表达，忽略即诚实）。 */
    private static final Set<String> IGNORED = Set.of(
        "title", "$comment", "examples", "default", "deprecated", "readOnly", "writeOnly",
        "$schema", "$id", "$defs", "$anchor", "definitions",
        "pattern", "format", "minLength", "maxLength",
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
        "minItems", "maxItems", "uniqueItems",
        "minProperties", "maxProperties", "propertyNames");

    /** 显式不支持的构造（点名诊断；白名单外不论何名一律排除，等价 fail loud）。 */
    private static final Set<String> UNSUPPORTED = Set.of(
        "oneOf", "anyOf", "allOf", "not",
        "$ref", "$dynamicRef", "$recursiveRef",
        "if", "then", "else",
        "prefixItems", "contains",
        "dependencies", "dependentSchemas", "dependentRequired",
        "unevaluatedItems", "unevaluatedProperties");

    private McpSchemaMapping() {
    }

    /**
     * 映射入口（纯函数）。
     *
     * @param inputSchemaJson tools/list 的 JSON Schema 原文
     * @param toolLabel 工具全名（{@code mcp__<server>__<raw>}，warnings 上下文）
     * @return Mapping；schema=null 表示该工具排除（warnings 携带理由，含标签）
     */
    static Mapping map(String inputSchemaJson, String toolLabel) {
        JsonNode root;
        try {
            root = MAPPER.readTree(inputSchemaJson);
        } catch (Exception e) {
            return new Mapping(null, List.of(toolLabel + " excluded: input schema is not valid JSON"));
        }
        List<String> warnings = new ArrayList<>();
        try {
            return new Mapping(mapRoot(root, toolLabel, warnings), warnings);
        } catch (ExcludedException e) {
            warnings.add(toolLabel + " excluded: " + e.getMessage());
            return new Mapping(null, warnings);
        }
    }

    private static ValueSchema mapRoot(JsonNode root, String toolLabel, List<String> warnings) {
        if (!root.isObject()) {
            throw new ExcludedException("input schema must be a JSON object");
        }
        scanKeywords(root);
        JsonNode type = root.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            throw new ExcludedException("top-level schema must be type \"object\"");
        }
        try {
            return mapObject(root, "$", toolLabel, warnings);
        } catch (PropertyStructuralFailure e) {
            throw new ExcludedException(e.getMessage()); // 根位结构失败 = 工具级
        }
    }

    /** 每个对象节点先过关键字白名单（工具级 fail loud：任意位置的不支持构造都排除该工具）。 */
    private static void scanKeywords(JsonNode node) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (UNSUPPORTED.contains(name)) {
                throw new ExcludedException("unsupported construct: " + name);
            }
            if ("additionalProperties".equals(name)) {
                if (!node.get(name).isBoolean()) {
                    throw new ExcludedException(
                        "unsupported construct: additionalProperties (schema form)");
                }
                continue; // 布尔形：忽略（语义同 JSON Schema 缺省宽容面）
            }
            if (!CONSUMED.contains(name) && !IGNORED.contains(name)) {
                throw new ExcludedException("unknown keyword: " + name);
            }
        }
    }

    private static ValueSchema mapObject(JsonNode node, String path,
                                         String toolLabel, List<String> warnings) {
        Set<String> required = requiredNames(node);
        Map<String, ValueSchema> properties = new LinkedHashMap<>();
        JsonNode propertiesNode = node.get("properties");
        if (propertiesNode != null) {
            if (!propertiesNode.isObject()) {
                throw new PropertyStructuralFailure("properties must be a JSON object");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                try {
                    properties.put(field.getKey(),
                        mapProperty(field.getValue(), childPath, toolLabel, warnings));
                } catch (PropertyStructuralFailure e) {
                    warnings.add(toolLabel + " " + childPath + " excluded: " + e.getMessage());
                }
            }
        }
        Map<String, ValueSchema> wrapped = new LinkedHashMap<>();
        for (Map.Entry<String, ValueSchema> entry : properties.entrySet()) {
            wrapped.put(entry.getKey(), required.contains(entry.getKey())
                ? entry.getValue()
                : new ValueSchema.Opt(entry.getValue()));
        }
        return new ValueSchema.Object(descriptionOf(node), wrapped);
    }

    private static ValueSchema mapProperty(JsonNode node, String path,
                                           String toolLabel, List<String> warnings) {
        if (!node.isObject()) {
            throw new PropertyStructuralFailure("schema must be a JSON object");
        }
        scanKeywords(node);
        JsonNode type = node.get("type");
        if (type == null || !type.isTextual()) {
            throw new PropertyStructuralFailure("type must be a single supported string");
        }
        return switch (type.asText()) {
            case "object" -> mapObject(node, path, toolLabel, warnings);
            case "array" -> mapArray(node, path, toolLabel, warnings);
            case "string" -> new ValueSchema.Str(descriptionOf(node));
            case "number", "integer" -> new ValueSchema.Num(descriptionOf(node));
            case "boolean" -> new ValueSchema.Bool(descriptionOf(node));
            default -> throw new PropertyStructuralFailure(
                "type must be a single supported string (got: " + type.asText() + ")");
        };
    }

    private static ValueSchema mapArray(JsonNode node, String path,
                                        String toolLabel, List<String> warnings) {
        JsonNode items = node.get("items");
        if (items == null || !items.isObject()) {
            throw new PropertyStructuralFailure("array items must be a single schema object");
        }
        return new ValueSchema.Arr(descriptionOf(node),
            mapProperty(items, path + "[]", toolLabel, warnings));
    }

    /** required：缺 → 空（全 Opt）；非字符串数组 → 工具级排除；不在 properties 的名字静默忽略。 */
    private static Set<String> requiredNames(JsonNode node) {
        JsonNode requiredNode = node.get("required");
        if (requiredNode == null) {
            return Set.of();
        }
        if (!requiredNode.isArray()) {
            throw new ExcludedException("required must be an array of strings");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode item : requiredNode) {
            if (!item.isTextual()) {
                throw new ExcludedException("required must be an array of strings");
            }
            names.add(item.asText());
        }
        return names;
    }

    /** description（非文本按缺失）+ enum/const 降级拼接（join：非空则换行追加）。 */
    private static String descriptionOf(JsonNode node) {
        JsonNode description = node.get("description");
        String text = description != null && description.isTextual() ? description.asText() : "";
        String degraded = degradationText(node);
        if (degraded.isEmpty()) {
            return text;
        }
        return text.isEmpty() ? degraded : text + "\n" + degraded;
    }

    /** enum/const 降级为描述文本；形态坏（非数组 enum / 非标量值）→ 工具级排除。 */
    private static String degradationText(JsonNode node) {
        List<String> parts = new ArrayList<>(2);
        JsonNode enumNode = node.get("enum");
        if (enumNode != null) {
            if (!enumNode.isArray()) {
                throw new ExcludedException("enum must be an array");
            }
            if (!enumNode.isEmpty()) { // 空 enum：跳过（不降级、不告警）
                List<String> values = new ArrayList<>(enumNode.size());
                for (JsonNode item : enumNode) {
                    if (!item.isValueNode() || item.isNull()) {
                        throw new ExcludedException("enum values must be scalars");
                    }
                    values.add(item.asText());
                }
                parts.add("allowed values: " + String.join(", ", values));
            }
        }
        JsonNode constNode = node.get("const");
        if (constNode != null) {
            if (!constNode.isValueNode() || constNode.isNull()) {
                throw new ExcludedException("const must be a scalar");
            }
            parts.add("const: " + constNode.asText());
        }
        return String.join("\n", parts);
    }

    /** 映射结果：schema 非空 = 可注册；schema 为 null = 该工具排除（warnings 携带理由）。 */
    record Mapping(ValueSchema schema, List<String> warnings) {

        Mapping {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * 工具级排除（不支持构造 / 未知关键字 / 顶层形态不符 / required・enum・const 形态坏）。
     *
     * <p>控制流豁免（评审备注 1）：解析器递归下降的最近捕获点语义——以禁栈异常做
     * 深度取消、非错误路径；返回值表达会让各分支逐层判空回传，白名单分档失真。
     */
    private static final class ExcludedException extends RuntimeException {

        ExcludedException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * 属性级结构失败：最近的对象分支按子捕获 → 该属性排除 + WARN，工具仍注册。
     *
     * <p>控制流豁免同 {@link ExcludedException}：分档容错的「最近捕获点」取消信号。
     */
    private static final class PropertyStructuralFailure extends RuntimeException {

        PropertyStructuralFailure(String message) {
            super(message, null, false, false);
        }
    }
}
