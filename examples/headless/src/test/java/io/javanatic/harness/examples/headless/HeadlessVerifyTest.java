package io.javanatic.harness.examples.headless;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * --verify/policy 语义 + 07 §6 治理摘要（stdout）：STANDARD 过、PRODUCTION 拒
 * AUTO / 零预算、补齐 budget 后可达、无 key 可跑；摘要字段全部来自实现自述。
 */
class HeadlessVerifyTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void verifyStandardPassesWithoutKeyAndPrintsGovernanceSummary() throws Exception {
        Result result = run("--verify");
        assertThat(result.exit()).isZero();
        assertThat(result.stdout())
            .contains("Profile: headless   policy: STANDARD")
            .containsPattern("  composition: \\d+ rows, \\d+ discovered, 0 unreferenced")
            .contains("  approval: AUTO (approval-auto)")
            .contains("  audit: jsonl " + sessions + " (durable)")
            .contains("  stop: max-turns=50 max-steps=40 budget=unlimited");
    }

    @Test
    void verifyProductionRejectsAutoApproval() throws Exception {
        // 组合默认 AUTO → PRODUCTION 必须拒绝并指出违规项;摘要不打(违规走 stderr)
        Result result = run("--verify", "--policy=PRODUCTION");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void verifyAcceptsHumanGateAndDenyAll() throws Exception {
        assertThat(run("--verify", "--approval=ask").exit()).isZero();
        assertThat(run("--verify", "--approval=deny").exit()).isZero();
    }

    @Test
    void verifyProductionReachableWithHumanGateAndBudget() throws Exception {
        // S2 收口:ask 人闸 + jsonl 耐久 + --budget 非零 → PRODUCTION 组合可达
        Result result = run("--verify", "--policy=PRODUCTION", "--approval=ask", "--budget=50000");
        assertThat(result.exit()).isZero();
        assertThat(result.stdout())
            .contains("policy: PRODUCTION")
            .contains("  approval: HUMAN_GATE (approval-ask)")
            .contains("  stop: max-turns=50 max-steps=40 budget=50000");
    }

    @Test
    void verifyProductionWithoutBudgetFailsOnTokenBudgetViolation() throws Exception {
        // 反例:其余可达条件齐备,零预算仍 exit 1 且违规项指名 budget(非审批)
        assertThat(run("--verify", "--policy=PRODUCTION", "--approval=ask").exit()).isEqualTo(1);
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
        assertThat(run("t").exit()).isEqualTo(2);
    }

    private Result run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(HeadlessMain.parse(args), workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String stdout, String stderr) {
    }
}
