package io.javanatic.harness.llm.openai.compat;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.LlmAdapter;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmCallException;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.StreamChunk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * OpenAI 兼容流式适配器：producer 虚拟线程逐行 SSE → 有界队列(64,满则挂起=背压)；
 * consumer 阻塞 Stream 轮询消费。传输韧性:429/5xx/连接错误按指数退避+抖动有界重试
 * (尊重 Retry-After);空闲看门狗掐断挂死连接;取消经 checkAbort 轮询。
 * 厂商差异收敛在 {@link VendorProfile}(baseUrl/端点/附加头)。
 */
public final class OpenAiCompatAdapter implements LlmAdapter {

    private static final int QUEUE_CAPACITY = 64;
    private static final long POLL_SLICE_MS = 100;

    /** 错误体读取上限(分类与诊断摘录足够;防对端超长响应拖住重试路径)。 */
    private static final int ERROR_BODY_CAP = 512;

    /** 溢出厂商信号并集(it10 字符串匹配的原词表,分类上移到知 wire 事实的层)。 */
    private static final List<String> OVERFLOW_MARKERS = List.of(
        "context_length_exceeded", "maximum context", "token limit", "context length");

    private record End() {}
    private record Failed(Throwable cause) {}

    private final VendorProfile profile;
    private final TransportOptions options;
    private final HttpClient http;

    /** @param profile 厂商差异(baseUrl/端点/附加头) @param options 传输韧性参数(含凭据) */
    public OpenAiCompatAdapter(VendorProfile profile, TransportOptions options) {
        this.profile = profile;
        this.options = options;
        this.http = HttpClient.newBuilder().connectTimeout(options.connectTimeout()).build();
    }

