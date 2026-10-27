package io.javanatic.harness.web.search.deepseek;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebSearchRequest;
import io.javanatic.harness.web.WebSearchResult;
import io.javanatic.harness.web.WebSearchService;

/**
 * DeepSeek 原生搜索 (dsh {@code DeepSeekSearchProvider} 同口径)：一次调用 =
 * 一次 Anthropic 兼容 Messages 请求 + 原生 {@code web_search_20250305} 工具。
 *
 * <p>固定 {@link HttpClient.Redirect#NEVER}（凭据不随 3xx）；连接超时 10s、请求
 * 截止 {@code timeoutSeconds}（含响应体读取）、响应体 1 MiB 上限。取消经
 * {@code onCancel} 取消 future/关闭响应流，最终穿透 {@code AbortedException}。
 *
 * <p>错误消息只含状态码与有界、脱敏的 provider 文本；请求头与 key 绝不回显。
 * 响应只经 {@link AnthropicSearchMapper} 取结构化来源——无结果块即错，不做散文刮取。
 */
final class DeepSeekSearchProvider implements WebSearchService {

    /** 单次响应体上限（字节；超限视为 provider 错误）。 */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** 错误消息中 provider 文本的字符上限。 */
    static final int MAX_ERROR_DETAIL_CHARS = 512;

    /** 建连超时（与请求截止分离）。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    private static final int NON_JSON_DETAIL_BYTES = 1024;

    /** 非 2xx 诊断体只读这么多：保住状态码，避免大错误体吃掉内存/时间。 */
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;

    /** 即使 provider 回显了未知 key，也按常见形态再遮一层。 */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeepSeekSearchConfig config;
    private final String apiKey;
    private final HttpClient client;
    private final ResponseObserver responseObserver;

    DeepSeekSearchProvider(DeepSeekSearchConfig config, String apiKey, HttpClient client) {
        this(config, apiKey, client, (status, bytes) -> { });
    }

