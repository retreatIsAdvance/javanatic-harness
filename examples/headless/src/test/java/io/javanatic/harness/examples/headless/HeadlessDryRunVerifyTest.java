package io.javanatic.harness.examples.headless;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * it36 停 2 组合编辑干跑(it36 W4 verifyComposition 装配面):临时 profile +
 * 启动期同 overlays/policy 跑 AppBoot verify 断言。组合级失败(策略违规/坏 YAML/
 * 未知 bundle)一律归一为违规清单(不抛——编辑器 UX 逐项展示);临时文件零残留。
 */
class HeadlessDryRunVerifyTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    private static final String GOOD_PROFILE = """
        name: headless
        policy: standard
        bundles: [base]
        rows: []
        """;

    private AppBoot.BootOptions serverBoot(Policy policy) throws Exception {
        return new AppBoot.BootOptions(
            HeadlessMain.resolveProfile(null),
            HeadlessMain.buildOverlays(HeadlessMain.parse(new String[] {}), workspace, sessions),
            true, policy);
    }

    @Test
    void goodCompositionYieldsNoViolations() throws Exception {
        assertThat(HeadlessMain.dryRunVerify(GOOD_PROFILE, serverBoot(Policy.STANDARD))).isEmpty();
    }

    @Test
    void policyViolationSurfacesAsViolations() throws Exception {
        // 缺省组合审批 AUTO → PRODUCTION 拒;违规进清单而非抛出
        List<String> violations = HeadlessMain.dryRunVerify(GOOD_PROFILE, serverBoot(Policy.PRODUCTION));
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.contains("AUTO"));
    }

    @Test
    void badYamlSurfacesAsViolationNotThrow() throws Exception {
        List<String> violations = HeadlessMain.dryRunVerify("name: [unclosed", serverBoot(Policy.STANDARD));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void unknownBundleSurfacesAsViolation() throws Exception {
        List<String> violations = HeadlessMain.dryRunVerify("""
            name: headless
            policy: standard
            bundles: [no-such-bundle]
            rows: []
            """, serverBoot(Policy.STANDARD));
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.contains("no-such-bundle"));
    }

    @Test
    void dryRunLeavesNoTempProfileResidue() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = countDryRunTempFiles(tmp);
        HeadlessMain.dryRunVerify(GOOD_PROFILE, serverBoot(Policy.STANDARD));
        HeadlessMain.dryRunVerify("name: [unclosed", serverBoot(Policy.STANDARD));
        assertThat(countDryRunTempFiles(tmp)).isEqualTo(before);
    }

    private static long countDryRunTempFiles(Path tmp) throws Exception {
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("jh-verify-composition")).count();
        }
    }
}
