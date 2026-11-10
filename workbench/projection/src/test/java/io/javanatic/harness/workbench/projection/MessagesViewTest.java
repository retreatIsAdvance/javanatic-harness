package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.UserMessage;

import java.util.List;

import org.junit.jupiter.api.Test;

import static io.javanatic.harness.workbench.projection.TestEvents.assistant;
import static io.javanatic.harness.workbench.projection.TestEvents.assistantEmpty;
import static io.javanatic.harness.workbench.projection.TestEvents.call;
import static io.javanatic.harness.workbench.projection.TestEvents.header;
import static io.javanatic.harness.workbench.projection.TestEvents.result;
import static io.javanatic.harness.workbench.projection.TestEvents.user;
import static org.assertj.core.api.Assertions.assertThat;

class MessagesViewTest {

    @Test
    void projectsSurfaceIncludingToolResults() {
        List<SessionEvent> events = List.of(
            user(0, "hi"),
            call(1, 0, 0, "c1", "read_file", "{}"),
            result(2, 0, 0, "c1", "file content", false),
            assistant(3, 0, 0, "hello")
        );

        List<Message> messages = SessionProjection.messages(events, header("s1"));

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(0)).source()).isEqualTo(new MessageSource.User());

        // 工具结果投影为 UserMessage(Tool, [ToolResultBlock])
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        UserMessage toolMessage = (UserMessage) messages.get(1);
        assertThat(toolMessage.source()).isInstanceOf(MessageSource.Tool.class);
        assertThat(toolMessage.content()).hasSize(1);
        assertThat(toolMessage.content().get(0)).isInstanceOf(ToolResultBlock.class);
        assertThat(((ToolResultBlock) toolMessage.content().get(0)).content()).isEqualTo("file content");

        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
    }

    /**
     * DeriveMessage 语义:空 content 列表的 assistant 只承载 usage,投影为 null;
     * 单空 TextBlock 则保留(列表非空)——投影层原样透传,不自行判定。
     */
    @Test
    void emptyContentListAssistantIsDropped() {
        List<Message> messages = SessionProjection.messages(List.of(
            user(0, "hi"),
            assistantEmpty(1, 0, 0, new TokenUsage(5, 0, 0))
        ), header("s1"));

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void emptyTextBlockAssistantIsRetained() {
        List<Message> messages = SessionProjection.messages(List.of(
            user(0, "hi"),
            assistant(1, 0, 0, "")
        ), header("s1"));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messages.get(1)).content())
            .containsExactly(new TextBlock(""));
    }

    @Test
    void emptyLogYieldsEmptyMessages() {
        assertThat(SessionProjection.messages(List.of(), header("s1"))).isEmpty();
    }

    @Test
    void nullHeaderStillProjects() {
        List<Message> messages = SessionProjection.messages(List.of(
            user(0, "hi"),
            assistant(1, 0, 0, "hello")
        ), null);

        assertThat(messages).hasSize(2);
    }
}
