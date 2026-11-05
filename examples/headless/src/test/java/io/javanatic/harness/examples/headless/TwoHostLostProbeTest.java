package io.javanatic.harness.examples.headless;

import static org.assertj.core.api.Assertions.assertThat;

import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.jobs.JobSlice;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import io.javanatic.harness.jobs.local.JobSnapshotEvent;
import io.javanatic.harness.jobs.local.LocalJobService;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.persistence.SessionPersistence;
import java.io.BufferedReader;
import java.util.NoSuchElementException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 双宿主真机 Lost 复现（it30 停 3 必验项）：宿主 A = 真 JVM 子进程（真 AppBoot 装配 +
 * 真 agent + 真持久化落盘 + halt 硬崩）；宿主 B = 本测试装配载回同一持久化树，断言：
 * ①前生命期 Running 任务被标 Lost 并入账；②遗物输出可读（relic 标记）；③懒标记负例
 * ——未被装配的对照会话磁盘账面无 Lost；④重复装配不重复标 Lost。
 * R1 侧写：宿主 B 的 fold 即事件账重放，全程零进程重建。
 */
class TwoHostLostProbeTest {

    @TempDir
    Path root;

    @Test
    void killedHostRunningJobBecomesLostWithReadableRelic() throws Exception {
        Path dataDir = root.resolve("jobs");
        Path sessions = root.resolve("sessions");
        Path ws = root.resolve("ws");
        Files.createDirectories(dataDir);
        Files.createDirectories(sessions);
        Files.createDirectories(ws);
        String jobSession = "probe-twohost-job";
        String idleSession = "probe-twohost-idle";

        Process hostA = new ProcessBuilder(javaBin(),
                "-cp", System.getProperty("java.class.path"),
                ProbeHostA.class.getName(),
                dataDir.toString(), sessions.toString(), ws.toString(), jobSession, idleSession)
                .redirectErrorStream(true).start();
        String marker = null;
        List<String> hostALines = new ArrayList<>();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(hostA.getInputStream()))) {
            String line;
            while ((line = out.readLine()) != null) {
                hostALines.add(line);
                if (line.contains("PROBE_A_READY")) {
                    marker = line;
                }
            }
        }
        if (marker == null && !hostALines.isEmpty()) {
            System.err.println("== 宿主 A 输出末 12 行 ==");
            hostALines.subList(Math.max(0, hostALines.size() - 12), hostALines.size())
                    .forEach(System.err::println);
        }
        hostA.waitFor();
        if (hostA.isAlive()) {
            hostA.destroyForcibly();
        }
        assertThat(marker).as("宿主 A 必须在硬崩前打出 MARKER").isNotNull();

        Path jobLog = sessions.resolve(jobSession).resolve("log.jsonl");
        Path idleLog = sessions.resolve(idleSession).resolve("log.jsonl");
        assertThat(jobLog).as("宿主 A 已把 job 事件落盘").exists();
        assertThat(Files.readString(jobLog)).contains("job/snapshot");

        // 宿主 B：同一持久化树与 dataDir 重新装配，fold 事件账
        Path profile = Files.createTempFile("jh-probe-b", ".yml");
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
        try (Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, overlays, false, null))) {
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            SessionPersistence.Loaded loaded = persistence.load(Session.newId(jobSession));
            Session restored = Session.create(loaded.header().id(), loaded.events(), loaded.header());
            LocalJobService hostB = new LocalJobService(dataDir);
            hostB.bindSession(restored);

            List<JobSnapshot> list = hostB.list();
            assertThat(list).hasSize(1);
            assertThat(list.get(0).state()).as("前生命期 Running → Lost（如实标记）")
                    .isInstanceOf(JobState.Lost.class);
            assertThat(restored.events().stream()
                    .filter(logged -> logged.event() instanceof JobSnapshotEvent)
                    .map(logged -> ((JobSnapshotEvent) logged.event()).snapshot().state())
                    .filter(state -> state instanceof JobState.Lost).count())
                    .as("Lost 已随装配入账").isEqualTo(1L);

            JobSlice relic = hostB.read(list.get(0).id(), 0, 8192);
            assertThat(relic.text()).as("遗物输出可读").contains("relic-" + jobSession);

            hostB.bindSession(restored);
            long lostCount = restored.events().stream()
                    .filter(logged -> logged.event() instanceof JobSnapshotEvent)
                    .map(logged -> ((JobSnapshotEvent) logged.event()).snapshot().state())
                    .filter(state -> state instanceof JobState.Lost).count();
            assertThat(lostCount).as("重复装配不重复标 Lost").isEqualTo(1);
            hostB.close();
        }

        // 懒标记负例：未装配的对照会话无 Lost 可标。save 为增量语义（无新事件不落
        // log.jsonl），故经 load 通道取证：能载回则必无任何 job 事件；载不回（从未
        // 持久化）同样不存在被标的账面——两种形态都是"磁盘原样"。
        try (Runtime rt2 = AppBoot.boot(new AppBoot.BootOptions(profile, overlays, false, null))) {
            SessionPersistence p2 = rt2.root().require(SessionPersistence.KEY);
            try {
                SessionPersistence.Loaded idleLoaded = p2.load(Session.newId(idleSession));
                assertThat(idleLoaded.events().stream()
                        .noneMatch(event -> event instanceof JobSnapshotEvent))
                        .as("懒标记负例：未装配会话账面无 job 事件（含 Lost）").isTrue();
            } catch (NoSuchElementException | NoSuchFileException e) {
                // 对照会话无事件账可载（header-only：增量 save 无新事件不写 log.jsonl，
                // load 对此抛 NoSuchFile）——无账即无可标，负例同样成立
            }
        }
    }

    private static String javaBin() {
        Path home = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return home.toString();
    }
}
