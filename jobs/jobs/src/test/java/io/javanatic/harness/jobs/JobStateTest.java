package io.javanatic.harness.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 状态判别联合：终态语义、时间序校验与 Lost 的诚实标记位。 */
class JobStateTest {

    private static final Instant T0 = Instant.parse("2026-11-04T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-04T00:01:00Z");

    @Test
    void runningIsTheOnlyNonTerminalState() {
        assertThat(new JobState.Running(T0).terminal()).isFalse();
    }

    @Test
    void allFourTerminalVariantsMarkTerminal() {
        assertThat(new JobState.Succeeded(0, T0, T1).terminal()).isTrue();
        assertThat(new JobState.Failed(JobFailureReason.TIMED_OUT, 137, T0, T1).terminal()).isTrue();
        assertThat(new JobState.Cancelled(T0, T1).terminal()).isTrue();
        assertThat(new JobState.Lost(T0, T1).terminal()).isTrue();
    }

    @Test
    void rejectsNullTimestamps() {
        assertThatThrownBy(() -> new JobState.Running(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobState.Succeeded(0, null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFinishedBeforeStarted() {
        assertThatThrownBy(() -> new JobState.Succeeded(0, T1, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precede");
        assertThatThrownBy(() -> new JobState.Lost(T1, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedRequiresReason() {
        assertThatThrownBy(() -> new JobState.Failed(null, -1, T0, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }
}
