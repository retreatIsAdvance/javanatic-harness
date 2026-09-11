package io.javanatic.harness.sandbox.local;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.sandbox.sandbox.ConfinedArgv;
import io.javanatic.harness.sandbox.sandbox.SandboxEnforcement;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.sandbox.sandbox.WritableRoots;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本机沙箱 Provider（id "sandbox-local"，dsh sandbox-local 形状）：
 * <b>平台链先行</b>——平台→候选链，仅多候选才探针仲裁。今日 darwin=[seatbelt]；
 * linux=[bwrap,landlock] 与 win32=[windows-acl]（设计见 05 §6，实现挂 it13
 * CI runner）为空链：受限 confine 一律 fail-closed，静默透传被禁止。
 */
public final class SandboxLocalPlugin implements Plugin {

    /** Seatbelt 的拒绝方言：EPERM（消费方按行内大小写不敏感匹配）。 */
    static final List<String> SEATBELT_DENIALS = List.of("Operation not permitted");

    private static final String SEPARATOR = "--";
    private static final boolean DARWIN =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final String seatbeltBinary;

    /** 数据组合路径：seatbelt 二进制按平台默认路径。 */
    public SandboxLocalPlugin() {
        this("/usr/bin/sandbox-exec");
    }

    /** @param seatbeltBinary 显式二进制路径（测试注伪探 fail-closed） */
    public SandboxLocalPlugin(String seatbeltBinary) {
        this.seatbeltBinary = Objects.requireNonNull(seatbeltBinary, "seatbeltBinary");
    }

    @Override
    public String id() {
        return "sandbox-local";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(SandboxProvider.KEY, new SeatbeltBackend());
    }

    /** darwin 唯一候选（内核强制、无 ABI 降档——FULL 是 profile 事实）。 */
    private final class SeatbeltBackend implements SandboxProvider {

        private Boolean probed;

        @Override
        public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
            Objects.requireNonNull(argv, "argv");
            Objects.requireNonNull(policy, "policy");
            if (!policy.confining()) {
                throw new IllegalArgumentException(
                    "confine accepts a confining policy; danger-full-access is the caller's explicit bypass");
            }
            if (argv.isEmpty() || argv.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("argv must be non-empty and null-free");
            }
            if (!DARWIN) {
                throw new SandboxUnavailableException(policy.mode(),
                    "no backend on this platform yet (linux: bwrap/landlock, windows: windows-acl — it13 CI)");
            }
            if (!probeUsable()) {
                throw new SandboxUnavailableException(policy.mode(),
                    "seatbelt probe failed (binary: " + seatbeltBinary + ")");
            }
            List<String> wrapped = new ArrayList<>(argv.size() + 4);
            wrapped.add(seatbeltBinary);
            wrapped.add("-p");
            wrapped.add(profile(policy));
            wrapped.add(SEPARATOR);
            wrapped.addAll(argv);
            return new ConfinedArgv(List.copyOf(wrapped), SandboxEnforcement.FULL, SEATBELT_DENIALS);
        }

        /**
         * 功能探针：read-only profile 下跑 {@code /usr/bin/true}——内核拒绝
         * profile 时 sandbox-exec 自身非零退出。首探缓存。
         */
        private boolean probeUsable() {
            if (probed == null) {
                try {
                    Process probe = new ProcessBuilder(seatbeltBinary, "-p",
                        profile(new SandboxPolicy(SandboxMode.READ_ONLY, Path.of("/"))),
                        SEPARATOR, "/usr/bin/true").redirectErrorStream(true).start();
                    probed = probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0;
                } catch (Exception spawnOrWaitFailed) {
                    probed = false;
                }
            }
            return probed;
        }
    }

    /** SBPL：全默认放行、拒写；workspace-write 再放行可写根（WritableRoots 单一来源）。 */
    private static String profile(SandboxPolicy policy) {
        StringBuilder sb = new StringBuilder("(version 1)(allow default)(deny file-write*)"
            + "(allow file-write* (literal " + sbplString("/dev/null") + "))");
        Set<Path> roots = WritableRoots.of(policy);
        if (!roots.isEmpty()) {
            sb.append("(allow file-write* ");
            for (Path root : roots) {
                sb.append("(subpath ").append(sbplString(root.toString())).append(") ");
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** SBPL 字符串字面量：反斜杠与双引号转义。 */
    private static String sbplString(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
