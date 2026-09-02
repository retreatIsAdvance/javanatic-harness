package io.javanatic.harness.session.message;

import io.javanatic.harness.kernel.brand.Id;

import java.util.Objects;

/** 模型发起的一次工具调用（arguments 为原始 JSON 字符串，未解析）。 */
public record ToolUseBlock(Id<CallId> id, String name, String arguments) implements ContentBlock {

    /** @throws NullPointerException 任一字段为 null 时 */
    public ToolUseBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}
