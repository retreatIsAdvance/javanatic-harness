package io.javanatic.harness.session;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Session 日志语义：seq 连续、投影、replace、provenance、防重入、观察者 contained、end-seed。 */
class SessionTest {

    private static final MessageSource.User USER = new MessageSource.User();
    private static final MessageSource.Model MODEL = new MessageSource.Model("deepseek", "v4");

    @Test
    void appendAssignsContiguousSeqAndSnapshotDoesNotGrow() {
        Session s = Session.create(Session.newId("a"), null, null);
        LoggedEvent<TurnStart> e0 = s.append(new TurnStart(1, 0));
        LoggedEvent<TurnStart> e1 = s.append(new TurnStart(2, 1));
        assertThat(e0.seq()).isZero();
        assertThat(e1.seq()).isEqualTo(1);
        List<LoggedEvent<? extends SessionEvent>> snapshot = s.events();
        s.append(new TurnStart(3, 2));
        assertThat(snapshot).hasSize(2); // 旧快照不增长
        assertThat(s.seq()).isEqualTo(3);
        assertThat(s.events().stream().map(LoggedEvent::seq)).containsExactly(0L, 1L, 2L);
    }

    @Test
    void projectionFollowsSurfaceRules() {
        Session s = Session.create(Session.newId("a"), null, null);
        s.append(user("你好"));
        s.append(assistant("你好！"));
        s.append(assistantEmptyUsageOnly());
        List<Message> messages = s.deriveMessages();
        assertThat(messages).hasSize(2); // 空 content 只承载 usage，不进入历史
        assertThat(((UserMessage) messages.getFirst()).content().getFirst())
            .isEqualTo(new io.javanatic.harness.session.message.TextBlock("你好"));
    }

    @Test
    void replaceShadowsRangeInProjection() {
        Session s = Session.create(Session.newId("a"), null, null);
        s.append(user("第一轮"));
        long q = s.append(user("第二轮")).seq();
        s.append(assistant("第一轮的回答"));
        s.append(new AssistantMessageEvent(4, 0, 0, AssistantMessage.of("（摘要）", MODEL), null,
            new SurfaceOp.Replace(q, q), List.of(q)));
        assertThat(s.deriveMessages())
            .extracting(m -> ((io.javanatic.harness.session.message.TextBlock) m.content().getFirst()).text())
            .containsExactly("第一轮", "（摘要）", "第一轮的回答");
        // 事件日志不动——replace 只影响投影
        assertThat(s.events()).hasSize(4);
    }

