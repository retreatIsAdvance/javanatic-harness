package io.javanatic.harness.session.message;

import java.util.List;

/**
 * 进入 LLM 请求的消息。sealed：本轮只有用户与助手两种；工具结果以
 * UserMessage + ToolResultBlock 的形态随 tools 切片加入。
 * 归属 session（被日志的事实）：dsh 依赖 TS type-only import 从 llm 借用消息
 * 类型，Java 无此机制，依赖方向要求消息模型在被日志的一侧。
 */
public sealed interface Message permits UserMessage, AssistantMessage {

    /** 消息来源。 */
    MessageSource source();

    /** 内容块（构造时已冻结为不可变列表）。 */
    List<ContentBlock> content();
}
