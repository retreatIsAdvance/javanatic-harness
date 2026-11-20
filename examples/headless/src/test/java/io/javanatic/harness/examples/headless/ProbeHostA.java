package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.jobs.JobRequest;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.jobs.JobState;
import io.javanatic.harness.jobs.local.JobSnapshotEvent;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.persistence.SessionPersistence;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 双宿主真机探针·宿主 A（it30 停 3 必验项）：真实 AppBoot 装配（base 含 jobs 两行）
 * + replay provider（免 key）+ 真 agent（触发 agent/created → jobs 绑定会话）。
 * 起长任务（首行打遗物标记）→ 等 Running 快照入账（条件等待，不赌固定时延）→ save 落账
 * → fsync 并回读 evidence → 打 MARKER → {@code Runtime.halt(0)}
 * 硬崩（无 close、无取消收敛——宿主强杀的最小等价）。宿主 B 断言见
 * {@code TwoHostLostProbeTest}。
 */
public final class ProbeHostA {

    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 25;

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path sessions = Path.of(args[1]);
        Path ws = Path.of(args[2]);
        String jobSession = args[3];
        String idleSession = args[4];
        Path profile = Files.createTempFile("jh-probe-profile", ".yml");
        Files.writeString(profile, "name: jh-probe\nbundles: [base]\n");
        List<ConfigRowSpec> overlays = List.of(
                new ConfigRowSpec.Replace("fs-local", Map.of("root", ws.toString()), null),
                new ConfigRowSpec.Replace("shell-tool",
                        Map.of("workspace", ws.toString(), "timeoutSeconds", 30), null),
                new ConfigRowSpec.Replace("sandbox-policy",
                        Map.of("mode", "workspace-write", "workspace", ws.toString()), null),
                new ConfigRowSpec.Replace("agent-loop", Map.of("cwd", ws.toString()), null),
                new ConfigRowSpec.Replace("skill-local", Map.of("cwd", ws.toString()), null),
                new ConfigRowSpec.Replace("persistence-jsonl", Map.of("root", sessions.toString()), null),
                new ConfigRowSpec.Replace("jobs-local", Map.of("dataDir", dataDir.toString()), null));
        Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, overlays, false, null));
        AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
        SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

        // 无任务的对照会话：宿主 B 不装配它——懒标记负例（磁盘账面必须原样）
        AgentHandle idle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId(idleSession), new AgentOptions("replay", "m")));
        persistence.save(idle.agent().session());

        // 任务会话：真 agent → jobs 绑定 → 长任务（首行遗物标记）→ Running 事件入账落盘
        AgentHandle handle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId(jobSession), new AgentOptions("replay", "m")));
        Session session = handle.agent().session();
        JobService jobs = rt.root().require(JobService.KEY);
        jobs.start(new JobRequest(
                "echo relic-" + jobSession + (windows() ? "; Start-Sleep -Seconds 60" : "; sleep 60"),
                ws, Duration.ofSeconds(120), 8192));
        awaitRunningSnapshot(session);
        persistence.save(session);
        fsyncAndVerify(sessions.resolve(jobSession).resolve("log.jsonl"));
        System.out.println("PROBE_A_READY");
        System.out.flush();
        // 同名冲突豁免：kernel.scope.Runtime 遮蔽 java.lang.Runtime，硬崩必须用后者
        java.lang.Runtime.getRuntime().halt(0);
    }

    /**
     * 条件等待：job 侧 Running 快照必须已 append 进会话账（save 才有东西可写）。
     * 慢 runner 上固定 sleep 会让 save 写入零条、日志文件根本不存在——这正是
     * TwoHostLostProbe 两次 CI 红的根因；超时则大声失败并带上诊断，不静默继续。
     */
    private static void awaitRunningSnapshot(Session session) {
        long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            boolean running = session.events().stream()
                    .anyMatch(logged -> logged.event() instanceof JobSnapshotEvent event
                            && event.snapshot().state() instanceof JobState.Running);
            if (running) {
                return;
            }
            sleepQuietly();
        }
        failProbe("timed out waiting for Running job snapshot after " + SNAPSHOT_TIMEOUT);
    }

    /** halt 前可观察证据：fsync 落盘并回读 job/snapshot，字节数与内容双证。 */
    private static void fsyncAndVerify(Path jobLog) throws IOException {
        long bytes;
        try (FileChannel channel = FileChannel.open(jobLog, StandardOpenOption.WRITE)) {
            channel.force(true);
            bytes = channel.size();
        }
        if (bytes == 0 || !Files.readString(jobLog).contains("job/snapshot")) {
            failProbe("job log missing job/snapshot after save: " + jobLog);
        }
        System.out.println("PROBE_A_FSYNCED " + jobLog + " bytes=" + bytes);
        System.out.flush();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failProbe("interrupted while waiting for Running snapshot");
        }
    }

    private static void failProbe(String reason) {
        System.out.println("PROBE_A_FAILED: " + reason);
        System.out.flush();
        java.lang.Runtime.getRuntime().halt(3);
        throw new IllegalStateException("unreachable");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private ProbeHostA() {
    }
}
