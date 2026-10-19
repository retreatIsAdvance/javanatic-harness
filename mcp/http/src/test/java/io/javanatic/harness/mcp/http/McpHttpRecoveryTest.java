package io.javanatic.harness.mcp.http;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 重连/换代矩阵（直接驱动 HttpMcpConnection，短退避注伪提速）：404 会话失效 /
 * 重连换清单换代 / 通知通道驱动刷新 / 刷新畸形断链重连 / 退避耗尽。 */
class McpHttpRecoveryTest {

    @TempDir
    Path tmp;

    private static final Duration[] FAST = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200)};

    private static List<String> toolNames(HttpMcpConnection connection) {
        return connection.tools().stream().map(McpToolDescriptor::name).toList();
    }

    /** 404 会话失效 → 有限重连（新会话）；重连清单变化按新代发布，不终态化。 */
    @Test
    void sessionExpiryReconnectsAndSwapsCatalogGeneration() {
        Path expireMarker = tmp.resolve("expire");
        Path variantMarker = tmp.resolve("variant");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--session-id", "sess-rec",
                "--tools", "echo",
                "--expire-once", expireMarker.toString(),
                "--variant-tools", variantMarker.toString(), "echo,drifted");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client, FAST);
            try {
                assertThat(connection.catalogGeneration()).isEqualTo(1);
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpConnectionException.class)
                    .hasMessageContaining("session expired");

                Fixtures.awaitTrue("reconnected with swapped catalog", () ->
                    connection.state() == McpServerState.CONNECTED
                        && connection.catalogGeneration() == 2);
                assertThat(toolNames(connection)).containsExactly("echo", "drifted");
                assertThat(connection.call("echo", "{\"message\":\"after swap\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: after swap");
            } finally {
                connection.close();
            }
        }
    }

    /** 通知通道 list_changed → 异步 tools/list → 内容变化递增代际（轮内注册面不动）。 */
    @Test
    void listChangedOverGetChannelBumpsGeneration() {
        Path marker = tmp.resolve("list-changed");
        Path getRecord = tmp.resolve("get");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--session-id", "sess-lc",
                "--tools", "echo",
                "--notifications",
                "--record-get-to", getRecord.toString(),
                "--list-changed-once", marker.toString(), "echo,extra");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client, FAST);
            try {
                Fixtures.awaitTrue("GET notification channel established",
                    () -> !Fixtures.lines(getRecord).isEmpty());
                assertThat(connection.catalogGeneration()).isEqualTo(1);
                assertThat(connection.call("echo", "{\"message\":\"first\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: first");

                Fixtures.awaitTrue("catalog generation bumped by list_changed", () ->
                    connection.catalogGeneration() == 2);
                assertThat(toolNames(connection)).containsExactly("echo", "extra");
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(connection.call("echo", "{\"message\":\"second\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: second");
            } finally {
                connection.close();
            }
        }
    }

    /** 刷新畸形（list_changed 后的 tools/list 非 JSON）→ 断链重连；恢复后可用且代际不变。 */
    @Test
    void malformedRefreshCascadesAndReconnects() {
        Path garbleMarker = tmp.resolve("garble-refresh");
        Path getRecord = tmp.resolve("get");
        Path initRecord = tmp.resolve("init");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--session-id", "sess-gr",
                "--notifications",
                "--record-get-to", getRecord.toString(),
                "--record-init-to", initRecord.toString(),
                "--garble-refresh-once", garbleMarker.toString());
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client, FAST);
            try {
                Fixtures.awaitTrue("GET notification channel established",
                    () -> !Fixtures.lines(getRecord).isEmpty());
                assertThat(connection.call("echo", "{\"message\":\"x\"}", AbortSignal.never()).content())
                    .isEqualTo("echo: x"); // 触发 list_changed 与随后的畸形 tools/list

                Fixtures.awaitTrue("second initialize after malformed refresh", () ->
                    Fixtures.lines(initRecord).size() >= 2);
                Fixtures.awaitTrue("reconnected after malformed refresh", () ->
                    connection.state() == McpServerState.CONNECTED);
                assertThat(connection.call("echo", "{\"message\":\"ok\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: ok");
                assertThat(connection.catalogGeneration()).isEqualTo(1); // 清单一致不换代
            } finally {
                connection.close();
            }
        }
    }

    /** 退避耗尽 → FAILED（终态，不可用事实）。 */
    @Test
    void reconnectExhaustionFailsServer() {
        Path expireOn = tmp.resolve("expire-on");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--session-id", "sess-dead", "--expire-on", expireOn.toString());
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50), Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpConnectionException.class);

                Fixtures.awaitTrue("FAILED after reconnect exhaustion",
                    () -> connection.state() == McpServerState.FAILED);
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpConnectionException.class)
                    .hasMessageContaining("reconnect exhausted after 2 attempts");
            } finally {
                connection.close();
            }
        }
    }
}
