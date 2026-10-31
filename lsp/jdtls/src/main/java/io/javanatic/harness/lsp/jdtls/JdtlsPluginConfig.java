package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.javanatic.harness.kernel.config.ConfigValues;

/**
 * lsp-jdtls 行配置（边界 B27 输入面）：键白名单，未知键 / 坏值 fail loud。
 * serverDir / configurationDir / projectRoot 必填（JdtlsLaunch 校验存在）；
 * javaHome 缺省探测 {@code java.home}（主动探测披露见治理摘要）；homeDir 缺省落在
 * 临时目录（LS 缓存 / 索引不触碰用户真实 HOME，重启后索引重建），indexDir 缺省为其子目录。
 * 目录创建（homeDir / indexDir）发生在 {@link #toLaunch()}——解析本身无文件系统副作用。
 */
record JdtlsPluginConfig(Path javaHome, Path serverDir, Path configurationDir, Path projectRoot,
                         Path indexDir, Path homeDir, Optional<Path> mavenUserSettings,
                         Optional<Path> mavenGlobalSettings) {

    private static final Set<String> KEYS = Set.of(
        "javaHome", "serverDir", "configurationDir", "projectRoot",
        "indexDir", "homeDir", "mavenUserSettings", "mavenGlobalSettings");

    JdtlsPluginConfig {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(serverDir, "serverDir");
        Objects.requireNonNull(configurationDir, "configurationDir");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(homeDir, "homeDir");
        Objects.requireNonNull(mavenUserSettings, "mavenUserSettings");
        Objects.requireNonNull(mavenGlobalSettings, "mavenGlobalSettings");
    }

    static JdtlsPluginConfig parse(Map<String, Object> config, String pluginId) {
        Objects.requireNonNull(config, "config");
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(pluginId + ": unknown key '" + key
                    + "' (allowed: " + KEYS + ")");
            }
        }
        Path javaHome = Path.of(ConfigValues.stringValue(config, pluginId, "javaHome",
            System.getProperty("java.home")));
        Path serverDir = Path.of(ConfigValues.requireString(config, pluginId, "serverDir"));
        Path configurationDir = Path.of(ConfigValues.requireString(config, pluginId, "configurationDir"));
        Path projectRoot = Path.of(ConfigValues.requireString(config, pluginId, "projectRoot"));
        String homeText = ConfigValues.stringValue(config, pluginId, "homeDir", null);
        Path homeDir = homeText == null ? defaultHomeDir() : Path.of(homeText);
        String indexText = ConfigValues.stringValue(config, pluginId, "indexDir", null);
        Path indexDir = indexText == null ? homeDir.resolve("index") : Path.of(indexText);
        return new JdtlsPluginConfig(javaHome, serverDir, configurationDir, projectRoot,
            indexDir, homeDir, optionalPath(config, pluginId, "mavenUserSettings"),
            optionalPath(config, pluginId, "mavenGlobalSettings"));
    }

    /** 创建临时数据目录并构造启动档案；路径存在性 / 可执行性由 JdtlsLaunch 构造校验。 */
    JdtlsLaunch toLaunch() throws IOException {
        Files.createDirectories(homeDir);
        Files.createDirectories(indexDir);
        return new JdtlsLaunch(javaHome, serverDir, configurationDir, projectRoot,
            indexDir, homeDir, mavenUserSettings, mavenGlobalSettings);
    }

    /** 缺省临时数据目录：{@code <tmpdir>/jh-lsp-<8位随机>}——临时生命周期随 OS。 */
    private static Path defaultHomeDir() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return Path.of(System.getProperty("java.io.tmpdir"), "jh-lsp-" + suffix);
    }

    private static Optional<Path> optionalPath(Map<String, Object> config, String pluginId,
                                               String key) {
        String text = ConfigValues.stringValue(config, pluginId, key, null);
        return text == null ? Optional.empty() : Optional.of(Path.of(text));
    }
}
