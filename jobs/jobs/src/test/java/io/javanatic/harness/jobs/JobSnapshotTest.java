package io.javanatic.harness.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 快照即校验 + 终态委托（state().terminal()）。 */
class JobSnapshotTest {

    private static final Instant T0 = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void rejectsMissingIdOrSummaryOrState() {
        assertThatThrownBy(() -> new JobSnapshot(null, "mvn", new JobState.Running(T0), 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new JobSnapshot(Job.newId("job-1"), " ", new JobState.Running(T0), 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandSummary");
        assertThatThrownBy(() -> new JobSnapshot(Job.newId("job-1"), "mvn", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    void rejectsNegativeCapturedBytes() {
        assertThatThrownBy(() -> new JobSnapshot(Job.newId("job-1"), "mvn", new JobState.Running(T0), -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capturedBytes");
    }

    @Test
    void terminalDelegatesToState() {
        JobSnapshot running = new JobSnapshot(Job.newId("job-1"), "mvn", new JobState.Running(T0), 0, false);
        JobSnapshot lost = new JobSnapshot(Job.newId("job-2"), "mvn", new JobState.Lost(T0, T0), 8, true);
        assertThat(running.state().terminal()).isFalse();
        assertThat(lost.state().terminal()).isTrue();
        assertThat(lost.outputTruncated()).isTrue();
    }
}
