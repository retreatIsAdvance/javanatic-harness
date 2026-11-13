package io.javanatic.harness.workbench.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * W3 绑定面薄壳(B29 硬线):jdk.httpserver 恒绑 127.0.0.1 回环,构造面
 * 不收 bind 地址——编译期形状即「无 --host」;POST / → wire 派发,虚拟
 * 线程 per task;close() 停止(R3 收口)。
 *
 * <p>协议语义(JSON-RPC 帧、错误分档)全部归 {@link JsonRpcWire};本类只做
 * transport 守卫:非 POST → 405(Allow: POST)、POST 非 / → 404、响应
 * Content-Type application/json。bind 失败(端口占用)IOException 上抛,
 * 不翻译不吞(CLI 停 2 映射 exit 3)。
 */
public final class RpcHttpServer implements AutoCloseable {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";

    private final JsonRpcWire wire = new JsonRpcWire();
    private final JsonRpcWire.Handler handler;
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
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] out = wire.handle(body, handler).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.sendResponseHeaders(200, out.length);
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
