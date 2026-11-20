package io.javanatic.harness.workbench.rpc;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(25)
class RpcHttpServerSseTest {
    private static final String EVENTS = "/events?session=s1";

    @Test
    void defaultAndMinusOneIncludeSeqZeroButZeroIsExclusive() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            for (String suffix : List.of("", "&lastSeq=-1")) {
                var response = get(client, server, EVENTS + suffix);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream; charset=utf-8");
                assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
                assertThat(ids(response.body())).containsExactly("id: 0", "id: 1", "id: 2");
            }
            assertThat(ids(get(client, server, EVENTS + "&lastSeq=0").body())).containsExactly("id: 1", "id: 2");
            assertThat(get(client, server, EVENTS + "&lastSeq=2").body()).isEmpty();
            assertThat(source.afters).containsExactly(-1L, -1L, 0L, 2L);
            assertThat(source.sessions).containsOnly(new Id<Session>("s1"));
        }
    }

    @Test
    void lastEventIdHeaderTakesPrecedenceOverQuery() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            var request = request(server, EVENTS + "&lastSeq=0").header("Last-Event-ID", "1").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(ids(response.body())).containsExactly("id: 2");
            assertThat(source.afters).containsExactly(1L);
            var invalid = request(server, EVENTS + "&lastSeq=0").header("Last-Event-ID", "bad").GET().build();
            assertThat(client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(source.afters).containsExactly(1L);
        }
    }

    @Test
    void malformedParametersAndDuplicateValuesAreNamed400BeforeOpen() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            for (String path : List.of("/events", "/events?session=", "/events?session=+", EVENTS + "&lastSeq=bad",
                    EVENTS + "&lastSeq=-2", EVENTS + "&lastSeq=9223372036854775808", EVENTS + "&lastSeq=",
                    EVENTS + "&session=s2", EVENTS + "&lastSeq=0&lastSeq=1", "/events?session=%C3%28")) {
                var response = get(client, server, path);
                assertThat(response.statusCode()).as(path).isEqualTo(400);
                assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain; charset=utf-8");
                assertThat(response.body()).contains("invalid SSE");
            }
            var duplicate = request(server, EVENTS).header("Last-Event-ID", "0").header("Last-Event-ID", "1").GET().build();
            assertThat(client.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(source.opens.get()).isZero();
        }
    }

    @Test
    void beyondStableHeadIs400AndOpenedIsClosedBeforeNext() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            var response = get(client, server, EVENTS + "&lastSeq=3");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("cursor exceeds head");
            assertThat(source.cursors).hasSize(1);
            assertThat(source.cursors.getFirst().closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(source.cursors.getFirst().reader).isNull();
        }
    }

    @Test
    void sourceOpenFailuresArePlainTextBeforeSseHeaders() throws Exception {
        for (int status : List.of(404, 500)) {
            try (var server = server((session, after) -> {
                throw new SseEventSource.HttpFailureException(status, status == 404 ? "unknown session" : "corrupt session log");
            }); var client = HttpClient.newHttpClient()) {
                var response = get(client, server, EVENTS);
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain; charset=utf-8");
                assertThat(response.body()).contains(status == 404 ? "unknown session" : "corrupt session log");
            }
        }
    }

    @Test
    void fenceRejectsEvilHostAndOriginBeforeSourceWithLegal200Control() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            var evil = request(server, EVENTS).header("Origin", "http://evil.com").GET().build();
            var rejected = client.send(evil, HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(403);
            assertThat(rejected.body()).contains("origin not allowed");
            assertThat(raw(server, EVENTS, "Host: evil.com\r\n")).contains("403", "host not allowed");
            assertThat(source.opens.get()).isZero();
            assertThat(get(client, server, EVENTS).statusCode()).isEqualTo(200);
            assertThat(source.opens.get()).isEqualTo(1);
        }
    }

    @Test
    void duplicateOriginCannotHideBehindFirstAllowedValue() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            String origin = "http://127.0.0.1:" + server.address().getPort();
            var request = request(server, EVENTS).header("Origin", origin).header("Origin", "http://evil.com").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("origin");
            assertThat(source.opens.get()).isZero();
        }
    }

    @Test
    void duplicateHostIsRejectedBeforeSource() throws Exception {
        Fixture source = new Fixture(false);
        try (var server = server(source)) {
            String response = raw(server, EVENTS, "Host: 127.0.0.1:" + server.address().getPort() + "\r\nHost: evil.com\r\n");
            // JDK HTTP parser 本身也可拒双 Host(400)；不得放行到 source。
            assertThat(response).doesNotContain("200 OK");
            assertThat(response).containsAnyOf("400", "403");
            assertThat(source.opens.get()).isZero();
        }
    }

    @Test
    void enabledHeadPostAndDisabledMatrixRemainSeparate() throws Exception {
        Fixture source = new Fixture(false);
        try (var enabled = server(source); var disabled = new RpcHttpServer((method, params) -> null);
                var client = HttpClient.newHttpClient()) {
            disabled.start(0);
            var head = client.send(request(enabled, EVENTS).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(head.statusCode()).isEqualTo(405);
            assertThat(head.headers().firstValue("Allow")).hasValue("GET");
            assertThat(get(client, disabled, EVENTS).statusCode()).isEqualTo(404);
            for (var server : List.of(enabled, disabled)) {
                var post = request(server, EVENTS).POST(HttpRequest.BodyPublishers.noBody()).build();
                assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
                assertThat(get(client, server, "/events/extra?session=s1").statusCode()).isEqualTo(404);
                assertThat(get(client, server, "/").statusCode()).isEqualTo(200);
            }
            assertThat(source.opens.get()).isZero();
        }
    }

    @Test
    void disconnectAndReconnectUsesRecordedExclusiveCursorAcrossLiveAppend() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); var client = HttpClient.newHttpClient();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = client.send(request(server, EVENTS).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var body = response.body(); var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                assertThat(pool.submit(() -> readId(reader)).get(3, TimeUnit.SECONDS)).isEqualTo("id: 0");
                assertThat(pool.submit(() -> readId(reader)).get(3, TimeUnit.SECONDS)).isEqualTo("id: 1");
            }
            source.append();
            source.finish();
            var reconnect = request(server, EVENTS + "&lastSeq=0").header("Last-Event-ID", "1").GET().build();
            var resumed = client.send(reconnect, HttpResponse.BodyHandlers.ofString());
            assertThat(resumed.statusCode()).isEqualTo(200);
            assertThat(ids(resumed.body())).containsExactly("id: 2", "id: 3");
            assertThat(source.afters).containsExactly(-1L, 1L);
            assertThat(source.cursors.getFirst().head()).isEqualTo(2);
            assertThat(source.cursors.getLast().head()).isEqualTo(3);
        }
    }

    @Test
    void serverCloseWakesBlockedNextAndReclaimsReader() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); var client = HttpClient.newHttpClient()) {
            var response = client.send(request(server, EVENTS + "&lastSeq=2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                Cursor cursor = source.cursors.getFirst();
                assertThat(cursor.waiting.await(2, TimeUnit.SECONDS)).isTrue();
                server.close();
                server.close();
                assertThat(cursor.closed.await(2, TimeUnit.SECONDS)).isTrue();
                cursor.reader.join(2000);
                assertThat(cursor.reader.isAlive()).isFalse();
                assertThat(cursor.closeCalls.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void serverCloseDoesNotDrainMissingGetBodyBeforeStoppingSockets() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            Thread closer = Thread.ofVirtual().unstarted(() -> {
                try {
                    server.close();
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            try {
                openWithMissingBody(socket, server);
                Cursor cursor = source.cursors.getFirst();
                assertThat(cursor.waiting.await(2, TimeUnit.SECONDS)).isTrue();
                closer.start();
                assertExited(closer, "server.close must return within two seconds");
                assertThat(failure.get()).isNull();
                assertReclaimed(cursor);
                assertSocketClosed(socket);
                server.close();
                assertThat(cursor.closeCalls.get()).isEqualTo(1);
            } finally {
                // 红测也必须主动撤销自己的欠缺正文，不能把堵塞线程遗留给后续用例。
                socket.close();
                closer.interrupt();
                closer.join(2000);
                reclaimFixture(source);
            }
        }
    }

    @Test
    void sourceEofReclaimsMissingBodyConnectionWithoutServerClose() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            try {
                openWithMissingBody(socket, server);
                Cursor cursor = source.cursors.getFirst();
                assertThat(cursor.waiting.await(2, TimeUnit.SECONDS)).isTrue();
                source.finish();
                assertReclaimed(cursor);
                assertSocketClosed(socket);
            } finally {
                socket.close();
                reclaimFixture(source);
            }
        }
    }

    @Test
    void overflowReclaimsMissingBodyConnectionWithoutServerClose() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            try {
                openWithMissingBody(socket, server);
                Cursor cursor = source.cursors.getFirst();
                assertThat(cursor.waiting.await(2, TimeUnit.SECONDS)).isTrue();
                source.overflow();
                assertReclaimed(cursor);
                assertSocketClosed(socket);
            } finally {
                socket.close();
                reclaimFixture(source);
            }
        }
    }

    @Test
    void closeCallbacksRunOutsideGlobalLockAndOneFailureCannotLeakOtherConnections() throws Exception {
        Fixture source = new Fixture(true);
        AtomicReference<RpcHttpServer> owner = new AtomicReference<>();
        AtomicInteger closeCount = new AtomicInteger();
        try (var probe = Executors.newVirtualThreadPerTaskExecutor();
                var server = server((session, after) -> {
                    var cursor = source.open(session, after);
                    return new SseEventSource.Opened() {
                        @Override
                        public long head() { return cursor.head(); }
                        @Override
                        public Optional<SseFrame> next() throws InterruptedException { return cursor.next(); }
                        @Override
                        public void close() {
                            cursor.close();
                            try {
                                // address() 需要 server 全局锁；外部回调持锁会在此失败。
                                probe.submit(() -> owner.get().address()).get(2, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new IllegalStateException("global lock held during external close", e);
                            }
                            throw new IllegalStateException(closeCount.incrementAndGet() == 1
                                ? "first source close failed" : "second source close failed");
                        }
                    };
                }); var client = HttpClient.newHttpClient()) {
            owner.set(server);
            var first = client.send(request(server, EVENTS + "&lastSeq=2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            var second = client.send(request(server, EVENTS + "&lastSeq=2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var firstBody = first.body(); var secondBody = second.body()) {
                for (Cursor cursor : source.cursors) {
                    assertThat(cursor.waiting.await(2, TimeUnit.SECONDS)).isTrue();
                }
                assertThatThrownBy(server::close).isInstanceOf(IllegalStateException.class)
                    .hasMessage("first source close failed")
                    .satisfies(error -> assertThat(error.getSuppressed()).singleElement()
                        .satisfies(suppressed -> assertThat(suppressed).hasMessage("second source close failed")));
                assertThat(closeCount.get()).isEqualTo(2);
                for (Cursor cursor : source.cursors) {
                    assertThat(cursor.closed.await(2, TimeUnit.SECONDS)).isTrue();
                    cursor.reader.join(2000);
                    assertThat(cursor.reader.isAlive()).isFalse();
                }
            }
        }
    }

    @Test
    void nullOpenedIsNamed500NotSilentDisabledFallback() throws Exception {
        try (var server = server((session, after) -> null); var client = HttpClient.newHttpClient()) {
            var response = get(client, server, EVENTS);
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("SSE source open failed");
            assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain; charset=utf-8");
        }
    }

    @Test
    void openReturningAfterServerCloseCannotLeakRegistration() throws Exception {
        Fixture source = new Fixture(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var server = server((session, after) -> {
            entered.countDown();
            // 模拟不可中断的外部 open；close 后仍返回资源，检验迟到登记。
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return source.open(session, after);
        }); var client = HttpClient.newHttpClient(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> get(client, server, EVENTS));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                server.close();
            } finally {
                release.countDown();
            }
            assertThat(source.opened.await(2, TimeUnit.SECONDS)).isTrue();
            Cursor cursor = source.cursors.getFirst();
            assertThat(cursor.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cursor.reader).isNull();
            try {
                pending.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(IOException.class);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void missingCodecIsAnExplicitMidstreamFailureAfter200() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = new RpcHttpServer((method, params) -> null, source, new SessionCodecRegistry());
                var client = HttpClient.newHttpClient()) {
            server.start(0);
            var response = client.send(request(server, EVENTS + "&lastSeq=2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                source.append();
                assertThat(source.cursors.getFirst().closed.await(2, TimeUnit.SECONDS)).isTrue();
                try {
                    assertThat(body.readAllBytes()).isEmpty();
                } catch (IOException e) {
                    // TCP 终结可能表现为 EOF 或 IOException；JDK 包装层措辞不是契约。
                    assertThat(e.getMessage()).isNotBlank();
                }
            }
        }
    }

    @Test
    void idleConnectionWritesCommentAtFifteenSecondsWithoutId() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); var client = HttpClient.newHttpClient();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            var response = client.send(request(server, EVENTS + "&lastSeq=2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body(); var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(pool.submit(reader::readLine).get(20, TimeUnit.SECONDS)).isEqualTo(": keepalive");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(14));
                assertThat(reader.readLine()).isEmpty();
            }
        }
    }

    @Test
    void keepaliveDeadlineIsNotPostponedByLiveTraffic() throws Exception {
        Fixture source = new Fixture(true);
        try (var server = server(source); var client = HttpClient.newHttpClient();
                var timer = Executors.newSingleThreadScheduledExecutor();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = client.send(request(server, EVENTS + "&lastSeq=2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body(); var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                timer.scheduleAtFixedRate(source::append, 0, 250, TimeUnit.MILLISECONDS);
                var comment = pool.submit(() -> {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(":")) {
                            return line;
                        }
                    }
                    return "EOF";
                });
                assertThat(comment.get(20, TimeUnit.SECONDS)).isEqualTo(": keepalive");
            }
        }
    }

    @Test
    void lifecycleAndHalfAssemblyFailLoud() throws Exception {
        assertThatThrownBy(() -> new RpcHttpServer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RpcHttpServer((method, params) -> null, null, SseEncoderTest.codecs()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RpcHttpServer((method, params) -> null, new Fixture(false), null))
            .isInstanceOf(NullPointerException.class);
        try (var server = new RpcHttpServer((method, params) -> null)) {
            assertThatThrownBy(server::address).isInstanceOf(IllegalStateException.class);
            server.start(0);
            assertThatThrownBy(() -> server.start(0)).isInstanceOf(IllegalStateException.class);
            server.close();
            assertThatThrownBy(() -> server.start(0)).isInstanceOf(IllegalStateException.class);
        }
    }

    private static RpcHttpServer server(SseEventSource source) throws IOException {
        var server = new RpcHttpServer((method, params) -> null, source, SseEncoderTest.codecs());
        server.start(0);
        return server;
    }

    private static HttpRequest.Builder request(RpcHttpServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path))
            .timeout(Duration.ofSeconds(4));
    }

    private static HttpResponse<String> get(HttpClient client, RpcHttpServer server, String path) throws Exception {
        return client.send(request(server, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> ids(String body) {
        return body.lines().filter(line -> line.startsWith("id:")).toList();
    }

    private static String readId(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("id:")) {
                return line;
            }
        }
        throw new IOException("unexpected EOF");
    }

    private static void openWithMissingBody(Socket socket, RpcHttpServer server) throws IOException {
        socket.setSoTimeout(2000);
        socket.getOutputStream().write(("GET " + EVENTS + "&lastSeq=2 HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + server.address().getPort() + "\r\n"
            + "Content-Length: 1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        // 不发送承诺的一个字节，且一直保留 socket 到回收断言之后。
        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        assertThat(input.readLine()).isEqualTo("HTTP/1.1 200 OK");
        String line;
        do {
            line = input.readLine();
            assertThat(line).as("complete response headers").isNotNull();
        } while (!line.isEmpty());
    }

    private static void assertExited(Thread thread, String reason) throws InterruptedException {
        thread.join(2000);
        assertThat(thread.isAlive()).as("%s; stack=%s", reason, List.of(thread.getStackTrace())).isFalse();
    }

    private static void assertReclaimed(Cursor cursor) throws InterruptedException {
        assertThat(cursor.closed.await(2, TimeUnit.SECONDS)).as("source closed").isTrue();
        assertExited(cursor.reader, "source reader exited");
        assertExited(cursor.writer, "HTTP writer exited without server.close");
        assertThat(cursor.closeCalls.get()).isEqualTo(1);
    }

    private static void assertSocketClosed(Socket socket) throws IOException {
        try {
            socket.getInputStream().readAllBytes();
        } catch (SocketException e) {
            // TCP reset 与 EOF 都是已终结；SocketTimeoutException 不在此分支，仍必红。
            assertThat(e.getMessage()).isNotBlank();
        }
    }

    private static void reclaimFixture(Fixture source) throws InterruptedException {
        source.finish();
        for (Cursor cursor : source.cursors) {
            if (cursor.reader != null) {
                cursor.reader.interrupt();
                cursor.reader.join(2000);
            }
            cursor.writer.interrupt();
            cursor.writer.join(2000);
        }
    }

    private static String raw(RpcHttpServer server, String path, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n" + headers
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 有记录的日志夹具；同一锁内确定 head 与下标，append 不会落在订阅空窗。 */
    private static final class Fixture implements SseEventSource {
        private final List<SseFrame> log = new ArrayList<>();
        private final List<Cursor> cursors = new CopyOnWriteArrayList<>();
        private final List<Long> afters = new CopyOnWriteArrayList<>();
        private final List<Id<Session>> sessions = new CopyOnWriteArrayList<>();
        private final AtomicInteger opens = new AtomicInteger();
        private final CountDownLatch opened = new CountDownLatch(1);
        private boolean finished;

        Fixture(boolean live) {
            finished = !live;
            for (int i = 0; i < 3; i++) {
                log.add(SseEncoderTest.frame(i));
            }
        }

        @Override
        public synchronized Opened open(Id<Session> session, long after) {
            opens.incrementAndGet();
            sessions.add(session);
            afters.add(after);
            Cursor cursor = new Cursor(this, log.size() - 1, after + 1);
            cursors.add(cursor);
            opened.countDown();
            return cursor;
        }

        synchronized void append() {
            log.add(SseEncoderTest.frame(log.size()));
            notifyAll();
        }

        synchronized void overflow() {
            log.add(new SseFrame.Control(JsonValue.object().set("text", "x".repeat(8 * 1024 * 1024)).build()));
            notifyAll();
        }

        synchronized void finish() {
            finished = true;
            notifyAll();
        }
    }

    private static final class Cursor implements SseEventSource.Opened {
        private final Fixture source;
        private final Thread writer = Thread.currentThread();
        private final long head;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile Thread reader;
        private long index;
        private boolean ended;

        Cursor(Fixture source, long head, long index) {
            this.source = source;
            this.head = head;
            this.index = index;
        }

        @Override
        public long head() {
            return head;
        }

        @Override
        public Optional<SseFrame> next() throws InterruptedException {
            reader = Thread.currentThread();
            synchronized (source) {
                while (!ended && index >= source.log.size() && !source.finished) {
                    waiting.countDown();
                    source.wait();
                }
                return ended || index >= source.log.size() ? Optional.empty() : Optional.of(source.log.get((int) index++));
            }
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            synchronized (source) {
                ended = true;
                source.notifyAll();
            }
            closed.countDown();
        }
    }
}
