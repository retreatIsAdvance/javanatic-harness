package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.TokenUsage;

import java.util.List;

import org.junit.jupiter.api.Test;

import static io.javanatic.harness.workbench.projection.TestEvents.assistant;
import static io.javanatic.harness.workbench.projection.TestEvents.call;
import static io.javanatic.harness.workbench.projection.TestEvents.stepEnd;
import static io.javanatic.harness.workbench.projection.TestEvents.stepStart;
import static io.javanatic.harness.workbench.projection.TestEvents.turnEnd;
import static io.javanatic.harness.workbench.projection.TestEvents.turnStart;
import static io.javanatic.harness.workbench.projection.TestEvents.user;
import static org.assertj.core.api.Assertions.assertThat;

class TurnsViewTest {

    @Test
    void closedTurnCarriesLifecycleAndStats() {
        List<SessionEvent> events = List.of(
            turnStart(100, 1),
            stepStart(110, 1, 0),
            stepEnd(115, 1, 0),
            call(120, 1, 0, "c1", "read_file", "{\"path\":\"x\"}"),
            call(121, 1, 0, "c2", "shell", "{\"cmd\":\"ls\"}"),
            assistant(130, 1, 0, "text", new TokenUsage(10, 20, 3)),
            turnEnd(140, 1, new TurnEndReason.Completed())
        );

        List<TurnSummary> turns = SessionProjection.turns(events);

        assertThat(turns).hasSize(1);
        TurnSummary turn = turns.get(0);
        assertThat(turn.turn()).isEqualTo(1);
        assertThat(turn.open()).isFalse();
        assertThat(turn.startTime()).isEqualTo(100L);
        assertThat(turn.endTime()).isEqualTo(140L);
        assertThat(turn.endReason()).isEqualTo("completed");
        assertThat(turn.steps()).isEqualTo(1);
        assertThat(turn.toolCalls()).isEqualTo(2);
        assertThat(turn.usage()).isEqualTo(new TokenUsage(10, 20, 3));
    }

    @Test
    void openTurnWithoutEndStaysOpen() {
        List<SessionEvent> events = List.of(
            turnStart(100, 2),
            assistant(110, 2, 0, "hi", new TokenUsage(1, 2, 0))
        );

        TurnSummary turn = SessionProjection.turns(events).get(0);

        assertThat(turn.open()).isTrue();
        assertThat(turn.endTime()).isNull();
        assertThat(turn.endReason()).isNull();
    }

    @Test
    void endReasonsRenderByVariant() {
        assertThat(SessionProjection.turns(List.of(
            turnStart(0, 1),
            turnEnd(10, 1, new TurnEndReason.Aborted("user"))
        )).get(0).endReason()).isEqualTo("aborted:user");

        assertThat(SessionProjection.turns(List.of(
            turnStart(0, 1),
            turnEnd(10, 1, new TurnEndReason.Error("boom", FailureKind.AUTH))
        )).get(0).endReason()).isEqualTo("error:AUTH:boom");
    }

    @Test
    void extensionTurnEndReasonFallsBackToSimpleName() {
        TurnSummary turn = SessionProjection.turns(List.of(
            turnStart(0, 1),
            turnEnd(10, 1, new CustomReason())
        )).get(0);

        assertThat(turn.endReason()).isEqualTo("CustomReason");
    }

    @Test
    void implicitTurnWithoutTurnStartOpensAtFirstEvent() {
        TurnSummary turn = SessionProjection.turns(List.of(
            call(100, 3, 0, "c", "tool", "{}")
        )).get(0);

        assertThat(turn.turn()).isEqualTo(3);
        assertThat(turn.open()).isTrue();
        assertThat(turn.startTime()).isEqualTo(100L);
        assertThat(turn.toolCalls()).isEqualTo(1);
    }

    @Test
    void eventsWithoutTurnNumberAreIgnored() {
        List<TurnSummary> turns = SessionProjection.turns(List.of(
            user(50, "hi")
        ));

        assertThat(turns).isEmpty();
    }

    @Test
    void missingUsageIsNullAndSumSkipsMissing() {
        TurnSummary onlyUnreported = SessionProjection.turns(List.of(
            turnStart(0, 1),
            assistant(10, 1, 0, "a")
        )).get(0);
        assertThat(onlyUnreported.usage()).isNull();

        TurnSummary mixed = SessionProjection.turns(List.of(
            turnStart(0, 1),
            assistant(10, 1, 0, "a", new TokenUsage(1, 2, 0)),
            assistant(20, 1, 0, "b")
        )).get(0);
        assertThat(mixed.usage()).isEqualTo(new TokenUsage(1, 2, 0));
    }

    @Test
    void statsDoNotLeakAcrossTurns() {
        List<TurnSummary> turns = SessionProjection.turns(List.of(
            turnStart(100, 1),
            call(110, 1, 0, "a", "t", "{}"),
            turnEnd(120, 1, new TurnEndReason.Completed()),
            turnStart(130, 2),
            turnEnd(140, 2, new TurnEndReason.Aborted("x"))
        ));

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).toolCalls()).isEqualTo(1);
        assertThat(turns.get(1).toolCalls()).isEqualTo(0);
        assertThat(turns.get(1).endReason()).isEqualTo("aborted:x");
    }

    /** 扩展变体:断言投影按简单类名回退,不解析自定义负载。 */
    record CustomReason() implements TurnEndReason {
    }
}