    @Test
    void provenanceRejectsFutureSeq() {
        Session s = Session.create(Session.newId("a"), null, null);
        assertThatThrownBy(() -> s.append(new UserMessageEvent(1, UserMessage.of("x", USER),
            new SurfaceOp.Append(), List.of(1L))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("earlier");
    }

    @Test
    void provenanceRejectsDuplicates() {
        Session s = Session.create(Session.newId("a"), null, null);
        long q = s.append(user("第一轮")).seq();
        assertThatThrownBy(() -> s.append(new UserMessageEvent(2, UserMessage.of("x", USER),
            new SurfaceOp.Append(), List.of(q, q))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicates");
    }

    @Test
    void provenanceRejectsUncitedShadowedNode() {
        Session s = Session.create(Session.newId("a"), null, null);
        long q = s.append(user("第一轮")).seq();
        assertThatThrownBy(() -> s.append(new AssistantMessageEvent(2, 0, 0,
            AssistantMessage.of("摘要", MODEL), null, new SurfaceOp.Replace(q, q), List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shadowed");
    }

    @Test
    void provenanceRejectsEmptySourcesExceptAssistant() {
        Session s = Session.create(Session.newId("a"), null, null);
        assertThatThrownBy(() -> s.append(new UserMessageEvent(1, UserMessage.of("x", USER),
            new SurfaceOp.Append(), List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-empty");
    }

    @Test
    void replaceRejectsRangeNotInSurface() {
        Session s = Session.create(Session.newId("a"), null, null);
        long q = s.append(user("第一轮")).seq();
        assertThatThrownBy(() -> s.append(new AssistantMessageEvent(2, 0, 0,
            AssistantMessage.of("摘要", MODEL), null, new SurfaceOp.Replace(99, 99), List.of(q))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a current surface range");
    }

    @Test
    void failedValidationLeavesLogUnchanged() {
        Session s = Session.create(Session.newId("a"), null, null);
        s.append(user("第一轮"));
        long before = s.seq();
        assertThatThrownBy(() -> s.append(new UserMessageEvent(2, UserMessage.of("x", USER),
            new SurfaceOp.Append(), List.of(9L)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.seq()).isEqualTo(before);
        assertThat(s.deriveMessages()).hasSize(1);
    }

    @Test
    void reentrantAppendThrowsAndGuardResets() {
        List<Session.Observer> observers = new ArrayList<>();
        Session s = new Session(Session.newId("a"), null, null, observers);
        observers.add((session, event) -> {
            assertThatThrownBy(() -> session.append(new TurnStart(99, 9)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reenter");
        });
        s.append(new TurnStart(1, 0));
        s.append(new TurnStart(2, 1)); // guard 已复位，后续 append 正常
        assertThat(s.seq()).isEqualTo(2);
    }

    @Test
    void observerFailureIsContained() {
        AtomicInteger seen = new AtomicInteger();
        Session s = new Session(Session.newId("a"), null, null, List.of(
            (session, event) -> {
                throw new IllegalStateException("observer boom");
            },
            (session, event) -> seen.incrementAndGet()));
        LoggedEvent<TurnStart> entry = s.append(new TurnStart(1, 0));
        assertThat(entry.seq()).isZero();
        assertThat(seen.get()).isEqualTo(1); // 抛异常的观察者不影响后续观察者
        assertThat(s.seq()).isEqualTo(1);
    }

    @Test
    void seedGetsEndSeedMarkerAndReopenDoesNotRemark() {
        List<SessionEvent> seed = List.of(new TurnStart(1, 0), user("你好"));
        Session first = Session.create(Session.newId("a"), seed, null);
        assertThat(first.firstLiveSeq()).isEqualTo(2);
        assertThat(first.events()).hasSize(3); // seed 2 + marker 1
        assertThat(first.events().getLast().type()).isEqualTo("session/end-seed");

        // 用完整日志重开（resume 语义）：不重标
        Session reopened = Session.create(Session.newId("a"), first.events().stream()
            .<SessionEvent>map(LoggedEvent::event).toList(), null);
        assertThat(reopened.events()).hasSize(3);
        assertThat(reopened.events().getLast().type()).isEqualTo("session/end-seed");
    }

    @Test
    void nullSeedHasNoMarker() {
        Session s = Session.create(Session.newId("a"), null, null);
        assertThat(s.firstLiveSeq()).isZero();
        assertThat(s.events()).isEmpty();
        assertThat(s.header().version()).isEqualTo(SessionHeader.FORMAT_VERSION);
    }

    private static UserMessageEvent user(String text) {
        return new UserMessageEvent(System.currentTimeMillis(), UserMessage.of(text, USER),
            new SurfaceOp.Append(), null);
    }

    private static AssistantMessageEvent assistant(String text) {
        return new AssistantMessageEvent(System.currentTimeMillis(), 0, 0,
            AssistantMessage.of(text, MODEL), null, new SurfaceOp.Append(), List.of());
    }

    private static AssistantMessageEvent assistantEmptyUsageOnly() {
        return new AssistantMessageEvent(System.currentTimeMillis(), 0, 0,
            new AssistantMessage(MODEL, List.of()), new io.javanatic.harness.session.message.TokenUsage(1, 2, 3),
            new SurfaceOp.Append(), List.of());
    }
}
