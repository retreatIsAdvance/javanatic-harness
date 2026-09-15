package io.javanatic.harness.llm.openai.compat;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmCallException;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** 本地假服务端驱动的 keyless 全覆盖:双形状 SSE/请求形状/参数归一/重试/看门狗/取消/脱敏。 */
class OpenAiCompatAdapterTest {

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<ResponseScript> scripts = new CopyOnWriteArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();

    private record ResponseScript(int status, String retryAfter, List<String> sseLines,
                                  long stallAfterLinesMillis) {}

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
        warmUp();
    }

    /**
     * 冷启动预热:部分平台(本机 macOS 实测)首个请求可耗近 1s(首个 SYN 重传),
     * 撞用例 400ms 的请求超时——预热走真实路径,再清计数与请求记录,断言无感。
     */
    private void warmUp() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder()
                    .uri(URI.create(base() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        }
        hits.set(0);
        requests.clear();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
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

    private OpenAiCompatAdapter adapter() {
        return new OpenAiCompatAdapter(VendorProfile.of(base()), transport());
    }

    private OpenAiCompatAdapter stalledAdapter(long idleMillis) {
        return new OpenAiCompatAdapter(VendorProfile.of(base()),
            new TransportOptions("test-key", Duration.ofSeconds(2), Duration.ofMillis(idleMillis),
                3, Duration.ofMillis(5), Duration.ofMillis(50)));
    }

    private static TransportOptions transport() {
        return new TransportOptions("test-key", Duration.ofSeconds(2), Duration.ofMillis(400),
            3, Duration.ofMillis(5), Duration.ofMillis(50));
    }

    private String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static LlmRequest request() {
        return new LlmRequest("你是测试系统。",
            List.of(UserMessage.of("hi", new MessageSource.User())),
            List.of(new ToolSchema("fs_read", "读文件", "{\"type\":\"object\"}")),
            Map.of("temperature", "0.7", "vendor_extra", "keep"));
    }

    /** OpenAI 分离形状:usage 在 finish 之后的独立空 choices 分块。 */
    private static List<String> separatedSse() {
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

    /** DeepSeek 实测合并形状:结束分块同带 content:""/finish_reason/usage。 */
    private static List<String> mergedSse() {
        return List.of(
            "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"答\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}",
            "[DONE]");
    }

    private ChunkAssembly.Assembled consume(OpenAiCompatAdapter adapter) {
        try (Stream<StreamChunk> chunks = adapter.stream(
                new LlmCallConfig("vendor", "test-model"), request(), AbortSignal.never())) {
            return ChunkAssembly.fold(chunks.toList());
        }
    }

    @Test
    void separatedShapeAssemblesThroughChunkAssembly() throws Exception {
        scripts.add(new ResponseScript(200, null, separatedSse(), 0));
        ChunkAssembly.Assembled assembled = consume(adapter());
        assertThat(assembled.text()).isEqualTo("你");
        assertThat(assembled.toolCalls()).hasSize(1);
        assertThat(assembled.toolCalls().getFirst().arguments()).isEqualTo("{\"path\":\"a\"}");
        assertThat(assembled.usage().inputTokens()).isEqualTo(11);
        assertThat(assembled.finishReason()).isEqualTo(FinishReason.TOOL_USE);
    }

    @Test
    void mergedFinishUsageChunkAssemblesIdentically() {
        scripts.add(new ResponseScript(200, null, mergedSse(), 0));
        ChunkAssembly.Assembled assembled = consume(adapter());
        assertThat(assembled.text()).isEqualTo("答");
        assertThat(assembled.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(assembled.usage().inputTokens()).isEqualTo(3);
        assertThat(assembled.usage().outputTokens()).isEqualTo(4);
    }

    @Test
    void requestShapeSystemFirstToolsAndNumericParams() {
        scripts.add(new ResponseScript(200, null, separatedSse(), 0));
        consume(adapter());
        JsonNode body = requests.getFirst();
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("stream_options").get("include_usage").asBoolean()).isTrue();
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("tools").get(0).get("function").get("parameters")
            .get("type").asText()).isEqualTo("object");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.get("vendor_extra").asText()).isEqualTo("keep");
    }

    @Test
    void profileExtraHeadersAndEndpointAreHonored() throws Exception {
        server.removeContext("/chat/completions");
        server.createContext("/v1/chat", this::handle);
        scripts.add(new ResponseScript(200, null, mergedSse(), 0));
        OpenAiCompatAdapter adapter = new OpenAiCompatAdapter(
            new VendorProfile(base(), "/v1/chat", Map.of("X-Org", "jh")),
            transport());
        consume(adapter);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void retries429WithRetryAfterThenSucceeds() {
        scripts.add(new ResponseScript(429, "0", List.of(), 0));
        scripts.add(new ResponseScript(200, null, separatedSse(), 0));
        assertThat(consume(adapter()).text()).isEqualTo("你");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void retryExhaustsOn5xxFailsLoud() {
        for (int i = 0; i < 3; i++) {
            scripts.add(new ResponseScript(500, null, List.of(), 0));
        }
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmCallException.Kind.SERVER);
                assertThat(e).hasMessageContaining("500");
            });
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void rateLimitExhaustsWithRateLimitKind() {
        for (int i = 0; i < 3; i++) {
            scripts.add(new ResponseScript(429, "0", List.of(), 0));
        }
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmCallException.Kind.RATE_LIMIT));
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void nonRetryable401FailsWithoutRetryAsAuth() {
        scripts.add(new ResponseScript(401, null, List.of(), 0));
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmCallException.Kind.AUTH);
                assertThat(e).hasMessageContaining("401");
            });
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void forbidden403MapsToAuthWithoutRetry() {
        scripts.add(new ResponseScript(403, null, List.of(), 0));
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmCallException.Kind.AUTH));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void other4xxMapsToProtocolWithoutRetry() {
        scripts.add(new ResponseScript(400, null, List.of(), 0));
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmCallException.Kind.PROTOCOL);
                assertThat(e).hasMessageContaining("400");
            });
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void connectionFailureMapsToNetwork() throws Exception {
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        OpenAiCompatAdapter adapter = new OpenAiCompatAdapter(
            VendorProfile.of("http://localhost:" + deadPort), transport());
        assertThatThrownBy(() -> consume(adapter))
            .isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmCallException.Kind.NETWORK));
    }

    @Test
    void malformedSseFailsAsProtocol() {
        scripts.add(new ResponseScript(200, null, List.of("not-json"), 0));
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmCallException.Kind.PROTOCOL);
                assertThat(e).hasMessageContaining("malformed SSE event");
            });
    }

    @Test
    void unknownFinishReasonFailsAsProtocol() {
        scripts.add(new ResponseScript(200, null,
            List.of("{\"choices\":[{\"delta\":{},\"finish_reason\":\"weird\"}]}"), 0));
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmCallException.Kind.PROTOCOL);
                assertThat(e).hasMessageContaining("unknown finish_reason");
            });
    }

    @Test
    void streamEndWithoutFinishFailsAsProtocol() {
        scripts.add(new ResponseScript(200, null,
            List.of("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}"), 0));
        assertThatThrownBy(() -> consume(adapter()))
            .isInstanceOfSatisfying(LlmCallException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmCallException.Kind.PROTOCOL);
                assertThat(e).hasMessageContaining("without finish_reason");
            });
    }

    @Test
    void idleWatchdogCutsStalledStreamAsTimeout() {
        scripts.add(new ResponseScript(200, null,
            List.of("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}"), 5000));
        long start = System.nanoTime();
        assertThatThrownBy(() -> consume(stalledAdapter(300)))
            .isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmCallException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void cancelMidStreamThrowsAborted() throws Exception {
        scripts.add(new ResponseScript(200, null,
            List.of("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}"), 5000));
        OpenAiCompatAdapter adapter = stalledAdapter(10_000);
        Cancellable signal = new Cancellable();
        try (Stream<StreamChunk> chunks = adapter.stream(
                new LlmCallConfig("vendor", "m"), request(), signal)) {
            Iterator<StreamChunk> iterator = chunks.iterator();
            assertThat(iterator.next()).isInstanceOf(StreamChunk.Delta.class);
            signal.cancel();
            assertThatThrownBy(iterator::next).isInstanceOf(AbortedException.class);
        }
    }

    @Test
    void transportOptionsToStringMasksApiKey() {
        String text = new TransportOptions("sk-secret-123", Duration.ofSeconds(1),
            Duration.ofSeconds(1), 2, Duration.ofMillis(1), Duration.ofMillis(2)).toString();
        assertThat(text).doesNotContain("sk-secret-123").contains("****");
    }

    @Test
    void vendorProfileValidatesFields() {
        assertThatThrownBy(() -> VendorProfile.of(""))
            .isInstanceOf(IllegalArgumentException.class);
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
