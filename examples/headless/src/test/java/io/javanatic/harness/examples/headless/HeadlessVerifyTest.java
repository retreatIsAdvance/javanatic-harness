package io.javanatic.harness.examples.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** --verify/policy 语义:STANDARD 过、PRODUCTION 拒 AUTO、无 key 可跑。 */
class HeadlessVerifyTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void verifyStandardPassesWithoutKey() throws Exception {
        assertThat(HeadlessMain.run(null, true, Policy.STANDARD, workspace, sessions)).isZero();
    }

    @Test
    void verifyProductionRejectsAutoApproval() throws Exception {
        // 组合默认 AUTO → PRODUCTION 必须拒绝并指出违规项
        int exit = HeadlessMain.run(null, true, Policy.PRODUCTION, workspace, sessions);
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void missingTaskWithKeyAbsentFailsLoud() throws Exception {
        assertThat(HeadlessMain.run(null, false, Policy.STANDARD, workspace, sessions)).isEqualTo(2);
    }
}
