package io.javanatic.harness.lsp.jdtls;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * it29 停3-S4 真实 JDT LS 三能力探针（本机代跑）：把生产 {@link JdtlsProvider} 接到钉定
 * 1.63.0 / 202610191322 真机（停 0 解包工件 + 停 0 夹具工作区复制到自有运行目录），
 * ≥3 启停逐轮取证：诊断（PUBLISHED 真实批次）→ 定义（textDocument/definition 真实响应）
 * → 引用（textDocument/references 含声明）。类名不含 *Test，surefire 默认包含规则不匹配，
 * 模块测试计数不受影响；以 {@code java -cp} 类路径模式运行（忽略 module-info）。
 * 退出码 0 = 全部断言通过；1 = 任一断言失败（证据如实打印，不凑绿）。
 */
public final class JdtlsThreeCapabilityProbe {

    private static final String LAUNCHER_MARK = "org.eclipse.equinox.launcher";

    private static final Duration COLD_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration COLD_WINDOW = Duration.ofSeconds(240);
    private static final Duration WARM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WARM_WINDOW = Duration.ofSeconds(90);

    /** 夹具 App.java 中 `Calc` 的 0 基 UTF-16 位置：`        return Calc.twice(...)`
     *  → 8 空格 + "return "（7 字符）→ line 5（1 基第 6 行）、column 15。 */
    private static final int CALC_LINE = 5;
    private static final int CALC_COLUMN = 15;

    private final Path runRoot;
    private final Path stop0Root;
    private final Path javaHome;
    private final int rounds;
    private final List<String> failures = new ArrayList<>();

    private Path projectDir;
    private Path indexDir;
    private Path homeDir;

    private JdtlsThreeCapabilityProbe(Path runRoot, Path stop0Root, Path javaHome, int rounds) {
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
        new JdtlsThreeCapabilityProbe(runRoot, stop0Root, javaHome, rounds).run();
    }

