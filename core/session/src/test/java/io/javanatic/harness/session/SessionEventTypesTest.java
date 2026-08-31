package io.javanatic.harness.session;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 事件与消息类型层：type 词表、ignorable 默认、构造时冻结。 */
class SessionEventTypesTest {

    private static final MessageSource.User USER = new MessageSource.User();
    private static final MessageSource.Model MODEL = new MessageSource.Model("deepseek", "v4");

    @Test
    void typeVocabularyMatchesDshNames() {
        assertThat(new TurnStart(0, 0).type()).isEqualTo("turn/start");
        assertThat(new TurnEnd(0, 0, new TurnEndReason.Completed()).type()).isEqualTo("turn/end");
        assertThat(new SessionEndSeedEvent(0).type()).isEqualTo("session/end-seed");
        assertThat(llmRequest().type()).isEqualTo("llm/request");
        assertThat(userEvent().type()).isEqualTo("user/message");
    }

    @Test
    void ignorableDefaultsToRequiredExceptLlmRequest() {
        assertThat(new TurnStart(0, 0).ignorable()).isFalse();
        assertThat(userEvent().ignorable()).isFalse();
        assertThat(new SessionEndSeedEvent(0).ignorable()).isFalse();
        assertThat(llmRequest().ignorable()).isTrue();
    }

    @Test
    void constructionFreezesMutableCollections() {
        ArrayList<ContentBlock> blocks = new ArrayList<>(List.of(new TextBlock("你好")));
        UserMessage message = new UserMessage(USER, blocks);
        blocks.add(new TextBlock("后加的"));
        assertThat(message.content()).hasSize(1);

        ArrayList<Long> sources = new ArrayList<>(List.of(1L));
        UserMessageEvent event = new UserMessageEvent(0, message, new SurfaceOp.Append(), sources);
        sources.add(2L);
        assertThat(event.sourceEventSeqs()).containsExactly(1L);

        Map<String, String> params = new java.util.HashMap<>(Map.of("model", "v4"));
        LlmRequestEvent request = new LlmRequestEvent(0, 0, 0, "a", "b", 0, 0, params);
        params.put("sneaky", "x");
        assertThat(request.params()).hasSize(1);
    }

    @Test
    void nullContractsFailLoud() {
        assertThatThrownBy(() -> new UserMessage(null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("source");
        assertThatThrownBy(() -> new TurnEnd(0, 0, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> new UserMessageEvent(0, null, new SurfaceOp.Append(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void turnEndReasonAcceptsCustomVariants() {
        record TimedOut(long millis) implements TurnEndReason {}
        SessionEvent end = new TurnEnd(0, 3, new TimedOut(30_000));
        assertThat(end.type()).isEqualTo("turn/end");
    }

    @Test
    void assistantMessageAllowsEmptyContent() {
        AssistantMessageEvent empty = new AssistantMessageEvent(0, 0, 0,
            new AssistantMessage(MODEL, List.of()), null, new SurfaceOp.Append(), List.of());
        assertThat(empty.message().content()).isEmpty();
        assertThat(empty.sourceEventSeqs()).isEmpty();
    }

    private static UserMessageEvent userEvent() {
        return new UserMessageEvent(0, UserMessage.of("hi", USER), new SurfaceOp.Append(), null);
    }

    private static LlmRequestEvent llmRequest() {
        return new LlmRequestEvent(0, 0, 0, "s", "t", 0, 0, Map.of());
    }
}
