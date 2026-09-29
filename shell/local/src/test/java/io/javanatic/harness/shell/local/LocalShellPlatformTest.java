package io.javanatic.harness.shell.local;

import io.javanatic.harness.shell.shell.ShellUnavailableException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void windowsArgvPinsPwshWithProfileAndInteractiveSuppressed() {
        Path pwsh = Path.of("C:", "Program Files", "PowerShell", "7", "pwsh.exe");
        ShellPlatform platform = ShellPlatform.of(ShellPlatform.WIN32, Optional.of(pwsh));
        assertThat(platform.argv("echo hi"))
            .containsExactly(pwsh.toString(), "-NoProfile", "-NonInteractive", "-Command", "echo hi");
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
