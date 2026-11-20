package io.javanatic.harness.workbench.rpc;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.javanatic.harness.session.persistence.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(8)
class SseConnectionTest {
    @Test
    void blockedWriterCannotBlockReaderAndSixtyFifthQueuedFrameClosesEverything() throws Exception {
        overflow(65, "small");
    }

    @Test
    void encodedByteBudgetAlsoClosesBlockedWriterAndSource() throws Exception {
        overflow(3, "x".repeat(4 * 1024 * 1024));
    }

    @Test
    void oversizedFrameClosesWithoutWritingIt() throws Exception {
        Exchange exchange = new Exchange(false);
        Source source = new Source(List.of(control("x".repeat(8 * 1024 * 1024))));
        try (var connection = new SseConnection(exchange, source, new SseEncoder(SseEncoderTest.codecs()), -1)) {
            runWriter(connection);
            assertThat(source.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(exchange.closed.get()).isEqualTo(1);
            assertThat(exchange.output.size()).isZero();
        }
    }

    @Test
    void eofDrainsFramesBeforeClose() throws Exception {
        Exchange exchange = new Exchange(false);
        Source source = new Source(List.of(SseEncoderTest.frame(0), SseEncoderTest.frame(1)));
        try (var connection = new SseConnection(exchange, source, new SseEncoder(SseEncoderTest.codecs()), -1)) {
            runWriter(connection);
            assertThat(exchange.output.toString().lines().filter(line -> line.startsWith("id:")))
                .containsExactly("id: 0", "id: 1");
            assertThat(source.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(exchange.closed.get()).isEqualTo(1);
        }
    }

    @Test
    void eofCloseDoesNotInterruptSourceCallbackOrLeaveWriterInterrupted() throws Exception {
        assertWriterInterruptPreserved(false);
    }

    @Test
    void closePreservesAnAlreadyInterruptedWriter() throws Exception {
        assertWriterInterruptPreserved(true);
    }

    private static void assertWriterInterruptPreserved(boolean initiallyInterrupted) throws Exception {
        Exchange exchange = new Exchange(false);
        AtomicReference<Boolean> callbackInterrupted = new AtomicReference<>();
        AtomicReference<Boolean> writerInterrupted = new AtomicReference<>();
        Source source = new Source(List.of()) {
            @Override
            public void close() {
                callbackInterrupted.set(Thread.currentThread().isInterrupted());
                super.close();
            }
        };
        try (var connection = new SseConnection(exchange, source, new SseEncoder(SseEncoderTest.codecs()), -1)) {
            Thread writer = Thread.ofVirtual().start(() -> {
                if (initiallyInterrupted) {
                    Thread.currentThread().interrupt();
                }
                connection.run();
                writerInterrupted.set(Thread.currentThread().isInterrupted());
            });
            try {
                writer.join(2000);
                assertThat(writer.isAlive()).isFalse();
                assertThat(callbackInterrupted.get()).isEqualTo(initiallyInterrupted);
                assertThat(writerInterrupted.get()).isEqualTo(initiallyInterrupted);
                assertThat(exchange.closed.get()).isEqualTo(1);
            } finally {
                connection.close();
                writer.interrupt();
                writer.join(2000);
            }
        }
    }

    @Test
    void explicitCloseInterruptsBlockedReaderAndWriterWithoutAnotherWrite() throws Exception {
        Exchange exchange = new Exchange(true);
        CountDownLatch nextEntered = new CountDownLatch(1);
        CountDownLatch nextExited = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        Source source = new Source(List.of(control("first"))) {
            @Override
            public Optional<SseFrame> next() throws InterruptedException {
                reader.set(Thread.currentThread());
                if (index.get() == 0) {
                    return super.next();
                }
                nextEntered.countDown();
                try {
                    closed.await();
                    return Optional.empty();
                } finally {
                    nextExited.countDown();
                }
            }
        };
        try (var connection = new SseConnection(exchange, source, new SseEncoder(SseEncoderTest.codecs()), -1)) {
            Thread writer = Thread.ofVirtual().start(connection::run);
            try {
                assertThat(exchange.writeEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(nextEntered.await(2, TimeUnit.SECONDS)).isTrue();
                connection.close();
                connection.close();
                assertThat(nextExited.await(2, TimeUnit.SECONDS)).isTrue();
                writer.join(2000);
                reader.get().join(2000);
                assertThat(writer.isAlive()).isFalse();
                assertThat(reader.get().isAlive()).isFalse();
                assertThat(source.closeCalls.get()).isEqualTo(1);
                assertThat(exchange.closed.get()).isEqualTo(1);
            } finally {
                connection.close();
                writer.interrupt();
                writer.join(2000);
            }
        }
    }

    @Test
    void sourceCloseFailureStillClosesExchange() {
        Exchange exchange = new Exchange(false);
        Source source = new Source(List.of()) {
            @Override
            public void close() {
                super.close();
                throw new IllegalStateException("source close failure");
            }
        };
        var connection = new SseConnection(exchange, source, new SseEncoder(SseEncoderTest.codecs()), -1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThatThrownBy(connection::close).isInstanceOf(IllegalStateException.class).hasMessage("source close failure");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(exchange.closed.get()).isEqualTo(1);
        assertThat(source.closeCalls.get()).isEqualTo(1);
        connection.close();
    }

    private static void overflow(int frames, String payload) throws Exception {
        Exchange exchange = new Exchange(true);
        AtomicReference<Thread> reader = new AtomicReference<>();
        SseFrame data = control(payload);
        Source source = new Source(List.of()) {
            @Override
            public Optional<SseFrame> next() throws InterruptedException {
                reader.set(Thread.currentThread());
                int next = index.getAndIncrement();
                if (next == 0) {
                    return Optional.of(control("first"));
                }
                if (!exchange.writeEntered.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("writer did not enter");
                }
                if (next <= frames) {
                    return Optional.of(data);
                }
                closed.await();
                return Optional.empty();
            }
        };
        try (var connection = new SseConnection(exchange, source, new SseEncoder(SseEncoderTest.codecs()), -1)) {
            Thread writer = Thread.ofVirtual().start(connection::run);
            try {
                assertThat(source.closed.await(4, TimeUnit.SECONDS)).isTrue();
                writer.join(2000);
                reader.get().join(2000);
                assertThat(writer.isAlive()).isFalse();
                assertThat(reader.get().isAlive()).isFalse();
                assertThat(reader.get().isVirtual()).isTrue();
                assertThat(exchange.closed.get()).isEqualTo(1);
                assertThat(source.closeCalls.get()).isEqualTo(1);
                assertThat(exchange.output.size()).isZero();
            } finally {
                connection.close();
                writer.interrupt();
                writer.join(2000);
            }
        }
    }

    private static void runWriter(SseConnection connection) throws InterruptedException {
        Thread writer = Thread.ofVirtual().start(connection::run);
        try {
            writer.join(2000);
            assertThat(writer.isAlive()).isFalse();
        } finally {
            connection.close();
            writer.interrupt();
            writer.join(2000);
        }
    }

    private static SseFrame control(String text) {
        return new SseFrame.Control(JsonValue.object().set("text", text).build());
    }

    private static class Source implements SseEventSource.Opened {
        private final List<SseFrame> frames;
        final AtomicInteger index = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();

        Source(List<SseFrame> frames) {
            this.frames = frames;
        }

        @Override
        public long head() {
            return -1;
        }

        @Override
        public Optional<SseFrame> next() throws InterruptedException {
            int next = index.getAndIncrement();
            return next < frames.size() ? Optional.of(frames.get(next)) : Optional.empty();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }
    }

    /** 可确定阻塞在 write 的 HTTP 边界，不依赖操作系统发送缓冲大小。 */
    private static final class Exchange extends HttpExchange {
        private final Headers headers = new Headers();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger closed = new AtomicInteger();
        private final boolean blocked;

        Exchange(boolean blocked) {
            this.blocked = blocked;
        }

        @Override
        public Headers getRequestHeaders() { return headers; }
        @Override
        public Headers getResponseHeaders() { return headers; }
        @Override
        public URI getRequestURI() { return URI.create("/events?session=s1"); }
        @Override
        public String getRequestMethod() { return "GET"; }
        @Override
        public HttpContext getHttpContext() { return null; }
        @Override
        public void close() {
            closed.incrementAndGet();
            released.countDown();
        }
        @Override
        public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override
        public OutputStream getResponseBody() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    writeEntered.countDown();
                    if (blocked) {
                        try {
                            released.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("writer interrupted", e);
                        }
                        throw new IOException("exchange closed");
                    }
                    output.write(value);
                }
            };
        }
        @Override
        public void sendResponseHeaders(int code, long length) { }
        @Override
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override
        public int getResponseCode() { return 200; }
        @Override
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override
        public String getProtocol() { return "HTTP/1.1"; }
        @Override
        public Object getAttribute(String name) { return null; }
        @Override
        public void setAttribute(String name, Object value) { }
        @Override
        public void setStreams(InputStream input, OutputStream output) { }
        @Override
        public HttpPrincipal getPrincipal() { return null; }
    }
}
