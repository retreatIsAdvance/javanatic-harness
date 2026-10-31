package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JdtlsPlugin 行配置解析（边界 B27 输入面，纯值面不起进程）：
 * 键白名单、未知键 / 坏值 fail loud、缺省探测与派生路径、toLaunch 创建临时数据目录。
 */
class JdtlsPluginConfigTest {

    private static final String LAUNCHER_NAME = "org.eclipse.equinox.launcher_1.8.0.jar";

    @TempDir
    Path temp;

    @Test
    void unknownKeyFailsLoud() {
        assertThatThrownBy(() -> JdtlsPluginConfig.parse(Map.of(
            "serverDir", "s", "configurationDir", "c", "projectRoot", "p", "bogus", 1),
            "lsp-jdtls"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lsp-jdtls")
            .hasMessageContaining("bogus");
    }

    @Test
    void missingRequiredDirectoryFailsLoud() {
        assertThatThrownBy(() -> JdtlsPluginConfig.parse(
            Map.of("configurationDir", "c", "projectRoot", "p"), "lsp-jdtls"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("serverDir");
    }

    @Test
    void nonStringValueFailsLoud() {
        assertThatThrownBy(() -> JdtlsPluginConfig.parse(Map.of(
            "serverDir", "s", "configurationDir", "c", "projectRoot", "p", "javaHome", true),
            "lsp-jdtls"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("javaHome");
    }

    @Test
    void defaultsProbeJavaHomeAndDeriveTempDirs() {
        JdtlsPluginConfig config = JdtlsPluginConfig.parse(Map.of(
            "serverDir", "s", "configurationDir", "c", "projectRoot", "p"), "lsp-jdtls");
        assertThat(config.javaHome()).isEqualTo(Path.of(System.getProperty("java.home")));
        assertThat(config.homeDir().toString())
            .startsWith(Path.of(System.getProperty("java.io.tmpdir"), "jh-lsp-").toString());
        assertThat(config.indexDir()).isEqualTo(config.homeDir().resolve("index"));
        assertThat(config.mavenUserSettings()).isEmpty();
        assertThat(config.mavenGlobalSettings()).isEmpty();
    }

    @Test
    void explicitValuesOverrideDefaults() throws IOException {
        Path settings = Files.writeString(temp.resolve("settings.xml"), "x");
        JdtlsPluginConfig config = JdtlsPluginConfig.parse(Map.of(
            "javaHome", temp.resolve("jdk").toString(),
            "serverDir", "s", "configurationDir", "c", "projectRoot", "p",
            "homeDir", temp.resolve("home").toString(),
            "indexDir", temp.resolve("idx").toString(),
            "mavenUserSettings", settings.toString()), "lsp-jdtls");
        assertThat(config.javaHome()).isEqualTo(temp.resolve("jdk"));
        assertThat(config.homeDir()).isEqualTo(temp.resolve("home"));
        assertThat(config.indexDir()).isEqualTo(temp.resolve("idx"));
        assertThat(config.mavenUserSettings()).contains(settings);
        assertThat(config.mavenGlobalSettings()).isEmpty();
    }

    @Test
    void toLaunchCreatesHomeAndIndexAndBuildsValidArchive() throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home")); // bin/java 天然可执行
        Path serverDir = dir("server");
        Files.createDirectories(serverDir.resolve("plugins"));
        Files.writeString(serverDir.resolve("plugins").resolve(LAUNCHER_NAME), "fake");
        Path configurationDir = dir("configuration");
        Path projectRoot = dir("project");
        JdtlsPluginConfig config = JdtlsPluginConfig.parse(Map.of(
            "javaHome", javaHome.toString(),
            "serverDir", serverDir.toString(),
            "configurationDir", configurationDir.toString(),
            "projectRoot", projectRoot.toString(),
            "homeDir", temp.resolve("new-home").toString(),
            "indexDir", temp.resolve("new-idx").toString()), "lsp-jdtls");
        JdtlsLaunch launch = config.toLaunch();
        assertThat(launch.javaHome()).isEqualTo(javaHome);
        assertThat(launch.serverDir()).isEqualTo(serverDir);
        assertThat(launch.projectRoot()).isEqualTo(projectRoot);
        assertThat(Files.isDirectory(launch.homeDir())).isTrue();
        assertThat(Files.isDirectory(launch.indexDir())).isTrue();
    }

    private Path dir(String name) throws IOException {
        return Files.createDirectories(temp.resolve(name));
    }
}
