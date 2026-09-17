package io.javanatic.harness.examples.headless;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** --verify/policy 语义:STANDARD 过、PRODUCTION 拒 AUTO / 零预算、补齐 budget 后可达、无 key 可跑。 */
class HeadlessVerifyTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void verifyStandardPassesWithoutKey() throws Exception {
        assertThat(HeadlessMain.run(HeadlessMain.parse(new String[] {"--verify"}), workspace, sessions)).isZero();
    }

    @Test
    void verifyProductionRejectsAutoApproval() throws Exception {
        // 组合默认 AUTO → PRODUCTION 必须拒绝并指出违规项
        int exit = HeadlessMain.run(HeadlessMain.parse(new String[] {"--verify", "--policy=PRODUCTION"}), workspace, sessions);
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void verifyAcceptsHumanGateAndDenyAll() throws Exception {
        assertThat(HeadlessMain.run(
            HeadlessMain.parse(new String[] {"--verify", "--approval=ask"}), workspace, sessions)).isZero();
        assertThat(HeadlessMain.run(
            HeadlessMain.parse(new String[] {"--verify", "--approval=deny"}), workspace, sessions)).isZero();
    }

    @Test
    void verifyProductionReachableWithHumanGateAndBudget() throws Exception {
        // S2 收口:ask 人闸 + jsonl 耐久 + --budget 非零 → PRODUCTION 组合可达
        assertThat(HeadlessMain.run(HeadlessMain.parse(new String[] {
            "--verify", "--policy=PRODUCTION", "--approval=ask", "--budget=50000"}),
            workspace, sessions)).isZero();
    }

    @Test
    void verifyProductionWithoutBudgetFailsOnTokenBudgetViolation() throws Exception {
        // 反例:其余可达条件齐备,零预算仍 exit 1 且违规项指名 budget(非审批)
        assertThat(HeadlessMain.run(HeadlessMain.parse(new String[] {
            "--verify", "--policy=PRODUCTION", "--approval=ask"}), workspace, sessions)).isEqualTo(1);
        AppBoot.BootOptions boot = new AppBoot.BootOptions(
            HeadlessMain.resolveProfile(null),
            HeadlessMain.buildOverlays(
                HeadlessMain.parse(new String[] {"--approval=ask"}), workspace, sessions),
            true, Policy.PRODUCTION);
        assertThatThrownBy(() -> AppBoot.boot(boot))
            .isInstanceOf(AppBoot.VerifyFailedException.class)
            .hasMessageContaining("token budget 为零")
            .hasMessageNotContaining("审批为 AUTO");
    }

    @Test
    void missingTaskWithKeyAbsentFailsLoud() throws Exception {
        assertThat(HeadlessMain.run(HeadlessMain.parse(new String[] {"t"}), workspace, sessions)).isEqualTo(2);
    }
}
