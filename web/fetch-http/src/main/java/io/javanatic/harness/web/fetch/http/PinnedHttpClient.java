package io.javanatic.harness.web.fetch.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.web.WebErrorCode;
import io.javanatic.harness.web.WebException;
import io.javanatic.harness.web.WebFetchBody;
import io.javanatic.harness.web.WebFetchRequest;
import io.javanatic.harness.web.WebFetchResult;

/**
 * 钉地址 HTTPS/1.1 客户端（T2 选型：自建）。
 *
 * <p><b>TOCTOU 闭合</b>：每一跳先解析一次拿整组地址、整组过地址闸，随后直接
 * {@code Socket.connect} 到该 {@link InetAddress}；TLS 用
 * {@code SSLSocketFactory.createSocket(plain, urlHost, port, true)} 在已连通的
 * 明文 socket 上分层——SNI 与 endpoint identification 取 URL 主机名，TCP 目标
 * 保持钉住，全程不再触发系统解析。字面 IP 走 {@code ofLiteral} 后进同一地址闸，
 * 不因来源是字面量而绕过。
 *
 * <p><b>重定向</b>：301/302/303/307/308 一律按 GET 重发，跨源允许；每跳重跑
 * URL 策略、重新解析、重新过闸、重新钉地址；不转发任何头/凭据（本就没有），
 * 拒绝 https→http 降级与超过 5 跳。每跳新建连接。
 *
 * <p>超时是整次 fetch 的总截止时间；连接 10s 与读写都用剩余时间。取消通过
 * {@code onCancel} 关闭当前 socket 唤醒阻塞读；{@code IOException} 先
 * {@code checkAbort()} 再翻译——取消永不包装成 WebException。
 */
final class PinnedHttpClient {

    /** 重定向上限（安全常量，不可配置）。 */
    static final int MAX_REDIRECTS = 5;

    /** 单次建连超时（不超过总截止时间）。 */
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** 0.3 线路标识（版本升级时随 release 更新）。 */
    static final String USER_AGENT = "javanatic-harness/0.3";

    private static final int READ_BUFFER_BYTES = 16 * 1024;

    private final FetchLimits limits;
    private final AddressResolver resolver;
    private final AddressGate gate;
    private final SSLContext sslContext;

