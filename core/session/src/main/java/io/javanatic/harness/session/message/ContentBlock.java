package io.javanatic.harness.session.message;

/**
 * LLM 消息内容块：文本、模型发起的工具调用、工具结果（tools 切片已进齐）。
 */
public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock {
}