    @Override
    public Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        Thread producer = Thread.ofVirtual().name("jh-deepseek-producer").start(() ->
            produce(config, request, signal, queue));
        ChunkIterator iterator = new ChunkIterator(queue, signal);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
            .onClose(producer::interrupt);
    }

    // ────────── producer ──────────

    private void produce(LlmCallConfig config, LlmRequest request, AbortSignal signal,
                         ArrayBlockingQueue<Object> queue) {
        try {
            HttpResponse<InputStream> response = sendWithRetry(config, request, signal);
            pump(response.body(), signal, queue);
        } catch (AbortedException e) {
            // 取消不得伪装成流尾（End 只表干净 EOF）——否则消费侧在 poll 窗口内会静默收口,
            // 落成 "stream ended without a Finish chunk" 的假协议错误而非 aborted
            offer(queue, new Failed(e));
        } catch (IllegalStateException e) {
            // wire 解析失败（畸形 SSE/未知 finish_reason 等）是 seam 词表的 PROTOCOL,
            // 不是内部不变量破损——types 化后经 ChunkIterator 原样surface
            offer(queue, new Failed(new LlmCallException(LlmCallException.Kind.PROTOCOL,
                e.getMessage(), e)));
        } catch (Exception e) {
            offer(queue, new Failed(e));
        }
    }

    /** 传输重试:Kind 可重试者(429/5xx/IOException);退避 = base*2^(n-1)+抖动,封顶 backoffMax。 */
    private HttpResponse<InputStream> sendWithRetry(LlmCallConfig config, LlmRequest request,
                                                    AbortSignal signal) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(profile.baseUrl() + profile.endpointPath()))
            .timeout(options.idleTimeout())
            .header("Authorization", "Bearer " + options.apiKey())
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                RequestBody.build(config, request).toString(), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : profile.extraHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpRequest httpRequest = builder.build();
        LlmCallException last = null;
        for (int attempt = 1; attempt <= options.maxAttempts(); attempt++) {
            signal.checkAbort();
            try {
                HttpResponse<InputStream> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    return response;
                }
                last = httpFailure(response.statusCode(), response.body());
                if (!last.kind().retryable()) {
                    throw last;
                }
                sleep(backoff(attempt, response.headers().firstValue("Retry-After").orElse(null)));
            } catch (IOException e) {
                last = new LlmCallException(LlmCallException.Kind.NETWORK,
                    "deepseek transport failed: " + e.getMessage(), e);
                sleep(backoff(attempt, null));
            }
        }
        throw last == null ? new LlmCallException(LlmCallException.Kind.PROTOCOL, "unreachable") : last;
    }

    /**
     * HTTP 状态 → Kind 词表（401/403 AUTH、429 RATE_LIMIT、5xx SERVER,
     * 400 + 溢出厂商信号 OVERFLOW,其余 PROTOCOL）。溢出分类读错误体——
     * 厂商细节（如 "maximum context length"）停在适配器层,上层只读 kind。
     */
    private static LlmCallException httpFailure(int status, InputStream body) {
        String excerpt = status == 400 ? errorExcerpt(body) : "";
        if (status == 400 && isOverflowSignal(excerpt)) {
            return new LlmCallException(LlmCallException.Kind.OVERFLOW,
                "deepseek http 400: context overflow");
        }
        LlmCallException.Kind kind = switch (status) {
            case 401, 403 -> LlmCallException.Kind.AUTH;
            case 429 -> LlmCallException.Kind.RATE_LIMIT;
            default -> status >= 500 ? LlmCallException.Kind.SERVER : LlmCallException.Kind.PROTOCOL;
        };
        return new LlmCallException(kind, "deepseek http " + status
            + (excerpt.isBlank() ? "" : ": " + excerpt));
    }

    /** 有界读错误体(读失败按无体处理——分类退化为状态码,不吞掉重试路径)。 */
    private static String errorExcerpt(InputStream body) {
        try (body) {
            return new String(body.readNBytes(ERROR_BODY_CAP), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isOverflowSignal(String excerpt) {
        String lower = excerpt.toLowerCase(Locale.ROOT);
        return OVERFLOW_MARKERS.stream().anyMatch(lower::contains);
    }

    /** 指数退避 + 抖动;Retry-After(秒)优先。 */
    private Duration backoff(int attempt, String retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(retryAfterSeconds.trim()));
            } catch (NumberFormatException ignored) {
                // 非数值 Retry-After(HTTP-date)按退避公式处理
            }
        }
        long exponent = 1L << Math.min(attempt - 1, 16);
        long millis = Math.min(options.backoffBase().toMillis() * exponent, options.backoffMax().toMillis());
        return Duration.ofMillis((long) (millis * (0.5 + Math.random() / 2)));
    }

    private void sleep(Duration delay) throws InterruptedException {
        Thread.sleep(delay);
    }

    /**
     * 阻塞逐字节读 + 空闲看门狗:远端 EOF 时 read 返回 -1(JDK 实测);停滞时
     * 看门狗 close() 会唤醒阻塞 read 抛 IOException(JDK 25 实测)——cut 标志
     * 区分「看门狗掐断」与「异常关闭」。wire 顺序(include_usage 下 usage 在
     * finish 之后)与 seam 契约(Finish 恒为最后一块)不同:持有 Finish,流尾补发。
     */
    private void pump(InputStream body, AbortSignal signal, ArrayBlockingQueue<Object> queue)
            throws Exception {
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicBoolean cut = new AtomicBoolean(false);
        Thread watchdog = startWatchdog(body, lastActivity, cut);
        SseDecoder decoder = new SseDecoder();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StreamChunk.Finish heldFinish = null;
        try {
            int b;
            while ((b = body.read()) != -1) {
                signal.checkAbort();
                lastActivity.set(System.nanoTime());
                if (b == '\n') {
                    heldFinish = dispatchLine(line.toByteArray(), decoder, queue, heldFinish);
                    line.reset();
                } else if (b != '\r') {
                    line.write(b);
                }
            }
            if (line.size() > 0) {
                heldFinish = dispatchLine(line.toByteArray(), decoder, queue, heldFinish);
            }
            if (heldFinish == null) {
                // 流正常 EOF 而无 finish_reason:wire 违反 seam 契约(Finish 恒为最后一块),
                // types 化为 PROTOCOL(此前由 ChunkAssembly.fold 以裸 IAE 兜底)
                throw new LlmCallException(LlmCallException.Kind.PROTOCOL,
                    "deepseek stream ended without finish_reason");
            }
            offer(queue, heldFinish);
            offer(queue, new End());
        } catch (IOException e) {
            if (cut.get()) {
                throw new LlmCallException(LlmCallException.Kind.TIMEOUT,
                    "deepseek stream idle over " + options.idleTimeout());
            }
            throw new LlmCallException(LlmCallException.Kind.NETWORK,
                "deepseek stream read failed: " + e.getMessage(), e);
        } finally {
            watchdog.interrupt();
        }
    }

    /** 处理一行 SSE(UTF-8 行尾解码):Finish 返回持有(流尾补发),其余入队。 */
    private static StreamChunk.Finish dispatchLine(byte[] rawLine, SseDecoder decoder,
                                                   ArrayBlockingQueue<Object> queue,
                                                   StreamChunk.Finish heldFinish)
            throws InterruptedException {
        String text = new String(rawLine, StandardCharsets.UTF_8);
        if (!text.startsWith("data:")) {
            return heldFinish;
        }
        for (StreamChunk chunk : decoder.decode(text.substring("data:".length()).trim())) {
            if (chunk instanceof StreamChunk.Finish finish) {
                heldFinish = finish;
            } else {
                offer(queue, chunk);
            }
        }
        return heldFinish;
    }

    /** 空闲看门狗:超时未活动即置 cut 并 close()(实测能唤醒阻塞 read)。 */
    private Thread startWatchdog(InputStream body, AtomicLong lastActivity, AtomicBoolean cut) {
        return Thread.ofVirtual().name("jh-deepseek-watchdog").start(() -> {
            Duration timeout = options.idleTimeout();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 检查周期 = 超时的 1/4,下限 50ms(Duration 重载——毫秒重载吃纳秒会睡走 20 小时)
                    Thread.sleep(timeout.dividedBy(4).compareTo(Duration.ofMillis(50)) < 0
                        ? Duration.ofMillis(50) : timeout.dividedBy(4));
                } catch (InterruptedException e) {
                    return;
                }
                if (System.nanoTime() - lastActivity.get() > timeout.toNanos()) {
                    cut.set(true);
                    try {
                        body.close();
                    } catch (IOException e) {
                        // close 失败无妨:cut 标志已置,阻塞读按超时语义处理
                    }
                    return;
                }
            }
        });
    }

    private static void offer(ArrayBlockingQueue<Object> queue, Object item) {
        try {
            queue.put(item);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ────────── consumer ──────────

    private static final class ChunkIterator implements Iterator<StreamChunk> {
        private final ArrayBlockingQueue<Object> queue;
        private final AbortSignal signal;
        private StreamChunk next;

        ChunkIterator(ArrayBlockingQueue<Object> queue, AbortSignal signal) {
            this.queue = queue;
            this.signal = signal;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            try {
                while (true) {
                    signal.checkAbort();
                    Object item = queue.poll(POLL_SLICE_MS, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        continue;
                    }
                    if (item instanceof End) {
                        return false;
                    }
                    if (item instanceof Failed failed) {
                        throw failed.cause() instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException("deepseek stream failed", failed.cause());
                    }
                    if (item instanceof StreamChunk chunk) {
                        next = chunk;
                        return true;
                    }
                    throw new IllegalStateException("unknown channel item: " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("deepseek consumer interrupted", e);
            }
        }

        @Override
        public StreamChunk next() {
            if (!hasNext()) {
                throw new NoSuchElementException("stream ended");
            }
            StreamChunk chunk = next;
            next = null;
            return chunk;
        }
    }
}
