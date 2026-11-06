package io.javanatic.harness.delegation.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.delegation.Delegation;
import io.javanatic.harness.delegation.DelegationFailureReason;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.session.Session;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** codec 六态往返 + 预算随载荷（R1 回放期聚合的地基）。 */
class DelegationSnapshotCodecTest {

    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-06T00:01:00Z");
    private final DelegationSnapshotCodec codec = new DelegationSnapshotCodec();

    @Test
    void roundTripsRunningWithBudget() {
        DelegationSnapshot snapshot = snapshot(new DelegationState.Running(T0), 5000);
        DelegationSnapshotEvent restored = codec.read(codec.write(new DelegationSnapshotEvent(1L, snapshot)));
        assertThat(restored).isEqualTo(new DelegationSnapshotEvent(1L, snapshot));
        assertThat(restored.snapshot().budgetTokens()).isEqualTo(5000);
    }

    @Test
    void roundTripsNeedsParentInput() {
        assertRoundTrips(snapshot(new DelegationState.NeedsParentInput("哪条路径？", T0, T1), 100));
    }

    @Test
    void roundTripsTerminalVariants() {
        assertRoundTrips(snapshot(new DelegationState.Succeeded("结果摘要", T0, T1), 100));
        assertRoundTrips(snapshot(new DelegationState.Failed(DelegationFailureReason.BUDGET_EXHAUSTED, T0, T1), 100));
        assertRoundTrips(snapshot(new DelegationState.Cancelled(T0, T1), 100));
        assertRoundTrips(snapshot(new DelegationState.Lost(T0, T1), 100));
    }

    private static DelegationSnapshot snapshot(DelegationState state, long budget) {
        return new DelegationSnapshot(Delegation.newId("del-rt"), "检索三模块", Session.newId("child-rt"),
                budget, state);
    }

    private void assertRoundTrips(DelegationSnapshot snapshot) {
        assertThat(codec.read(codec.write(new DelegationSnapshotEvent(1L, snapshot))))
                .isEqualTo(new DelegationSnapshotEvent(1L, snapshot));
    }
}
