package io.javanatic.harness.sandbox.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Landlock 助手的协议面与启动面（it24 S-a）：架构白名单、三形态启动、
 * 指令往返与非法拒绝、真启动后的 fail-closed 结论。平台无关部分在 darwin
 * 全跑；真强制（真建 ruleset + 真拒写 + 真 exec）只在 Linux 内核可验，
 * 由 S-c 的 CI 腿覆盖——本类不假装验过。
 */
class LandlockTest {

    // ---- 架构白名单：不猜 syscall 号 ----

    @Test
    void syscallNumbersAreWhitelistedForBothSupportedArches() {
        for (String arch : List.of("amd64", "x86_64", "aarch64", "arm64", "AMD64", "AArch64")) {
            assertThat(Landlock.syscallsForArch(arch)).get()
                .isEqualTo(new Landlock.Syscalls(444, 445, 446));
        }
    }

    @Test
    void otherArchesFailLoudInsteadOfGuessingNumbers() {
        for (String arch : List.of("i386", "i686", "ppc64le", "s390x", "riscv64", "unknown", "")) {
            assertThat(Landlock.syscallsForArch(arch)).isEmpty();
        }
    }

    @Test
    void unsupportedReasonNamesTheOffendingFact() {
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "aarch64");
            assertThat(Landlock.unsupportedReason()).get().asString()
                .contains("unsupported platform").contains("Mac OS X");

            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "ppc64le");
            assertThat(Landlock.unsupportedReason()).get().asString()
                .contains("unsupported architecture").contains("ppc64le");

            System.setProperty("os.arch", "aarch64");
            assertThat(Landlock.unsupportedReason()).isEmpty();
        } finally {
            System.setProperty("os.name", osName);
            System.setProperty("os.arch", osArch);
        }
    }

    // ---- 入选判据：ABI 1–2 的两洞必须拒 ----

    @Test
    void rightsWithoutReferOrTruncateAreRefused() {
        assertThat(Landlock.admits(Landlock.FULL_REQUIREMENT)).isTrue();
        assertThat(Landlock.admits(Landlock.WRITE_EFFECTS_V1)).isFalse();
        assertThat(Landlock.admits(Landlock.FULL_REQUIREMENT & ~Landlock.ACCESS_FS_TRUNCATE)).isFalse();
        assertThat(Landlock.admits(Landlock.FULL_REQUIREMENT & ~Landlock.ACCESS_FS_REFER)).isFalse();
        assertThat(Landlock.admits(0)).isFalse();
        // 内核多出的新位不影响入选（向前兼容）
        assertThat(Landlock.admits(Landlock.FULL_REQUIREMENT | (1L << 20))).isTrue();
    }

    // ---- 启动三形态（+ 不自洽时 fail loud） ----

    @Test
    void helperCommandClasspathFormForUnnamedModule() {
        assertThat(Landlock.helperCommand("/jdk", "/tmp/helper.jar", "", null).orElseThrow())
            .containsExactly("/jdk/bin/java", "-XX:-UsePerfData", "--enable-native-access=ALL-UNNAMED",
                "-cp", "/tmp/helper.jar", LandlockExecMain.class.getName());
    }

    @Test
    void helperCommandModulePathFormWhenModulePathPresent() {
        assertThat(Landlock.helperCommand("/jdk", "", "/modules", "io.javanatic.harness.sandbox.local")
            .orElseThrow())
            .containsExactly("/jdk/bin/java", "-XX:-UsePerfData",
                "--enable-native-access=io.javanatic.harness.sandbox.local",
                "--module-path", "/modules", "-m",
                "io.javanatic.harness.sandbox.local/" + LandlockExecMain.class.getName());
    }

    @Test
    void helperCommandImageFormWhenModulePathAbsent() {
        assertThat(Landlock.helperCommand("/image", "", "", "io.javanatic.harness.sandbox.local")
            .orElseThrow())
            .containsExactly("/image/bin/java", "-XX:-UsePerfData",
                "--enable-native-access=io.javanatic.harness.sandbox.local", "-m",
                "io.javanatic.harness.sandbox.local/" + LandlockExecMain.class.getName());
    }

    @Test
    void helperCommandWithoutDecidableFormIsEmpty() {
        assertThat(Landlock.helperCommand("/jdk", "", "", null)).isEmpty();
        assertThat(Landlock.helperCommand("/jdk", null, "/modules", null)).isEmpty();
    }

    @Test
    void hostHelperCommandIsSelfConsistent() {
        List<String> command = Landlock.hostHelperCommand().orElseThrow();
        assertThat(command).first().asString().endsWith("/bin/java");
        assertThat(command).contains("-XX:-UsePerfData");
        assertThat(command).anyMatch(token -> token.startsWith("--enable-native-access="));
    }

    // ---- 指令往返与非法拒绝 ----

    @Test
    void runArgsRoundTripKeepsModeRootsAndArgv() {
        List<Path> roots = List.of(Path.of("/tmp/ws"), Path.of("/tmp"));
        List<String> argv = List.of("bash", "-c", "echo hi > out.txt");
        Landlock.Invocation parsed = Landlock.parse(
            Landlock.runArgs(Landlock.MODE_WORKSPACE_WRITE, roots, argv)).orElseThrow();
        assertThat(parsed.probe()).isFalse();
        assertThat(parsed.mode()).isEqualTo(Landlock.MODE_WORKSPACE_WRITE);
        assertThat(parsed.roots()).containsExactlyElementsOf(roots);
        assertThat(parsed.argv()).containsExactlyElementsOf(argv);

        Landlock.Invocation readOnly = Landlock.parse(
            Landlock.runArgs(Landlock.MODE_READ_ONLY, List.of(), argv)).orElseThrow();
        assertThat(readOnly.mode()).isEqualTo(Landlock.MODE_READ_ONLY);
        assertThat(readOnly.roots()).isEmpty();
    }

    @Test
    void separatorInsideArgvIsNotReparsed() {
        Landlock.Invocation parsed = Landlock.parse(
            Landlock.runArgs(Landlock.MODE_READ_ONLY, List.of(), List.of("echo", "--", "x"))).orElseThrow();
        assertThat(parsed.argv()).containsExactly("echo", "--", "x");
    }

    @Test
    void probeArgsRoundTrip() {
        Landlock.Invocation parsed = Landlock.parse(Landlock.probeArgs()).orElseThrow();
        assertThat(parsed.probe()).isTrue();
        assertThat(parsed.roots()).isEmpty();
        assertThat(parsed.argv()).isEmpty();
    }

    @Test
    void malformedInvocationIsRejectedNotGuessed() {
        assertThat(Landlock.parse(List.of())).isEmpty();
        assertThat(Landlock.parse(List.of("--mode"))).isEmpty();
        assertThat(Landlock.parse(List.of("--mode", "sandbox"))).isEmpty();
        assertThat(Landlock.parse(List.of("--mode", "read-only"))).isEmpty();
        assertThat(Landlock.parse(List.of("--mode", "read-only", "--"))).isEmpty();
        assertThat(Landlock.parse(List.of("--mode", "read-only", "--root"))).isEmpty();
        assertThat(Landlock.parse(List.of("--mode", "read-only", "--mode", "workspace-write", "--", "true")))
            .isEmpty();
        assertThat(Landlock.parse(List.of("--root", "/tmp", "--mode", "read-only", "--", "true"))).isEmpty();
        assertThat(Landlock.parse(List.of("--unknown", "x", "--", "true"))).isEmpty();
        assertThat(Landlock.parse(List.of("--probe", "extra"))).isEmpty();
    }

    // ---- 真启动：绑定与 fail-closed 结论 ----
    // 分平台挂靠（@EnabledOnOs 用 JUnit 自己的真实事实，不借被测代码的平台判断——
    // 否则「平台门被摘掉」这类突变会把测试分支一起搬走，突变不红即失去判别力）

    @Test
    @EnabledOnOs(OS.MAC)
    void helperProbeOffLinuxFailsClosedNamingThePlatform() throws Exception {
        ProcessResult result = launch(Landlock.hostHelperCommand().orElseThrow(), Landlock.probeArgs());
        assertThat(result.exit()).isEqualTo(Landlock.EXIT_UNAVAILABLE);
        assertThat(result.stderr()).contains("landlock: unsupported platform")
            .contains(System.getProperty("os.name"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void helperProbeOnLinuxSpeaksOnlyProtocolVerdicts() throws Exception {
        ProcessResult result = launch(Landlock.hostHelperCommand().orElseThrow(), Landlock.probeArgs());
        assertThat(result.exit()).isIn(Landlock.EXIT_OK, Landlock.EXIT_UNAVAILABLE,
            Landlock.EXIT_ABI_INSUFFICIENT, Landlock.EXIT_NOT_ENFORCING, Landlock.EXIT_APPLY_FAILED);
        assertThat(result.stderr()).contains("landlock:");
        if (result.exit() == Landlock.EXIT_OK) {
            assertThat(result.stderr()).contains("landlock: ready").contains("write denied");
        }
    }

    /** 内核 errno（uapi）：给哪个码取决于内核查序与 LSM 状态，故按档列举（见用例注释）。 */
    private static final int EPERM = 1;
    private static final int EBADF = 9;
    private static final int ENOSYS = 38;
    private static final int EOPNOTSUPP = 95;

    /**
     * 原生调用点类型纪律（it24 首跑修正）：syscall 蹦床句柄形参全 long，invokeExact 不做隐式
     * 加宽——fd 若以 int 直喂，异常落在 FFM 层而到不了内核。以非法 fd 真调一次：裁决必须
     * 来自内核，不是 WrongMethodTypeException。
     *
     * <p>errno 分档随内核查序（`security/landlock/syscalls.c`）：`restrict_self` 先查
     * no_new_privs/CAP_SYS_ADMIN（普通 JVM 两者皆无 → EPERM），之后才校验 ruleset fd（EBADF）；
     * `add_rule` 先校验 fd（EBADF）。LSM 未启用给 EOPNOTSUPP，系统调用号不存在给 ENOSYS。
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void bogusFdReachesTheKernelInsteadOfFailingAtInvokeExact() throws Throwable {
        Landlock.CallResult restricted = Landlock.restrictSelf(-1);
        assertThat(restricted.value()).isEqualTo(-1L);
        assertThat(restricted.errno()).isIn(EPERM, EBADF, ENOSYS, EOPNOTSUPP);

        Landlock.CallResult ruled = Landlock.addPathBeneathRule(-1, -1, Landlock.WRITE_EFFECTS_V1);
        assertThat(ruled.value()).isEqualTo(-1L);
        assertThat(ruled.errno()).isIn(EBADF, ENOSYS, EOPNOTSUPP);
    }

    @Test
    void helperRejectsMalformedInvocationWithUsageExit() throws Exception {
        ProcessResult result = launch(Landlock.hostHelperCommand().orElseThrow(),
            List.of("--mode", "sandbox", "--", "true"));
        assertThat(result.exit()).isEqualTo(Landlock.EXIT_USAGE);
        assertThat(result.stderr()).contains(Landlock.USAGE);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void runModeOffLinuxRefusesToExecuteUnconfined() throws Exception {
        ProcessResult result = launch(Landlock.hostHelperCommand().orElseThrow(),
            Landlock.runArgs(Landlock.MODE_READ_ONLY, List.of(), List.of("/bin/echo", "must-not-run")));
        assertThat(result.exit()).isEqualTo(Landlock.EXIT_UNAVAILABLE);
        assertThat(result.stderr()).contains("landlock: unsupported platform");
        assertThat(result.stdout()).doesNotContain("must-not-run");
    }

    /** 启动助手并按协议收裁决（stdout/stderr 分持；输出量级字节级，无死锁面）。 */
    private static ProcessResult launch(List<String> command, List<String> args) throws IOException {
        List<String> full = new ArrayList<>(command);
        full.addAll(args);
        Process process = new ProcessBuilder(full).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("helper did not terminate within 30s: " + full);
            }
            return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AssertionError("interrupted while waiting for helper: " + full, interrupted);
        }
    }

    private record ProcessResult(int exit, String stdout, String stderr) {
    }
}
