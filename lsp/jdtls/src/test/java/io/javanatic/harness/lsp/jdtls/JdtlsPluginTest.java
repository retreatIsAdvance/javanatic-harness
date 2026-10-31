package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件装配（不起真实 LS 进程，工厂缝注入假服务）：provide、坏配置回滚、scope 关闭收敛。 */
class JdtlsPluginTest {

    private static final String LAUNCHER_NAME = "org.eclipse.equinox.launcher_1.8.0.jar";

    @TempDir
    Path temp;

    @Test
    void pluginShapeIsAnLspProvider() {
        JdtlsPlugin plugin = new JdtlsPlugin();
        assertThat(plugin.id()).isEqualTo("lsp-jdtls");
        assertThat(plugin.requires()).isEmpty();
    }

    @Test
    void applyProvidesServiceAndScopeCloseClosesProvider() throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home")); // bin/java 天然可执行
        Path serverDir = serverDir("server");
        Path configurationDir = dir("configuration");
        Path projectRoot = dir("project");
        FakeLspService fake = new FakeLspService();
        try (Runtime rt = runtime(Map.of(
            "serverDir", serverDir.toString(),
            "configurationDir", configurationDir.toString(),
            "projectRoot", projectRoot.toString()))) {
            new PluginLoader().loadAll(rt, List.of(new JdtlsPlugin(launch -> fake)));
            assertThat(rt.root().require(LspService.KEY)).isSameAs(fake);
            assertThat(fake.closed).isFalse();
        }
        assertThat(fake.closed).isTrue();
    }

    @Test
    void badConfigRollsBackAndLeavesNoService() {
        try (Runtime rt = runtime(Map.of("unknownKey", 1))) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(new JdtlsPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lsp-jdtls");
            assertThat(rt.root().resolve(LspService.KEY)).isEmpty();
        }
    }

    @Test
    void factoryReceivesLaunchBuiltFromConfig() throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path serverDir = serverDir("server");
        Path configurationDir = dir("configuration");
        Path projectRoot = dir("project");
        AtomicReference<JdtlsLaunch> received = new AtomicReference<>();
        try (Runtime rt = runtime(Map.of(
            "serverDir", serverDir.toString(),
            "configurationDir", configurationDir.toString(),
            "projectRoot", projectRoot.toString()))) {
            new PluginLoader().loadAll(rt, List.of(new JdtlsPlugin(launch -> {
                received.set(launch);
                return new FakeLspService();
            })));
            assertThat(received.get()).isNotNull();
            assertThat(received.get().serverDir()).isEqualTo(serverDir);
            assertThat(received.get().javaHome()).isEqualTo(javaHome);
            assertThat(received.get().projectRoot()).isEqualTo(projectRoot);
        }
    }

    private Runtime runtime(Map<String, Object> config) {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> config);
        return rt;
    }

    private Path dir(String name) throws IOException {
        return Files.createDirectories(temp.resolve(name));
    }

    private Path serverDir(String name) throws IOException {
        Path serverDir = dir(name);
        Files.createDirectories(serverDir.resolve("plugins"));
        Files.writeString(serverDir.resolve("plugins").resolve(LAUNCHER_NAME), "fake");
        return serverDir;
    }

    /** 装配测试假服务：只观测 close；三个查询方法从不被调用。 */
    private static final class FakeLspService implements LspService {
        boolean closed;

        @Override
        public LspResult<LspDiagnostic> diagnostics(LspQuery request, AbortSignal signal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LspResult<LspLocation> definition(LspPositionQuery request, AbortSignal signal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LspResult<LspLocation> references(LspPositionQuery request, AbortSignal signal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
