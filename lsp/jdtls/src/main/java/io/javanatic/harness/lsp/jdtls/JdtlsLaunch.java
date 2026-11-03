package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JDT LS 启动档案（边界 B26 输入面）：只校验路径存在与 javaHome/bin/java 可执行，
 * 不校验「LS 是否可用」。argv / env 形状同停 0 探针（stop0-probe.py 与 effective-settings.txt）。
 */
public record JdtlsLaunch(Path javaHome, Path serverDir, Path configurationDir, Path projectRoot,
                          Path indexDir, Path homeDir, Optional<Path> mavenUserSettings,
                          Optional<Path> mavenGlobalSettings) {

    public JdtlsLaunch {
        Objects.requireNonNull(javaHome, "javaHome");
        String javaBinaryName = javaBinaryName(isWindows());
        Path javaBinary = javaHome.resolve("bin").resolve(javaBinaryName);
        if (!Files.isRegularFile(javaBinary) || !Files.isExecutable(javaBinary)) {
            throw new IllegalArgumentException("javaHome lacks an executable bin/" + javaBinaryName + ": " + javaHome);
        }
        requireDir(serverDir, "serverDir");
        requireDir(configurationDir, "configurationDir");
        requireDir(projectRoot, "projectRoot");
        requireDir(indexDir, "indexDir");
        requireDir(homeDir, "homeDir");
        Objects.requireNonNull(mavenUserSettings, "mavenUserSettings");
        mavenUserSettings.ifPresent(path -> requireFile(path, "mavenUserSettings"));
        Objects.requireNonNull(mavenGlobalSettings, "mavenGlobalSettings");
        mavenGlobalSettings.ifPresent(path -> requireFile(path, "mavenGlobalSettings"));
        if (launcherJar(serverDir).isEmpty()) {
            throw new IllegalArgumentException("serverDir must contain a launcher jar"
                + " (plugins/org.eclipse.equinox.launcher_*.jar): " + serverDir);
        }
    }

    /** 启动 argv（同停 0 探针形状；-D 系统属性一律在 -jar 之前）。 */
    List<String> argv() {
        List<String> args = new ArrayList<>();
        args.add(javaHome.resolve("bin").resolve(javaBinaryName(isWindows())).toString());
        args.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        args.add("-Dosgi.bundles.defaultStartLevel=4");
        args.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        args.add("-Xmx1G");
        args.add("-Duser.home=" + homeDir);
        args.add("-Djava.io.tmpdir=" + homeDir.resolve("tmp"));
        args.add("-Dmaven.repo.local=" + homeDir.resolve(".m2/repository"));
        mavenUserSettings.ifPresent(path -> args.add("-Dmaven.user.settings=" + path));
        mavenGlobalSettings.ifPresent(path -> args.add("-Dmaven.global.settings=" + path));
        args.add("--add-modules=ALL-SYSTEM");
        args.add("--add-opens");
        args.add("java.base/java.util=ALL-UNNAMED");
        args.add("--add-opens");
        args.add("java.base/java.lang=ALL-UNNAMED");
        args.add("-jar");
        args.add(launcherJar(serverDir).orElseThrow().toString());
        args.add("-configuration");
        args.add(configurationDir.toString());
        args.add("-data");
        args.add(indexDir.toString());
        return List.copyOf(args);
    }

    /** 显式环境（不整体继承父环境）：JAVA_HOME / PATH（javaHome 首位）/ HOME / TMPDIR。 */
    Map<String, String> env() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", javaHome.toString());
        String path = javaHome.resolve("bin") + System.getProperty("path.separator")
            + System.getenv().getOrDefault("PATH", "");
        environment.put("PATH", path);
        environment.put("HOME", homeDir.toString());
        environment.put("TMPDIR", homeDir.resolve("tmp").toString());
        return Map.copyOf(environment);
    }

    /** 平台感知 java 可执行名（纯函数：分支选择可单测、可突变）；Windows JDK 为 java.exe。 */
    static String javaBinaryName(boolean windows) {
        return windows ? "java.exe" : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Optional<Path> launcherJar(Path serverDir) {
        Path plugins = serverDir.resolve("plugins");
        if (!Files.isDirectory(plugins)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(plugins)) {
            return entries
                .filter(path -> path.getFileName().toString()
                    .matches("org\\.eclipse\\.equinox\\.launcher_.*\\.jar"))
                .sorted()
                .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void requireDir(Path path, String name) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(name + " must exist and be a directory: " + path);
        }
    }

    private static void requireFile(Path path, String name) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(name + " must exist: " + path);
        }
    }
}
