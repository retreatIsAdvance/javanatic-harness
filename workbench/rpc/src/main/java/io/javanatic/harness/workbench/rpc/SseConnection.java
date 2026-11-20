package io.javanatic.harness.workbench.rpc;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;

/** 一个 source reader 与一个 HTTP writer；socket 不参与生产者队列锁。 */
final class SseConnection implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(SseConnection.class.getName());
    private static final Duration KEEPALIVE = Duration.ofSeconds(15);

    private final HttpExchange exchange;
    private final SseEventSource.Opened opened;
    private final SseEncoder encoder;
    private final SseBuffer buffer = new SseBuffer();
    private final Thread reader;
    private Thread writer;
    private volatile boolean closed;
    private long lastSeq;

    SseConnection(HttpExchange exchange, SseEventSource.Opened opened, SseEncoder encoder, long afterSeq) {
        this.exchange = exchange;
        this.opened = opened;
        this.encoder = encoder;
        this.lastSeq = afterSeq;
        reader = Thread.ofVirtual().name("sse-reader").unstarted(this::read);
    }

    void run() {
        synchronized (this) {
            if (writer != null) {
                throw new IllegalStateException("SSE connection already started");
            }
            if (closed) {
                return;
            }
            writer = Thread.currentThread();
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            synchronized (this) {
                if (closed) {
                    return;
                }
                reader.start();
            }
            write(exchange.getResponseBody());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "SSE socket closed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "SSE writer failed after 200", e);
        } finally {
            closeAtBoundary();
        }
    }

    private void write(OutputStream output) throws IOException, InterruptedException {
        long heartbeat = System.nanoTime() + KEEPALIVE.toNanos();
        while (!closed) {
            Duration remaining = Duration.ofNanos(Math.max(0, heartbeat - System.nanoTime()));
            switch (buffer.poll(remaining)) {
                case SseBuffer.Data data -> output.write(data.bytes());
                case SseBuffer.Idle ignored -> {
                    // deadline 到期，统一在下面写无 id 的注释，不被业务帧重置。
                }
                case SseBuffer.End ignored -> {
                    return;
                }
            }
            if (System.nanoTime() >= heartbeat) {
                output.write(SseEncoder.keepalive());
                heartbeat = System.nanoTime() + KEEPALIVE.toNanos();
            }
            output.flush();
        }
    }

    private void read() {
        try {
            while (!closed) {
                Optional<SseFrame> next = opened.next();
                if (next.isEmpty()) {
                    buffer.finish();
                    return;
                }
                SseFrame frame = next.orElseThrow();
                if (frame instanceof SseFrame.Persisted persisted) {
                    if (persisted.logged().seq() <= lastSeq) {
                        throw new IllegalStateException("SSE source sequence is not strictly increasing");
                    }
                    lastSeq = persisted.logged().seq();
                }
                if (!buffer.offer(encoder.encode(frame))) {
                    LOG.log(System.Logger.Level.DEBUG, "SSE buffer overflow or connection revoked");
                    closeAtBoundary();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeAtBoundary();
        } catch (RuntimeException e) {
            // 200 已发送，不能再写 HTTP 错误或伪造 R1 帧：具名日志并主动终结连接。
            LOG.log(System.Logger.Level.ERROR, "SSE source/codec failed after 200", e);
            closeAtBoundary();
        }
    }

    private void closeAtBoundary() {
        try {
            close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "SSE close failed", e);
        }
    }

    @Override
    public void close() {
        Thread activeWriter;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            activeWriter = writer;
        }
        buffer.close();
        reader.interrupt();
        if (activeWriter != null && activeWriter != Thread.currentThread()) {
            activeWriter.interrupt();
        }
        // 先唤醒所有等待者，再做外部回调；任何回调失败仍必须关闭 exchange。
        try {
            opened.close();
        } finally {
            if (activeWriter == Thread.currentThread() && !activeWriter.isInterrupted()) {
                closeFromWriter();
            } else {
                exchange.close();
            }
        }
    }

    private void closeFromWriter() {
        // EOF writer 是自有任务：仅请求体排水可中断，不能污染外部 close 调用者或 source 回调。
        Thread.currentThread().interrupt();
        try {
            exchange.getRequestBody().close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "SSE unread request body closed", e);
        } finally {
            // 无请求体的正常 EOF 仍需写出末尾 chunk；带着中断写会截断合法响应。
            Thread.interrupted();
            exchange.close();
        }
    }
}
