package io.javanatic.harness.llm;

import java.util.Objects;

/**
 * 暴露给模型的工具描述（厂商中立形态；wire 侧的 function/tool 翻译归 adapter）。
 *
 * @param name           工具名（跨厂商稳定标识）
 * @param description    给模型看的说明
 * @param parametersJson JSON Schema（原文字符串）
 */
public record ToolSchema(String name, String description, String parametersJson) {

    /** @throws NullPointerException 任一字段为 null 时 */
    public ToolSchema {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJson, "parametersJson");
    }
}
