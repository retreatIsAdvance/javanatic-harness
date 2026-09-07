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
        assertThat(HeadlessMain.run(HeadlessMain.parse(new String[] {"--verify"}), workspace, sessions)).isZero();
    }

    @Test
    void verifyProductionRejectsAutoApproval() throws Exception {
        // 组合默认 AUTO → PRODUCTION 必须拒绝并指出违规项
        int exit = HeadlessMain.run(HeadlessMain.parse(new String[] {"--verify", "--policy=PRODUCTION"}), workspace, sessions);
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void missingTaskWithKeyAbsentFailsLoud() throws Exception {
        assertThat(HeadlessMain.run(HeadlessMain.parse(new String[] {"t"}), workspace, sessions)).isEqualTo(2);
    }
}
