package io.javanatic.harness.session.message;

/** 一步的 token 计量（适配器报告什么就记什么，未报告的字段为 0）。 */
public record TokenUsage(long inputTokens, long outputTokens, long reasoningTokens) {
}
