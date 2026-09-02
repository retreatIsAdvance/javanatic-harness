package io.javanatic.harness.llm;

import io.javanatic.harness.session.message.Message;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次流式请求的厂商中立形态（adapter 负责翻译为各厂商 wire 格式）。
 *
 * @param system   系统提示词全文（null = 无系统提示词的请求）
 * @param messages 会话消息（来自 session 投影）
 * @param tools    暴露的工具（空 = 无工具请求）
 * @param params   透传参数（采样与厂商特有项；adapter 内显式 resolve 默认值）
 */
public record LlmRequest(String system, List<Message> messages,
                         List<ToolSchema> tools, Map<String, String> params) {

    /** @throws NullPointerException messages/tools/params 为 null 时 */
    public LlmRequest {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(params, "params");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        params = Map.copyOf(params);
    }
}
