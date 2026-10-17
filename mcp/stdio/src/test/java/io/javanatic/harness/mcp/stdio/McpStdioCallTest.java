package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.mcp.McpProtocolException;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 调用面错误分档（流级与调用级不能互相污染）与取消语义（AbortedException 上抛、
 * 连接保持、晚到响应静默丢弃）。 */
class McpStdioCallTest {

    @TempDir
    Path tmp;

    private static final Duration[] FAST = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200)};

    /** 错 id 响应 → 记日志丢弃；该调用按自身超时收口（不假装成功），流保持可用。
     * 超时取 2s（>夹具 JVM 冷启 + 高负载余量；1s 曾在宿主机极端负载下把 initialize 握手挤红）。 */
    @Test
    void wrongIdResponseIsDroppedAndCallTimesOut() {
        Path marker = tmp.resolve("wrong-id");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(2), "--wrong-id-once", marker.toString()), FAST);
        try {
            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> connection.call("echo", "{\"message\":\"x\"}", AbortSignal.never()))
                .isInstanceOf(McpTimeoutException.class)
                .hasMessageContaining("timed out after 2s");
            assertThat(Duration.ofNanos(System.nanoTime() - startNanos))
                .isGreaterThanOrEqualTo(Duration.ofMillis(1800));

            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                .isEqualTo("echo: after");
        } finally {
            connection.close();
        }
    }

    /** 匹配 id 但缺 result/error → 该调用协议级失败；流仍可用（连接不翻转）。 */
    @Test
    void missingResultSurfacesProtocolFailureAndKeepsStream() {
        Path marker = tmp.resolve("missing-result");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5), "--missing-result-once", marker.toString()), FAST);
        try {
            assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("neither result nor error");
            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                .isEqualTo("echo: after");
        } finally {
            connection.close();
        }
    }

    /** error 成员 → 该调用协议级失败（附远端错误摘要）；流仍可用。 */
    @Test
    void rpcErrorSurfacesProtocolFailureAndKeepsStream() {
        Path marker = tmp.resolve("rpc-error");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(5), "--rpc-error-once", marker.toString()), FAST);
        try {
            assertThatThrownBy(() -> connection.call("echo", "{}", AbortSignal.never()))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("fixture boom");
            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                .isEqualTo("echo: after");
        } finally {
            connection.close();
        }
    }

    /** 等待中取消：AbortedException 上抛（不落错误结果）；连接保持 CONNECTED；
     * 晚到响应（已撤销）静默丢弃，后续调用不受污染。 */
    @Test
    void cancelAbortsCallAndKeepsConnection() throws Exception {
        Path marker = tmp.resolve("delay");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(10), "--delay-once", marker.toString(), "1200"), FAST);
        try {
            Fixtures.TestSignal signal = new Fixtures.TestSignal();
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    connection.call("echo", "{\"message\":\"slow\"}", signal);
                    outcome.complete(null);
                } catch (Throwable thrown) {
                    outcome.complete(thrown);
                }
            });

            Fixtures.sleep(200); // 调用已发出并进入等待
            signal.cancel("user");
            Throwable thrown = outcome.get(5, TimeUnit.SECONDS);
            assertThat(thrown).isInstanceOf(AbortedException.class);
            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);

            Fixtures.sleep(1400); // 晚到响应到达并静默丢弃(已撤销记忆)
            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                .isEqualTo("echo: after");
        } finally {
            connection.close();
        }
    }

    /** 轮询型取消（不实现 onCancel 的 signal，评审 F1 钉死）：收口落在 checkAbort 路径——
     * 同样撤销 pending + 尽力发 notifications/cancelled（夹具写文件为证）+ 记撤销记忆
     * （晚到响应静默丢弃，无 unmatched WARN）；取消后连接保持可用。 */
    @Test
    void pollOnlyCancelNotifiesServerAndDropsLateResponse() throws Exception {
        Path marker = tmp.resolve("delay");
        Path recorded = tmp.resolve("cancel-record.txt");
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(10), "--delay-once", marker.toString(), "1200",
            "--record-cancel-to", recorded.toString()), FAST);
        RecordingHandler handler = new RecordingHandler();
        Logger peerLogger = Logger.getLogger("io.javanatic.harness.mcp.stdio");
        peerLogger.addHandler(handler);
        try {
            Fixtures.PollOnlySignal signal = new Fixtures.PollOnlySignal();
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    connection.call("echo", "{\"message\":\"slow\"}", signal);
                    outcome.complete(null);
                } catch (Throwable thrown) {
                    outcome.complete(thrown);
                }
            });

            Fixtures.sleep(200); // 调用已发出并进入等待
            signal.cancel();
            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(AbortedException.class);

            Fixtures.awaitTrue("fixture recorded notifications/cancelled",
                () -> Fixtures.readPidOrNull(recorded) != null);
            assertThat(Fixtures.readPidOrNull(recorded)).matches("\\d+");

            // 晚到响应(已撤销)静默丢弃:after 调用经同一读线程按序派发——其返回即证明
            // 晚到响应已走完 dispatch,故 WARN 断言窗口确定(非留白竞态)
            assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
            assertThat(connection.call("echo", "{\"message\":\"after\"}", AbortSignal.never()).content())
                .isEqualTo("echo: after");
            assertThat(handler.records).noneMatch(record ->
                record.getLevel().intValue() >= Level.WARNING.intValue()
                    && String.valueOf(record.getMessage()).contains("unmatched response id"));
        } finally {
            peerLogger.removeHandler(handler);
            connection.close();
        }
    }

    /** 大输出真帧：1 MiB 结果经单行 JSON-RPC 帧完整往返（读侧无行上限、无截断）。 */
    @Test
    void oneMebibyteResultFrameRoundTripsIntact() {
        int size = 1024 * 1024;
        StdioMcpConnection connection = StdioMcpConnection.start(Fixtures.server("alpha",
            Duration.ofSeconds(10), "--tools", "big", "--big-bytes", Integer.toString(size)), FAST);
        try {
            String content = connection.call("big", "{}", AbortSignal.never()).content();
            assertThat(content).hasSize(size).isEqualTo("x".repeat(size));
        } finally {
            connection.close();
        }
    }

    /** JUL 捕获器：挂在 peer 日志树根部，全量收集（测试自行按级别/内容过滤）。 */
    private static final class RecordingHandler extends Handler {

        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
