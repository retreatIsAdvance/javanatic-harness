package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    // ────────── T13 端到端三方法闭环 ──────────

    @Test
    void e2eRoundTripsThreeMethodsOverHttpWithJsonContentType() throws Exception {
        RpcFixtures.FakePersistence persistence = new RpcFixtures.FakePersistence();
        RpcFixtures.FakeTail tail = new RpcFixtures.FakeTail();
        persistence.catalog = List.of(summary("s1", 2L, Optional.empty(), false));
        persistence.total = 1;
        persistence.sessions.put("s1", loaded(richEvents()));
        tail.sessions.put("s1", tailEvents());
        RpcHttpServer server = new RpcHttpServer(new WorkbenchRpc(persistence, tail, ""));
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
            new WorkbenchRpc(new RpcFixtures.FakePersistence(), tail, ""));
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

    // ────────── 工具面 ──────────

    private JsonNode tailResult(RpcHttpServer server, int lastSeq) throws Exception {
        HttpResponse<String> res = post(server,
            "{\"jsonrpc\":\"2.0\",\"method\":\"workbench/sessionTail\","
                + "\"params\":{\"session\":\"s1\",\"lastSeq\":" + lastSeq + "},\"id\":1}");
        return MAPPER.readTree(res.body()).get("result");
    }

    private HttpResponse<String> post(RpcHttpServer server, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(server, "/"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(RpcHttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.address().getPort() + path);
    }
}
