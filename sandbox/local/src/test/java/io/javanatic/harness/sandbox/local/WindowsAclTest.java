package io.javanatic.harness.sandbox.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * windows-acl 助手的协议面、引号面与真强制腿（it25 S-a）：纯函数（指令往返、
 * 引号口径、SID 字节形、平台门、方言解码）在任意宿主全跑；真机制（低完整性令牌 +
 * 逐对象打标 + 真拒写 + stdio 透传 + TEMP 重定向）只在 Windows 可验——darwin 上验
 * 平台门 fail-closed，真腿在 VM/CI windows job 跑（本类不假装验过）。
 *
 * <p>stdio 透传与内嵌引号是探针断言不到的面（探针读不到自己的 stdout），由本类
 * 的真跑腿补上——见 {@link WindowsAcl} 类注「拓扑不变量」。
 */
class WindowsAclTest {

    @TempDir
    Path workspace;

    /** 未打标面（反例用）；与 workspace 同级但不在打标树内。 */
    @TempDir
    Path outside;

    /** 参照 VM（zh-CN）控制台码页：chcp 936 = GBK。 */
    private static final Charset CONSOLE_GBK = Charset.forName("GBK");

    /**
     * 助手 stderr 的两种现实解码（S-a 修正 2）：子进程 stderr 按控制台码页字节透传——
     * en-US 控制台为 ASCII（UTF-8 兼容），zh-CN chcp 936 为 GBK（参照 VM 实测形态）。
     * 只用 UTF-8 一种解码时 GBK 的「拒绝访问」永不成串，方言绑定腿在参照 VM 上必红。
     */
    private static final List<Charset> STDERR_CHARSETS = List.of(StandardCharsets.UTF_8, CONSOLE_GBK);

    // ---- 平台门与 SID（纯函数） ----

