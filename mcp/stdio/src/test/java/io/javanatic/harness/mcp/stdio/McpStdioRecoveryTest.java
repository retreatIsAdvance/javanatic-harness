package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 重连/刷新矩阵（直接驱动 StdioMcpConnection，短退避注伪提速）：崩溃恢复 /
 * 重连换清单换代 / 通知驱动刷新 / 刷新畸形断链重连 / 退避耗尽。 */
class McpStdioRecoveryTest {

    @TempDir
    Path tmp;

    private static final Duration[] FAST = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200)};

    private static List<String> toolNames(StdioMcpConnection connection) {
        return connection.tools().stream().map(McpToolDescriptor::name).toList();
    }

    /** 断线 → 退避重连 → 新进程承接调用；旧进程回收；清单一致时代际不变。 */
    @Test
    void crashAfterHandshakeReconnectsWithFreshProcess() {
        Path pidFile = tmp.resolve("alpha.pid");
        Path marker = tmp.resolve("exit-after-list");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--pid-file", pidFile.toString(),
            "--exit-after-list-once", marker.toString()), FAST);
        try {
            long pid1 = Fixtures.readPid(pidFile);
            assertThat(connection.catalogGeneration()).isEqualTo(1);
            Fixtures.awaitTrue("run 1 process gone and run 2 adopted", () -> !Fixtures.alive(pid1)
                && !String.valueOf(pid1).equals(Fixtures.readPidOrNull(pidFile))
                && connection.state() == McpServerState.CONNECTED);

            assertThat(connection.call("echo", "{\"message\":\"again\"}", AbortSignal.never()).content())
                .isEqualTo("echo: again");
            assertThat(toolNames(connection)).containsExactly("echo", "big", "env", "fail");
            assertThat(connection.catalogGeneration()).isEqualTo(1); // 内容不变不换代
        } finally {
            connection.close();
        }
    }

    /** T4 重裁：重连后清单不一致 → 换代（CONNECTED + generation 递增 + 新清单），不再终态化。 */
    @Test
    void catalogChangeOnReconnectSwapsGeneration() {
        Path exitMarker = tmp.resolve("exit-after-list");
        Path variantMarker = tmp.resolve("variant");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--tools", "echo",
            "--exit-after-list-once", exitMarker.toString(),
            "--variant-tools", variantMarker.toString(), "echo,drifted"), FAST);
        try {
            Fixtures.awaitTrue("reconnected with swapped catalog", () ->
                connection.state() == McpServerState.CONNECTED
                    && connection.catalogGeneration() == 2);

            assertThat(toolNames(connection)).containsExactly("echo", "drifted");
            assertThat(connection.call("echo", "{\"message\":\"after swap\"}", AbortSignal.never()).content())
                .isEqualTo("echo: after swap");
        } finally {
            connection.close();
        }
    }

    /** 通知驱动刷新：list_changed → 异步 tools/list → 内容变化递增代际，旧调用面继续可用。 */
    @Test
    void listChangedNotificationRefreshesCatalogGeneration() {
        Path marker = tmp.resolve("list-changed");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--tools", "echo",
            "--list-changed-once", marker.toString(), "echo,extra"), FAST);
        try {
            assertThat(toolNames(connection)).containsExactly("echo");
            assertThat(connection.catalogGeneration()).isEqualTo(1);

            assertThat(connection.call("echo", "{\"message\":\"first\"}", AbortSignal.never()).content())
                .isEqualTo("echo: first"); // 首个 tools/call 后夹具发通知并换清单

            Fixtures.awaitTrue("catalog generation bumped by list_changed", () ->
                connection.catalogGeneration() == 2);
            assertThat(toolNames(connection)).containsExactly("echo", "extra");
            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"second\"}", AbortSignal.never()).content())
                .isEqualTo("echo: second");
        } finally {
            connection.close();
        }
    }

    /** 刷新畸形（通知后的 tools/list 非 JSON）→ 断链重连；重启档正常后恢复可用。 */
    @Test
    void malformedRefreshCascadesAndReconnects() {
        Path pidFile = tmp.resolve("alpha.pid");
        Path marker = tmp.resolve("garble-refresh");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--pid-file", pidFile.toString(),
            "--garble-refresh-once", marker.toString()),
            new Duration[]{Duration.ofMillis(400), Duration.ofMillis(800)});
        try {
            long pid1 = Fixtures.readPid(pidFile);
            assertThat(connection.call("echo", "{\"message\":\"x\"}", AbortSignal.never()).content())
                .isEqualTo("echo: x"); // 触发通知与随后的畸形 tools/list

            Fixtures.awaitTrue("run 2 adopted after malformed refresh", () -> !Fixtures.alive(pid1)
                && !String.valueOf(pid1).equals(Fixtures.readPidOrNull(pidFile))
                && connection.state() == McpServerState.CONNECTED);

            assertThat(connection.call("echo", "{\"message\":\"ok\"}", AbortSignal.never()).content())
                .isEqualTo("echo: ok");
            assertThat(connection.catalogGeneration()).isEqualTo(1); // 重启档默认清单与首代一致
        } finally {
            connection.close();
        }
    }

    /** 退避耗尽 → FAILED（终态，不可用事实）。 */
    @Test
    void reconnectExhaustionFailsServer() {
        Path exitMarker = tmp.resolve("exit-after-list");
        Path deadMarker = tmp.resolve("dead");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--exit-after-list-once", exitMarker.toString(),
            "--exit-immediately-if", deadMarker.toString()),
            new Duration[]{Duration.ofMillis(50), Duration.ofMillis(50)});
        try {
            Fixtures.awaitTrue("FAILED after reconnect exhaustion",
                () -> connection.state() == McpServerState.FAILED);

            assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                .isInstanceOf(McpConnectionException.class)
                .hasMessageContaining("reconnect exhausted after 2 attempts");
        } finally {
            connection.close();
        }
    }

    /** T7 传输层帧帽（stdio 侧）：超 8 MiB 单行 = 残帧不可再信 → 在途调用协议级联
     * 失败 + 断链重连；恢复后同一 server 继续可用（不静默截断）。 */
    @Test
    void overCapFrameCascadesAndReconnects() {
        Path pidFile = tmp.resolve("alpha.pid");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(10),
            "--pid-file", pidFile.toString(),
            "--big-bytes", "9000000"), new Duration[]{Duration.ofMillis(400), Duration.ofMillis(800)});
        try {
            long pid1 = Fixtures.readPid(pidFile);

            assertThatThrownBy(() -> connection.call("big", "{}", AbortSignal.never()))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("mcp frame exceeds 8388608 bytes");

            Fixtures.awaitTrue("run 2 adopted after over-cap frame", () -> !Fixtures.alive(pid1)
                && !String.valueOf(pid1).equals(Fixtures.readPidOrNull(pidFile))
                && connection.state() == McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"ok\"}", AbortSignal.never()).content())
                .isEqualTo("echo: ok");
            assertThat(connection.catalogGeneration()).isEqualTo(1); // 清单一致不换代
        } finally {
            connection.close();
        }
    }

    /** 畸形帧 = 残帧流不可再信：在途调用协议级联失败 + 重连；重建期调用快速失败
     * （不静默挂起）；恢复后清单一致仍可用且代际不变。 */
    @Test
    void malformedFrameCascadesAndReconnects() {
        Path pidFile = tmp.resolve("alpha.pid");
        Path garbleMarker = tmp.resolve("garble");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--pid-file", pidFile.toString(),
            "--garble-once", garbleMarker.toString()),
            new Duration[]{Duration.ofMillis(400), Duration.ofMillis(800)});
        try {
            long pid1 = Fixtures.readPid(pidFile);

            assertThatThrownBy(() -> connection.call("echo", "{\"message\":\"x\"}", AbortSignal.never()))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("malformed frame");

            // 重建期调用快速失败（退避 400ms 窗口内到达；两条失败文案都含 "reconnecting"）
            assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                .isInstanceOf(McpConnectionException.class)
                .hasMessageContaining("reconnecting");

            Fixtures.awaitTrue("run 2 adopted after protocol cascade", () -> !Fixtures.alive(pid1)
                && !String.valueOf(pid1).equals(Fixtures.readPidOrNull(pidFile))
                && connection.state() == McpServerState.CONNECTED);

            assertThat(connection.call("echo", "{\"message\":\"ok\"}", AbortSignal.never()).content())
                .isEqualTo("echo: ok");
            assertThat(toolNames(connection)).containsExactly("echo", "big", "env", "fail");
            assertThat(connection.catalogGeneration()).isEqualTo(1);
        } finally {
            connection.close();
        }
    }
}
