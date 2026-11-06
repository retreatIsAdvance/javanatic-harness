package io.javanatic.harness.delegation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 快照即校验：四要素在场（childSessionId 树链接必在）。 */
class DelegationSnapshotTest {

    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");

    @Test
    void rejectsMissingPieces() {
        Id<Delegation> id = Delegation.newId("del-1");
        Id<Session> child = Session.newId("child-1");
        DelegationState state = new DelegationState.Running(T0);
        long budget = 5000;
        assertThatThrownBy(() -> new DelegationSnapshot(null, "任务", child, budget, state))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new DelegationSnapshot(id, " ", child, budget, state))
                .hasMessageContaining("taskSummary");
        assertThatThrownBy(() -> new DelegationSnapshot(id, "任务", null, budget, state))
                .hasMessageContaining("childSessionId");
        assertThatThrownBy(() -> new DelegationSnapshot(id, "任务", child, budget, null))
                .hasMessageContaining("state");
    }
}
