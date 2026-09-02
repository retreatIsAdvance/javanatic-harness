package io.javanatic.harness.session.message;

import io.javanatic.harness.kernel.brand.Id;

/**
 * 消息来源。开放接口（变体可扩展），核心以嵌套 record 提供：
 * {@link User}（人类输入或注入上下文）、{@link Model}（模型输出）、
 * {@link Tool}（工具结果——callId 与 ToolResultBlock 配对）。
 */
public interface MessageSource {

    /** 人类输入。 */
    record User() implements MessageSource {}

    /** 模型输出。 */
    record Model(String provider, String model) implements MessageSource {}

    /** 工具结果（toolUseId 即其响应的那个调用）。 */
    record Tool(Id<CallId> toolUseId) implements MessageSource {}
}
