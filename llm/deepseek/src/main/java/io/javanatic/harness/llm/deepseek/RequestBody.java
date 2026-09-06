package io.javanatic.harness.llm.deepseek;

import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.ToolSchema;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容请求体构造（纯函数，keyless 可测）。消息/工具是模型信任边界：
 * 所有内容以字符串原样进 wire；参数归一在此集中（known 数值键转换，未知键透传）。
 */
final class RequestBody {

    /** 归一为数值的采样键；其余键原样透传（厂商拒绝时其错误如实浮出）。 */
    private static final Set<String> NUMERIC_PARAMS = Set.of("temperature", "top_p",
        "presence_penalty", "frequency_penalty", "max_tokens");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBody() {
    }

    /** @return POST /chat/completions 的 JSON 请求体 */
    static ObjectNode build(LlmCallConfig config, LlmRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model());
        body.put("stream", true);
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);

        ArrayNode messages = body.putArray("messages");
        if (request.system() != null) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.system());
        }
        for (Message message : request.messages()) {
            appendMessage(messages, message);
        }

        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSchema schema : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", schema.name());
                function.put("description", schema.description());
                function.set("parameters", readParameters(schema));
            }
        }

        for (Map.Entry<String, String> entry : request.params().entrySet()) {
            if (NUMERIC_PARAMS.contains(entry.getKey())) {
                body.put(entry.getKey(), Double.parseDouble(entry.getValue()));
            } else {
                body.put(entry.getKey(), entry.getValue());
            }
        }
        return body;
    }

    private static JsonNode readParameters(ToolSchema schema) {
        try {
            return MAPPER.readTree(schema.parametersJson());
        } catch (Exception e) {
            throw new IllegalStateException("tool schema not valid JSON: " + schema.name(), e);
        }
    }

    private static void appendMessage(ArrayNode messages, Message message) {
        switch (message) {
            case UserMessage user -> appendUser(messages, user);
            case AssistantMessage assistant -> appendAssistant(messages, assistant);
        }
    }

    /** 单文本 → content 字符串;工具结果块 → role=tool 行(与 tool_use 配对)。 */
    private static void appendUser(ArrayNode messages, UserMessage user) {
        String text = null;
        for (ContentBlock block : user.content()) {
            if (block instanceof ToolResultBlock toolResult) {
                ObjectNode tool = messages.addObject();
                tool.put("role", "tool");
                tool.put("tool_call_id", toolResult.toolUseId().value());
                tool.put("content", toolResult.content());
            } else if (block instanceof TextBlock textBlock) {
                text = textBlock.text();
            }
        }
        if (text != null) {
            ObjectNode node = messages.addObject();
            node.put("role", "user");
            node.put("content", text);
        }
    }

    private static void appendAssistant(ArrayNode messages, AssistantMessage assistant) {
        ObjectNode node = messages.addObject();
        node.put("role", "assistant");
        StringBuilder content = new StringBuilder();
        ArrayNode toolCalls = null;
        for (ContentBlock block : assistant.content()) {
            if (block instanceof TextBlock textBlock) {
                content.append(textBlock.text());
            } else if (block instanceof ToolUseBlock toolUse) {
                if (toolCalls == null) {
                    toolCalls = node.putArray("tool_calls");
                }
                ObjectNode call = toolCalls.addObject();
                call.put("id", toolUse.id().value());
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", toolUse.name());
                function.put("arguments", toolUse.arguments());
            }
        }
        if (!content.isEmpty()) {
            node.put("content", content.toString());
        } else {
            node.putNull("content");
        }
    }
}