    private void run() throws Exception {
        out("== stop3-S4 real three-capability evidence run start " + Instant.now());
        prepare();
        capabilityRounds();
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

    /** ≥3 启停逐轮：每轮三能力（诊断 PUBLISHED → 定义 REQUEST_RESPONSE → 引用 REQUEST_RESPONSE），
     *  同一关闭实现真机收尾证据（EXIT / EOF / pending / 线程 / ps 复验）。 */
    private void capabilityRounds() throws Exception {
        for (int round = 1; round <= rounds; round++) {
            int current = round;
            boolean cold = round == 1;
            out("== round " + current + " start " + Instant.now());
            writeApp(current, appText());
            withProvider(provider -> {
                long pid = serverPid();
                OptionalLong pgid = Session.readPgid(pid);
                out("round " + current + ": pid=" + pid + " pgid=" + pgid);
                Duration timeout = cold ? COLD_TIMEOUT : WARM_TIMEOUT;
                Duration window = cold ? COLD_WINDOW : WARM_WINDOW;
                LspResult<LspDiagnostic> diagnostics = pullDiagnostics(provider, timeout, window);
                out("round " + current + " diagnostics: " + describeDiagnostics(diagnostics));
                if (!(diagnostics instanceof LspResult.Observed<LspDiagnostic> observed)
                    || observed.observation().evidence().origin() != LspEvidence.Origin.PUBLISHED_DIAGNOSTICS
                    || observed.items().isEmpty()) {
                    fail("round " + current + ": expected non-empty PUBLISHED_DIAGNOSTICS");
                }
                LspPositionQuery calc = position(appPath(), CALC_LINE, CALC_COLUMN,
                    WARM_TIMEOUT, WARM_WINDOW);
                LspResult<LspLocation> definition = provider.definition(calc, AbortSignal.never());
                out("round " + current + " definition: " + describeLocations(definition));
                if (!(definition instanceof LspResult.Observed<LspLocation> def)
                    || def.observation().evidence().origin() != LspEvidence.Origin.REQUEST_RESPONSE
                    || def.items().isEmpty()
                    || def.items().stream().noneMatch(item -> item.uri().getPath().endsWith("/probe/Calc.java"))) {
                    fail("round " + current + ": expected non-empty REQUEST_RESPONSE definition in Calc.java");
                }
                LspResult<LspLocation> references = provider.references(calc, AbortSignal.never());
                out("round " + current + " references: " + describeLocations(references));
                if (!(references instanceof LspResult.Observed<LspLocation> refs)
                    || refs.observation().evidence().origin() != LspEvidence.Origin.REQUEST_RESPONSE
                    || refs.items().size() < 2
                    || refs.items().stream().noneMatch(item -> item.uri().getPath().endsWith("/probe/Calc.java"))
                    || refs.items().stream().noneMatch(item -> item.uri().getPath().endsWith("/probe/App.java"))) {
                    fail("round " + current + ": expected ≥2 REQUEST_RESPONSE references (declaration + call site)");
                }
            });
            out("== round " + current + " end " + Instant.now());
        }
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

    private LspResult<LspDiagnostic> pullDiagnostics(JdtlsProvider provider, Duration timeout,
                                                     Duration window) {
        return provider.diagnostics(new LspQuery(appPath().toUri(), timeout, window),
            AbortSignal.never());
    }

    private LspPositionQuery position(Path document, int line, int column, Duration timeout,
                                      Duration window) {
        return new LspPositionQuery(new LspQuery(document.toUri(), timeout, window),
            new LspPosition(line, column));
    }

    private static String describeDiagnostics(LspResult<LspDiagnostic> result) {
        if (result instanceof LspResult.Observed<LspDiagnostic> observed) {
            return "Observed origin=" + observed.observation().evidence().origin()
                + " seq=" + observed.observation().evidence().receiveSequence()
                + " serverVersion=" + observed.observation().evidence().serverVersion()
                + " items=" + observed.items().size() + " codes="
                + observed.items().stream().map(LspDiagnostic::code).toList();
        }
        return describeKind(result);
    }

    private static String describeLocations(LspResult<LspLocation> result) {
        if (result instanceof LspResult.Observed<LspLocation> observed) {
            return "Observed origin=" + observed.observation().evidence().origin()
                + " seq=" + observed.observation().evidence().receiveSequence()
                + " serverVersion=" + observed.observation().evidence().serverVersion()
                + " items=" + observed.items().size() + " ["
                + observed.items().stream()
                    .map(item -> item.uri() + "@" + item.range().start().line() + ":"
                        + item.range().start().character())
                    .reduce((a, b) -> a + "; " + b).orElse("")
                + "]";
        }
        return describeKind(result);
    }

    private static String describeKind(LspResult<?> result) {
        if (result instanceof LspResult.NotReady<?> notReady) {
            return "NotReady(" + notReady.reason() + ")";
        }
        if (result instanceof LspResult.Stale<?> stale) {
            return "Stale before=" + stale.before() + " after=" + stale.after();
        }
        if (result instanceof LspResult.TimedOut<?> timedOut) {
            return "TimedOut(" + timedOut.timeout() + ")";
        }
        return result.getClass().getName();
    }

    private Path appPath() {
        return projectDir.resolve("src").resolve("main").resolve("java").resolve("probe").resolve("App.java");
    }

    private static String appText() {
        return "package probe;\n"
            + "import module java.base;\n"
            + "public final class App {\n"
            + "    public static int run() {\n"
            + "        String label = \"ok\";\n"
            + "        return Calc.twice(label.length());\n"
            + "    }\n"
            + "}\n";
    }

    private void writeApp(int round, String text) throws IOException {
        Files.writeString(appPath(), text, StandardCharsets.UTF_8);
        snapshot("round-" + round + "-App.java", appPath(), text);
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
