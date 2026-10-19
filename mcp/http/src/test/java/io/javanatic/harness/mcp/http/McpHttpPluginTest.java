package io.javanatic.harness.mcp.http;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpRegistryPlugin;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpToolDescriptor;
import io.javanatic.harness.mcp.stdio.McpStdioPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件装配路径（Runtime + PluginLoader）：servers[] 行配置 → 注册表聚合；
 * 零 server 静默；scope close 发 DELETE；跨 transport 撞 id fail loud。 */
class McpHttpPluginTest {

    @TempDir
    Path tmp;

    private static Runtime runtimeWith(Map<String, Object> httpConfig, Map<String, Object> stdioConfig) {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> switch (id) {
            case "mcp-http" -> httpConfig;
            case "mcp-stdio" -> stdioConfig;
            default -> Map.of();
        });
        return rt;
    }

    private static Map<String, Object> httpEntry(String id, int port, Map<String, Object> extra) {
        java.util.Map<String, Object> entry = new java.util.HashMap<>(Map.of(
            "id", id,
            "url", "http://127.0.0.1:" + port + "/mcp",
            "timeoutSeconds", 10));
        entry.putAll(extra);
        return Map.copyOf(entry);
    }

    @Test
    void pluginShapeIsHttpProviderOverRegistry() {
        McpHttpPlugin plugin = new McpHttpPlugin();
        assertThat(plugin.id()).isEqualTo("mcp-http");
        assertThat(plugin.requires()).containsExactly("mcp-registry");
    }

    /** 零 server = 零噪声：不建连接、不注册，注册表为空且可用。 */
    @Test
    void absentServersKeyLoadsSilentlyWithEmptyRegistry() {
        try (Runtime rt = runtimeWith(Map.of(), Map.of())) {
            new PluginLoader().loadAll(rt, List.of(new McpRegistryPlugin(), new McpHttpPlugin()));
            assertThat(rt.root().require(McpClientService.KEY).connections()).isEmpty();
        }
    }

    /** 装配：多个 server 全拉起，注册表按 id 排序；调用面直达各 server。 */
    @Test
    void assemblyRegistersSortedConnectionsAndCalls() {
        try (Fixtures.Fixture beta = Fixtures.start(tmp, "--session-id", "sess-beta");
             Fixtures.Fixture alpha = Fixtures.start(tmp, "--session-id", "sess-alpha", "--sse")) {
            try (Runtime rt = runtimeWith(Map.of("servers", List.of(
                    httpEntry("beta", beta.port(), Map.of()),
                    httpEntry("alpha", alpha.port(), Map.of()))), Map.of())) {
                new PluginLoader().loadAll(rt, List.of(new McpRegistryPlugin(), new McpHttpPlugin()));
                McpClientService service = rt.root().require(McpClientService.KEY);

                assertThat(service.connections()).extracting(McpServerConnection::serverId)
                    .containsExactly("alpha", "beta");
                assertThat(service.connection("missing")).isEmpty();
                assertThat(service.connection("alpha").orElseThrow().tools())
                    .extracting(McpToolDescriptor::name, McpToolDescriptor::serverId)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("echo", "alpha"),
                        org.assertj.core.groups.Tuple.tuple("big", "alpha"),
                        org.assertj.core.groups.Tuple.tuple("env", "alpha"),
                        org.assertj.core.groups.Tuple.tuple("fail", "alpha"));

                McpCallResult echoed = service.connection("beta").orElseThrow()
                    .call("echo", "{\"message\":\"hi\"}", AbortSignal.never());
                assertThat(echoed.isError()).isFalse();
                assertThat(echoed.content()).isEqualTo("echo: hi");

                McpCallResult failed = service.connection("alpha").orElseThrow()
                    .call("fail", "{}", AbortSignal.never());
                assertThat(failed.isError()).isTrue();
                assertThat(failed.content()).isEqualTo("fixture failure");
            }
        }
    }

    /** 生命周期归 scope：close 回收会话并尽力 DELETE（有会话 id 才发）。 */
    @Test
    void scopeCloseDeletesRemoteSessions() {
        Path deleteRecord = tmp.resolve("delete");
        Fixtures.Fixture fixture = Fixtures.start(tmp,
            "--session-id", "sess-close", "--record-delete-to", deleteRecord.toString());
        try {
            Runtime rt = runtimeWith(Map.of("servers", List.of(
                httpEntry("alpha", fixture.port(), Map.of()))), Map.of());
            new PluginLoader().loadAll(rt, List.of(new McpRegistryPlugin(), new McpHttpPlugin()));
            assertThat(rt.root().require(McpClientService.KEY).connection("alpha")).isPresent();
            rt.close();
            Fixtures.awaitTrue("remote session deleted on scope close",
                () -> Fixtures.lines(deleteRecord).contains("sess-close"));
        } finally {
            fixture.close();
        }
    }

    /** 跨 transport 撞 server id：装配期 fail loud（不静默遮蔽先注册者）。 */
    @Test
    void duplicateIdAcrossTransportsFailsLoud() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp, "--session-id", "sess-dup")) {
            Map<String, Object> stdioConfig = Map.of("servers", List.of(Map.of(
                "id", "shared",
                "command", Fixtures.javaBin(),
                "args", List.of(Fixtures.minimalStdioSource()),
                "timeoutSeconds", 10)));
            try (Runtime rt = runtimeWith(
                    Map.of("servers", List.of(httpEntry("shared", fixture.port(), Map.of()))),
                    stdioConfig)) {
                assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                    List.of(new McpRegistryPlugin(), new McpStdioPlugin(), new McpHttpPlugin())))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause().hasMessageContaining("already registered")
                    .hasMessageContaining("shared");
            }
        }
    }
}
