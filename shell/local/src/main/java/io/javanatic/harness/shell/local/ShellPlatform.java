package io.javanatic.harness.shell.local;

import io.javanatic.harness.shell.shell.ShellUnavailableException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 平台 argv 分派：POSIX = {@code [bash, -c, cmd]}；Windows = {@code [pwsh, -NoProfile,
 * -NonInteractive, -Command, cmd]}。两平台键（win32 / posix）——解释器差异只在此处，
 * executor、沙箱包装与契约层平台无关。
 *
 * <p>pwsh 缺席 = fail-loud（{@link ShellUnavailableException}）：Windows PowerShell 5.1
 * 的引号/编码/退出码方言与 7 不同，静默代跑会把契约漂移藏进运行结果。宿主探测发生在
 * 执行期（argv 构造）而非装配期——Windows 上缺 pwsh 不影响组合与启动，只有真要跑命令
 * 时才拒绝。
 */
final class ShellPlatform {

    /** Windows 平台键。 */
    static final String WIN32 = "win32";

    /** POSIX 平台键（darwin / linux 共用 bash 形状）。 */
    static final String POSIX = "posix";

    /** Windows 解释器文件名（PATH 逐目录探测）。 */
    private static final String PWSH_EXE = "pwsh.exe";

    private final String platform;
    private final Optional<Path> windowsShell;

    private ShellPlatform(String platform, Optional<Path> windowsShell) {
        this.platform = platform;
        this.windowsShell = windowsShell;
    }

    /** 宿主平台：os.name 含 win 归 win32，其余归 posix。 */
    static ShellPlatform host() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new ShellPlatform(WIN32, findWindowsShell(pathEntries()));
        }
        return new ShellPlatform(POSIX, Optional.empty());
    }

    /**
     * 测试注伪：平台键与 Windows 解释器路径均为显式参数（形状可在任意宿主单测）。
     *
     * @param platform     {@link #WIN32} 或 {@link #POSIX}
     * @param windowsShell pwsh 绝对路径（Windows 宿主由 {@link #host()} 探测；缺席 = 空）
     */
    static ShellPlatform of(String platform, Optional<Path> windowsShell) {
        return new ShellPlatform(platform, windowsShell);
    }

    /**
     * 构造目标 argv。
     *
     * @throws ShellUnavailableException Windows 宿主上 pwsh 缺席（fail-loud，无 5.1 兜底）
     */
    List<String> argv(String command) {
        if (WIN32.equals(platform)) {
            Path shell = windowsShell.orElseThrow(() -> new ShellUnavailableException(
                "shell interpreter unavailable: pwsh (PowerShell 7) not found on PATH — install it "
                    + "(winget install Microsoft.PowerShell) or run with --docker "
                    + "(the container backend needs no host shell)"));
            return List.of(shell.toString(), "-NoProfile", "-NonInteractive", "-Command", command);
        }
        return List.of("bash", "-c", command);
    }

    /** PATH 条目按序扫描 pwsh.exe，首命中即用（Windows 命令解析同序）；无命中为空。 */
    static Optional<Path> findWindowsShell(List<Path> entries) {
        for (Path entry : entries) {
            Path candidate = entry.resolve(PWSH_EXE);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    /** PATH 拆成条目：跳过空段，剥掉安装器可能带上的包裹引号。 */
    private static List<Path> pathEntries() {
        return pathEntries(System.getenv());
    }

    /**
     * PATH 拆成条目（环境映射为显式参数，形状可在任意宿主单测）。
     *
     * <p>键名大小写不敏感：Windows 上系统变量惯名为 {@code Path}，而 {@code System.getenv()}
     * 的 Map 视图按精确键名查找（只有 {@code getenv(name)} 访问器大小写不敏感）——按精确
     * {@code "PATH"} 查在 Windows 上恒为空，pwsh 永远探不到。空段跳过，安装器可能带上的包裹
     * 引号剥掉（见 Windows PATH 常见形态）。
     */
    static List<Path> pathEntries(Map<String, String> env) {
        String raw = null;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if ("path".equalsIgnoreCase(entry.getKey())) {
                raw = entry.getValue();
                break;
            }
        }
        List<Path> entries = new ArrayList<>();
        for (String segment : (raw == null ? "" : raw).split(File.pathSeparator)) {
            String entry = segment.strip();
            if (entry.startsWith("\"") && entry.endsWith("\"") && entry.length() >= 2) {
                entry = entry.substring(1, entry.length() - 1);
            }
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry));
            }
        }
        return entries;
    }
}
