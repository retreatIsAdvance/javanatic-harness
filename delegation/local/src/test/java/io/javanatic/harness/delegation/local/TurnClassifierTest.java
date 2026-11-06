package io.javanatic.harness.delegation.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.event.SurfaceOp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 四路终局映射逐路钉死（停 2 评审重点①：问态是第四路，不走 TurnEndReason 三因）。 */
class TurnClassifierTest {

    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");

    @Test
    void completedWithConcludesTurnIsNeedsParentInputNotSucceeded() {
        List<LoggedEvent<? extends SessionEvent>> events = new ArrayList<>();
        events.add(new LoggedEvent<>(1, toolResult("哪个分支？", true)));
        events.add(new LoggedEvent<>(2, new TurnEnd(3, 1, new TurnEndReason.Completed())));
        assertThat(TurnClassifier.classify(events, T0, false))
                .isInstanceOf(DelegationState.NeedsParentInput.class)
                .extracting(st -> ((DelegationState.NeedsParentInput) st).question())
                .isEqualTo("哪个分支？");
    }

    @Test
    void completedWithoutStopResultIsSucceededWithFinalAnswer() {
        List<LoggedEvent<? extends SessionEvent>> events = new ArrayList<>();
        events.add(new LoggedEvent<>(1, assistant("最终答案文本")));
        events.add(new LoggedEvent<>(2, new TurnEnd(3, 1, new TurnEndReason.Completed())));
        DelegationState state = TurnClassifier.classify(events, T0, false);
        assertThat(state).isInstanceOf(DelegationState.Succeeded.class);
        assertThat(((DelegationState.Succeeded) state).resultSummary()).isEqualTo("最终答案文本");
    }

    @Test
    void errorTurnIsFailedModelError() {
        List<LoggedEvent<? extends SessionEvent>> events = new ArrayList<>();
        events.add(new LoggedEvent<>(1, new TurnEnd(2, 1,
                new TurnEndReason.Error("boom", FailureKind.UNKNOWN))));
        assertThat(TurnClassifier.classify(events, T0, false))
                .isInstanceOf(DelegationState.Failed.class);
    }

    @Test
    void abortedWithCancellingIsCancelledNotFailed() {
        List<LoggedEvent<? extends SessionEvent>> events = new ArrayList<>();
        events.add(new LoggedEvent<>(1, new TurnEnd(2, 1, new TurnEndReason.Aborted("user"))));
        assertThat(TurnClassifier.classify(events, T0, true))
                .isInstanceOf(DelegationState.Cancelled.class);
    }

    @Test
    void abortedWithoutCancellingIsFailedExecutionError() {
        List<LoggedEvent<? extends SessionEvent>> events = new ArrayList<>();
        events.add(new LoggedEvent<>(1, new TurnEnd(2, 1, new TurnEndReason.Aborted("other"))));
        assertThat(TurnClassifier.classify(events, T0, false))
                .isInstanceOf(DelegationState.Failed.class);
    }

    @Test
    void noTurnEndYetIsRunning() {
        assertThat(TurnClassifier.classify(List.of(), T0, false))
                .isInstanceOf(DelegationState.Running.class);
    }

    private static ToolResultEvent toolResult(String content, boolean concludesTurn) {
        return new ToolResultEvent(1, 1, 0,
                new ToolResultBlock(CallId.of("c1"), content, false),
                concludesTurn, new SurfaceOp.Append(), null);
    }

    private static AssistantMessageEvent assistant(String text) {
        return new AssistantMessageEvent(0, 1, 0,
                new AssistantMessage(new MessageSource.Model("replay", "m"),
                        List.of(new TextBlock(text))),
                null, new SurfaceOp.Append(), null);
    }
}
