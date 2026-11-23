package io.javanatic.harness.workbench.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * W3 绑定面薄壳(B29 硬线):jdk.httpserver 恒绑 127.0.0.1 回环,构造面
 * 不收 bind 地址——编译期形状即「无 --host」;虚拟线程 per task;
 * close() 停止(R3 收口)。
 *
 * <p>未注入 SSE 时沿用 W4 路由(it36 停 1,T1 精度):仅精确路径 {@code /} 的 GET/HEAD
 * 服务单文件 UI({@link WorkbenchUi},text/html + no-store),其余一切
 * GET/HEAD 404——不泛化成目录服务;POST {@code /} → wire 派发,POST 非
 * {@code /} 404;其余方法 405(Allow: GET, POST)。
 * 显式注入 source/codec 后仅增加 GET {@code /events} 流与 HEAD 405(Allow: GET)，
 * 其余路由不变；打开源及游标校验先于 SSE 200，仍先过 Host/Origin 围栏。
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
    private static final System.Logger LOG = System.getLogger(RpcHttpServer.class.getName());

    private final JsonRpcWire wire = new JsonRpcWire();
    private final JsonRpcWire.Handler handler;
    private final WorkbenchUi ui;
    private final Optional<EventStream> events;
    private final Set<SseConnection> connections = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    private ExecutorService executor;
    private boolean closed;

    private record EventStream(SseEventSource source, SseEncoder encoder) {}

    /** @param handler 方法派发面(WorkbenchRpc);wire 为内部纯函数组合；不启用 SSE */
    public RpcHttpServer(JsonRpcWire.Handler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.ui = new WorkbenchUi(false);
        events = Optional.empty();
    }

    /**
     * 显式启用 GET /events；source 与 codec 缺一即构造失败，不静默退回 404。
     * 页面按只读面服务（0.5.0 形状）。
     *
     * @param handler JSON-RPC 方法派发面
     * @param source 已原子接续的 SSE 日志源
     * @param codecs 原始事件 codec 注册表
     */
    public RpcHttpServer(JsonRpcWire.Handler handler, SseEventSource source, SessionCodecRegistry codecs) {
        this(handler, source, codecs, false);
    }

    /**
     * 启用 SSE 并钉死页面写面形状（it38 停 2）：{@code webWrite = true} 时
     * 页面加载聊天/审批控件；只读面页面不初始化任何写路径。本构造只描述
     * **页面形状**，四写方法是否可达仍由注入的 {@link ChatControl} 决定。
     *
     * @param handler  JSON-RPC 方法派发面
     * @param source   已原子接续的 SSE 日志源
     * @param codecs   原始事件 codec 注册表
     * @param webWrite true = 页面呈现写面控件
     */
    public RpcHttpServer(JsonRpcWire.Handler handler, SseEventSource source,
                         SessionCodecRegistry codecs, boolean webWrite) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.ui = new WorkbenchUi(webWrite);
        events = Optional.of(new EventStream(Objects.requireNonNull(source, "source"), new SseEncoder(codecs)));
    }

    /**
     * 绑定回环并启动。{@code port = 0} = ephemeral(测试与取证实录用)。
     *
     * @throws IOException 端口占用等 bind 失败——原样上抛,不翻译不吞
     */
    public synchronized void start(int port) throws IOException {
        if (closed || server != null) {
            throw new IllegalStateException("RPC server already started or closed");
        }
        server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::dispatch);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    /** 实际绑定地址(ephemeral 时取真实端口);运行时断言面 {@code isLoopbackAddress()}。 */
    public synchronized InetSocketAddress address() {
        if (server == null) {
            throw new IllegalStateException("RPC server not started");
        }
        return server.getAddress();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        if (events.isPresent() && "/events".equals(exchange.getRequestURI().getPath())
                && (exchange.getRequestHeaders().getOrDefault("Host", List.of()).size() > 1
                || exchange.getRequestHeaders().getOrDefault("Origin", List.of()).size() > 1)) {
            respondText(exchange, 403, "multiple host/origin headers not allowed");
            return;
        }
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
        if (events.isPresent() && "/events".equals(exchange.getRequestURI().getPath())) {
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("GET".equals(method)) {
                serveEvents(exchange, events.orElseThrow());
                return;
            }
        }
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

    private void serveEvents(HttpExchange exchange, EventStream stream) throws IOException {
        SseConnection connection;
        try {
            SseRequest request = SseRequest.parse(exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getOrDefault("Last-Event-ID", List.of()));
            SseEventSource.Opened opened = Objects.requireNonNull(
                stream.source().open(request.session(), request.afterSeq()), "SSE source returned null");
            try {
                long head = opened.head();
                if (head < -1) {
                    throw new IllegalStateException("invalid SSE source head");
                }
                if (request.afterSeq() > head) {
                    throw new SseEventSource.HttpFailureException(400, "SSE cursor exceeds head");
                }
                connection = new SseConnection(exchange, opened, stream.encoder(), request.afterSeq());
            } catch (RuntimeException e) {
                try {
                    opened.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        } catch (SseEventSource.HttpFailureException e) {
            respondText(exchange, e.status(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "SSE open failed before 200", e);
            respondText(exchange, 500, "SSE source open failed");
            return;
        }
        boolean accepted;
        synchronized (this) {
            accepted = !closed;
            if (accepted) {
                connections.add(connection);
            }
        }
        // open 与 close 的迟到竞态：全局锁外撤销，绝不发出 SSE 200。
        if (!accepted) {
            connection.close();
            return;
        }
        try {
            connection.run();
        } finally {
            connections.remove(connection);
        }
    }

    /** 围栏及 SSE 打开失败响应：具名理由行(text/plain; charset=utf-8)。 */
    private static void respondText(HttpExchange exchange, int status, String reason)
            throws IOException {
        byte[] out = (reason + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** 幂等终结；撤销所有 SSE 连接并中断 executor，不持全局锁调用外部 close。 */
    @Override
    public void close() {
        List<SseConnection> active;
        HttpServer bound;
        ExecutorService workers;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            active = List.copyOf(connections);
            connections.clear();
            bound = server;
            workers = executor;
        }
        RuntimeException failure = null;
        try {
            // exchange.close 会排空未读请求体；先强制终止 socket，不能等客户端补齐正文。
            if (bound != null) {
                bound.stop(0);
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            try {
                for (SseConnection connection : active) {
                    try {
                        connection.close();
                    } catch (RuntimeException e) {
                        if (failure == null) {
                            failure = e;
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                }
            } finally {
                if (workers != null) {
                    workers.shutdownNow();
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
