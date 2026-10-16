package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpCatalogDriftException;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 重连矩阵（直接驱动 StdioMcpConnection，短退避注伪提速）：崩溃恢复 / 对账漂移（T4）/
 * 退避耗尽 / 畸形帧协议级联通重连。 */
class McpStdioRecoveryTest {

    @TempDir
    Path tmp;

    private static final Duration[] FAST = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200)};

    /** 断线 → 退避重连 → 新进程承接调用；旧进程回收；冻结清单不变。 */
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
            Fixtures.awaitTrue("run 1 process gone and run 2 adopted", () -> !Fixtures.alive(pid1)
                && !String.valueOf(pid1).equals(Fixtures.readPidOrNull(pidFile))
                && connection.state() == McpServerState.CONNECTED);

            assertThat(connection.call("echo", "{\"message\":\"again\"}", AbortSignal.never()).content())
                .isEqualTo("echo: again");
            assertThat(connection.tools())
                .extracting(McpToolDescriptor::name)
                .containsExactly("echo", "big", "env", "fail");
        } finally {
            connection.close();
        }
    }

    /** T4 落点：重连后清单不一致 → FAILED（终态），调用抛 McpCatalogDriftException，
     * 诊断含「请重开会话」与漂移摘要。 */
    @Test
    void catalogDriftOnReconnectFailsServerWithRestartDiagnosis() {
        Path exitMarker = tmp.resolve("exit-after-list");
        Path variantMarker = tmp.resolve("variant");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5),
            "--tools", "echo",
            "--exit-after-list-once", exitMarker.toString(),
            "--variant-tools", variantMarker.toString(), "echo,drifted"), FAST);
        try {
            Fixtures.awaitTrue("FAILED after catalog drift",
                () -> connection.state() == McpServerState.FAILED);

            assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                .isInstanceOf(McpCatalogDriftException.class)
                .hasMessageContaining("请重开会话")
                .hasMessageContaining("added=[drifted]");
        } finally {
            connection.close();
        }
    }

    /** 退避耗尽 → FAILED（终态，非漂移档）。 */
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
                .isNotInstanceOf(McpCatalogDriftException.class)
                .hasMessageContaining("reconnect exhausted after 2 attempts");
        } finally {
            connection.close();
        }
    }

    /** 畸形帧 = 残帧流不可再信：在途调用协议级联失败 + 重连；重建期调用快速失败
     * （不静默挂起）；恢复后按冻结清单对账并可用。 */
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
            assertThat(connection.tools())
                .extracting(McpToolDescriptor::name)
                .containsExactly("echo", "big", "env", "fail");
        } finally {
            connection.close();
        }
    }
}
