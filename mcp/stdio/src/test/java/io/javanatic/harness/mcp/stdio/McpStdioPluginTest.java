package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpToolDescriptor;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件装配路径（Runtime + PluginLoader）：servers[] 行配置 → 全部 server 拉起 →
 * McpClientService 提供排序后的连接面；装配期失败一律 fail loud（不留半装配）。 */
class McpStdioPluginTest {

    @TempDir
    Path tmp;

    private static Runtime runtimeWith(List<Map<String, Object>> servers) {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> "mcp-stdio".equals(id)
            ? Map.of("servers", servers) : Map.of());
        return rt;
    }

    private static Map<String, Object> serverEntry(String id, String... fixtureArgs) {
        List<String> args = new ArrayList<>(fixtureArgs.length + 1);
        args.add(Fixtures.fixtureSource());
        args.addAll(List.of(fixtureArgs));
        return Map.of("id", id, "command", Fixtures.javaBin(), "args", args, "timeoutSeconds", 5);
    }

    @Test
    void assemblyServesSortedConnectionsToolsAndCalls() {
        try (Runtime rt = runtimeWith(List.of(
                serverEntry("beta", "--tools", "echo"),
                serverEntry("alpha", "--tools", "echo,fail")))) {
            new PluginLoader().loadAll(rt, List.of(new McpStdioPlugin()));
            McpClientService service = rt.root().require(McpClientService.KEY);

            assertThat(service.connections())
                .extracting(McpServerConnection::serverId)
                .containsExactly("alpha", "beta");
            assertThat(service.connection("missing")).isEmpty();
            assertThat(service.connection("alpha").orElseThrow().tools())
                .extracting(McpToolDescriptor::name, McpToolDescriptor::serverId)
                .containsExactly(Tuple.tuple("echo", "alpha"), Tuple.tuple("fail", "alpha"));

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

    /** env 清洗（R4 面）：子进程环境 = 缺省最小环境 + 行配置白名单——不继承全量。 */
    @Test
    void childEnvIsMinimalPlusConfiguredWhitelist() {
        Map<String, Object> entry = Map.of("id", "alpha", "command", Fixtures.javaBin(),
            "args", List.of(Fixtures.fixtureSource(), "--tools", "env"),
            "env", Map.of("FIXTURE_MARKER", "on"), "timeoutSeconds", 5);
        try (Runtime rt = runtimeWith(List.of(entry))) {
            new PluginLoader().loadAll(rt, List.of(new McpStdioPlugin()));
            String dump = rt.root().require(McpClientService.KEY).connection("alpha").orElseThrow()
                .call("env", "{}", AbortSignal.never()).content();

            assertThat(dump).contains("PATH=").contains("FIXTURE_MARKER=on");
            Set<String> allowed = Set.of("PATH", "HOME", "TMPDIR", "FIXTURE_MARKER");
            String outsider = System.getenv().keySet().stream()
                .filter(name -> !allowed.contains(name))
                .findFirst().orElse(null);
            if (outsider != null) {
                assertThat(dump).doesNotContain(outsider + "=");
            }
        }
    }

    /** 生命周期绑 scope：close 回收进程树（pid 实证）。 */
    @Test
    void closeRecyclesServerProcesses() {
        Path pidFile = tmp.resolve("alpha.pid");
        long pid;
        try (Runtime rt = runtimeWith(List.of(serverEntry("alpha", "--pid-file", pidFile.toString())))) {
            new PluginLoader().loadAll(rt, List.of(new McpStdioPlugin()));
            pid = Fixtures.readPid(pidFile);
            assertThat(Fixtures.alive(pid)).isTrue();
        }
        Fixtures.awaitTrue("server process recycled after scope close", () -> !Fixtures.alive(pid));
    }

    /** T2 配置校验经装载路径 fail loud（'__' 拒收，消 mcp__<server>__<raw> 二义）。 */
    @Test
    void doubleUnderscoreServerIdFailsLoudAtConfig() {
        try (Runtime rt = runtimeWith(List.of(serverEntry("bad__id")))) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(new McpStdioPlugin())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("must not contain '__'");
        }
    }

    @Test
    void startFailsLoudWhenProcessDiesImmediately() {
        assertStartFails(McpConnectionException.class, "--exit-immediately");
    }

    @Test
    void startFailsLoudOnProtocolVersionMismatch() {
        assertStartFails(McpProtocolException.class, "--protocol-version", "2025-06-18");
    }

    /** raw 名含 "__" 是合法远端名（消费面确定性归一）；装配不拒，原样进入冻结清单。 */
    @Test
    void doubleUnderscoreRawNameIsAcceptedAtTransport() {
        try (Runtime rt = runtimeWith(List.of(serverEntry("alpha", "--bad-name")))) {
            new PluginLoader().loadAll(rt, List.of(new McpStdioPlugin()));
            assertThat(rt.root().require(McpClientService.KEY).connection("alpha").orElseThrow().tools())
                .extracting(McpToolDescriptor::name)
                .contains("bad__name");
        }
    }

    @Test
    void startFailsLoudOnDuplicateToolName() {
        assertStartFails(McpProtocolException.class, "--dup-tools");
    }

    @Test
    void startFailsLoudOnMissingInputSchema() {
        assertStartFails(McpProtocolException.class, "--missing-schema");
    }

    @Test
    void startFailsLoudWhenPaginationNeverEnds() {
        assertStartFails(McpProtocolException.class, "--endless-pages");
    }

    private void assertStartFails(Class<? extends RuntimeException> rootCause, String... fixtureArgs) {
        try (Runtime rt = runtimeWith(List.of(serverEntry("alpha", fixtureArgs)))) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(new McpStdioPlugin())))
                .hasRootCauseInstanceOf(rootCause);
        }
    }
}
