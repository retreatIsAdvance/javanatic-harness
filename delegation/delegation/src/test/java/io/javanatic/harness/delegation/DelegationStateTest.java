package io.javanatic.harness.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 状态联合：问与败分界、暂停可续、终态穷尽、时间序校验。 */
class DelegationStateTest {

    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T00:01:00Z");

    @Test
    void runningAndNeedsParentInputAreNonTerminal() {
        assertThat(new DelegationState.Running(T0).terminal()).isFalse();
        assertThat(new DelegationState.NeedsParentInput("哪个分支？", T0, T1).terminal()).isFalse();
    }

    @Test
    void pausedOnlyForNeedsParentInput() {
        assertThat(new DelegationState.NeedsParentInput("哪个分支？", T0, T1).paused()).isTrue();
        assertThat(new DelegationState.Running(T0).paused()).isFalse();
        assertThat(new DelegationState.Succeeded("done", T0, T1).paused()).isFalse();
        assertThat(new DelegationState.Cancelled(T0, T1).paused()).isFalse();
    }

    @Test
    void fourTerminalVariants() {
        assertThat(new DelegationState.Succeeded("done", T0, T1).terminal()).isTrue();
        assertThat(new DelegationState.Failed(DelegationFailureReason.MODEL_ERROR, T0, T1).terminal()).isTrue();
        assertThat(new DelegationState.Cancelled(T0, T1).terminal()).isTrue();
        assertThat(new DelegationState.Lost(T0, T1).terminal()).isTrue();
    }

    @Test
    void questionMustBeNonBlank() {
        assertThatThrownBy(() -> new DelegationState.NeedsParentInput("  ", T0, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void rejectsBadTimeRangesAndNulls() {
        assertThatThrownBy(() -> new DelegationState.Running(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelegationState.NeedsParentInput("q", T1, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precede");
        assertThatThrownBy(() -> new DelegationState.Failed(null, T0, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }
}
