package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agentloop.AssistantChunkEvent;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 渲染面:入队-出队顺序、工具一行摘要、typed 失败文案(kind 驱动,不解析消息文本)、轮末统计行。 */
class StreamRendererTest {

    private static final MessageSource.Model MODEL = new MessageSource.Model("replay", "m");

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
            stats: turn=1 steps=0 tokens_in=0 tokens_out=0 elapsed=0.0s
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
            stats: turn=1 steps=0 tokens_in=0 tokens_out=0 elapsed=0.0s
            """);
    }

    @Test
    void usageAndToolUseDeltasRenderNothing() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new AssistantChunkEvent(0, 1, 0, new StreamChunk.DeltaToolUse(
                CallId.of("c1"), "read_file", "{\"path\":")));
            renderer.onEvent(new AssistantChunkEvent(1, 1, 0, new StreamChunk.Usage(
                new TokenUsage(10, 5, 0))));
        }
        assertThat(text()).isEmpty();
    }

    @Test
    void turnEndPrintsStatsLineWithStepsTokensAndElapsed() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new TurnStart(1000, 1));
            renderer.onEvent(new StepStart(1100, 1, 1));
            renderer.onEvent(new AssistantChunkEvent(1200, 1, 1, new StreamChunk.Delta("半")));
            renderer.onEvent(new AssistantMessageEvent(1300, 1, 1, AssistantMessage.of("半", MODEL),
                new TokenUsage(120, 45, 0), new SurfaceOp.Append(), null));
            renderer.onEvent(new StepStart(2000, 1, 2));
            renderer.onEvent(new AssistantMessageEvent(2100, 1, 2, AssistantMessage.of("done", MODEL),
                new TokenUsage(30, 10, 0), new SurfaceOp.Append(), null));
            renderer.onEvent(new TurnEnd(4500, 1, new TurnEndReason.Completed()));
        }
        assertThat(text()).isEqualTo(
            "半\n"
            + "stats: turn=1 steps=2 tokens_in=150 tokens_out=55 elapsed=3.5s\n");
    }

    @Test
    void statsAccumulatorResetsEachTurn() {
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            renderer.onEvent(new TurnStart(1000, 1));
            renderer.onEvent(new StepStart(1100, 1, 1));
            renderer.onEvent(new AssistantMessageEvent(1200, 1, 1, AssistantMessage.of("a", MODEL),
                new TokenUsage(10, 5, 0), new SurfaceOp.Append(), null));
            renderer.onEvent(new TurnEnd(2000, 1, new TurnEndReason.Completed()));
            renderer.onEvent(new TurnStart(5000, 2));
            renderer.onEvent(new StepStart(5100, 2, 1));
            renderer.onEvent(new TurnEnd(5200, 2, new TurnEndReason.Aborted("user")));
        }
        assertThat(text()).isEqualTo(
            "stats: turn=1 steps=1 tokens_in=10 tokens_out=5 elapsed=1.0s\n"
            + "stats: turn=2 steps=1 tokens_in=0 tokens_out=0 elapsed=0.2s\n");
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
        assertThat(StreamRenderer.failureText(new TurnEndReason.Error("boom", FailureKind.DISK)))
            .contains("写盘失败").contains("--resume");
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
