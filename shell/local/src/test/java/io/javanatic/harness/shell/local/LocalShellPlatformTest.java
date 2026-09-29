package io.javanatic.harness.shell.local;

import io.javanatic.harness.shell.shell.ShellUnavailableException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 平台 argv 形状与 pwsh 探测的纯单测（任意宿主可跑；真执行归两条腿的执行器测试）。 */
class LocalShellPlatformTest {

    @Test
    void posixArgvIsBashDashC() {
        ShellPlatform platform = ShellPlatform.of(ShellPlatform.POSIX, Optional.empty());
        assertThat(platform.argv("echo hi")).containsExactly("bash", "-c", "echo hi");
    }

    /**
     * Windows argv：命令文本经 {@code -EncodedCommand}（UTF-16LE base64 单 token）装载。
     * 裸文本装载跨 provider→助手一跳时会撞宿主 JVM 的 ProcessBuilder 引号口径——含引号的
     * 命令串两态互斥失效（it25 探针 P6/P7：pwsh 腿 exit=1、stderr 空、命令根本没执行，
     * 即 S-0 首跑 e2e 之谜）；编码后线路上无引号无空白，两跳都字节保真（P10 实测）。
     */
    @Test
    void windowsArgvCarriesTheCommandEncodedIntoAQuoteFreeToken() {
        Path pwsh = Path.of("C:", "Program Files", "PowerShell", "7", "pwsh.exe");
        ShellPlatform platform = ShellPlatform.of(ShellPlatform.WIN32, Optional.of(pwsh));
        String command = "cmd.exe /d /c \"echo 拒绝访问 > C:\\dir with space\\x.txt\"";
        List<String> argv = platform.argv(command);
        assertThat(argv.subList(0, 4))
            .containsExactly(pwsh.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand");
        assertThat(argv).hasSize(5);
        String carrier = argv.getLast();
        assertThat(carrier).matches("[A-Za-z0-9+/=]+");
        assertThat(new String(Base64.getDecoder().decode(carrier), StandardCharsets.UTF_16LE))
            .isEqualTo(command);
    }

    /** pwsh 缺席 = fail-loud：消息点名解释器、给安装指引、指路 --docker（无 5.1 兜底）。 */
    @Test
    void windowsWithoutPwshFailsLoudAndNamesRemedies() {
        ShellPlatform platform = ShellPlatform.of(ShellPlatform.WIN32, Optional.empty());
        assertThatThrownBy(() -> platform.argv("echo hi"))
            .isInstanceOf(ShellUnavailableException.class)
            .hasMessageContaining("pwsh")
            .hasMessageContaining("install")
            .hasMessageContaining("--docker");
    }

    @Test
    void findWindowsShellPicksFirstPathHit(@TempDir Path first, @TempDir Path second) throws IOException {
        Path pwsh = Files.createFile(second.resolve("pwsh.exe"));
        assertThat(ShellPlatform.findWindowsShell(List.of(first, second)))
            .contains(pwsh.toAbsolutePath());
    }

    @Test
    void findWindowsShellIsEmptyWithoutPwsh(@TempDir Path empty) {
        assertThat(ShellPlatform.findWindowsShell(List.of(empty))).isEmpty();
    }

    /** PATH 键名大小写不敏感：Windows 系统变量惯名 Path，精确查 "PATH" 会得到空表——
     * （System.getenv() 的 Map 视图按精确键名查找，只有 getenv(name) 访问器不敏感）。 */
    @Test
    void pathEntriesIsCaseInsensitiveAcrossWindowsAndPosixKeyShapes() {
        String sep = File.pathSeparator;
        assertThat(ShellPlatform.pathEntries(Map.of("Path", "/one" + sep + "/two")))
            .containsExactly(Path.of("/one"), Path.of("/two"));
        assertThat(ShellPlatform.pathEntries(Map.of("PATH", "/one" + sep + "/two")))
            .containsExactly(Path.of("/one"), Path.of("/two"));
        assertThat(ShellPlatform.pathEntries(Map.of("UNRELATED", "x"))).isEmpty();
    }

    /** 真宿主的 host() 在这两个平台上必须是 POSIX 形状（win32 真形状归 Windows 腿）。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void hostDispatchesPosixOnPosixHosts() {
        assertThat(ShellPlatform.host().argv("echo hi")).containsExactly("bash", "-c", "echo hi");
    }
}
