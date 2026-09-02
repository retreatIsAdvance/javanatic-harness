package io.javanatic.harness.session.message;

import io.javanatic.harness.kernel.brand.Id;

/**
 * 工具调用 id 的品牌类型：模型 tool-use 与 executor 的 tool/result 经它配对。
 * 归属 session.message（消息域身份——dsh 放 llm 包靠 type-only import，
 * Java 下 ToolUseBlock/ToolResultEvent 需要它，llm 反向引用才不成环）。
 */
public final class CallId {

    private CallId() {
    }

    /** @param raw 调用 id 原文（非空） */
    public static Id<CallId> of(String raw) {
        return new Id<>(raw);
    }
}
