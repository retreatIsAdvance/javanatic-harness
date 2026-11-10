package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.message.TokenUsage;

import java.util.List;

import org.junit.jupiter.api.Test;

import static io.javanatic.harness.workbench.projection.TestEvents.assistant;
import static org.assertj.core.api.Assertions.assertThat;

class UsageViewTest {

    @Test
    void rollsUpPerTurnAndTotal() {
        List<SessionEvent> events = List.of(
            assistant(100, 1, 0, "a", new TokenUsage(1, 2, 0)),
            assistant(110, 1, 0, "b"),
            assistant(120, 2, 0, "c", new TokenUsage(3, 4, 5))
        );

        UsageSummary usage = SessionProjection.usage(events);

        assertThat(usage.total()).isEqualTo(new TokenUsage(4, 6, 5));
        assertThat(usage.perTurn()).hasSize(2);
        assertThat(usage.perTurn().get(0).turn()).isEqualTo(1);
        assertThat(usage.perTurn().get(0).usage()).isEqualTo(new TokenUsage(1, 2, 0));
        assertThat(usage.perTurn().get(1).turn()).isEqualTo(2);
        assertThat(usage.perTurn().get(1).usage()).isEqualTo(new TokenUsage(3, 4, 5));
    }

    @Test
    void turnWithoutReportedUsageIsNullNotZero() {
        UsageSummary usage = SessionProjection.usage(List.of(
            assistant(100, 1, 0, "a")
        ));

        assertThat(usage.total()).isNull();
        assertThat(usage.perTurn()).hasSize(1);
        assertThat(usage.perTurn().get(0).usage()).isNull();
    }

    @Test
    void turnOrderFollowsFirstAssistantEvent() {
        UsageSummary usage = SessionProjection.usage(List.of(
            assistant(100, 5, 0, "a", new TokenUsage(1, 0, 0)),
            assistant(110, 3, 0, "b", new TokenUsage(2, 0, 0))
        ));

        assertThat(usage.perTurn()).extracting(UsageSummary.TurnUsage::turn)
            .containsExactly(5, 3);
    }

    @Test
    void emptyLogYieldsNothingReported() {
        UsageSummary usage = SessionProjection.usage(List.of());

        assertThat(usage.total()).isNull();
        assertThat(usage.perTurn()).isEmpty();
    }
}
