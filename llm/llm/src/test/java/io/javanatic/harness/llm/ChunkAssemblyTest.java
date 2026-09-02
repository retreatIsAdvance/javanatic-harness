package io.javanatic.harness.llm;
import io.javanatic.harness.session.message.CallId;

import io.javanatic.harness.session.message.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 组装纯函数：文本拼接、tool-use 增量累积、usage 末次生效、Finish 协议。 */
class ChunkAssemblyTest {

    @Test
    void foldsDeltasIntoText() {
        ChunkAssembly.Assembled a = ChunkAssembly.fold(List.of(
            new StreamChunk.Delta("你"),
            new StreamChunk.Delta("好"),
            new StreamChunk.Finish(FinishReason.STOP)));
        assertThat(a.text()).isEqualTo("你好");
        assertThat(a.toolCalls()).isEmpty();
        assertThat(a.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(a.usage()).isNull();
    }

    @Test
    void accumulatesToolUseArgumentsPerCallId() {
        ChunkAssembly.Assembled a = ChunkAssembly.fold(List.of(
            new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read", "{\"pa"),
            new StreamChunk.DeltaToolUse(CallId.of("c1"), null, "th\":\"/tmp\"}"),
            new StreamChunk.Finish(FinishReason.TOOL_USE)));
        assertThat(a.toolCalls()).hasSize(1);
        assertThat(a.toolCalls().getFirst().id()).isEqualTo(CallId.of("c1"));
        assertThat(a.toolCalls().getFirst().name()).isEqualTo("fs_read");
        assertThat(a.toolCalls().getFirst().arguments()).isEqualTo("{\"path\":\"/tmp\"}");
        assertThat(a.text()).isEmpty(); // 纯工具调用步
    }

    @Test
    void keepsToolCallOrderAndUsageLastWins() {
        ChunkAssembly.Assembled a = ChunkAssembly.fold(List.of(
            new StreamChunk.Usage(new TokenUsage(1, 2, 3)),
            new StreamChunk.DeltaToolUse(CallId.of("b"), "shell", "{}"),
            new StreamChunk.DeltaToolUse(CallId.of("a"), "fs_read", "{}"),
            new StreamChunk.Usage(new TokenUsage(9, 9, 9)),
            new StreamChunk.Finish(FinishReason.TOOL_USE)));
        assertThat(a.toolCalls()).extracting(ChunkAssembly.ToolCall::name)
            .containsExactly("shell", "fs_read"); // 首次出现序
        assertThat(a.usage()).isEqualTo(new TokenUsage(9, 9, 9));
    }

    @Test
    void missingFinishFailsLoud() {
        assertThatThrownBy(() -> ChunkAssembly.fold(List.of(new StreamChunk.Delta("x"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("without a Finish");
    }

    @Test
    void chunkAfterFinishFailsLoud() {
        assertThatThrownBy(() -> ChunkAssembly.fold(List.of(
            new StreamChunk.Finish(FinishReason.STOP),
            new StreamChunk.Delta("late"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("after Finish");
    }

    @Test
    void conflictingNamesForSameCallIdFailLoud() {
        assertThatThrownBy(() -> ChunkAssembly.fold(List.of(
            new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read", "{}"),
            new StreamChunk.DeltaToolUse(CallId.of("c1"), "shell", "{}"),
            new StreamChunk.Finish(FinishReason.TOOL_USE))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("disagree on name");
    }
}
