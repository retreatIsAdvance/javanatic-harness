package io.javanatic.harness.web.search.deepseek;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;

/**
 * 停 2 搜索夹具：运行时 keytool 生成自签 PKCS#12，回环 {@link HttpsServer}
 * 记录请求头/体并按脚本响应。生产配置只允许 https，所以夹具必须真 TLS。
 */
final class SearchFixture implements AutoCloseable {

    /** 证书口令（测试专用）。 */
    static final char[] PASSWORD = "changeit".toCharArray();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpsServer server;
    private final SSLContext clientContext;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final CountDownLatch firstRequest = new CountDownLatch(1);

    private SearchFixture(Path dir) {
        try {
            SSLContext[] contexts = contexts(dir);
            server = HttpsServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(contexts[0]));
            server.createContext("/anthropic/v1/messages", this::handle);
            server.setExecutor(command -> Thread.ofVirtual().start(command));
            clientContext = contexts[1];
            responder.set(json(200, """
                {"content": [{"type": "web_search_tool_result", "content": [
                  {"type": "web_search_result", "url": "https://default.test"}
                ]}]}
                """));
            server.start();
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("cannot start search fixture", e);
        }
    }

    /**
     * @param dir 临时目录（存放运行时生成的 keystore）
     * @return 已监听回环端口的 HTTPS 夹具
     */
    static SearchFixture start(Path dir) {
        return new SearchFixture(dir);
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** @return 指向夹具的 Messages 基址（{@code /messages} 由 provider 追加） */
    String baseUrl() {
        return "https://127.0.0.1:" + port() + "/anthropic/v1";
    }

    /** @return 信任夹具自签证书、固定 Redirect.NEVER 的测试客户端 */
    java.net.http.HttpClient client() {
        return java.net.http.HttpClient.newBuilder()
            .sslContext(clientContext)
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();
    }

    void respond(Responder next) {
        responder.set(next);
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    RecordedRequest lastRequest() {
        List<RecordedRequest> all = requests();
        if (all.isEmpty()) {
            throw new AssertionError("fixture received no request");
        }
        return all.get(all.size() - 1);
    }

    boolean awaitFirstRequest(Duration timeout) {
        try {
            return firstRequest.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new RecordedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
            firstRequest.countDown();
            responder.get().respond(exchange);
        } catch (IOException ignored) {
            // 客户端取消/超时/超限主动断开:夹具不承担断言职责
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 响应脚本。 */
    @FunctionalInterface
    interface Responder {
        void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    /** JSON 响应。 */
    static Responder json(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    /** 空响应（重定向等）。 */
    static Responder empty(int status) {
        return exchange -> exchange.sendResponseHeaders(status, -1);
    }

    /** 纯文本响应（非 JSON 错误体用例）。 */
    static Responder text(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    /** 延迟后用给定脚本响应（超时/取消用例）。 */
    static Responder delayed(Duration delay, Responder then) {
        return exchange -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            then.respond(exchange);
        };
    }

    /** 一次请求记录。 */
    record RecordedRequest(String method, String path, Headers headers, byte[] body) {

        String header(String name) {
            return headers.getFirst(name);
        }

        JsonNode json() throws IOException {
            return MAPPER.readTree(body);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** 可取消信号（取消同步触发已注册动作）。 */
    static final class TestSignal implements AbortSignal {

        private final AtomicReference<String> reason = new AtomicReference<>();
        private final List<Runnable> actions = new CopyOnWriteArrayList<>();

        void cancel(String why) {
            if (reason.compareAndSet(null, why)) {
                for (Runnable action : actions) {
                    action.run();
                }
            }
        }

        @Override
        public void checkAbort() {
            String why = reason.get();
            if (why != null) {
                throw new AbortedException("test cancel: " + why);
            }
        }

        @Override
        public void onCancel(Runnable action) {
            actions.add(action);
            if (reason.get() != null) {
                action.run();
            }
        }
    }

    private static SSLContext[] contexts(Path dir) {
        Path keyStore = dir.resolve("search-fixture.p12");
        generateKeyStore(keyStore);
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keyStore)) {
                store.load(in, PASSWORD);
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, PASSWORD);
            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keys.getKeyManagers(), null, null);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, trust.getTrustManagers(), null);
            return new SSLContext[] {server, client};
        } catch (Exception e) {
            throw new AssertionError("cannot load fixture key store", e);
        }
    }

    private static void generateKeyStore(Path keyStore) {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new ArrayList<>();
        command.add(keytool.toString());
        command.add("-genkeypair");
        command.add("-alias");
        command.add("fixture");
        command.add("-keyalg");
        command.add("EC");
        command.add("-groupname");
        command.add("secp256r1");
        command.add("-dname");
        command.add("CN=127.0.0.1");
        command.add("-ext");
        command.add("SAN=ip:127.0.0.1");
        command.add("-validity");
        command.add("2");
        command.add("-storetype");
        command.add("PKCS12");
        command.add("-keystore");
        command.add(keyStore.toString());
        command.add("-storepass");
        command.add(new String(PASSWORD));
        command.add("-keypass");
        command.add(new String(PASSWORD));
        command.add("-noprompt");
        Path log = keyStore.resolveSibling("keytool.log");
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                String output = Files.exists(log) ? Files.readString(log) : "";
                throw new AssertionError("keytool failed:\n" + output);
            }
        } catch (IOException e) {
            throw new AssertionError("cannot run keytool", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running keytool", e);
        }
    }
}
