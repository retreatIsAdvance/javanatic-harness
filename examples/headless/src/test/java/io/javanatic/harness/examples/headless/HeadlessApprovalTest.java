package io.javanatic.harness.examples.headless;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.tools.ApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** --approval 三值的组合期落点:overlay 启一禁二,解析出的 ApprovalService 模式逐一对应。 */
class HeadlessApprovalTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void approvalFlagSelectsExactlyOneProvider() throws Exception {
        assertThat(bootedMode(null)).isEqualTo(ApprovalService.Mode.AUTO);
        assertThat(bootedMode("auto")).isEqualTo(ApprovalService.Mode.AUTO);
        assertThat(bootedMode("ask")).isEqualTo(ApprovalService.Mode.HUMAN_GATE);
        assertThat(bootedMode("deny")).isEqualTo(ApprovalService.Mode.DENY_ALL);
    }

    private ApprovalService.Mode bootedMode(String approval) throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            approval == null ? new String[] {"t"} : new String[] {"t", "--approval=" + approval});
        AppBoot.BootOptions boot = new AppBoot.BootOptions(
            HeadlessMain.resolveProfile(null),
            HeadlessMain.buildOverlays(options, workspace, sessions), false, Policy.STANDARD);
        try (Runtime rt = AppBoot.boot(boot)) {
            return rt.root().resolve(ApprovalService.KEY).orElseThrow().mode();
        }
    }
}
