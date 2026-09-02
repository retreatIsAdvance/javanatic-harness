package io.javanatic.harness.session.message;

import io.javanatic.harness.kernel.brand.Id;

import java.util.Objects;

/** 工具结果块：toolUseId 与被响应的 {@link ToolUseBlock} 配对。 */
public record ToolResultBlock(Id<CallId> toolUseId, String content, boolean isError) implements ContentBlock {

    /** @throws NullPointerException toolUseId/content 为 null 时 */
    public ToolResultBlock {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(content, "content");
    }
}
