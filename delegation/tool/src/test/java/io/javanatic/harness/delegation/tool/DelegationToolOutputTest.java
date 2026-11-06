package io.javanatic.harness.delegation.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.delegation.Delegation;
import io.javanatic.harness.delegation.DelegationFailureReason;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.session.Session;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 六态词 + 树链接 + 问败分界的逐字模板钉死（禁词突变另证）。 */
class DelegationToolOutputTest {

    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-06T00:01:00Z");

    @Test
    void questionStateCarriesVerbatimQuestionNotFailure() {
        String word = DelegationToolOutput.stateWord(
                new DelegationState.NeedsParentInput("用哪个分支？", T0, T1));
        assertThat(word).contains("needs_parent_input").contains("「用哪个分支？」");
        assertThat(word).doesNotContain("failed").doesNotContain("失败");
    }

    @Test
    void lostSaysLostNotResumable() {
        assertThat(DelegationToolOutput.stateWord(new DelegationState.Lost(T0, T1)))
                .contains("lost").contains("失联").contains("不冒充可续接");
    }

    @Test
    void lineCarriesTreeLinkAndBudget() {
        String line = DelegationToolOutput.line(snapshot(new DelegationState.Running(T0), 5000));
        assertThat(line).startsWith("delegation ").contains(" budget=5000 ")
                .contains(" childSession=child-tpl ").contains(" task=并行检索");
    }

    @Test
    void terminalWordsAreStable() {
        assertThat(DelegationToolOutput.stateWord(new DelegationState.Succeeded("三处都已修", T0, T1)))
                .contains("succeeded").contains("三处都已修");
        assertThat(DelegationToolOutput.stateWord(
                new DelegationState.Failed(DelegationFailureReason.BUDGET_EXHAUSTED, T0, T1)))
                .isEqualTo("failed(budget_exhausted)");
        assertThat(DelegationToolOutput.stateWord(new DelegationState.Cancelled(T0, T1)))
                .isEqualTo("cancelled");
    }

    private static DelegationSnapshot snapshot(DelegationState state, long budget) {
        return new DelegationSnapshot(Delegation.newId("del-tpl"), "并行检索", Session.newId("child-tpl"),
                budget, state);
    }
}