    /**
     * @param config 已解析配置
     * @param apiKey 已解析 key（非空；控制字符拒绝）
     * @param client HTTP 客户端（生产为默认信任面 + Redirect.NEVER；测试注入自签信任面）
     * @param responseObserver 响应体积观测（探针取证；生产 no-op）
     */
    DeepSeekSearchProvider(DeepSeekSearchConfig config, String apiKey, HttpClient client,
                           ResponseObserver responseObserver) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.responseObserver = Objects.requireNonNull(responseObserver, "responseObserver");
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("web-search-deepseek: api key must be non-empty");
        }
        for (int index = 0; index < apiKey.length(); index++) {
            char ch = apiKey.charAt(index);
            if (ch < 0x20 || ch == 0x7f) {
                throw new IllegalArgumentException(
                    "web-search-deepseek: api key contains control characters");
            }
        }
        this.apiKey = apiKey;
    }

    @Override
    public String id() {
        return "deepseek";
    }

    @Override
    public String displayName() {
        return "DeepSeek (" + config.model() + ")";
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty();
    }

    @Override
    public WebSearchResult search(WebSearchRequest request, AbortSignal signal) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(signal, "signal");
        signal.checkAbort();
        HttpRequest httpRequest = request(request);
        long deadline = System.nanoTime() + config.timeoutSeconds() * 1_000_000_000L;
        CompletableFuture<HttpResponse<InputStream>> future;
        try {
            future = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException e) {
            signal.checkAbort();
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek search request could not be dispatched: " + describe(e), e);
        }
        signal.onCancel(() -> future.cancel(true));
        HttpResponse<InputStream> response;
        try {
            response = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            signal.checkAbort();
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR, "DeepSeek search interrupted", e);
        } catch (CancellationException e) {
            signal.checkAbort();
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek search request was cancelled without an abort signal", e);
        } catch (ExecutionException e) {
            signal.checkAbort();
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw timeout();
            }
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek search request failed: " + describe(cause), cause);
        }
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            signal.onCancel(() -> closeQuietly(body));
            signal.checkAbort();
            BodyWatchdog watchdog = new BodyWatchdog(body, deadline - System.nanoTime());
            try {
                return readResult(body, signal, watchdog, status, request.maxResults());
            } finally {
                watchdog.close();
            }
        } catch (IOException e) {
            signal.checkAbort();
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek search response could not be closed: " + describe(e), e);
        }
    }

    private HttpRequest request(WebSearchRequest request) {
        return HttpRequest.newBuilder(config.endpoint())
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .header("x-api-key", apiKey)
            .header("authorization", "Bearer " + apiKey)
            .header("anthropic-version", DeepSeekWire.API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "application/json")
            .header("user-agent", DeepSeekWire.USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(
                DeepSeekWire.requestBody(config, request.query())))
            .build();
    }

    private WebSearchResult readResult(InputStream body, AbortSignal signal, BodyWatchdog watchdog,
                                       int status, int maxResults) throws IOException {
        if (status < 200 || status >= 300) {
            String detail;
            try {
                byte[] errorBody = readErrorBody(body, signal);
                responseObserver.onResponse(status, errorBody.length);
                detail = errorDetail(errorBody);
            } catch (IOException e) {
                signal.checkAbort();
                if (watchdog.expired()) {
                    throw timeout();
                }
                throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                    "DeepSeek API error (HTTP " + status + "); response body unreadable: "
                        + describe(e), e);
            }
            String message = "DeepSeek API error (HTTP " + status + ")";
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                detail.isEmpty() ? message : message + ": " + detail);
        }
        byte[] bytes;
        try {
            bytes = readCapped(body, signal);
        } catch (IOException e) {
            signal.checkAbort();
            if (watchdog.expired()) {
                throw timeout();
            }
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek search response read failed: " + describe(e), e);
        }
        responseObserver.onResponse(status, bytes.length);
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (IOException e) {
            signal.checkAbort();
            throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                "DeepSeek returned an unprocessable response body: "
                    + sanitize(String.valueOf(e.getMessage())), e);
        }
        return AnthropicSearchMapper.map(root, maxResults);
    }

    /**
     * 读响应体，超过 1 MiB 即 {@link WebErrorCode#WEB_PROVIDER_ERROR}；取消在每个
     * 读间隙检查，超时由 {@link BodyWatchdog} 关闭流唤醒阻塞读。
     */
    private static byte[] readCapped(InputStream body, AbortSignal signal) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int read;
        while ((read = body.read(buffer)) != -1) {
            signal.checkAbort();
            if (out.size() + read > MAX_RESPONSE_BYTES) {
                throw new WebException(WebErrorCode.WEB_PROVIDER_ERROR,
                    "DeepSeek response exceeds " + MAX_RESPONSE_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 读非 2xx 诊断体的前缀（至多 {@value #MAX_ERROR_BODY_BYTES} 字节），不因超大报错。 */
    private static byte[] readErrorBody(InputStream body, AbortSignal signal) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int read;
        while ((read = body.read(buffer)) != -1) {
            signal.checkAbort();
            int room = MAX_ERROR_BODY_BYTES - out.size();
            if (room <= 0) {
                break;
            }
            int keep = Math.min(read, room);
            out.write(buffer, 0, keep);
            if (keep < read) {
                break;
            }
        }
        return out.toByteArray();
    }

    private String errorDetail(byte[] bytes) {
        String detail = null;
        try {
            JsonNode root = MAPPER.readTree(bytes);
            if (root != null && root.isObject()) {
                JsonNode error = root.get("error");
                if (error != null) {
                    if (error.isTextual()) {
                        detail = error.asText();
                    } else if (error.isObject() && error.path("message").isTextual()) {
                        detail = error.path("message").asText();
                    }
                }
                if (detail == null && root.path("message").isTextual()) {
                    detail = root.path("message").asText();
                }
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体（网关 5xx/429 常见）：有界原文已足够诊断
        }
        if (detail == null) {
            detail = new String(bytes, 0, Math.min(bytes.length, NON_JSON_DETAIL_BYTES),
                StandardCharsets.UTF_8);
        }
        return sanitize(detail);
    }

    /** 去 key、折叠控制字符/空白并截断；provider 文本只作诊断，不进结果。 */
    private String sanitize(String text) {
        String redacted = text.replace(apiKey, "****");
        redacted = API_KEY_PATTERN.matcher(redacted).replaceAll("sk-****");
        StringBuilder out = new StringBuilder(Math.min(redacted.length(), MAX_ERROR_DETAIL_CHARS));
        boolean pendingSpace = false;
        for (int index = 0; index < redacted.length() && out.length() < MAX_ERROR_DETAIL_CHARS; index++) {
            char ch = redacted.charAt(index);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(ch);
            }
        }
        String sanitized = out.toString().trim();
        return redacted.length() > MAX_ERROR_DETAIL_CHARS ? sanitized + "…" : sanitized;
    }

    private String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + sanitize(message));
    }

    private WebException timeout() {
        return new WebException(WebErrorCode.WEB_TIMEOUT,
            "DeepSeek search timed out after " + config.timeoutSeconds() + "s (endpoint "
                + config.endpoint().getHost() + ")");
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // 取消/超时收口:关闭失败不掩盖原始错误
        }
    }

    /**
     * 一次已读响应（任意状态）的状态与体积观测（探针取证；生产路径 no-op）。
     * 测试缝边界：不得演化为生产回调面；脱离探针取证用途须回评审。
     */
    @FunctionalInterface
    interface ResponseObserver {

        /**
         * @param status HTTP 状态码
         * @param bytes 实际读入的响应体字节数
         */
        void onResponse(int status, int bytes);
    }

    /**
     * 响应体看门狗：{@code HttpRequest.timeout} 只管到响应头，体读挂死时在剩余
     * 截止时间关闭流唤醒阻塞读；关闭动作幂等。
     */
    private static final class BodyWatchdog {

        private final AtomicBoolean expired = new AtomicBoolean();
        private final Thread thread;

        BodyWatchdog(InputStream body, long remainingNanos) {
            if (remainingNanos <= 0) {
                expired.set(true);
                closeQuietly(body);
                thread = null;
                return;
            }
            long millis = Math.max(1, remainingNanos / 1_000_000L);
            thread = Thread.ofVirtual().name("jh-web-search-body-timeout").start(() -> {
                try {
                    Thread.sleep(millis);
                    expired.set(true);
                    closeQuietly(body);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        boolean expired() {
            return expired.get();
        }

        void close() {
            if (thread == null) {
                return;
            }
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
