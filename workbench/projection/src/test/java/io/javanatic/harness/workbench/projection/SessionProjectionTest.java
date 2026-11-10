package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.persistence.SessionPersistence;

import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static io.javanatic.harness.workbench.projection.TestEvents.FakeExtension;
import static io.javanatic.harness.workbench.projection.TestEvents.assistant;
import static io.javanatic.harness.workbench.projection.TestEvents.call;
import static io.javanatic.harness.workbench.projection.TestEvents.header;
import static io.javanatic.harness.workbench.projection.TestEvents.result;
import static io.javanatic.harness.workbench.projection.TestEvents.turnEnd;
import static io.javanatic.harness.workbench.projection.TestEvents.turnStart;
import static io.javanatic.harness.workbench.projection.TestEvents.user;
import static org.assertj.core.api.Assertions.assertThat;

class SessionProjectionTest {

    @Test
    void projectWiresAllFiveViewsFromLoaded() {
        SessionHeader sessionHeader = header("s1");
        List<SessionEvent> events = List.of(
            turnStart(0, 1),
            call(1, 1, 0, "c1", "t", "{}"),
            result(2, 1, 0, "c1", "ok", false),
            assistant(3, 1, 0, "a", new TokenUsage(1, 2, 0)),
            turnEnd(4, 1, new TurnEndReason.Completed())
        );
        SessionPersistence.Loaded loaded = new SessionPersistence.Loaded(sessionHeader, events);

        SessionViews views = SessionProjection.project(loaded);

        assertThat(views.header()).isSameAs(sessionHeader);
        assertThat(views.turns()).isEqualTo(SessionProjection.turns(events));
        assertThat(views.tools()).isEqualTo(SessionProjection.tools(events));
        assertThat(views.usage()).isEqualTo(SessionProjection.usage(events));
        assertThat(views.timeline()).isEqualTo(SessionProjection.timeline(events));
        assertThat(views.messages()).isEqualTo(SessionProjection.messages(events, sessionHeader));
        assertThat(views.turns()).hasSize(1);
        assertThat(views.messages()).hasSize(2);
    }

    /**
     * 折叠不变量:任意合法事件流(含未知扩展与隐式开轮)上 timeline 不抛、
     * seq 恒等于下标、type 恒等于事件 type;其余三视图同输入不抛。
     * (messages 走 surface 校验,畸形流上可抛,不在本属性面。)
     */
    @Property
    void foldsNeverThrowAndSeqEqualsIndex(@ForAll("eventLists") List<SessionEvent> events) {
        List<TimelineEntry> entries = SessionProjection.timeline(events);
        assertThat(entries).hasSize(events.size());
        for (int i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).seq()).isEqualTo(i);
            assertThat(entries.get(i).type()).isEqualTo(events.get(i).type());
        }
        SessionProjection.turns(events);
        SessionProjection.tools(events);
        SessionProjection.usage(events);
    }

    @Provide
    Arbitrary<List<SessionEvent>> eventLists() {
        Arbitrary<Integer> turns = Arbitraries.integers().between(0, 3);
        Arbitrary<SessionEvent> event = Arbitraries.oneOf(
            turns.map(t -> turnStart(1, t)),
            turns.map(t -> turnEnd(2, t, new TurnEndReason.Completed())),
            turns.flatMap(t -> Arbitraries.integers().between(0, 2)
                .map(s -> call(3, t, s, "c", "tool", "{}"))),
            turns.flatMap(t -> Arbitraries.integers().between(0, 2)
                .map(s -> result(4, t, s, "c", "ok", false))),
            turns.flatMap(t -> Arbitraries.integers().between(0, 2)
                .map(s -> assistant(5, t, s, "a"))),
            Arbitraries.just(user(6, "hi")),
            Arbitraries.just(new FakeExtension(7, "jobs", "payload"))
        );
        return event.list().ofMaxSize(30);
    }
}
