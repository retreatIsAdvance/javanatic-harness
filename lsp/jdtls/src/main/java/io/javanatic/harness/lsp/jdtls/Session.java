package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * LSP server 进程启动与专属进程组（it29 停 2 设计 §4.1）：
 * POSIX 用会话包装器使子进程自成 session（pgid == 子 pid，同停 0 断言）；
 * macOS 用系统 perl 包装器（系统无 setsid），Linux 用 /usr/bin/setsid；
 * Windows 直接启动（Job / 进程树不在本停点范围，packet 明示平台限制）。
 * 包装器缺失时直接启动并记录披露（无专属组）。
 */
final class Session {

    private Session() {
    }

    /** 一次启动的结果：进程句柄 + 数值 pid/pgid + 专属组成立与否 + 披露注记。 */
    record Launch(Process process, long pid, OptionalLong pgid, boolean dedicatedGroup, String note) {
    }

    /** 启动 argv（cwd = projectRoot；env null = 继承）。IO 失败原样抛（Provider 分档为 LspException(IO)）。 */
    static Launch start(List<String> argv, Map<String, String> env, Path workDir) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = argv;
        String note = "no dedicated process group";
        if (os.contains("mac")) {
            Path perl = Path.of("/usr/bin/perl");
            if (Files.isExecutable(perl)) {
                command = wrap(perl.toString(), "-MPOSIX", "-e", "POSIX::setsid() and exec @ARGV", argv);
                note = "perl setsid wrapper";
            } else {
                note = "macOS without /usr/bin/perl: no dedicated process group (disclosed)";
            }
        } else if (os.contains("linux")) {
            Path setsid = Path.of("/usr/bin/setsid");
            if (Files.isExecutable(setsid)) {
                command = buildLinuxCommand(setsid, argv);
                note = "setsid wrapper";
            } else {
                note = "Linux without /usr/bin/setsid: no dedicated process group (disclosed)";
            }
        } else {
            note = "Windows: direct launch, process tree not covered (disclosed)";
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        if (env != null) {
            builder.environment().putAll(env);
        }
        Process process = builder.start();
        long pid = process.pid();
        // setsid 包装器与 ps 捕获有竞态：重试到 pgid == pid（专属组成立）或进程死亡 / 2s 上限。
        // 上限后 pgid 非空仍如实返回（dedicated=false 保守），不复验为残留误报。
        OptionalLong pgid = capturePgid(process);
        boolean dedicated = pgid.isPresent() && pgid.getAsLong() == pid;
        return new Launch(process, pid, pgid, dedicated, note);
    }

    /** 捕获 pgid：等 setsid 生效（pgid == pid）或进程死 / 2s 上限；查不到 = empty。 */
    private static OptionalLong capturePgid(Process process) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            OptionalLong pgid = readPgid(process.pid());
            if (pgid.isPresent() && pgid.getAsLong() == process.pid()) {
                return pgid;
            }
            if (!process.isAlive()) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return readPgid(process.pid());
            }
        }
        return readPgid(process.pid());
    }

    /** 构建 Linux 会话包装器的最终 argv，不启动进程。 */
    static List<String> buildLinuxCommand(Path setsid, List<String> argv) {
        List<String> command = new ArrayList<>();
        command.add(setsid.toString());
        command.add("--");
        command.addAll(argv);
        return command;
    }

    private static List<String> wrap(String wrapper, String first, String second, String third,
                                     List<String> argv) {
        List<String> command = new ArrayList<>();
        command.add(wrapper);
        command.add(first);
        command.add(second);
        command.add(third);
        command.add("--");
        command.addAll(argv);
        return command;
    }

    /** 启动时捕获数值 pgid（ps 查询）；不可查 = empty（保守：不伪称专属组成立）。 */
    static OptionalLong readPgid(long pid) {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "pgid=", "-p", String.valueOf(pid))
                .redirectErrorStream(true).start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor();
            if (ps.exitValue() != 0 || out.isEmpty()) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(out.split("\\s+")[0]));
        } catch (IOException | InterruptedException | NumberFormatException | IndexOutOfBoundsException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return OptionalLong.empty();
        }
    }

    /**
     * 关闭复验：数值 pid 与 pgid 无残留行才算消失（设计 §4.2 步 6）。
     * ProcessHandle 无该 pid 且 ps -axo pid=,ppid=,pgid= 无 pid 行；
     * pgid 行只在专属组确已成立时检查（未成立时 pgid 或为父组，查会误报测试 JVM 自身）。
     * ps 不可查 / 失败 = 保守判「未证消失」。Windows 无 ps：仅 ProcessHandle 面。
     */
    static boolean noResidue(long pid, OptionalLong pgid, boolean dedicatedGroup) {
        if (ProcessHandle.of(pid).isPresent()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return true; // 平台限制披露：Job / 进程树复验不覆盖
        }
        try {
            Process ps = new ProcessBuilder("ps", "-axo", "pid=,ppid=,pgid=")
                .redirectErrorStream(true).start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ps.waitFor();
            if (ps.exitValue() != 0) {
                return false;
            }
            for (String line : out.split("\\R")) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length < 3) {
                    continue;
                }
                long rowPid = Long.parseLong(columns[0]);
                long rowPgid = Long.parseLong(columns[2]);
                if (rowPid == pid) {
                    return false;
                }
                if (dedicatedGroup && pgid.isPresent() && rowPgid == pgid.getAsLong()) {
                    return false;
                }
            }
            return true;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
