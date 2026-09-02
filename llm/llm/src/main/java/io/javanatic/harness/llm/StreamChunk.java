package io.javanatic.harness.llm;
import io.javanatic.harness.session.message.CallId;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.TokenUsage;

/**
 * 流式分块词表（厂商中立）。tool-use 分块按 {@code id} 配对累积
 * arguments 增量；Usage 可多次出现（末次生效）；Finish 恒为最后一块。
 */
public sealed interface StreamChunk permits StreamChunk.Delta, StreamChunk.DeltaToolUse,
        StreamChunk.Usage, StreamChunk.Finish {

    /** 文本增量。 */
    record Delta(String text) implements StreamChunk {}

    /** 工具调用增量：id 首次出现时携带 name，后续同 id 追加 argumentsDelta。 */
    record DeltaToolUse(Id<CallId> id, String name, String argumentsDelta) implements StreamChunk {}

    /** token 计量（多次出现末次生效）。 */
    record Usage(TokenUsage usage) implements StreamChunk {}

    /** 流结束原因。 */
    record Finish(FinishReason reason) implements StreamChunk {}
}
