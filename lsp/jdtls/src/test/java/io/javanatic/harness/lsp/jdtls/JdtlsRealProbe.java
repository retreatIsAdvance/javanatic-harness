package io.javanatic.harness.lsp.jdtls;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * it29 停2-S5 真实 JDT LS 证据跑（本机代跑）：把生产 {@link JdtlsProvider} 接到钉定
 * 1.63.0 / 202610191322 真机（停 0 解包工件 + 停 0 夹具工作区复制到自有运行目录），
 * ≥3 启停逐轮取证 + 四态真机区分。类名不含 *Test，surefire 默认包含规则不匹配，
 * 模块测试计数不受影响；以 {@code java -cp} 类路径模式运行（忽略 module-info）。
 * 退出码 0 = 全部断言通过；1 = 任一断言失败（证据如实打印，不凑绿）。
 */
public final class JdtlsRealProbe {

    private static final String LAUNCHER_MARK = "org.eclipse.equinox.launcher";

    private static final Duration COLD_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration COLD_WINDOW = Duration.ofSeconds(240);
    private static final Duration WARM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WARM_WINDOW = Duration.ofSeconds(90);
    private static final Duration TIGHT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TIGHT_WINDOW = Duration.ofSeconds(30);
    /** 200ms 远小于本机实测 handshake 下限（≈1.0–1.5s，含进程 spawn + JVM boot），
     *  使「initialize 未完成」前提确定成立：1s 预算曾在真实跑中让前提失效——
     *  warm handshake 在期限内完成 → 观察正常路径走到窗口段到期 → TimedOut(PT1S)（如实记入 packet）。 */
    private static final Duration NOT_READY_BUDGET = Duration.ofMillis(200);

    private final Path runRoot;
    private final Path stop0Root;
    private final Path javaHome;
    private final int rounds;
    private final List<String> failures = new ArrayList<>();

    private Path projectDir;
    private Path indexDir;
    private Path homeDir;

    private JdtlsRealProbe(Path runRoot, Path stop0Root, Path javaHome, int rounds) {
        this.runRoot = runRoot;
        this.stop0Root = stop0Root;
        this.javaHome = javaHome;
        this.rounds = rounds;
    }

    public static void main(String[] args) throws Exception {
        Path runRoot = null;
        Path stop0Root = null;
        Path javaHome = null;
        int rounds = 3;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run-root" -> runRoot = Path.of(args[++i]);
                case "--stop0-root" -> stop0Root = Path.of(args[++i]);
                case "--java-home" -> javaHome = Path.of(args[++i]);
                case "--rounds" -> rounds = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (runRoot == null || stop0Root == null || javaHome == null) {
            throw new IllegalArgumentException("--run-root, --stop0-root and --java-home are required");
        }
        new JdtlsRealProbe(runRoot, stop0Root, javaHome, rounds).run();
    }

    private void run() throws Exception {
        out("== stop2-S5 real JDT LS evidence run start " + Instant.now());
        prepare();
        lifecycleRounds();
        statePublishedTypeError();
        statePublishedWarningOnly();
        stateQuietWindow();
        stateStale();
        stateNotReady();
        stateTimedOut();
        out("== verdict: " + (failures.isEmpty() ? "ALL ASSERTIONS PASSED" : "FAILED " + failures));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** 运行目录：复制停 0 夹具（只取 pom.xml 与 src 树，排除停 0 运行产物）+ 暖 .m2 + 自建 settings。 */
    private void prepare() throws IOException {
        deleteRecursively(runRoot);
        projectDir = Files.createDirectories(runRoot.resolve("project"));
        indexDir = Files.createDirectories(runRoot.resolve("index"));
        homeDir = Files.createDirectories(runRoot.resolve("home"));
        Files.createDirectories(homeDir.resolve("tmp"));
        Path sourceProject = stop0Root.resolve("project");
        copyFixtureTree(sourceProject, projectDir);
        Path warmRepo = stop0Root.resolve("home").resolve(".m2").resolve("repository");
        if (Files.isDirectory(warmRepo)) {
            copyTree(warmRepo, homeDir.resolve(".m2").resolve("repository"));
            out("prepare: warm .m2 copied from stop0 home (" + warmRepo + ")");
        } else {
            out("prepare: no warm .m2 found; LS may download dependencies (network not excluded)");
        }
        String settings = settingsXml(homeDir.resolve(".m2").resolve("repository"));
        Path userSettings = homeDir.resolve("maven-user-settings.xml");
        Path globalSettings = homeDir.resolve("maven-global-settings.xml");
        Files.writeString(userSettings, settings, StandardCharsets.UTF_8);
        Files.writeString(globalSettings, settings, StandardCharsets.UTF_8);
        snapshot("fixture-baseline-App.java", appPath(), appText());
        out("prepare: project copied (pom.xml + src only), index/home fresh under " + runRoot);
    }

    private static String settingsXml(Path repository) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"\n"
            + "          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "          xsi:schemaLocation=\"http://maven.apache.org/SETTINGS/1.0.0"
            + " https://maven.apache.org/xsd/settings-1.0.0.xsd\">\n"
            + "  <localRepository>" + repository.toAbsolutePath() + "</localRepository>\n"
            + "  <interactiveMode>false</interactiveMode>\n"
            + "  <offline>false</offline>\n"
            + "</settings>\n";
    }

