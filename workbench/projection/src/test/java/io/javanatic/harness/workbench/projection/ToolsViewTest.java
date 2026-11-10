package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.event.SessionEvent;

import java.util.List;

import org.junit.jupiter.api.Test;

import static io.javanatic.harness.workbench.projection.TestEvents.call;
import static io.javanatic.harness.workbench.projection.TestEvents.result;
import static io.javanatic.harness.workbench.projection.TestEvents.resultConcluding;
import static org.assertj.core.api.Assertions.assertThat;

class ToolsViewTest {

    @Test
    void pairsByCallIdAndMarksUnmatchedAsUnknown() {
        List<SessionEvent> events = List.of(
            call(100, 1, 0, "c1", "read_file", "{}"),
            call(110, 1, 0, "c2", "shell", "{}"),
            result(150, 1, 0, "c1", "content", false)
        );

        List<ToolStep> steps = SessionProjection.tools(events);

        assertThat(steps).hasSize(1);
        ToolStep step = steps.get(0);
        assertThat(step.turn()).isEqualTo(1);
        assertThat(step.step()).isEqualTo(0);
        assertThat(step.orphans()).isEmpty();

        assertThat(step.pairs()).hasSize(2);
        ToolStep.Pair first = step.pairs().get(0);
        assertThat(first.callSeq()).isEqualTo(0L);
        assertThat(first.name()).isEqualTo("read_file");
        assertThat(first.arguments()).isEqualTo("{}");
        assertThat(first.resultSeq()).isEqualTo(2L);
        assertThat(first.isError()).isEqualTo(false);
        assertThat(first.concludesTurn()).isFalse();
        assertThat(first.durationMillis()).isEqualTo(50L);

        ToolStep.Pair unmatched = step.pairs().get(1);
        assertThat(unmatched.callSeq()).isEqualTo(1L);
        assertThat(unmatched.name()).isEqualTo("shell");
        assertThat(unmatched.resultSeq()).isNull();
        assertThat(unmatched.isError()).isNull();
        assertThat(unmatched.durationMillis()).isEqualTo(-1L);
    }

    @Test
    void outOfOrderResultsPairByCallIdNotPosition() {
        List<SessionEvent> events = List.of(
            call(100, 1, 0, "c1", "t", "{}"),
            call(110, 1, 0, "c2", "t", "{}"),
            result(150, 1, 0, "c2", "ok2", false),
            result(160, 1, 0, "c1", "ok1", false)
        );

        ToolStep step = SessionProjection.tools(events).get(0);

        assertThat(step.pairs()).hasSize(2);
        assertThat(step.pairs().get(0).resultSeq()).isEqualTo(3L);   // c1 ← 第 2 个 result
        assertThat(step.pairs().get(1).resultSeq()).isEqualTo(2L);   // c2 ← 第 1 个 result
        assertThat(step.orphans()).isEmpty();
    }

    @Test
    void resultWithoutCallBecomesOrphan() {
        List<SessionEvent> events = List.of(
            call(100, 1, 0, "c1", "t", "{}"),
            result(150, 1, 0, "c2", "orphan", true)
        );

        ToolStep step = SessionProjection.tools(events).get(0);

        assertThat(step.pairs()).hasSize(1);
        assertThat(step.pairs().get(0).resultSeq()).isNull();
        assertThat(step.orphans()).hasSize(1);
        ToolStep.Orphan orphan = step.orphans().get(0);
        assertThat(orphan.resultSeq()).isEqualTo(1L);
        assertThat(orphan.isError()).isTrue();
        assertThat(orphan.concludesTurn()).isFalse();
    }

    @Test
    void groupsByTurnAndStepInFirstSeenOrder() {
        List<SessionEvent> events = List.of(
            call(100, 1, 0, "a", "t", "{}"),
            call(110, 1, 1, "b", "t", "{}"),
            call(120, 2, 0, "c", "t", "{}")
        );

        List<ToolStep> steps = SessionProjection.tools(events);

        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).turn()).isEqualTo(1);
        assertThat(steps.get(0).step()).isEqualTo(0);
        assertThat(steps.get(1).turn()).isEqualTo(1);
        assertThat(steps.get(1).step()).isEqualTo(1);
        assertThat(steps.get(2).turn()).isEqualTo(2);
        assertThat(steps.get(2).step()).isEqualTo(0);
    }

    @Test
    void carriesErrorAndConcludesTurnFlags() {
        List<SessionEvent> events = List.of(
            call(100, 1, 0, "c1", "t", "{}"),
            resultConcluding(150, 1, 0, "c1", "failed", true)
        );

        ToolStep.Pair pair = SessionProjection.tools(events).get(0).pairs().get(0);

        assertThat(pair.isError()).isTrue();
        assertThat(pair.concludesTurn()).isTrue();
        assertThat(pair.durationMillis()).isEqualTo(50L);
    }

    @Test
    void noToolActivityYieldsEmptyView() {
        assertThat(SessionProjection.tools(List.of())).isEmpty();
    }
}
