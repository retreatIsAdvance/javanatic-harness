package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定 wire 反例经过真实进程与 Peer，不依赖私有白名单或源码文本断言。 */
class LspPeerProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration BOUND = Duration.ofSeconds(5);

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"client/registerCapability", "client/unregisterCapability", "workspace/configuration"})
    void unsupportedServerMethodGetsAnExplicitError(String method) throws Exception {
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":\"remote-7\",\"method\":\"" + method + "\",\"params\":{}}";
        try (LspPeer peer = launch(frame)) {
            peer.notify("trigger", null);
            String reply = Fixtures.readFileEventually(temp.resolve("client-reply"));
            assertThat(reply).isNotNull();
            JsonNode response = JSON.readTree(reply);
            assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(response.path("id").asText()).isEqualTo("remote-7");
            assertThat(response.has("result")).isFalse();
            assertThat(response.at("/error/code").intValue()).isEqualTo(-32601);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "1.0", "\"1\"", "null"})
    void nonIntegerResponseIdCannotCompleteIntegerRequest(String wrongId) throws Exception {
        String wrong = "{\"jsonrpc\":\"2.0\",\"id\":" + wrongId + ",\"result\":\"wrong\"}";
        String right = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"matched\"}";
        try (LspPeer peer = launch(wrong, right)) {
            JsonNode response = peer.request("trigger", null, BOUND, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).textValue()).isEqualTo("matched");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"id\":1,\"result\":null}",
        "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":null}",
        "{\"jsonrpc\":2.0,\"id\":1,\"result\":null}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null} {}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":42}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null}"
    })
    void malformedEnvelopeBreaksTheSession(String frame) throws Exception {
        try (LspPeer peer = launch(frame)) {
            assertThatThrownBy(() -> peer.request("trigger", null, BOUND, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
            assertThat(peer.isBroken()).isTrue();
            assertThat(peer.awaitStdoutEof(Duration.ofMillis(50))).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "null", "{}", "[]"})
    void unusableServerRequestIdIsAProtocolFailure(String id) throws Exception {
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"progress\"}}";
        try (LspPeer peer = launch(frame)) {
            assertThatThrownBy(() -> peer.request("trigger", null, BOUND, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"code\":-32603,\"message\":\"failed\"}"})
    void resultAndErrorTogetherCannotBecomeSuccess(String error) throws Exception {
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null,\"error\":" + error + "}";
        try (LspPeer peer = launch(frame)) {
            assertThatThrownBy(() -> peer.request("trigger", null, BOUND, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
            assertThat(peer.isBroken()).isFalse();
        }
    }

    private LspPeer launch(String... frames) throws Exception {
        Files.write(temp.resolve("server-frames"), List.of(frames));
        Path classes = Path.of(PeerBackpressureServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(Fixtures.javaBin(), "-cp", classes.toString(),
            PeerBackpressureServer.class.getName(), "scripted", temp.toString()).start();
        CountDownLatch ready = new CountDownLatch(1);
        LspPeer peer = new LspPeer(process, "protocol-" + process.pid(), message -> {
            if ("ready".equals(message.path("method").asText())) {
                ready.countDown();
            }
        });
        if (!ready.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
            peer.close();
            throw new AssertionError("fixture did not become ready");
        }
        return peer;
    }
}
