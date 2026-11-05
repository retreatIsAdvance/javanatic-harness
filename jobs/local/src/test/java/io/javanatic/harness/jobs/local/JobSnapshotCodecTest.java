package io.javanatic.harness.jobs.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.jobs.Job;
import io.javanatic.harness.jobs.JobFailureReason;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** codec 往返：五态判别式编码逐态还原（fold 端到端的地基）。 */
class JobSnapshotCodecTest {

    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T00:01:00Z");
    private final JobSnapshotCodec codec = new JobSnapshotCodec();

    @Test
    void typeMatchesEvent() {
        assertThat(codec.type()).isEqualTo("job/snapshot");
        assertThat(codec.typeClass()).isEqualTo(JobSnapshotEvent.class);
    }

    @Test
    void roundTripsRunning() {
        assertRoundTrips(snapshot(new JobState.Running(T0), 0, false));
    }

    @Test
    void roundTripsSucceeded() {
        assertRoundTrips(snapshot(new JobState.Succeeded(0, T0, T1), 42, false));
    }

    @Test
    void roundTripsFailed() {
        assertRoundTrips(snapshot(new JobState.Failed(JobFailureReason.TIMED_OUT, 137, T0, T1), 7, true));
    }

    @Test
    void roundTripsCancelled() {
        assertRoundTrips(snapshot(new JobState.Cancelled(T0, T1), 0, false));
    }

    @Test
    void roundTripsLost() {
        assertRoundTrips(snapshot(new JobState.Lost(T0, T1), 512, true));
    }

    private static JobSnapshot snapshot(JobState state, long capturedBytes, boolean truncated) {
        return new JobSnapshot(Job.newId("job-roundtrip"), "mvn -q package", state, capturedBytes, truncated);
    }

    private void assertRoundTrips(JobSnapshot snapshot) {
        JobSnapshotEvent event = new JobSnapshotEvent(1730764800000L, snapshot);
        assertThat(codec.read(codec.write(event))).isEqualTo(event);
    }
}
