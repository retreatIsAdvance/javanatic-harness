package io.javanatic.harness.session.event;

import java.util.Map;
import java.util.Objects;

/**
 * R1 锚点：一次 LLM 请求的指纹。log-only（非 surface）。内容不重复存——
 * 消息窗口可由 {@code [messagesFromSeq, messagesToSeq]}（含端点）重投影，
 * 系统提示词与工具 schema 的确定性由两个 sha256 证明（重组装后比对，
 * 代码演进悄悄改变提示词时旧会话回放立即暴露）。
 * ignorable：遥测性记录，旧读取方可跳过。
 */
public record LlmRequestEvent(
    long time,
    int turn,
    int step,
    String systemPromptSha256,
    String toolsSchemaSha256,
    long messagesFromSeq,
    long messagesToSeq,
    Map<String, String> params
) implements SessionEvent {

    /** @throws NullPointerException 哈希/params 为 null 时 */
    public LlmRequestEvent {
        Objects.requireNonNull(systemPromptSha256, "systemPromptSha256");
        Objects.requireNonNull(toolsSchemaSha256, "toolsSchemaSha256");
        params = Map.copyOf(params);
    }

    @Override public String type() { return "llm/request"; }

    @Override public boolean ignorable() { return true; }
}
