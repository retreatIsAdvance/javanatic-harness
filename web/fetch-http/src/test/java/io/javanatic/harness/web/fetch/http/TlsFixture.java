package io.javanatic.harness.web.fetch.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManagerFactory;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.AbortedException;

/**
 * 停 1 测试夹具：运行时 keytool 生成自签 PKCS#12（SAN 固定
 * {@code dns:pinned.test,dns:second.test,ip:127.0.0.1}），原始 {@link SSLServerSocket}
 * 逐字节写响应——覆盖 TLS + 任意畸形线路（chunked/头/重定向/截断/超时），
 * 不受 {@code com.sun.net.httpserver} 的规范化限制。
 */
final class TlsFixture {

    /** 证书口令（测试专用，不进任何生产配置）。 */
    static final char[] PASSWORD = "changeit".toCharArray();

    /** 证书覆盖的主机名（mismatch.test 故意不在 SAN 里）。 */
    static final List<String> CERT_HOSTS = List.of("pinned.test", "second.test");

    private TlsFixture() {
    }

    /** @return 生成并加载的服务端/客户端上下文（客户端把自签证书当信任锚） */
    static Contexts contexts(Path dir) {
        Path keyStore = dir.resolve("fixture.p12");
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
            return new Contexts(server, client);
        } catch (IOException e) {
            throw new AssertionError("cannot load fixture key store", e);
        } catch (Exception e) {
            throw new AssertionError("cannot build fixture SSL contexts", e);
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
        command.add("CN=pinned.test");
        command.add("-ext");
        command.add("SAN=dns:pinned.test,dns:second.test,ip:127.0.0.1");
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

    /** 服务端/客户端 SSL 上下文对。 */
    record Contexts(SSLContext server, SSLContext client) {
    }

    /** 响应写出动作（持有 socket 输出流）；路由与 helper 共用。 */
    @FunctionalInterface
    interface Route {
        void respond(OutputStream out) throws IOException;
    }

    /** 一次请求记录：原始头区 + 解析出的请求目标。 */
    record RecordedRequest(String requestTarget, String rawHead) {

        /** @return 头区中的原始行（含请求行） */
        List<String> lines() {
            return List.of(rawHead.split("\r\n"));
        }

        /** @param name 头名（大小写不敏感） @return 头值；缺失为 null */
        String header(String name) {
            for (String line : lines()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }

    /** 原始 HTTPS server：路由按请求目标精确匹配；未命中 404。 */
    static final class ScriptedHttpsServer implements AutoCloseable {

        private final SSLServerSocket serverSocket;
        private final Thread acceptor;
        private final Map<String, Route> routes = new ConcurrentHashMap<>();
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private final List<String> sniNames = new CopyOnWriteArrayList<>();

        private ScriptedHttpsServer(SSLContext context) throws IOException {
            serverSocket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
            SSLParameters parameters = serverSocket.getSSLParameters();
            parameters.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                @Override
                public boolean matches(SNIServerName name) {
                    sniNames.add(new String(name.getEncoded(), StandardCharsets.US_ASCII));
                    return true;
                }
            }));
            serverSocket.setSSLParameters(parameters);
            acceptor = Thread.ofVirtual().name("tls-fixture-accept").start(this::acceptLoop);
        }

        /** @param context 服务端上下文 @return 已监听回环端口的夹具 */
        static ScriptedHttpsServer start(SSLContext context) {
            try {
                return new ScriptedHttpsServer(context);
            } catch (IOException e) {
                throw new AssertionError("cannot start TLS fixture", e);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void route(String requestTarget, Route route) {
            routes.put(requestTarget, route);
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        /** @return 服务端观察到的 SNI 主机名（无 SNI 为空） */
        List<String> sniNames() {
            return List.copyOf(sniNames);
        }

        RecordedRequest lastRequest() {
            List<RecordedRequest> all = requests();
            if (all.isEmpty()) {
                throw new AssertionError("fixture received no request");
            }
            return all.get(all.size() - 1);
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    SSLSocket client = (SSLSocket) serverSocket.accept();
                    Thread.ofVirtual().start(() -> handle(client));
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        return;
                    }
                }
            }
        }

        private void handle(SSLSocket client) {
            try (SSLSocket socket = client) {
                socket.setSoTimeout(15_000);
                String head = readHead(socket.getInputStream());
                if (head == null) {
                    return;
                }
                String target = requestLine(head);
                requests.add(new RecordedRequest(target, head));
                Route route = routes.get(target);
                OutputStream out = socket.getOutputStream();
                if (route == null) {
                    writeText(out, "HTTP/1.1 404 Not Found\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: 9\r\nConnection: close\r\n\r\nnot found");
                } else {
                    route.respond(out);
                }
                out.flush();
            } catch (IOException ignored) {
                // 客户端可能主动断开(取消/超时/握手拒绝):夹具不承担断言职责
            }
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                matched = switch (matched) {
                    case 0 -> b == '\r' ? 1 : 0;
                    case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                    case 2 -> b == '\r' ? 3 : 0;
                    default -> b == '\n' ? 4 : 0;
                };
                if (matched == 4) {
                    return head.toString(StandardCharsets.ISO_8859_1);
                }
                if (head.size() > 64 * 1024) {
                    return null;
                }
            }
            return null;
        }

        private static String requestLine(String head) {
            int end = head.indexOf("\r\n");
            String line = end < 0 ? head : head.substring(0, end);
            String[] parts = line.split(" ");
            return parts.length >= 2 ? parts[1] : "";
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 夹具收口:端口已不可用即可,不掩盖测试断言
            }
            try {
                acceptor.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 写固定字符串响应（ISO-8859-1，线路字节原样；测试自行拼 CRLF）。 */
    static Route raw(String text) {
        return out -> out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 写头 + UTF-8 正文（Content-Length 由调用方给）。 */
    static Route utf8(String head, String body) {
        return out -> {
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body.getBytes(StandardCharsets.UTF_8));
        };
    }

    /** 写文本响应（ISO-8859-1，ASCII 场景）。 */
    static void writeText(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 可取消信号（镜像 AbortController；取消同步触发已注册动作）。 */
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

    /** 固定映射解析器：记录每次调用；未映射主机抛 UnknownHostException。 */
    static final class MapResolver implements AddressResolver {

        private final Map<String, InetAddress[]> addresses;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile boolean forbidCalls;

        MapResolver(Map<String, InetAddress[]> addresses) {
            this.addresses = Map.copyOf(addresses);
        }

        /** 后续任何调用都视为缺陷（字面 IP 不得回落到解析器）。 */
        void forbidCalls() {
            forbidCalls = true;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            calls.add(host);
            if (forbidCalls) {
                throw new AssertionError("resolver must not be called for literal IP: " + host);
            }
            InetAddress[] resolved = addresses.get(host);
            if (resolved == null) {
                throw new UnknownHostException(host);
            }
            return resolved.clone();
        }
    }

    /** 记录闸门调用并放行（TLS 夹具在回环上跑，生产闸门会拒绝）。 */
    static final class RecordingGate implements AddressGate {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void verify(String host, List<InetAddress> addresses) {
            calls.add(host + "=" + addresses.size());
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }

    /** @return 回环地址（IPv4，与证书 SAN 的 ip:127.0.0.1 对应） */
    static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            throw new AssertionError("loopback unavailable", e);
        }
    }

    /** @return 固定字面地址（地址闸分组用例） */
    static InetAddress address(String literal) {
        return InetAddress.ofLiteral(literal);
    }
}
