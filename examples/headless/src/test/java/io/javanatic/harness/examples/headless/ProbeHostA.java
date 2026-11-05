package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.jobs.JobRequest;
import io.javanatic.harness.jobs.JobService;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.persistence.SessionPersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 双宿主真机探针·宿主 A（it30 停 3 必验项）：真实 AppBoot 装配（base 含 jobs 两行）
 * + replay provider（免 key）+ 真 agent（触发 agent/created → jobs 绑定会话）。
 * 起长任务（首行打遗物标记）→ save 落账 → 打 MARKER → {@code Runtime.halt(0)}
 * 硬崩（无 close、无取消收敛——宿主强杀的最小等价）。宿主 B 断言见
 * {@code TwoHostLostProbeTest}。
 */
public final class ProbeHostA {

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
                "echo relic-" + jobSession + (windows() ? "; Start-Sleep -Seconds 300" : "; sleep 300"),
                ws, Duration.ofSeconds(300), 8192));
        Thread.sleep(800);
        persistence.save(session);
        System.out.println("PROBE_A_READY");
        System.out.flush();
        // 同名冲突豁免：kernel.scope.Runtime 遮蔽 java.lang.Runtime，硬崩必须用后者
        java.lang.Runtime.getRuntime().halt(0);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private ProbeHostA() {
    }
}