    /** ≥3 启停逐轮：同一关闭实现真机收尾证据（EXIT / EOF / pending / 线程 / ps 复验）。 */
    private void lifecycleRounds() throws Exception {
        for (int round = 1; round <= rounds; round++) {
            int current = round;
            boolean cold = round == 1;
            out("== round " + current + " start " + Instant.now());
            writeApp(appText());
            withProvider(provider -> {
                long pid = serverPid();
                OptionalLong pgid = Session.readPgid(pid);
                out("round " + current + ": pid=" + pid + " pgid=" + pgid);
                LspResult<LspDiagnostic> result =
                    pull(provider, appPath(), cold ? COLD_TIMEOUT : WARM_TIMEOUT, cold ? COLD_WINDOW : WARM_WINDOW);
                out("round " + current + " pull: " + describe(result));
                if (!(result instanceof LspResult.Observed<LspDiagnostic> observed)
                    || observed.observation().evidence().origin() != LspEvidence.Origin.PUBLISHED_DIAGNOSTICS
                    || observed.items().isEmpty()) {
                    fail("round " + current + ": expected non-empty PUBLISHED_DIAGNOSTICS");
                } else if (cold && !hasErrorCode(observed, "16777233")) {
                    fail("round 1: expected real type-error code 16777233 in batch");
                }
            });
            out("== round " + current + " end " + Instant.now());
        }
    }

    /** 四态 1：类型错误夹具（int→String）→ 真实 PUBLISHED_DIAGNOSTICS（code 16777233，hash 锚齐全）。 */
    private void statePublishedTypeError() throws Exception {
        out("== state A (type error) start " + Instant.now());
        writeApp(appText());
        withProvider(provider -> {
            LspResult<LspDiagnostic> result = pull(provider, appPath(), WARM_TIMEOUT, WARM_WINDOW);
            out("state A pull: " + describe(result));
            if (!(result instanceof LspResult.Observed<LspDiagnostic> observed)
                || observed.observation().evidence().origin() != LspEvidence.Origin.PUBLISHED_DIAGNOSTICS
                || !hasErrorCode(observed, "16777233")) {
                fail("state A: expected PUBLISHED_DIAGNOSTICS with code 16777233");
            }
        });
        out("== state A end " + Instant.now());
    }

    /** 四态 1b：warning-only 用例（停 0 夹具原样，仅未使用导入警告）作为非空诊断证据。 */
    private void statePublishedWarningOnly() throws Exception {
        out("== state A2 (warning only) start " + Instant.now());
        writeApp(cleanAppText());
        withProvider(provider -> {
            LspResult<LspDiagnostic> result = pull(provider, appPath(), WARM_TIMEOUT, WARM_WINDOW);
            out("state A2 pull: " + describe(result));
            if (!(result instanceof LspResult.Observed<LspDiagnostic> observed)
                || observed.observation().evidence().origin() != LspEvidence.Origin.PUBLISHED_DIAGNOSTICS
                || observed.items().isEmpty()) {
                fail("state A2: expected non-empty PUBLISHED_DIAGNOSTICS for warning-only fixture");
            }
        });
        out("== state A2 end " + Instant.now());
    }

    /** 四态 2：独立清洁用例（新增 Clean.java，明示与停 0 夹具的差异）→ 账面只写「拉取时点无诊断」；
     *  若 LS 实际发布空数组则如实记 PUBLISHED_DIAGNOSTICS，不改造证据凑 QUIET_WINDOW。 */
    private void stateQuietWindow() throws Exception {
        out("== state B (quiet window) start " + Instant.now());
        writeApp(cleanAppText());
        writeClean();
        withProvider(provider -> {
            LspResult<LspDiagnostic> result = pull(provider, cleanPath(), WARM_TIMEOUT, WARM_WINDOW);
            out("state B pull: " + describe(result));
            if (result instanceof LspResult.Observed<LspDiagnostic> observed
                && observed.observation().evidence().origin() == LspEvidence.Origin.QUIET_WINDOW) {
                String anchor = observed.observation().before().toString();
                out("state B 账面：拉取成功（" + anchor + "）、项目代稳定（前后锚相同）、完整等待窗"
                    + "（elapsed " + observed.observation().elapsedWait() + " ≥ required），拉取时点无诊断。");
            } else if (result instanceof LspResult.Observed<LspDiagnostic> observed) {
                out("state B: LS 实际发布诊断批次（空数组或非空），按 PUBLISHED_DIAGNOSTICS 如实记录，"
                    + "不改造证据凑 QUIET_WINDOW：items=" + observed.items().size());
            } else {
                fail("state B: unexpected result kind: " + result.getClass().getSimpleName());
            }
        });
        out("== state B end " + Instant.now());
    }

