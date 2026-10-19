package io.javanatic.harness.mcp.http;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpConnectionException;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpTimeoutException;
import io.javanatic.harness.mcp.McpToolDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP 协议面矩阵（直接驱动 HttpMcpConnection）：JSON/SSE、会话头、帧上限、
 * 畸形/错 id/缺 result/rpc error、超时、取消（尽力 cancelled 通知）、DELETE 收尾。 */
class McpHttpCallTest {

    @TempDir
    Path tmp;

    private static List<String> toolNames(HttpMcpConnection connection) {
        return connection.tools().stream().map(McpToolDescriptor::name).toList();
    }

    @Test
    void jsonTransportInitializesListsCallsAndDeletesOnClose() {
        Path deleteRecord = tmp.resolve("delete");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--session-id", "sess-json", "--record-delete-to", deleteRecord.toString());
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThat(connection.serverId()).isEqualTo("alpha");
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(connection.catalogGeneration()).isEqualTo(1);
                assertThat(toolNames(connection)).containsExactly("echo", "big", "env", "fail");

                McpCallResult echoed = connection.call("echo", "{\"message\":\"hi\"}",
                    AbortSignal.never());
                assertThat(echoed.isError()).isFalse();
                assertThat(echoed.content()).isEqualTo("echo: hi");

                McpCallResult failed = connection.call("fail", "{}", AbortSignal.never());
                assertThat(failed.isError()).isTrue();
                assertThat(failed.content()).isEqualTo("fixture failure");
            } finally {
                connection.close();
            }
            assertThat(Fixtures.lines(deleteRecord)).contains("sess-json");
        }
    }

    /** SSE 响应分流：initialize / tools/list / tools/call 全走 text/event-stream。 */
    @Test
    void sseTransportHandlesSingleEventResponses() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp, "--session-id", "sess-sse", "--sse");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(toolNames(connection)).containsExactly("echo", "big", "env", "fail");
                assertThat(connection.call("echo", "{\"message\":\"sse\"}", AbortSignal.never()).content())
                    .isEqualTo("echo: sse");
            } finally {
                connection.close();
            }
        }
    }

    /** 会话头与协议版本回传：initialize 不带，其后请求都带（会话 id 捕获后立即生效）。 */
    @Test
    void requestHeadersCarrySessionAndProtocolVersion() {
        Path headersRecord = tmp.resolve("headers");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--session-id", "sess-h", "--record-headers-to", headersRecord.toString());
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                connection.call("echo", "{\"message\":\"h\"}", AbortSignal.never());
            } finally {
                connection.close();
            }
            assertThat(Fixtures.lines(headersRecord)).contains(
                "method=initialize session=none version=none",
                "method=notifications/initialized session=sess-h version=none",
                "method=tools/list session=sess-h version=" + HttpRpcSession.PROTOCOL_VERSION,
                "method=tools/call session=sess-h version=" + HttpRpcSession.PROTOCOL_VERSION);
        }
    }

    /** T7 分层：1 MiB 真帧不被传输层截断（截断上限在消费面 256 KiB，停 3）。 */
    @Test
    void oneMebibyteFrameIsNotTruncated() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--big-bytes", "1048576", "--session-id", "sess-big");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(10)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThat(connection.call("big", "{}", AbortSignal.never()).content())
                    .hasSize(1048576);
            } finally {
                connection.close();
            }
        }
    }

    /** T7：超 8 MiB 帧 = 该请求协议失败；会话仍可用（HTTP 请求互相独立）。 */
    @Test
    void overEightMebibyteFrameFailsRequestButKeepsSession() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--big-bytes", "9000000", "--session-id", "sess-over");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(20)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("big", "{}", AbortSignal.never()))
                    .isInstanceOf(McpProtocolException.class)
                    .hasMessageContaining("exceeds " + HttpRpcSession.MAX_FRAME_BYTES);
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                    .isEqualTo("echo: after");
            } finally {
                connection.close();
            }
        }
    }

    /** T7：单 SSE 事件累计超 8 MiB（每行都在帽下）= 该请求协议失败；会话仍可用。
     * 事件级上限防「多行 data 绕过行帽」的无界累计。 */
    @Test
    void overEightMebibyteSseEventFailsRequestButKeepsSession() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--sse", "--sse-multiline", "--big-bytes", "9000000", "--session-id", "sess-sse-over");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(20)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("big", "{}", AbortSignal.never()))
                    .isInstanceOf(McpProtocolException.class)
                    .hasMessageContaining("event exceeds " + HttpRpcSession.MAX_FRAME_BYTES);
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                    .isEqualTo("echo: after");
            } finally {
                connection.close();
            }
        }
    }

    /** tools/list 分页：nextCursor 跟进并按序合并（与 stdio 同口径）。 */
    @Test
    void paginatedToolsListIsMergedInOrder() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--tools", "echo,big,env,fail", "--pages", "2", "--session-id", "sess-pages");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThat(toolNames(connection)).containsExactly("echo", "big", "env", "fail");
                assertThat(connection.catalogGeneration()).isEqualTo(1);
            } finally {
                connection.close();
            }
        }
    }

    /** 游标永不终止 = 页帽 fail loud（不静默截断清单）；启动期即报错。 */
    @Test
    void endlessPaginationFailsLoudAtStart() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--endless-pages", "--session-id", "sess-endless");
             HttpClient client = Fixtures.client()) {
            assertThatThrownBy(() -> HttpMcpConnection.start(
                    Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                    new Duration[]{Duration.ofMillis(50)}))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("pagination exceeds 100 pages");
        }
    }

    @Test
    void malformedResponseFailsCallWithoutKillingSession() {
        Path marker = tmp.resolve("garble");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--garble-once", marker.toString(), "--session-id", "sess-g");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpProtocolException.class)
                    .hasMessageContaining("malformed response");
                assertThat(connection.call("echo", "{\"message\":\"ok\"}", AbortSignal.never()).content())
                    .isEqualTo("echo: ok");
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void wrongIdResponseFailsCall() {
        assertCallFails("--wrong-id-once", "unexpected response id");
    }

    @Test
    void missingResultFailsCall() {
        assertCallFails("--missing-result-once", "neither result nor error");
    }

    @Test
    void rpcErrorMemberFailsCall() {
        assertCallFails("--rpc-error-once", "rpc error");
    }

    private void assertCallFails(String flag, String messagePart) {
        Path marker = tmp.resolve(flag.substring(2));
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                flag, marker.toString(), "--session-id", "sess-bad");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpProtocolException.class)
                    .hasMessageContaining(messagePart);
            } finally {
                connection.close();
            }
        }
    }

    /** 超时（响应头阶段）：timeoutSeconds 生效，不续等。 */
    @Test
    void timeoutFailsCall() {
        Path marker = tmp.resolve("delay");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--delay-once", marker.toString(), "3000", "--session-id", "sess-delay");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(1)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpTimeoutException.class)
                    .hasMessageContaining("timed out");
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            } finally {
                connection.close();
            }
        }
    }

    /** 超时（正文阶段）：响应头已到、体停滞 → 看门狗关流，超时档收口且会话不失效
     * （JDK HttpRequest.timeout 只管到响应头，体读兜底靠 BodyGuard）。 */
    @Test
    void bodyStallFailsCallAsTimeoutButKeepsSession() {
        Path marker = tmp.resolve("stall-body");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--stall-body-once", marker.toString(), "5000", "--session-id", "sess-stall");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(1)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                long start = System.nanoTime();
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpTimeoutException.class)
                    .hasMessageContaining("timed out");
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                assertThat(elapsedMillis)
                    .as("body watchdog closes the stream at timeout instead of waiting for server")
                    .isLessThan(3_000);
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(connection.call("echo", "{\"message\":\"after stall\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: after stall");
            } finally {
                connection.close();
            }
        }
    }

    /** 取消是正常路径：AbortedException（非工具错误）+ 尽力 notifications/cancelled。 */
    @Test
    void cancelAbortsCallAndSendsCancelledNotification() {
        Path marker = tmp.resolve("delay-cancel");
        Path cancelRecord = tmp.resolve("cancel");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--delay-once", marker.toString(), "5000", "--session-id", "sess-cancel",
                "--record-cancel-to", cancelRecord.toString());
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                Fixtures.TestSignal signal = new Fixtures.TestSignal();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread call = Thread.ofVirtual().start(() -> {
                    try {
                        connection.call("echo", "{\"message\":\"slow\"}", signal);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                Fixtures.sleep(300); // 等请求真正在途
                signal.cancel("user");
                try {
                    call.join(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while awaiting cancelled call", e);
                }
                assertThat(failure.get()).isInstanceOf(AbortedException.class);
                Fixtures.awaitTrue("fixture recorded notifications/cancelled",
                    () -> !Fixtures.lines(cancelRecord).isEmpty());
                assertThat(connection.call("echo", "{\"message\":\"after cancel\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: after cancel");
            } finally {
                connection.close();
            }
        }
    }

    /** 取消（正文阶段）：响应头已到、体停滞时取消 → BodyGuard 关流 + AbortedException，
     * 会话不失效；cancelled 通知照发。 */
    @Test
    void cancelDuringBodyStallAbortsCall() {
        Path marker = tmp.resolve("stall-cancel");
        Path cancelRecord = tmp.resolve("cancel-body");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--stall-body-once", marker.toString(), "5000", "--session-id", "sess-stall-cancel",
                "--record-cancel-to", cancelRecord.toString());
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(10)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                Fixtures.TestSignal signal = new Fixtures.TestSignal();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread call = Thread.ofVirtual().start(() -> {
                    try {
                        connection.call("echo", "{\"message\":\"slow-body\"}", signal);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                Fixtures.awaitTrue("fixture entered body stall", () -> Files.exists(marker));
                signal.cancel("user");
                try {
                    call.join(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while awaiting body-cancelled call", e);
                }
                assertThat(failure.get()).isInstanceOf(AbortedException.class);
                Fixtures.awaitTrue("fixture recorded notifications/cancelled (body)",
                    () -> !Fixtures.lines(cancelRecord).isEmpty());
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                assertThat(connection.call("echo", "{\"message\":\"after body cancel\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: after body cancel");
            } finally {
                connection.close();
            }
        }
    }

    /** GET 通知通道 405 = 降级 WARN；主连接与调用不受影响（不轮询）。 */
    @Test
    void getNotificationChannel405DegradesGracefully() {
        try (Fixtures.Fixture fixture = Fixtures.start(tmp, "--session-id", "sess-405");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
                Fixtures.sleep(100); // 等通知通道请求落地（405）
                assertThat(connection.call("echo", "{\"message\":\"still alive\"}",
                    AbortSignal.never()).content()).isEqualTo("echo: still alive");
                assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            } finally {
                connection.close();
            }
        }
    }

    /** 3xx 不跟随（Redirect.NEVER）：连接面失败，不把重定向目标当作合法响应。 */
    @Test
    void redirectIsNotFollowed() {
        Path marker = tmp.resolve("redirect");
        try (Fixtures.Fixture fixture = Fixtures.start(tmp,
                "--redirect-once", marker.toString(), "--session-id", "sess-redir");
             HttpClient client = Fixtures.client()) {
            HttpMcpConnection connection = HttpMcpConnection.start(
                Fixtures.config("alpha", fixture.port(), Duration.ofSeconds(5)), client,
                new Duration[]{Duration.ofMillis(50)});
            try {
                assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                    .isInstanceOf(McpConnectionException.class)
                    .hasMessageContaining("redirect")
                    .hasMessageContaining("disabled");
                assertThat(connection.call("echo", "{\"message\":\"ok\"}", AbortSignal.never()).content())
                    .isEqualTo("echo: ok");
            } finally {
                connection.close();
            }
        }
    }
}
