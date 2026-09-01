package io.javanatic.harness.session;

import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 不变式复核：信封连续、turn/step 嵌套、surface 重放。 */
class SessionInvariantsTest {

    private static final MessageSource.User USER = new MessageSource.User();

    @Test
    void validConversationPasses() {
        Session s = Session.create(Session.newId("a"), null, null);
        s.append(new TurnStart(1, 0));
        s.append(user(2));
        s.append(new TurnEnd(3, 0, new TurnEndReason.Completed()));
        s.append(new TurnStart(4, 1));
        s.append(user(5));
        s.append(new TurnEnd(6, 1, new TurnEndReason.Completed()));
        assertThatCode(() -> SessionInvariants.validate(s.events())).doesNotThrowAnyException();
    }

    @Test
    void brokenSeqContiguityRejected() {
        List<LoggedEvent<io.javanatic.harness.session.event.SessionEvent>> events = new ArrayList<>();
        events.add(new LoggedEvent<>(0, new TurnStart(1, 0)));
        events.add(new LoggedEvent<>(2, new TurnEnd(2, 0, new TurnEndReason.Completed()))); // 跳号
        assertThatThrownBy(() -> SessionInvariants.validate(List.copyOf(events)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("contiguity");
    }

    @Test
    void turnEndWithoutOpenTurnRejected() {
        assertThatThrownBy(() -> SessionInvariants.validate(List.of(
            new LoggedEvent<>(0, new TurnEnd(1, 0, new TurnEndReason.Completed())))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without matching open turn");
    }

    @Test
    void wrongTurnNumberRejected() {
        assertThatThrownBy(() -> SessionInvariants.validate(List.of(
            new LoggedEvent<>(0, new TurnStart(1, 5)))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("!= expected");
    }

    @Test
    void logEndingInsideOpenTurnRejected() {
        assertThatThrownBy(() -> SessionInvariants.validate(List.of(
            new LoggedEvent<>(0, new TurnStart(1, 0)))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inside open turn");
    }

    @Test
    void surfaceReplayCatchesBadProvenance() {
        List<io.javanatic.harness.session.event.SessionEvent> raw = List.of(
            new UserMessageEvent(1, UserMessage.of("你好", USER), new SurfaceOp.Append(), List.of(9L)));
        // 绕过 Session.append 的校验，直接构造信封喂给复核器
        List<LoggedEvent<io.javanatic.harness.session.event.SessionEvent>> events = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            events.add(new LoggedEvent<>(i, raw.get(i)));
        }
        assertThatThrownBy(() -> SessionInvariants.validate(List.copyOf(events)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("earlier");
    }

    private static UserMessageEvent user(long time) {
        return new UserMessageEvent(time, UserMessage.of("hi", USER), new SurfaceOp.Append(), null);
    }
}
