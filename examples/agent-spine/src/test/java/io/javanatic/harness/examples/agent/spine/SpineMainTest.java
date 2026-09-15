package io.javanatic.harness.examples.agent.spine;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.TextBlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 竖切证据：同一场景断言完整事件序列、真实 fs 工具结果与终答文本。 */
class SpineMainTest {

    @TempDir
    Path workspace;

    @Test
    void verticalSliceLogsFullContract() throws Exception {
        List<LoggedEvent<? extends SessionEvent>> events = SpineMain.run(workspace);

        assertThat(events.stream().map(entry -> entry.event().type()).toList()).containsExactly(
            "turn/start", "request/header", "user/message", "step/start", "llm/request",
            "assistant/chunk", "assistant/chunk", "assistant/chunk",
            "assistant/message", "tool/call", "tool/result", "step/end",
            "step/start", "llm/request", "assistant/chunk", "assistant/chunk",
            "assistant/message", "step/end", "turn/end");

        // 真实 fs_read 结果（经 executor pipeline，非伪造）
        ToolResultEvent result = events.stream()
            .map(LoggedEvent::event)
            .filter(ToolResultEvent.class::isInstance)
            .map(ToolResultEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(result.block().isError()).isFalse();
        assertThat(result.block().content()).isEqualTo("JH spine demo note");

        // 终答可见且引用笔记内容
        AssistantMessageEvent finalAnswer = events.stream()
            .map(LoggedEvent::event)
            .filter(AssistantMessageEvent.class::isInstance)
            .map(AssistantMessageEvent.class::cast)
            .reduce((first, second) -> second).orElseThrow();
        String text = finalAnswer.message().content().stream()
            .filter(TextBlock.class::isInstance)
            .map(block -> ((TextBlock) block).text())
            .findFirst().orElseThrow();
        assertThat(text).contains("JH spine demo note");
    }
}
