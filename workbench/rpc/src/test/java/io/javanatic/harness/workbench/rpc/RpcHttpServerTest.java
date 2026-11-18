package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.javanatic.harness.workbench.rpc.RpcFixtures.loaded;
import static io.javanatic.harness.workbench.rpc.RpcFixtures.richEvents;
import static io.javanatic.harness.workbench.rpc.RpcFixtures.summary;
import static io.javanatic.harness.workbench.rpc.RpcFixtures.tailEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 绑定面契约(停 1-S2 T12–T13):jdk.httpserver 恒回环、HTTP 守卫
 * (405/404/Content-Type)、ephemeral 端口真往返三方法闭环、并发 tail
 * 无共享态冒烟。全部经 java.net.http.HttpClient 真 HTTP 往返;
 * handler 用内存替身(真实 jsonl 后端保真留给停 3 真机)。
 */
class RpcHttpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";

    private final HttpClient http = HttpClient.newHttpClient();

    // ────────── T12 恒回环(B29 硬线) ──────────

    @Test
    void bindsToLoopbackAddressOnly() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();
            assertThat(server.address().getPort()).isPositive();
        } finally {
            server.close();
        }
    }

    @Test
    void bindFailureOnOccupiedPortThrowsIOExceptionUntranslated() throws Exception {
        RpcHttpServer first = new RpcHttpServer((method, params) -> null);
        first.start(0);
        int occupied = first.address().getPort();
        try {
            RpcHttpServer second = new RpcHttpServer((method, params) -> null);
            assertThatThrownBy(() -> second.start(occupied))
                .isInstanceOf(IOException.class);
        } finally {
            first.close();
        }
    }

    // ────────── T13 HTTP 守卫(it36 停 1 重钉:GET / 405→200,Allow 词表 GET, POST) ──────────

    @Test
    void unsupportedMethodYields405WithAllowGetPost() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(405);
            assertThat(res.headers().firstValue("Allow")).hasValue("GET, POST");
        } finally {
            server.close();
        }
    }

    @Test
    void postToUnknownPathYields404() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/nope"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(404);
        } finally {
            server.close();
        }
    }

    // ────────── it36 停 1 静态服务面(T1 精度:仅精确 / 的 GET/HEAD) ──────────

    @Test
    void getRootServesUiHtmlWithNoStore() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/")).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.headers().firstValue("Content-Type"))
                .hasValue("text/html; charset=utf-8");
            assertThat(res.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(res.body()).contains("javanatic-workbench-ui");
        } finally {
            server.close();
        }
    }

    @Test
    void getFaviconYields404() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/favicon.ico")).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(404);
        } finally {
            server.close();
        }
    }

    @Test
    void getIndexHtmlYields404() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/index.html")).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(404);
        } finally {
            server.close();
        }
    }

    @Test
    void headRootYields200WithoutBody() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.headers().firstValue("Content-Type"))
                .hasValue("text/html; charset=utf-8");
            assertThat(res.body()).isEmpty();
        } finally {
            server.close();
        }
    }

    // ────────── T13 端到端五方法闭环(it36 停 2 增 compositionDump/verifyComposition) ──────────

    @Test
    void e2eRoundTripsFiveMethodsOverHttpWithJsonContentType() throws Exception {
        RpcFixtures.FakePersistence persistence = new RpcFixtures.FakePersistence();
        RpcFixtures.FakeTail tail = new RpcFixtures.FakeTail();
        persistence.catalog = List.of(summary("s1", 2L, Optional.empty(), false));
        persistence.total = 1;
        persistence.sessions.put("s1", loaded(richEvents()));
        tail.sessions.put("s1", tailEvents());
        RpcHttpServer server = new RpcHttpServer(new WorkbenchRpc(persistence, tail,
            "approval-auto\n", yaml -> yaml.contains("bad") ? List.of("违规: " + yaml) : List.of()));
        server.start(0);
        try {
            HttpResponse<String> res = post(server,
                "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"id\":1}");
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.headers().firstValue("Content-Type")).hasValue(JSON_CONTENT_TYPE);
            JsonNode list = MAPPER.readTree(res.body()).get("result");
            assertThat(list.get("total").asInt()).isEqualTo(1);
            assertThat(list.get("sessions").get(0).get("id").asText()).isEqualTo("s1");

            res = post(server,
                "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionViews\","
                    + "\"params\":{\"session\":\"s1\"},\"id\":\"v\"}");
            JsonNode views = MAPPER.readTree(res.body()).get("result");
            assertThat(views.get("header").get("id").asText()).isEqualTo("s1");
            assertThat(views.get("turns")).hasSize(1);

            res = post(server,
                "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\","
                    + "\"params\":{\"session\":\"s1\",\"lastSeq\":-1},\"id\":3}");
            JsonNode tailResult = MAPPER.readTree(res.body()).get("result");
            assertThat(tailResult.get("nextSeq").asInt()).isEqualTo(2);
            assertThat(tailResult.get("events")).hasSize(3);
            assertThat(tail.closed).isTrue();

            res = post(server,
                "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/compositionDump\",\"id\":4}");
            JsonNode dump = MAPPER.readTree(res.body()).get("result");
            assertThat(dump.get("dump").asText()).isEqualTo("approval-auto\n");

            res = post(server,
                "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/verifyComposition\","
                    + "\"params\":{\"yaml\":\"name: good\"},\"id\":5}");
            JsonNode ok = MAPPER.readTree(res.body()).get("result");
            assertThat(ok.get("ok").asBoolean()).isTrue();

            res = post(server,
                "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/verifyComposition\","
                    + "\"params\":{\"yaml\":\"bad\"},\"id\":6}");
            JsonNode bad = MAPPER.readTree(res.body()).get("result");
            assertThat(bad.get("ok").asBoolean()).isFalse();
            assertThat(bad.get("violations").get(0).asText()).isEqualTo("违规: bad");
        } finally {
            server.close();
        }
    }

    // ────────── T13 并发 tail 无共享态冒烟 ──────────

    @Test
    void concurrentTailRequestsShareNoState() throws Exception {
        RpcFixtures.FakeTail tail = new RpcFixtures.FakeTail();
        tail.sessions.put("s1", tailEvents());
        RpcHttpServer server = new RpcHttpServer(
            new WorkbenchRpc(new RpcFixtures.FakePersistence(), tail, "", yaml -> List.of()));
        server.start(0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<JsonNode>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(() -> tailResult(server, -1));
                requests.add(() -> tailResult(server, 0));
            }
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (Callable<JsonNode> request : requests) {
                futures.add(pool.submit(request));
            }
            int fromHead = 0;
            int fromCursor = 0;
            for (Future<JsonNode> future : futures) {
                JsonNode result = future.get();
                if (result.get("events").size() == 3) {
                    fromHead++;
                } else if (result.get("events").size() == 2) {
                    fromCursor++;
                }
                assertThat(result.get("nextSeq").asInt()).isEqualTo(2);
            }
            // 各请求自开 Follower、自排空:半路游标互不污染
            assertThat(fromHead).isEqualTo(4);
            assertThat(fromCursor).isEqualTo(4);
        } finally {
            pool.shutdown();
            server.close();
        }
    }

    // ────────── R3 收口:close() 停止服务 ──────────

    @Test
    void closeStopsServingRequests() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        server.close();

        HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThatThrownBy(() -> http.send(req, HttpResponse.BodyHandlers.ofString()))
            .isInstanceOf(IOException.class);
    }

    // ────────── it37 停 1:B36/B37 读面信任围栏(HTTP 层真往返) ──────────

    @Test
    void evilOriginPostYields403() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
                .header("Origin", "http://evil.com")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"id\":1}"))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(403);
            assertThat(res.body()).contains("origin not allowed");
        } finally {
            server.close();
        }
    }

    @Test
    void evilHostGetRootYields403BeforeRouting() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            // java.net.http 限制 Host 头——裸套接字绕开发送侧限制;
            // 围栏先于路由:恶 Host 的 GET / 不得服务 UI(不是 200)
            assertThat(rawStatusLine(server, "evil.com")).startsWith("HTTP/1.1 403");
        } finally {
            server.close();
        }
    }

    @Test
    void loopbackHostGetRootYields200ViaRawSocket() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            // 正对照:裸套接字写法本身合法,合法 Host 放行(防假红)
            assertThat(rawStatusLine(server, "127.0.0.1:" + server.address().getPort()))
                .startsWith("HTTP/1.1 200");
        } finally {
            server.close();
        }
    }

    @Test
    void postFormEncodedYields415() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"id\":1}"))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(415);
            assertThat(res.body()).contains("content-type must be application/json");
        } finally {
            server.close();
        }
    }

    @Test
    void postTextPlainYields415() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"id\":1}"))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(415);
            assertThat(res.body()).contains("content-type must be application/json");
        } finally {
            server.close();
        }
    }

    @Test
    void postWithoutContentTypeYields415() throws Exception {
        RpcHttpServer server = new RpcHttpServer((method, params) -> null);
        server.start(0);
        try {
            // 显式不带 Content-Type(java.net.http 缺省不发)——不走 post() helper,
            // helper 加头后本用例仍钉「缺头拒」
            HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionsList\",\"id\":1}"))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(415);
            assertThat(res.body()).contains("content-type must be application/json");
        } finally {
            server.close();
        }
    }

    // ────────── 工具面 ──────────

    /** 裸套接字发 GET /,只取状态行(Connection: close + soTimeout 防挂)。 */
    private String rawStatusLine(RpcHttpServer server, String hostHeader) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(5000);
            String request = "GET / HTTP/1.1\r\nHost: " + hostHeader
                + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            assertThat(statusLine).isNotNull();
            return statusLine;
        }
    }

    private JsonNode tailResult(RpcHttpServer server, int lastSeq) throws Exception {
        HttpResponse<String> res = post(server,
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\","
                + "\"params\":{\"session\":\"s1\",\"lastSeq\":" + lastSeq + "},\"id\":1}");
        return MAPPER.readTree(res.body()).get("result");
    }

    private HttpResponse<String> post(RpcHttpServer server, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(RpcHttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.address().getPort() + path);
    }
}
