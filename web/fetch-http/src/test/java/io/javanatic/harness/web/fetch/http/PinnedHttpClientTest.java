package io.javanatic.harness.web.fetch.http;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebFetchBody;
import io.javanatic.harness.web.WebFetchRequest;
import io.javanatic.harness.web.WebFetchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉地址传输真跑：TLS 夹具 + 注入解析器/闸门。重点取证 TOCTOU 闭合
 * （解析一次、整组过闸、字面 IP 同闸、握手用 URL 主机名）与重定向逐跳复验。
 */
class PinnedHttpClientTest {

    @TempDir
    static Path certDir;

    private static TlsFixture.Contexts contexts;

    @BeforeAll
    static void initContexts() {
        contexts = TlsFixture.contexts(certDir);
    }

    @Test
    void pinsAddressAndSendsOnlyFixedHeaders() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/hello", html("<p>hi</p>"));
            TlsFixture.MapResolver resolver = resolver("pinned.test");
            TlsFixture.RecordingGate gate = new TlsFixture.RecordingGate();
            WebFetchResult result = client(resolver, gate, 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/hello")),
                    AbortSignal.never());

            assertThat(result.url()).isEqualTo(url(server, "pinned.test", "/hello"));
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(new WebFetchBody.Html("<p>hi</p>"));
            assertThat(result.truncated()).isFalse();

            assertThat(resolver.calls()).containsExactly("pinned.test");
            assertThat(gate.calls()).containsExactly("pinned.test=1");
            assertThat(server.sniNames()).containsExactly("pinned.test");