    PinnedHttpClient(FetchLimits limits, AddressResolver resolver, AddressGate gate,
                     SSLContext sslContext) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
    }

    /**
     * 取回一个 URL（含逐跳重定向）。
     *
     * @param request 请求
     * @param signal 取消信号
     * @return 最终 URL、状态、正文与截断事实
     * @throws WebException 安全或表示失败
     */
    WebFetchResult fetch(WebFetchRequest request, AbortSignal signal) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(signal, "signal");
        long deadline = System.nanoTime() + limits.timeoutSeconds() * 1_000_000_000L;
        AtomicReference<Socket> active = new AtomicReference<>();
        signal.onCancel(() -> closeQuietly(active.get()));
        FetchTarget target = FetchUrlPolicy.parse(request.url());
        int redirects = 0;
        while (true) {
            signal.checkAbort();
            try (Session session = open(target, deadline, signal, active)) {
                FetchProtocol.Head head = session.head();
                String location = head.first("location");
                if (isRedirect(head.statusCode())) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw new WebException(WebErrorCode.WEB_REDIRECT_BLOCKED,
                            "redirect chain exceeds " + MAX_REDIRECTS + " hops");
                    }
                    redirects++;
                    target = FetchUrlPolicy.resolveRedirect(target, location);
                    continue;
                }
                session.arm(deadline);
                FetchProtocol.Body body;
                try {
                    body = FetchProtocol.readBody(session.in(), head, limits.maxBodyBytes());
                } catch (IOException e) {
                    throw translate(e, deadline, signal, target);
                }
                WebFetchBody content = FetchContent.decode(head, body.bytes());
                return new WebFetchResult(target.base().toString(), head.statusCode(),
                    head.first("content-type"), content, body.truncated());
            }
        }
    }

    private Session open(FetchTarget target, long deadline, AbortSignal signal,
                         AtomicReference<Socket> active) {
        List<InetAddress> addresses = resolve(target, signal);
        gate.verify(target.host(), addresses);
        signal.checkAbort();
        IOException last = null;
        for (InetAddress address : addresses) {
            armedRemainingMillis(deadline);
            Socket plain = new Socket();
            active.set(plain);
            try {
                plain.connect(new InetSocketAddress(address, target.port()),
                    connectTimeoutMillis(deadline));
                SSLSocket tls = wrap(plain, target);
                active.set(tls);
                tls.setSoTimeout(armedRemainingMillis(deadline));
                tls.startHandshake();
                writeRequest(tls.getOutputStream(), target);
                InputStream in = new BufferedInputStream(tls.getInputStream(), READ_BUFFER_BYTES);
                FetchProtocol.Head head = FetchProtocol.readHead(in);
                return new Session(tls, in, head);
            } catch (IOException e) {
                closeQuietly(plain);
                active.set(null);
                last = e;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            } catch (RuntimeException e) {
                closeQuietly(plain);
                active.set(null);
                throw e;
            }
        }
        throw translate(last, deadline, signal, target);
    }

    private List<InetAddress> resolve(FetchTarget target, AbortSignal signal) {
        if (target.ipLiteral()) {
            // 策略层已用 ofLiteral 校验过；这里再取一次地址对象，仍走同一地址闸
            return List.of(InetAddress.ofLiteral(target.host()));
        }
        try {
            InetAddress[] resolved = resolver.resolve(target.host());
            if (resolved == null || resolved.length == 0) {
                throw new WebException(WebErrorCode.WEB_NETWORK_ERROR,
                    "no addresses for host '" + target.host() + "'");
            }
            return List.of(resolved);
        } catch (UnknownHostException e) {
            throw new WebException(WebErrorCode.WEB_NETWORK_ERROR,
                "cannot resolve host '" + target.host() + "'", e);
        } finally {
            signal.checkAbort();
        }
    }

    private SSLSocket wrap(Socket plain, FetchTarget target) throws IOException {
        SSLSocket tls = (SSLSocket) sslContext.getSocketFactory()
            .createSocket(plain, target.host(), target.port(), true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setApplicationProtocols(new String[] {"http/1.1"});
        if (!target.ipLiteral()) {
            parameters.setServerNames(List.of(new SNIHostName(target.host())));
        }
        tls.setSSLParameters(parameters);
        return tls;
    }

    private static void writeRequest(OutputStream out, FetchTarget target) throws IOException {
        String request = "GET " + target.requestTarget() + " HTTP/1.1\r\n"
            + "Host: " + target.hostHeader() + "\r\n"
            + "User-Agent: " + USER_AGENT + "\r\n"
            + "Accept: text/html,application/xhtml+xml,text/plain,application/json,application/xml\r\n"
            + "Accept-Encoding: identity\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static int connectTimeoutMillis(long deadline) {
        return Math.min(CONNECT_TIMEOUT_MILLIS, armedRemainingMillis(deadline));
    }

    /** 剩余时间（毫秒，至少 1）；截止已过 → {@code WEB_TIMEOUT}。 */
    private static int armedRemainingMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new WebException(WebErrorCode.WEB_TIMEOUT, "fetch deadline exceeded");
        }
        long millis = remainingNanos / 1_000_000L;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }

    private static WebException translate(IOException last, long deadline, AbortSignal signal,
                                          FetchTarget target) {
        signal.checkAbort();
        if (last == null) {
            return new WebException(WebErrorCode.WEB_NETWORK_ERROR,
                "no address could be connected for host '" + target.host() + "'");
        }
        boolean timedOut = last instanceof SocketTimeoutException || System.nanoTime() >= deadline;
        if (timedOut) {
            return new WebException(WebErrorCode.WEB_TIMEOUT,
                "timed out fetching '" + target.host() + "'", last);
        }
        String reason = last.getMessage() == null
            ? last.getClass().getSimpleName() : last.getClass().getSimpleName() + ": " + last.getMessage();
        return new WebException(WebErrorCode.WEB_NETWORK_ERROR,
            "network error fetching '" + target.host() + "': " + reason, last);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无可行动作:连接已不可用,后续读会失败并走正常错误面
            }
        }
    }

    /** 一跳连接：头已读完，正文流仍开；close 负责回收。 */
    private static final class Session implements AutoCloseable {

        private final SSLSocket socket;
        private final InputStream in;
        private final FetchProtocol.Head head;

        Session(SSLSocket socket, InputStream in, FetchProtocol.Head head) {
            this.socket = socket;
            this.in = in;
            this.head = head;
        }

        FetchProtocol.Head head() {
            return head;
        }

        InputStream in() {
            return in;
        }

        void arm(long deadline) {
            try {
                socket.setSoTimeout(armedRemainingMillis(deadline));
            } catch (IOException e) {
                throw new WebException(WebErrorCode.WEB_NETWORK_ERROR,
                    "cannot arm read timeout: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }
}