    /** 四态 3：等待窗内外部改写 App.java → 前后锚不同 → Stale。 */
    private void stateStale() throws Exception {
        out("== state C (stale) start " + Instant.now());
        writeApp(appText());
        withProvider(provider -> {
            AtomicReference<LspResult<LspDiagnostic>> box = new AtomicReference<>();
            Thread puller = new Thread(() -> box.set(pull(provider, appPath(), WARM_TIMEOUT, Duration.ofSeconds(45))),
                "probe-stale-puller");
            puller.start();
            sleepSeconds(3);
            Files.writeString(appPath(), appText() + "\n// probe stale rewrite\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
            puller.join();
            LspResult<LspDiagnostic> result = box.get();
            out("state C pull: " + describe(result));
            if (!(result instanceof LspResult.Stale<LspDiagnostic> stale) || stale.before().equals(stale.after())) {
                fail("state C: expected Stale with differing anchors");
            }
        });
        out("== state C end " + Instant.now());
    }

    /** 四态 4：构造后立即 pull（200ms 预算 << handshake 下限，initialize 必然未完成）→ INITIALIZING；
     *  warm 后 close；close 后 pull → CLOSED（closed 判定不依赖预算）。 */
    private void stateNotReady() throws Exception {
        out("== state D (not ready) start " + Instant.now());
        writeApp(appText());
        JdtlsProvider provider = newProvider();
        try {
            LspResult<LspDiagnostic> early = pull(provider, appPath(), NOT_READY_BUDGET, NOT_READY_BUDGET);
            out("state D immediate pull: " + describe(early));
            if (!(early instanceof LspResult.NotReady<LspDiagnostic> notReady)
                || notReady.reason() != LspResult.NotReadyReason.INITIALIZING) {
                fail("state D: expected NotReady(INITIALIZING)");
            }
            LspResult<LspDiagnostic> warm = pull(provider, appPath(), WARM_TIMEOUT, Duration.ofSeconds(60));
            out("state D warm pull (handshake settles): " + describe(warm));
        } finally {
            provider.close();
            out("state D close: OK");
        }
        LspResult<LspDiagnostic> afterClose = pull(provider, appPath(), NOT_READY_BUDGET, NOT_READY_BUDGET);
        out("state D after-close pull: " + describe(afterClose));
        if (!(afterClose instanceof LspResult.NotReady<LspDiagnostic> notReady)
            || notReady.reason() != LspResult.NotReadyReason.CLOSED) {
            fail("state D: expected NotReady(CLOSED)");
        }
        out("== state D end " + Instant.now());
    }

    /** 四态 5：紧 deadline（与窗口等长，窗口 ≤ 期限契约允许的确定性下界）→ TimedOut；
     *  设计「略大于窗口」在 warm 同步下余量耗不尽会回 Observed，取等长保证终检必然过期（诚实记录见 packet）。 */
    private void stateTimedOut() throws Exception {
        out("== state E (timed out) start " + Instant.now());
        writeApp(appText());
        withProvider(provider -> {
            LspResult<LspDiagnostic> warm = pull(provider, appPath(), WARM_TIMEOUT, Duration.ofSeconds(60));
            out("state E warm pull: " + describe(warm));
            LspResult<LspDiagnostic> tight = pull(provider, appPath(), TIGHT_TIMEOUT, TIGHT_WINDOW);
            out("state E tight pull: " + describe(tight));
            if (!(tight instanceof LspResult.TimedOut<LspDiagnostic>)) {
                fail("state E: expected TimedOut");
            }
        });
        out("== state E end " + Instant.now());
    }

    private JdtlsProvider newProvider() {
        JdtlsLaunch launch = new JdtlsLaunch(javaHome, stop0Root.resolve("server-1.63.0"),
            stop0Root.resolve("config_mac_arm"), projectDir, indexDir, homeDir,
            Optional.of(homeDir.resolve("maven-user-settings.xml")),
            Optional.of(homeDir.resolve("maven-global-settings.xml")));
        return new JdtlsProvider(launch);
    }

    private void withProvider(ProviderStep step) throws Exception {
        JdtlsProvider provider = newProvider();
        try {
            step.run(provider);
        } finally {
            provider.close();
            out("close: OK (exit/eof/pending/ioThreads 见上方 INFO 行；close 抛异常即失败)");
            out(postCloseEvidence());
        }
    }

    /** 关闭后独立复验：jh-lsp-* 线程归零；children 有界扫描 10s 内无 launcher 子进程 = 无残留。
     *  pid 未找到是正面证据（进程已消失），不得表述为「捕获失败」。 */
    private String postCloseEvidence() {
        long threads = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith("jh-lsp-")).count();
        long pid = serverPid();
        String handle = pid >= 0
            ? "pid=" + pid + " processHandleAlive="
                + ProcessHandle.of(pid).map(ProcessHandle::isAlive).map(String::valueOf).orElse("false")
            : "no live launcher child (residual-free)";
        return "post-close: jhThreads=" + threads + " " + handle;
    }

