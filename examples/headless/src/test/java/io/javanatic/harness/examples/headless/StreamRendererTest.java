package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agentloop.AssistantChunkEvent;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 渲染面:入队-出队顺序、工具一行摘要、typed 失败文案(kind 驱动,不解析消息文本)。 */
class StreamRendererTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    @Test
    void fragmentsToolLinesAndTypedFailureRenderInOrder() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new AssistantChunkEvent(0, 1, 0, new StreamChunk.Delta("答")));
            renderer.onEvent(new AssistantChunkEvent(1, 1, 0, new StreamChunk.Delta("案")));
            renderer.onEvent(new AssistantChunkEvent(2, 1, 0, new StreamChunk.Finish(FinishReason.STOP)));
            renderer.onEvent(new ToolCallEvent(3, 1, 0, CallId.of("c1"), "read_file",
                "{\"path\":\"a.txt\"}"));
            renderer.onEvent(new ToolResultEvent(4, 1, 0,
                new ToolResultBlock(CallId.of("c1"), "hello world", false), false,
                new SurfaceOp.Append(), null));
            renderer.onEvent(new TurnEnd(5, 1,
                new TurnEndReason.Error("deepseek http 401", FailureKind.AUTH)));
            renderer.println("面板");
        }
        assertThat(text()).isEqualTo("""
            答案
            → read_file {"path":"a.txt"}
            ← hello world
            turn 失败: 认证失败：检查 API key（--api-key= 或 --api-key-env=）（deepseek http 401）
            面板
            """);
    }

    @Test
    void errorToolResultCarriesErrorPrefixAndMultilineTextKeepsLineState() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new AssistantChunkEvent(0, 1, 0, new StreamChunk.Delta("行一\n行二")));
            renderer.onEvent(new AssistantChunkEvent(1, 1, 0, new StreamChunk.Finish(FinishReason.STOP)));
            renderer.onEvent(new ToolResultEvent(2, 1, 0,
                new ToolResultBlock(CallId.of("c1"), "boom\nmore", true), false,
                new SurfaceOp.Append(), null));
            renderer.onEvent(new TurnEnd(3, 1, new TurnEndReason.Completed()));
        }
        assertThat(text()).isEqualTo("""
            行一
            行二
            ← error: boom more
            """);
    }

    @Test
    void usageAndToolUseDeltasRenderNothing() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new AssistantChunkEvent(0, 1, 0, new StreamChunk.DeltaToolUse(
                CallId.of("c1"), "read_file", "{\"path\":")));
            renderer.onEvent(new TurnEnd(1, 1, new TurnEndReason.Aborted("disposed")));
        }
        assertThat(text()).isEmpty();
    }

    @Test
    void panelLineBreaksOpenStreamLine() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new AssistantChunkEvent(0, 1, 0, new StreamChunk.Delta("半句")));
            renderer.println("面板");
            renderer.onEvent(new AssistantChunkEvent(1, 1, 0, new StreamChunk.Delta("续")));
            renderer.onEvent(new AssistantChunkEvent(2, 1, 0, new StreamChunk.Finish(FinishReason.STOP)));
        }
        assertThat(text()).isEqualTo("半句\n面板\n续\n");
    }

    @Test
    void failureTextIsKindDriven() {
        String auth = StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.AUTH));
        String rate = StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.RATE_LIMIT));
        assertThat(auth).contains("认证失败").contains("API key");
        assertThat(rate).contains("被限流").contains("稍后重试");
        assertThat(auth).isNotEqualTo(rate);
        // 同一消息不同 kind → 不同文案:判定不吃消息文本
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.OVERFLOW)))
            .contains("上下文溢出");
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.NETWORK)))
            .contains("网络错误");
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.TIMEOUT)))
            .contains("超时");
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.SERVER)))
            .contains("服务端错误");
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.PROTOCOL)))
            .contains("协议错误");
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.UNKNOWN)))
            .contains("未分类");
    }

    @Test
    void previewCollapsesWhitespaceAndTruncates() {
        assertThat(StreamRenderer.preview("  a\n\tb  c ")).isEqualTo("a b c");
        String longText = "x".repeat(StreamRenderer.PREVIEW + 10);
        String preview = StreamRenderer.preview(longText);
        assertThat(preview).hasSize(StreamRenderer.PREVIEW + 1).endsWith("…");
    }

    private String text() {
        out.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