    @Test
    void unsupportedReasonNamesTheOffendingFact() {
        String osName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertThat(WindowsAcl.unsupportedReason()).get().asString()
                .contains("windows-acl: unsupported platform").contains("Mac OS X");
            System.setProperty("os.name", "Windows 11");
            assertThat(WindowsAcl.unsupportedReason()).isEmpty();
        } finally {
            System.setProperty("os.name", osName);
        }
    }

    @Test
    void integritySidBytesRenderAsTheLowIntegrityLevelSid() {
        assertThat(WindowsAcl.sidToString(WindowsAcl.integritySid())).isEqualTo("S-1-16-4096");
    }

    @Test
    void sidToStringReadsSubAuthoritiesLittleEndian() {
        byte[] sid = {1, 2, 0, 0, 0, 0, 0, 5, 32, 0, 0, 0, 0x20, 0x02, 0, 0};
        assertThat(WindowsAcl.sidToString(sid)).isEqualTo("S-1-5-32-544");
    }

    // ---- 指令往返与非法拒绝 ----

    @Test
    void runArgsRoundTripKeepsModeRootsTempAndArgv() {
        List<Path> roots = List.of(Path.of("C:\\work\\ws"));
        List<String> argv = List.of("cmd.exe", "/d", "/c", "echo hi > out.txt");
        WindowsAcl.Invocation parsed = WindowsAcl.parse(WindowsAcl.runArgs(
            WindowsAcl.MODE_WORKSPACE_WRITE, roots, Path.of("C:\\Temp"), argv)).orElseThrow();
        assertThat(parsed.probe()).isFalse();
        assertThat(parsed.mode()).isEqualTo(WindowsAcl.MODE_WORKSPACE_WRITE);
        assertThat(parsed.roots()).containsExactlyElementsOf(roots);
        assertThat(parsed.temp()).contains(Path.of("C:\\Temp"));
        assertThat(parsed.argv()).containsExactlyElementsOf(argv);

        WindowsAcl.Invocation readOnly = WindowsAcl.parse(
            WindowsAcl.runArgs(WindowsAcl.MODE_READ_ONLY, List.of(), null, argv)).orElseThrow();
        assertThat(readOnly.mode()).isEqualTo(WindowsAcl.MODE_READ_ONLY);
        assertThat(readOnly.roots()).isEmpty();
        assertThat(readOnly.temp()).isEmpty();
        assertThat(readOnly.argv()).containsExactlyElementsOf(argv);
    }

    @Test
    void separatorInsideArgvIsNotReparsed() {
        WindowsAcl.Invocation parsed = WindowsAcl.parse(WindowsAcl.runArgs(
            WindowsAcl.MODE_READ_ONLY, List.of(), null, List.of("echo", "--", "x"))).orElseThrow();
        assertThat(parsed.argv()).containsExactly("echo", "--", "x");
    }

    /**
     * 载体不变式（it25 探针 P1/P9 实测）：provider→助手一跳的发送方是宿主 JVM 的
     * ProcessBuilder——含引号参数在 LEGACY 口径下被吃引号劈段、WIN32_SAFE 口径下
     * {@code \"} 字面泄漏（两态互斥失效），只有「无引号无空白」的单 token 两态都保真
     * （P0b/P0c：含空白的路径 token 与尾反斜杠也保真，但命令串的引号是常态）。
     * 故 argv 整体进单参 base64 载体：引号/空白/非 ASCII/换行都压进字母表安全的 token。
     */
    @Test
    void runArgsCarriesArgvAsOneQuoteFreeBase64Token() {
        List<String> tricky = List.of("cmd.exe", "/d", "/c",
            "echo \"quoted arg\" > \"C:\\dir with space\\out.txt\"", "", "中文 参数", "--", "line\nbreak");
        List<String> wire = WindowsAcl.runArgs(WindowsAcl.MODE_WORKSPACE_WRITE,
            List.of(Path.of("C:\\work\\ws")), Path.of("C:\\Temp"), tricky);
        assertThat(wire).contains("--argv-b64");
        assertThat(wire.getLast()).matches("[A-Za-z0-9+/=]+");
        assertThat(wire).noneMatch(token -> token.indexOf('"') >= 0);
        assertThat(WindowsAcl.parse(wire).orElseThrow().argv()).containsExactlyElementsOf(tricky);
    }

    /**
     * blob 载体的准入纪律：结构损坏一律拒绝不猜（非 base64 / 长度字段超界 / 尾部残渣 /
     * 空载体）；旧「{@code --} 逐参」形态不再是协议（fail loud，无兼容解析）。
     */
    @Test
    void malformedArgvCarriersAreRejectedNotGuessed() {
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--argv-b64", "!!not-base64!!")))
            .isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--argv-b64",
            base64(new byte[] {0, 0, 0, 8, 'a', 'b'})))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--argv-b64",
            base64(new byte[] {0, 0, 0, 1, 'a', 'x'})))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--argv-b64", ""))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--argv-b64",
            rawBlob(new byte[] {'a'}), "trailing"))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--", "true"))).isEmpty();
    }

    /** 原始字节直接 Base64（手工造结构损坏载体的用武之地）。 */
    private static String base64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** 手工 blob：逐元素 {@code [4 字节大端长度][UTF-8 字节]} 串联后 Base64（与被测编码同构）。 */
    private static String rawBlob(byte[]... elements) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] element : elements) {
            out.write(element.length >>> 24);
            out.write(element.length >>> 16);
            out.write(element.length >>> 8);
            out.write(element.length);
            out.write(element, 0, element.length);
        }
        return base64(out.toByteArray());
    }

    /** 单元素合法 blob（解出即 {@code [arg]}）。 */
    private static String blobOf(String arg) {
        return rawBlob(arg.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void probeArgsRoundTrip() {
        WindowsAcl.Invocation parsed = WindowsAcl.parse(WindowsAcl.probeArgs()).orElseThrow();
        assertThat(parsed.probe()).isTrue();
        assertThat(parsed.roots()).isEmpty();
        assertThat(parsed.argv()).isEmpty();
    }

    @Test
    void malformedInvocationIsRejectedNotGuessed() {
        assertThat(WindowsAcl.parse(List.of())).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode"))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "danger-full-access", "--argv-b64", blobOf("true"))))
            .isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only"))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--argv-b64"))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--root", "C:\\ws", "--argv-b64", blobOf("true"))))
            .isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--temp", "C:\\T", "--argv-b64", blobOf("true"))))
            .isEmpty();
        // workspace-write 必须带 --temp（会话临时目录的父——接口承诺 TEMP/TMP 重定向）
        assertThat(WindowsAcl.parse(List.of("--mode", "workspace-write", "--root", "C:\\ws",
            "--argv-b64", blobOf("true")))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--mode", "read-only",
            "--argv-b64", blobOf("true")))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--temp", "C:\\T", "--temp", "C:\\T2",
            "--mode", "workspace-write", "--argv-b64", blobOf("true")))).isEmpty();
        assertThat(WindowsAcl.parse(List.of("--mode", "read-only", "--nope", "x", "--argv-b64", blobOf("true"))))
            .isEmpty();
        assertThat(WindowsAcl.parse(List.of("--probe", "extra"))).isEmpty();
    }

    // ---- 命令行引号（cmd 口径：只包裹不转义） ----

    @Test
    void commandLineWrapsOnlyWhatCmdWouldSplit() {
        assertThat(WindowsAcl.commandLine(List.of("cmd.exe", "/d", "/c", "echo hi")))
            .isEqualTo("cmd.exe /d /c \"echo hi\"");
        assertThat(WindowsAcl.commandLine(List.of("C:\\no\\quotes\\here.exe", "-x")))
            .isEqualTo("C:\\no\\quotes\\here.exe -x");
        // 空串与含引号者按字面包裹（不做 MSVCRT 反斜杠转义——第二跳的接收方是 cmd）
        assertThat(WindowsAcl.commandLine(List.of("a", "b c", "", "d\"e")))
            .isEqualTo("a \"b c\" \"\" \"d\"e\"");
        // 反斜杠保持字面：含路径与内层引号的命令串是本后端的常态形状
        assertThat(WindowsAcl.commandLine(List.of("cmd.exe", "/d", "/c", "echo x > \"C:\\Temp\\x.txt\"")))
            .isEqualTo("cmd.exe /d /c \"echo x > \"C:\\Temp\\x.txt\"\"");
    }

    @Test
    void hostHelperCommandIsSelfConsistent() {
        List<String> command = WindowsAcl.hostHelperCommand().orElseThrow();
        // 语义断言不写死 POSIX 分隔符：Windows 上 Path 渲染为反斜杠、且无 .exe 后缀
        // （S-a 修正 1——原 endsWith("/bin/java") 在 Windows 必红）
        Path java = Path.of(command.getFirst());
        assertThat(java.getFileName().toString()).isEqualTo("java");
        assertThat(java.getParent().getFileName().toString()).isEqualTo("bin");
        assertThat(command).contains("-XX:-UsePerfData");
        assertThat(command).anyMatch(token -> token.startsWith("--enable-native-access="));
        assertThat(command).last().asString().endsWith(WindowsAclExecMain.class.getName());
    }

    // ---- 方言解码（S-a 修正 2 的锚点；任意宿主全跑） ----

    @Test
    void dialectMatchingHoldsForBothConsoleCodepages() {
        assertThat(dialectSeen("Access is denied.".getBytes(StandardCharsets.US_ASCII)))
            .as("en-US 控制台（ASCII）").isTrue();
        assertThat(dialectSeen("拒绝访问。".getBytes(CONSOLE_GBK)))
            .as("zh-CN chcp 936（GBK）——单用 UTF-8 解码时必假红").isTrue();
        assertThat(dialectSeen("The system cannot find the file specified.".getBytes(StandardCharsets.US_ASCII)))
            .as("非方言文案不误报").isFalse();
    }

    // ---- 真启动：平台门与协议裁决（分平台挂靠，不借被测代码的平台判断） ----

    @Test
    @EnabledOnOs(OS.MAC)
    void helperProbeOffWindowsFailsClosedNamingThePlatform() throws Exception {
        ProcessResult result = launch(WindowsAcl.hostHelperCommand().orElseThrow(), WindowsAcl.probeArgs());
        assertThat(result.exit()).isEqualTo(WindowsAcl.EXIT_UNAVAILABLE);
        assertThat(result.stderrText()).contains("windows-acl: unsupported platform")
            .contains(System.getProperty("os.name"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void runModeOffWindowsRefusesToExecuteUnconfined() throws Exception {
        ProcessResult result = launch(WindowsAcl.hostHelperCommand().orElseThrow(),
            WindowsAcl.runArgs(WindowsAcl.MODE_READ_ONLY, List.of(), null,
                List.of("cmd.exe", "/d", "/c", "echo must-not-run")));
        assertThat(result.exit()).isEqualTo(WindowsAcl.EXIT_UNAVAILABLE);
        assertThat(result.stdoutText()).doesNotContain("must-not-run");
    }

    @Test
    void helperRejectsMalformedInvocationWithUsageExit() throws Exception {
        ProcessResult result = launch(WindowsAcl.hostHelperCommand().orElseThrow(),
            List.of("--mode", "sandbox", "--", "true"));
        assertThat(result.exit()).isEqualTo(WindowsAcl.EXIT_USAGE);
        assertThat(result.stderrText()).contains(WindowsAcl.USAGE);
    }

    // ---- 真强制腿：windows-acl（VM/CI windows job；darwin 上不跑） ----

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void helperProbeOnWindowsSpeaksOnlyProtocolVerdicts() throws Exception {
        ProcessResult result = launch(WindowsAcl.hostHelperCommand().orElseThrow(), WindowsAcl.probeArgs());
        assertThat(result.exit()).isIn(WindowsAcl.EXIT_OK, WindowsAcl.EXIT_UNAVAILABLE,
            WindowsAcl.EXIT_NOT_ENFORCING, WindowsAcl.EXIT_APPLY_FAILED, WindowsAcl.EXIT_EXEC_FAILED);
        assertThat(result.stderrText()).contains("windows-acl:");
        if (result.exit() == WindowsAcl.EXIT_OK) {
            assertThat(result.stderrText()).contains("windows-acl: ready").contains("PARTIAL");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readOnlyRunDeniesWriteOutsideTheLabeledSurface() throws Exception {
        Path target = workspace.resolve("denied.txt");
        ProcessResult result = runHelper(WindowsAcl.MODE_READ_ONLY, List.of(), null, cmdEcho(target));
        assertThat(result.exit()).isNotZero();
        assertThat(Files.exists(target)).isFalse();
        assertThat(dialectSeen(result.stderr())).as("stderr: %s", result.stderrText()).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void workspaceWriteRunAdmitsInsideAndDeniesOutside() throws Exception {
        Path inside = workspace.resolve("in.txt");
        ProcessResult allowed = runHelper(WindowsAcl.MODE_WORKSPACE_WRITE, List.of(workspace), outside,
            cmdEcho(inside));
        assertThat(allowed.exit()).isZero();
        assertThat(Files.readString(inside).strip()).isEqualTo("x");

        Path deniedTarget = outside.resolve("x.txt");
        ProcessResult denied = runHelper(WindowsAcl.MODE_WORKSPACE_WRITE, List.of(workspace), outside,
            cmdEcho(deniedTarget));
        assertThat(denied.exit()).isNotZero();
        assertThat(Files.exists(deniedTarget)).isFalse();
        assertThat(dialectSeen(denied.stderr())).as("stderr: %s", denied.stderrText()).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void stdioPassesThroughToTheLowIntegrityChild() throws Exception {
        ProcessResult result = runHelper(WindowsAcl.MODE_READ_ONLY, List.of(), null,
            List.of("cmd.exe", "/d", "/c", "echo hello-from-low"));
        assertThat(result.exit()).isZero();
        assertThat(result.stdoutText()).contains("hello-from-low");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void innerQuotesSurviveTheTwoHopQuoting() throws Exception {
        Path target = workspace.resolve("quoted.txt");
        ProcessResult result = runHelper(WindowsAcl.MODE_WORKSPACE_WRITE, List.of(workspace), outside,
            List.of("cmd.exe", "/d", "/c", "echo \"quoted arg\" > \"" + target + "\""));
        assertThat(result.exit()).isZero();
        assertThat(Files.readString(target)).contains("\"quoted arg\"");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void childExitCodePropagatesUnchanged() throws Exception {
        ProcessResult result = runHelper(WindowsAcl.MODE_READ_ONLY, List.of(), null,
            List.of("cmd.exe", "/d", "/c", "exit", "7"));
        assertThat(result.exit()).isEqualTo(7);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void sessionTempIsARedirectedPrivateDirUnderTheGivenParent() throws Exception {
        Path recorded = workspace.resolve("temp-path.txt");
        ProcessResult result = runHelper(WindowsAcl.MODE_WORKSPACE_WRITE, List.of(workspace), outside,
            List.of("cmd.exe", "/d", "/c", "echo %TEMP% > \"" + recorded + "\""));
        assertThat(result.exit()).isZero();
        Path temp = Path.of(Files.readString(recorded).strip());
        // runner 的 TEMP 可为 8.3 短名拼写：期望侧按 realpath 语义比较（同一目录、两种拼写）。
        assertThat(temp).startsWith(outside.toRealPath());
        assertThat(temp.getFileName().toString()).contains("jh-sbx-");
    }

    /** 跑一次受限会话（真助手 + 真机制；只在 Windows 挂靠）。 */
    private static ProcessResult runHelper(String mode, List<Path> roots, Path temp, List<String> argv)
        throws Exception {
        return launch(WindowsAcl.hostHelperCommand().orElseThrow(),
            WindowsAcl.runArgs(mode, roots, temp, argv));
    }

    /** {@code echo x > <路径>}（路径含空白由助手侧按 cmd 口径引号）。 */
    private static List<String> cmdEcho(Path target) {
        return List.of("cmd.exe", "/d", "/c", "echo x > \"" + target + "\"");
    }

    /**
     * provider 侧的拒绝方言（{@link SandboxLocalPlugin#WINDOWS_ACL_DENIALS}）在 stderr
     * 里可见（行内大小写不敏感）——方言表与真实现场绑定的断言点：locale 不符即红。
     * stderr 按字节收，两种控制台码页形态都试解（{@link #STDERR_CHARSETS}）后匹配，
     * 断言强度不变（S-a 修正 2：原先单用 UTF-8 解码，GBK 形态必假红）。
     */
    private static boolean dialectSeen(byte[] stderr) {
        for (Charset charset : STDERR_CHARSETS) {
            for (String line : new String(stderr, charset).split("\\R", -1)) {
                String lowered = line.toLowerCase(Locale.ROOT);
                for (String signature : SandboxLocalPlugin.WINDOWS_ACL_DENIALS) {
                    if (lowered.contains(signature.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 启动助手并按协议收裁决（stdout/stderr 原始字节分持；输出量级字节级，无死锁面）。 */
    private static ProcessResult launch(List<String> command, List<String> args) throws IOException {
        List<String> full = new ArrayList<>(command);
        full.addAll(args);
        Process process = new ProcessBuilder(full).start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("helper did not terminate within 120s: " + full);
            }
            return new ProcessResult(process.exitValue(),
                process.getInputStream().readAllBytes(),
                process.getErrorStream().readAllBytes());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AssertionError("interrupted while waiting for helper: " + full, interrupted);
        }
    }

    /**
     * 助手产出：字节持有、解码推迟到断言点（stderr 的成串性随控制台码页而变，
     * 提前按 UTF-8 解死会毁掉 GBK 形态——S-a 修正 2；{@link #stderrText()} 仅
     * 供诊断与 ASCII 断言用）。
     */
    private record ProcessResult(int exit, byte[] stdout, byte[] stderr) {

        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String stderrText() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }
}