    private long serverPid() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (ProcessHandle child : ProcessHandle.current().children().toList()) {
                String command = child.info().commandLine().orElse("");
                if (command.contains(LAUNCHER_MARK)) {
                    return child.pid();
                }
            }
            sleepMillis(100);
        }
        return -1;
    }

    private static LspResult<LspDiagnostic> pull(JdtlsProvider provider, Path document, Duration timeout,
                                                 Duration window) {
        return provider.diagnostics(new LspQuery(document.toUri(), timeout, window), AbortSignal.never());
    }

    private static String describe(LspResult<LspDiagnostic> result) {
        if (result instanceof LspResult.Observed<LspDiagnostic> observed) {
            return "Observed origin=" + observed.observation().evidence().origin()
                + " seq=" + observed.observation().evidence().receiveSequence()
                + " serverVersion=" + observed.observation().evidence().serverVersion()
                + " items=" + observed.items().size() + " codes="
                + observed.items().stream().map(LspDiagnostic::code).toList();
        }
        if (result instanceof LspResult.NotReady<LspDiagnostic> notReady) {
            return "NotReady(" + notReady.reason() + ")";
        }
        if (result instanceof LspResult.Stale<LspDiagnostic> stale) {
            return "Stale before=" + stale.before() + " after=" + stale.after();
        }
        if (result instanceof LspResult.TimedOut<LspDiagnostic> timedOut) {
            return "TimedOut(" + timedOut.timeout() + ")";
        }
        return result.getClass().getName();
    }

    private static boolean hasErrorCode(LspResult.Observed<LspDiagnostic> observed, String code) {
        return observed.items().stream()
            .anyMatch(diagnostic -> diagnostic.severity() == LspDiagnostic.Severity.ERROR
                && diagnostic.code().map(code::equals).orElse(false));
    }

    private Path appPath() {
        return projectDir.resolve("src").resolve("main").resolve("java").resolve("probe").resolve("App.java");
    }

    private Path cleanPath() {
        return projectDir.resolve("src").resolve("main").resolve("java").resolve("probe").resolve("Clean.java");
    }

    private static String appText() {
        return "package probe;\n"
            + "import module java.base;\n"
            + "public final class App {\n"
            + "    public static int run() {\n"
            + "        String label = 42;\n"
            + "        return Calc.twice(label.length());\n"
            + "    }\n"
            + "}\n";
    }

    private static String cleanAppText() {
        return "package probe;\n"
            + "import module java.base;\n"
            + "public final class App {\n"
            + "    public static int run() {\n"
            + "        String label = \"ok\";\n"
            + "        return Calc.twice(label.length());\n"
            + "    }\n"
            + "}\n";
    }

    private void writeApp(String text) throws IOException {
        Files.writeString(appPath(), text, StandardCharsets.UTF_8);
        snapshot("state-App.java", appPath(), text);
    }

    private void writeClean() throws IOException {
        Files.writeString(cleanPath(), "package probe;\n\npublic final class Clean {\n}\n", StandardCharsets.UTF_8);
        snapshot("state-Clean.java", cleanPath(), "package probe;\n\npublic final class Clean {\n}\n");
    }

    /** 每态源文件快照落盘到 run-root/evidence，与停 0 夹具原样可 diff。 */
    private void snapshot(String name, Path file, String text) throws IOException {
        Path evidence = Files.createDirectories(runRoot.resolve("evidence"));
        Files.writeString(evidence.resolve(name), text, StandardCharsets.UTF_8);
        out("snapshot " + name + " <- " + file);
    }

    private static void copyFixtureTree(Path source, Path target) throws IOException {
        Path pom = source.resolve("pom.xml");
        Files.copy(pom, target.resolve("pom.xml"));
        copyTree(source.resolve("src"), target.resolve("src"));
        out("prepare: copied pom.xml + src from " + source + " (excluded stop0 run artifacts: target/.settings/.classpath/.project)");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void fail(String message) {
        failures.add(message);
        out("FAIL: " + message);
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void out(String message) {
        System.out.println(message);
        System.out.flush();
    }

    @FunctionalInterface
    private interface ProviderStep {
        void run(JdtlsProvider provider) throws Exception;
    }
}
