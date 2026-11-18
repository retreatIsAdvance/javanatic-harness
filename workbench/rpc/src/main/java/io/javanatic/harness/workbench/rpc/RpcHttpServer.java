package io.javanatic.harness.workbench.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * W3 绑定面薄壳(B29 硬线):jdk.httpserver 恒绑 127.0.0.1 回环,构造面
 * 不收 bind 地址——编译期形状即「无 --host」;虚拟线程 per task;
 * close() 停止(R3 收口)。
 *
 * <p>W4 路由重钉(it36 停 1,T1 精度):仅精确路径 {@code /} 的 GET/HEAD
 * 服务单文件 UI({@link WorkbenchUi},text/html + no-store),其余一切
 * GET/HEAD 404——不泛化成目录服务;POST {@code /} → wire 派发,POST 非
 * {@code /} 404;其余方法 405(Allow: GET, POST)。
 *
 * <p>协议语义(JSON-RPC 帧、错误分档)全部归 {@link JsonRpcWire};本类只做
 * transport 守卫。bind 失败(端口占用)IOException 上抛,不翻译不吞
 * (CLI 停 2 映射 exit 3)。
 *
 * <p>it37 停 1 读面信任围栏(B36/B37,横切前置条件非路由变更——W4 T1
 * 矩阵在围栏放行域内逐字成立):dispatch 头部先过 {@link RequestFence}
 * Host/Origin 检查(一切方法,先于路由),拒 = 403 + 具名理由行;POST {@code /}
 * 读 body 之前过 Content-Type 闸(仅收 application/json,堵简单请求绕
 * CORS 预检变体),拒 = 415 + 具名理由行。理由行 text/plain; charset=utf-8。
 */
public final class RpcHttpServer implements AutoCloseable {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";

    private final JsonRpcWire wire = new JsonRpcWire();
    private final JsonRpcWire.Handler handler;
    private final WorkbenchUi ui = new WorkbenchUi();
    private HttpServer server;
    private ExecutorService executor;

    /** @param handler 方法派发面(WorkbenchRpc);wire 为内部纯函数组合 */
    public RpcHttpServer(JsonRpcWire.Handler handler) {
        this.handler = handler;
    }

    /**
     * 绑定回环并启动。{@code port = 0} = ephemeral(测试与取证实录用)。
     *
     * @throws IOException 端口占用等 bind 失败——原样上抛,不翻译不吞
     */
    public void start(int port) throws IOException {
        server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::dispatch);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    /** 实际绑定地址(ephemeral 时取真实端口);运行时断言面 {@code isLoopbackAddress()}。 */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        // B36 围栏先于路由(一切方法):Host 白名单 + Origin 同源,port = 实际绑定面
        Optional<String> rejection = RequestFence.hostOriginRejection(
            exchange.getRequestHeaders().getFirst("Host"),
            exchange.getRequestHeaders().getFirst("Origin"),
            server.getAddress().getPort());
        if (rejection.isPresent()) {
            respondText(exchange, 403, rejection.get());
            return;
        }
        String method = exchange.getRequestMethod();
        boolean root = "/".equals(exchange.getRequestURI().getPath());
        if ("GET".equals(method) || "HEAD".equals(method)) {
            if (!root) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] page = ui.html();
            exchange.getResponseHeaders().set("Content-Type", WorkbenchUi.CONTENT_TYPE);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
            return;
        }
        if (!"POST".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (!root) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        // B37 Content-Type 闸:读 body 之前,仅 POST / 处理路径
        if (!RequestFence.jsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            respondText(exchange, 415, "content-type must be application/json");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] out = wire.handle(body, handler).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** 围栏拒绝响应:具名理由行(text/plain; charset=utf-8),403/415 共用。 */
    private static void respondText(HttpExchange exchange, int status, String reason)
            throws IOException {
        byte[] out = (reason + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
}