            TlsFixture.RecordedRequest request = server.lastRequest();
            assertThat(request.header("Host")).isEqualTo("pinned.test:" + server.port());
            assertThat(request.header("User-Agent")).isEqualTo("javanatic-harness/0.3");
            assertThat(request.header("Accept")).isEqualTo(
                "text/html,application/xhtml+xml,text/plain,application/json,application/xml");
            assertThat(request.header("Accept-Encoding")).isEqualTo("identity");
            assertThat(request.header("Connection")).isEqualTo("close");
            assertThat(request.header("Cookie")).isNull();
            assertThat(request.header("Authorization")).isNull();
            assertThat(request.header("Referer")).isNull();
        }
    }

    @Test
    void literalIpSkipsResolverButStillGoesThroughGate() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/hello", html("literal"));
            TlsFixture.MapResolver resolver = new TlsFixture.MapResolver(Map.of());
            resolver.forbidCalls();
            TlsFixture.RecordingGate gate = new TlsFixture.RecordingGate();
            WebFetchResult result = client(resolver, gate, 10, 1024)
                .fetch(new WebFetchRequest("https://127.0.0.1:" + server.port() + "/hello"),
                    AbortSignal.never());

            assertThat(result.body()).isEqualTo(new WebFetchBody.Html("literal"));
            assertThat(resolver.calls()).isEmpty();
            assertThat(gate.calls()).containsExactly("127.0.0.1=1");
            assertThat(server.sniNames()).isEmpty();
            assertThat(server.lastRequest().header("Host"))
                .isEqualTo("127.0.0.1:" + server.port());
        }
    }

    @Test
    void anyNonPublicAnswerRejectsWholeSetBeforeConnecting() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            InetAddress publicAddress = TlsFixture.address("93.184.216.34");
            InetAddress privateAddress = TlsFixture.address("10.0.0.1");
            TlsFixture.MapResolver resolver = new TlsFixture.MapResolver(Map.of(
                "mixed.test", new InetAddress[] {publicAddress, privateAddress}));
            PinnedHttpClient client = new PinnedHttpClient(new FetchLimits(10, 1024), resolver,
                PublicAddressGate.INSTANCE, contexts.client());

            assertThatThrownBy(() -> client.fetch(
                new WebFetchRequest(url(server, "mixed.test", "/hello")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_BLOCKED_ADDRESS))
                .hasMessageContaining("10.0.0.1");
            assertThat(server.requests()).isEmpty();
            assertThat(resolver.calls()).containsExactly("mixed.test");
        }
    }

    @Test
    void hostnameVerificationFailureIsNetworkError() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/hello", html("never"));
            TlsFixture.MapResolver resolver = resolver("mismatch.test");
            assertThatThrownBy(() -> client(resolver, new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "mismatch.test", "/hello")),
                    AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_NETWORK_ERROR))
                .hasMessageContaining("mismatch.test");
        }
    }

    @ParameterizedTest(name = "{0} 按 GET 重发")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsAllRedirectStatusesAsGet(int status) {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/start", redirect(status, "/next"));
            server.route("/next", text(200, "ok"));
            WebFetchResult result = client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/start")), AbortSignal.never());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(new WebFetchBody.Text("ok"));
            assertThat(server.requests()).hasSize(2);
            assertThat(server.requests().get(1).rawHead()).startsWith("GET /next HTTP/1.1");
        }
    }

    @Test
    void followsCrossOriginRedirectWithPerHopResolutionAndNoForwardedHeaders() {
        try (TlsFixture.ScriptedHttpsServer first = start();
             TlsFixture.ScriptedHttpsServer second = start()) {
            String target = url(second, "second.test", "/next");
            first.route("/start", redirect(302, target));
            second.route("/next", text(200, "cross-origin"));
            TlsFixture.MapResolver resolver = new TlsFixture.MapResolver(Map.of(
                "pinned.test", new InetAddress[] {TlsFixture.loopback()},
                "second.test", new InetAddress[] {TlsFixture.loopback()}));
            TlsFixture.RecordingGate gate = new TlsFixture.RecordingGate();

            WebFetchResult result = client(resolver, gate, 10, 1024)
                .fetch(new WebFetchRequest(url(first, "pinned.test", "/start")),
                    AbortSignal.never());

            assertThat(result.url()).isEqualTo(target);
            assertThat(result.body()).isEqualTo(new WebFetchBody.Text("cross-origin"));
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(resolver.calls()).containsExactly("pinned.test", "second.test");
            assertThat(gate.calls()).containsExactly("pinned.test=1", "second.test=1");
            assertThat(first.requests()).hasSize(1);
            TlsFixture.RecordedRequest secondRequest = second.lastRequest();
            assertThat(secondRequest.header("Host")).isEqualTo("second.test:" + second.port());
            assertThat(secondRequest.header("Authorization")).isNull();
            assertThat(secondRequest.header("Cookie")).isNull();
            assertThat(secondRequest.header("Referer")).isNull();
        }
    }

    @Test
    void followsRelativeRedirectOnSameOrigin() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/a", redirect(301, "/b"));
            server.route("/b", html("relative"));
            WebFetchResult result = client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/a")), AbortSignal.never());
            assertThat(result.url()).isEqualTo(url(server, "pinned.test", "/b"));
            assertThat(result.body()).isEqualTo(new WebFetchBody.Html("relative"));
        }
    }

    @Test
    void rejectsRedirectDowngrade() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/start", redirect(302, "http://pinned.test:" + server.port() + "/plain"));
            server.route("/plain", html("downgraded"));
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/start")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_REDIRECT_BLOCKED));
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void rejectsRedirectWithoutLocation() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/nolo", TlsFixture.raw("HTTP/1.1 302 Found\r\n"
                + "Content-Type: text/html\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/nolo")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_REDIRECT_BLOCKED))
                .hasMessageContaining("Location");
        }
    }

    @Test
    void stopsAfterFiveRedirects() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            for (int i = 0; i <= 6; i++) {
                server.route("/r" + i, redirect(302, "/r" + (i + 1)));
            }
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/r0")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_REDIRECT_BLOCKED))
                .hasMessageContaining("5");
            assertThat(server.requests()).hasSize(6);
        }
    }

    @Test
    void decodesChunkedEndToEnd() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/chunked", TlsFixture.raw("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                + "4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Sum: 1\r\n\r\n"));
            WebFetchResult result = client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/chunked")), AbortSignal.never());
            assertThat(result.body()).isEqualTo(new WebFetchBody.Html("Wikipedia"));
            assertThat(result.truncated()).isFalse();
        }
    }

    @Test
    void gzipIsExplicitlyUnsupportedWithoutDecoding() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/gzip", TlsFixture.raw("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\n"
                + "Connection: close\r\n\r\nabc"));
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/gzip")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_UNSUPPORTED_CONTENT_TYPE))
                .hasMessageContaining("gzip");
        }
    }

    @Test
    void declaredTooLargeFailsBeforeBody() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/huge", TlsFixture.raw("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\nContent-Length: 9999999\r\nConnection: close\r\n\r\n"));
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/huge")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_TOO_LARGE));
        }
    }

    @Test
    void streamedOverCapIsTruncatedNotAnError() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/stream", TlsFixture.raw("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n"
                + "a".repeat(2048)));
            WebFetchResult result = client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/stream")), AbortSignal.never());
            assertThat(result.truncated()).isTrue();
            assertThat(result.body().content()).hasSize(1024).startsWith("aaaa");
        }
    }

    @Test
    void non2xxIsAResultWithBody() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/missing", text(404, "gone"));
            WebFetchResult result = client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/missing")), AbortSignal.never());
            assertThat(result.statusCode()).isEqualTo(404);
            assertThat(result.body()).isEqualTo(new WebFetchBody.Text("gone"));
        }
    }

    @Test
    void missingContentTypeIsUnsupported() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/bare", TlsFixture.raw("HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\nConnection: close\r\n\r\nok"));
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/bare")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_UNSUPPORTED_CONTENT_TYPE));
        }
    }

    @Test
    void stalledResponseTimesOut() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/slow", out -> {
                sleep(3_000);
                TlsFixture.writeText(out, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: 2\r\nConnection: close\r\n\r\nok");
            });
            long started = System.nanoTime();
            assertThatThrownBy(() -> client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 1, 1024)
                .fetch(new WebFetchRequest(url(server, "pinned.test", "/slow")), AbortSignal.never()))
                .isInstanceOf(WebException.class)
                .satisfies(error -> assertThat(((WebException) error).code())
                    .isEqualTo(WebErrorCode.WEB_TIMEOUT));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMillis).isLessThan(2_500L);
        }
    }

    @Test
    void cancelWakesBlockedReadAsAbortedException() {
        try (TlsFixture.ScriptedHttpsServer server = start()) {
            server.route("/slow", out -> {
                sleep(3_000);
                TlsFixture.writeText(out, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: 2\r\nConnection: close\r\n\r\nok");
            });
            TlsFixture.TestSignal signal = new TlsFixture.TestSignal();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    client(resolver("pinned.test"), new TlsFixture.RecordingGate(), 10, 1024)
                        .fetch(new WebFetchRequest(url(server, "pinned.test", "/slow")), signal);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            awaitTrue("fixture received request", () -> !server.requests().isEmpty());
            signal.cancel("user cancelled");
            joinQuietly(worker);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(AbortedException.class);
        }
    }

    private static TlsFixture.ScriptedHttpsServer start() {
        return TlsFixture.ScriptedHttpsServer.start(contexts.server());
    }

    private static TlsFixture.MapResolver resolver(String host) {
        return new TlsFixture.MapResolver(Map.of(host, new InetAddress[] {TlsFixture.loopback()}));
    }

    private static PinnedHttpClient client(TlsFixture.MapResolver resolver, AddressGate gate,
                                           int timeoutSeconds, int cap) {
        return new PinnedHttpClient(new FetchLimits(timeoutSeconds, cap), resolver, gate,
            contexts.client());
    }

    private static String url(TlsFixture.ScriptedHttpsServer server, String host, String path) {
        return "https://" + host + ":" + server.port() + path;
    }

    private static TlsFixture.Route html(String body) {
        return withBody(200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static TlsFixture.Route text(int status, String body) {
        return withBody(status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static TlsFixture.Route withBody(int status, String contentType, byte[] body) {
        return out -> {
            TlsFixture.writeText(out, "HTTP/1.1 " + status + " Fixture\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n");
            out.write(body);
        };
    }

    private static TlsFixture.Route redirect(int status, String location) {
        return out -> TlsFixture.writeText(out, "HTTP/1.1 " + status + " Redirect\r\n"
            + "Location: " + location + "\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n");
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("timed out awaiting: " + what);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
