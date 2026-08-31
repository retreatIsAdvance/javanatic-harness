package io.javanatic.harness.session.message;

/**
 * LLM 消息内容块。本迭代只含 {@link TextBlock}；tool-use / tool-result 块
 * 随 tools 切片进 permits（pre-release 无兼容承诺）。
 */
public sealed interface ContentBlock permits TextBlock {
}
