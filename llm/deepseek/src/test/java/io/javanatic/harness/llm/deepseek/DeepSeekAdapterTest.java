package io.javanatic.harness.llm.deepseek;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.ToolSchema;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地假服务端驱动的 keyless 全覆盖:SSE 组装/请求形状/参数归一/重试/看门狗/取消/脱敏。 */
class DeepSeekAdapterTest {

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<ResponseScript> scripts = new CopyOnWriteArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();

    /** 一次应答脚本:状态、Retry-After、SSE 行(逐行写+flush)或延迟毫秒。 */
    private record ResponseScript(int status, String retryAfter, List<String> sseLines,
                                  long stallAfterLinesMillis) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(readBody(exchange));
        hits.incrementAndGet();
        ResponseScript script = scripts.isEmpty()
            ? new ResponseScript(200, null, List.of(), 0) : scripts.remove(0);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        if (script.retryAfter() != null) {
            exchange.getResponseHeaders().set("Retry-After", script.retryAfter());
        }
        exchange.sendResponseHeaders(script.status(), 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String line : script.sseLines()) {
                out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (script.stallAfterLinesMillis() > 0) {
                sleep(script.stallAfterLinesMillis());
            }
        }
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        return new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private DeepSeekOptions fastOptions() {
        return new DeepSeekOptions(baseUrl(), "test-key", Duration.ofSeconds(2),
            Duration.ofMillis(400), 3, Duration.ofMillis(5), Duration.ofMillis(50));
    }

    private DeepSeekOptions idleOptions(long millis) {
        return new DeepSeekOptions(baseUrl(), "test-key", Duration.ofSeconds(2),
            Duration.ofMillis(millis), 3, Duration.ofMillis(5), Duration.ofMillis(50));
    }

    private static LlmRequest request() {
        return new LlmRequest("你是测试系统。",
            List.of(UserMessage.of("hi", new MessageSource.User())),
            List.of(new ToolSchema("fs_read", "读文件", "{\"type\":\"object\"}")),
            Map.of("temperature", "0.7", "vendor_extra", "keep"));
    }

    private static List<String> happySse() {
        return List.of(
            "{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"fs_read\",\"arguments\":\"\"}}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}",
            "[DONE]");
    }

    @Test
    void happyPathAssemblesThroughChunkAssembly() throws Exception {
        scripts.add(new ResponseScript(200, null, happySse(), 0));
        ChunkAssembly.Assembled assembled = consume(fastOptions());
        assertThat(assembled.text()).isEqualTo("你");
        assertThat(assembled.toolCalls()).hasSize(1);
        assertThat(assembled.toolCalls().getFirst().name()).isEqualTo("fs_read");
        assertThat(assembled.toolCalls().getFirst().arguments()).isEqualTo("{\"path\":\"a\"}");
        assertThat(assembled.usage().inputTokens()).isEqualTo(11);
        assertThat(assembled.usage().outputTokens()).isEqualTo(22);
        assertThat(assembled.finishReason())
            .isEqualTo(FinishReason.TOOL_USE);
    }

    @Test
    void requestShapeSystemFirstToolsAndNumericParams() throws Exception {
        scripts.add(new ResponseScript(200, null, happySse(), 0));
        consume(fastOptions());
        JsonNode body = requests.getFirst();
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("stream_options").get("include_usage").asBoolean()).isTrue();
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(body.get("tools").get(0).get("function").get("name").asText())
            .isEqualTo("fs_read");
        assertThat(body.get("tools").get(0).get("function").get("parameters").get("type").asText())
            .isEqualTo("object");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.get("vendor_extra").asText()).isEqualTo("keep");
        assertThat(body.has("params_placeholder")).isFalse();
    }

    @Test
    void retries429WithRetryAfterThenSucceeds() throws Exception {
        scripts.add(new ResponseScript(429, "0", List.of(), 0));
        scripts.add(new ResponseScript(200, null, happySse(), 0));
        ChunkAssembly.Assembled assembled = consume(fastOptions());
        assertThat(assembled.text()).isEqualTo("你");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void retryExhaustsOn5xxFailsLoud() {
        scripts.add(new ResponseScript(500, null, List.of(), 0));
        scripts.add(new ResponseScript(500, null, List.of(), 0));
        scripts.add(new ResponseScript(500, null, List.of(), 0));
        assertThatThrownBy(() -> consume(fastOptions()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("500");
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void nonRetryable401FailsWithoutRetry() {
        scripts.add(new ResponseScript(401, null, List.of(), 0));
        assertThatThrownBy(() -> consume(fastOptions()))
            .hasMessageContaining("401");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void idleWatchdogCutsStalledStream() {
        scripts.add(new ResponseScript(200, null,
            List.of("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}"), 5000));
        long start = System.nanoTime();
        assertThatThrownBy(() -> consume(idleOptions(300)))
            .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void cancelMidStreamThrowsAborted() throws Exception {
        scripts.add(new ResponseScript(200, null,
            List.of("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}"), 5000));
        DeepSeekAdapter adapter = new DeepSeekAdapter(idleOptions(10_000));
        Cancellable signal = new Cancellable();
        try (Stream<StreamChunk> chunks = adapter.stream(
                new LlmCallConfig("deepseek", "test-model"), request(), signal)) {
            Iterator<StreamChunk> iterator = chunks.iterator();
            assertThat(iterator.next()).isInstanceOf(StreamChunk.Delta.class);
            signal.cancel();
            assertThatThrownBy(iterator::next).isInstanceOf(AbortedException.class);
        }
    }

    @Test
    void optionsToStringMasksApiKey() {
        String text = new DeepSeekOptions(baseUrl(), "sk-secret-value-123",
            Duration.ofSeconds(1), Duration.ofSeconds(1), 2,
            Duration.ofMillis(1), Duration.ofMillis(2)).toString();
        assertThat(text).doesNotContain("sk-secret-value-123").contains("****");
    }

    private ChunkAssembly.Assembled consume(DeepSeekOptions options) {
        DeepSeekAdapter adapter = new DeepSeekAdapter(options);
        try (Stream<StreamChunk> chunks = adapter.stream(
                new LlmCallConfig("deepseek", "test-model"), request(), AbortSignal.never())) {
            return ChunkAssembly.fold(chunks.toList());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 双通道可取消信号(与 AbortController 语义一致)。 */
    private static final class Cancellable implements AbortSignal {
        private volatile boolean cancelled;
        private final List<Runnable> actions = new ArrayList<>();

        @Override
        public void checkAbort() {
            if (cancelled) {
                throw new AbortedException("test-cancel");
            }
        }

        @Override
        public void onCancel(Runnable action) {
            actions.add(action);
        }

        void cancel() {
            cancelled = true;
            actions.forEach(Runnable::run);
        }
    }
}
