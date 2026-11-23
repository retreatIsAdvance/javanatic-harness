package io.javanatic.harness.examples.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R4 纯预检矩阵：不绑定端口、不创建会话；缺 serve 在装配前拒绝。 */
class HeadlessWebWriteOptionsTest {
    @TempDir
    Path workspace;
    @TempDir
    Path sessions;

    @Test
    void verifyWithoutServeReportsWriteOff() throws Exception {
        Result result = run("--verify");
        assertThat(result.exit()).isZero();
        assertThat(result.stdout()).contains("  web-write: off（缺省）");
        assertNoSessionCreated();
    }

    @Test
    void verifyServeOffAndOnDoNotBindEvenAnOccupiedPort() throws Exception {
        try (ServerSocketChannel occupied = ServerSocketChannel.open()) {
            occupied.setOption(StandardSocketOptions.SO_REUSEADDR, false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) occupied.getLocalAddress()).getPort();
            for (boolean enabled : List.of(false, true)) {
                String[] args = enabled
                    ? new String[] {"--verify", "--serve", "--web-write", "--port=" + port}
                    : new String[] {"--verify", "--serve", "--port=" + port};
                assertThatCode(() -> HeadlessMain.parse(args)).doesNotThrowAnyException();
                Result result = run(args);
                assertThat(result.exit()).isZero();
                assertThat(result.stdout())
                    .contains(enabled ? "  web-write: on（--web-write）" : "  web-write: off（缺省）")
                    .doesNotContain("JSON-RPC server", "UI: http://");
                assertNoSessionCreated();
            }
        }
    }

    @Test
    void writeFlagRequiresServeEvenInVerifyMode() {
        for (String[] args : List.of(new String[] {"--web-write"},
                new String[] {"--verify", "--web-write"})) {
            assertThatThrownBy(() -> HeadlessMain.parse(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--web-write 需与 --serve 同用");
        }
    }

    @Test
    void writeFlagParsesWithoutRequiringVerify() {
        assertThatCode(() -> HeadlessMain.parse(new String[] {"--serve", "--web-write"}))
            .doesNotThrowAnyException();
    }

    @Test
    void verifyWriteKeepsAllOtherModeConflicts() {
        for (String extra : List.of("任务", "--resume=s1", "--sessions", "--session=s1",
                "--session-view=turns", "--workbench", "--follow", "--dump")) {
            assertThatThrownBy(() -> HeadlessMain.parse(
                new String[] {"--verify", "--serve", "--web-write", extra}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("未知参数");
        }
    }

    @Test
    void verifyStillRejectsInvalidPortRanges() {
        for (String port : List.of("0", "-1", "65536", "wat")) {
            assertThatThrownBy(() -> HeadlessMain.parse(
                new String[] {"--verify", "--serve", "--web-write", "--port=" + port}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--port");
        }
    }

    @Test
    void writePreflightPreservesPolicyFailureExitOne() throws Exception {
        String[] args = {"--verify", "--serve", "--web-write", "--policy=PRODUCTION"};
        assertThatCode(() -> HeadlessMain.parse(args)).doesNotThrowAnyException();
        Result result = run(args);
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertNoSessionCreated();
    }

    private void assertNoSessionCreated() throws Exception {
        try (var paths = Files.walk(sessions)) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("header.json")
                || path.getFileName().toString().equals("log.jsonl")).toList()).isEmpty();
        }
    }

    private Result run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(HeadlessMain.parse(args), workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String stdout) { }
}
