package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JdtlsLaunch 启动档案校验（纯值面，不起进程）：
 * 路径存在性、javaHome/bin/java 可执行、launcher jar 存在、argv/env 形状（同停 0 探针）。
 */
class JdtlsLaunchTest {

    private static final String LAUNCHER_NAME = "org.eclipse.equinox.launcher_1.8.0.jar";

    @TempDir
    Path temp;

    /** 全合法档案：javaHome 用真实 JDK（bin/java 天然可执行），其余目录与 launcher 在 temp 现造。 */
    private JdtlsLaunch validLaunch() throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path serverDir = dir("server");
        Files.createDirectories(serverDir.resolve("plugins"));
        Files.writeString(serverDir.resolve("plugins").resolve(LAUNCHER_NAME), "fake");
        return new JdtlsLaunch(javaHome, serverDir, dir("configuration"), dir("project"),
            dir("index"), dir("home"), Optional.empty(), Optional.empty());
    }

    private Path dir(String name) throws IOException {
        return Files.createDirectories(temp.resolve(name));
    }

    private Path file(String name) throws IOException {
        Path path = temp.resolve(name);
        Files.writeString(path, "content");
        return path;
    }

    @Test
    void rejectsMissingJavaHome() throws IOException {
        assertThatThrownBy(() -> new JdtlsLaunch(temp.resolve("no-such-java"), dir("server"),
            dir("configuration"), dir("project"), dir("index"), dir("home"),
            Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("javaHome");
    }

    @Test
    void rejectsJavaHomeWithoutJavaBinary() throws IOException {
        assertThatThrownBy(() -> new JdtlsLaunch(dir("java"), dir("server"), dir("configuration"),
            dir("project"), dir("index"), dir("home"), Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bin/java");
    }

    @Test
    void rejectsNonExecutableJavaBinary() throws IOException {
        Path javaHome = dir("java-nox");
        Path java = Files.createDirectories(javaHome.resolve("bin")).resolve("java");
        Files.writeString(java, "not executable");
        Files.setPosixFilePermissions(java, Set.of(PosixFilePermission.OWNER_READ));
        assertThatThrownBy(() -> new JdtlsLaunch(javaHome, dir("server"), dir("configuration"),
            dir("project"), dir("index"), dir("home"), Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("executable");
    }

    @Test
    void rejectsMissingServerDir() throws IOException {
        assertThatThrownBy(() -> new JdtlsLaunch(Path.of(System.getProperty("java.home")),
            temp.resolve("no-server"), dir("configuration"), dir("project"), dir("index"),
            dir("home"), Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serverDir");
    }

    @Test
    void rejectsMissingConfigurationDir() throws IOException {
        assertThatThrownBy(() -> new JdtlsLaunch(Path.of(System.getProperty("java.home")),
            dir("server"), temp.resolve("no-config"), dir("project"), dir("index"), dir("home"),
            Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configurationDir");
    }

    @Test
    void rejectsMissingProjectRoot() throws IOException {
        assertThatThrownBy(() -> new JdtlsLaunch(Path.of(System.getProperty("java.home")),
            dir("server"), dir("configuration"), temp.resolve("no-project"), dir("index"),
            dir("home"), Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("projectRoot");
    }

    @Test
    void rejectsMissingLauncherJar() throws IOException {
        Path serverDir = dir("server-no-launcher");
        Files.createDirectories(serverDir.resolve("plugins"));
        assertThatThrownBy(() -> new JdtlsLaunch(Path.of(System.getProperty("java.home")),
            serverDir, dir("configuration"), dir("project"), dir("index"), dir("home"),
            Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("launcher");
    }

    @Test
    void rejectsMissingMavenUserSettings() throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        assertThatThrownBy(() -> new JdtlsLaunch(javaHome, dir("server"), dir("configuration"),
            dir("project"), dir("index"), dir("home"),
            Optional.of(temp.resolve("no-settings.xml")), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mavenUserSettings");
    }

    @Test
    void buildsStopZeroShapedArgv() throws IOException {
        JdtlsLaunch launch = validLaunch();
        List<String> argv = launch.argv();
        assertThat(argv.get(0)).isEqualTo(
            launch.javaHome().resolve("bin").resolve("java").toString());
        assertThat(argv).contains("-Declipse.application=org.eclipse.jdt.ls.core.id1",
            "-Dosgi.bundles.defaultStartLevel=4", "-Declipse.product=org.eclipse.jdt.ls.core.product",
            "-Xmx1G", "--add-modules=ALL-SYSTEM", "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED");
        int jarAt = argv.indexOf("-jar");
        assertThat(jarAt).isPositive();
        assertThat(argv.get(jarAt + 1)).isEqualTo(
            launch.serverDir().resolve("plugins").resolve(LAUNCHER_NAME).toString());
        assertThat(argv).containsSubsequence("-configuration", launch.configurationDir().toString());
        assertThat(argv).containsSubsequence("-data", launch.indexDir().toString());
        assertThat(argv).contains("-Duser.home=" + launch.homeDir(),
            "-Djava.io.tmpdir=" + launch.homeDir().resolve("tmp"),
            "-Dmaven.repo.local=" + launch.homeDir().resolve(".m2/repository"));
        // -D 系统属性必须在 -jar 之前（-jar 之后全是应用参数）
        assertThat(argv.indexOf("-Duser.home=" + launch.homeDir())).isLessThan(jarAt);
    }

    @Test
    void appendsMavenSettingsBeforeJar() throws IOException {
        Path userSettings = file("user-settings.xml");
        Path globalSettings = file("global-settings.xml");
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path serverDir = dir("server");
        Files.createDirectories(serverDir.resolve("plugins"));
        Files.writeString(serverDir.resolve("plugins").resolve(LAUNCHER_NAME), "fake");
        JdtlsLaunch launch = new JdtlsLaunch(javaHome, serverDir, dir("configuration"),
            dir("project"), dir("index"), dir("home"), Optional.of(userSettings),
            Optional.of(globalSettings));
        List<String> argv = launch.argv();
        assertThat(argv).contains("-Dmaven.user.settings=" + userSettings,
            "-Dmaven.global.settings=" + globalSettings);
        assertThat(argv.indexOf("-Dmaven.user.settings=" + userSettings))
            .isLessThan(argv.indexOf("-jar"));
    }

    @Test
    void buildsExplicitEnvironment() throws IOException {
        JdtlsLaunch launch = validLaunch();
        Map<String, String> env = launch.env();
        assertThat(env).containsEntry("JAVA_HOME", launch.javaHome().toString());
        assertThat(env).containsEntry("HOME", launch.homeDir().toString());
        assertThat(env).containsEntry("TMPDIR", launch.homeDir().resolve("tmp").toString());
        assertThat(env.get("PATH")).startsWith(
            launch.javaHome().resolve("bin").toString());
    }
}
