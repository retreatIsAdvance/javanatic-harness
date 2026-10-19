/*
 * it27.1 mcp/http 测试夹具：单文件、零依赖、JDK 直跑
 * (java --add-modules jdk.httpserver FixtureHttpServer.java [flags])。
 *
 * Streamable HTTP 最小 server（单端点 /mcp）：POST JSON-RPC（JSON 或 SSE 响应）、
 * Mcp-Session-Id 签发与校验、GET SSE 通知通道（或 405）、DELETE 收尾。
 * 傻正则解析（有意为之——夹具不做 JSON 库，保持单文件可直跑），与
 * mcp/stdio 的 FixtureServer 同风格。
 *
 * 默认工具：echo / big / env / fail。
 *
 * 一次性故障注入（marker 语义）：带 marker 的旗标在「marker 不存在」时创建
 * marker 并武装故障；后续请求转正常档——一个进程文件支撑重连/换代/超时全矩阵。
 *   --port-file P              绑定后把实际端口写入 P（必填）
 *   --session-id S             签发并校验 Mcp-Session-Id（非 initialize 请求缺/错 → 404）
 *   --protocol-version V       initialize 协商返回的版本（默认 2026-07-28）
 *   --sse                      所有 JSON-RPC 响应改用 text/event-stream 单事件
 *   --sse-multiline            配合 --sse：超大响应（>4MB）拆成两条 data: 行
 *                              （单事件累计上限取证；小响应保持单行合法 JSON）
 *   --tools csv                初始工具清单（默认 echo,big,env,fail）
 *   --pages N                  tools/list 分 N 页返回（nextCursor 跟进用）
 *   --endless-pages            tools/list 永远给下一页（游标不终止；页帽取证）
 *   --big-bytes N              big 工具返回的文本长度（默认 4096）
 *   --variant-tools M csv      marker 已在 → 工具清单换 csv（重连换代用）
 *   --garble-once M            marker 刚建 → 首个 tools/call 应答非 JSON 垃圾
 *   --wrong-id-once M          marker 刚建 → 首个 tools/call 应答错 id（id+10000）
 *   --missing-result-once M    marker 刚建 → 首个 tools/call 应答缺 result/error
 *   --rpc-error-once M         marker 刚建 → 首个 tools/call 应答 error 成员
 *   --delay-once M ms          marker 刚建 → 首个 tools/call 延迟 ms 后再应答
 *   --stall-body-once M ms     marker 刚建 → 首个 tools/call 先回头+半截正文，
 *                              停 ms 后再收尾（正文阶段看门狗取证）
 *   --expire-once M            marker 刚建 → 首个 tools/call 应答 HTTP 404（会话失效）
 *   --expire-on M              首个 tools/call 建 marker 并 404；此后所有非 initialize
 *                              请求都 404（重连耗尽用）
 *   --redirect-once M          marker 刚建 → 首个 tools/call 应答 307（客户端不跟随）
 *   --notifications            GET 返回 SSE 通知通道（缺省 405）
 *   --list-changed-once M csv  marker 刚建 → 首个 tools/call 应答后经通知通道发
 *                              tools/list_changed 并换清单 csv
 *   --garble-refresh-once M    marker 刚建 → 首个 tools/call 后发 notifications/tools/list_changed，
 *                              随后首个 tools/list 返回非 JSON 垃圾（刷新畸形→重连用）
 *   --record-call-to P         每次 tools/call 追加工具名（副作用标记 / 重试观测）
 *   --record-cancel-to P       收到 notifications/cancelled 时追加 requestId
 *   --record-delete-to P       收到 DELETE 时追加 Mcp-Session-Id
 *   --record-headers-to P      每个 POST 追加 method/session/version 三列
 *   --record-get-to P          GET 通知通道建立时追加一行
 *   --record-init-to P         每次 initialize 追加一行（重连次数观测）
 */
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FixtureHttpServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\"\\s*:\\s*(\\d+)");
    private static final Pattern CURSOR = Pattern.compile("\"cursor\"\\s*:\\s*\"([^\"]*)\"");
    private static final Object NOTIFY_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();

    private static Path portFile;
    private static String sessionId;
    private static String protocolVersion = "2026-07-28";
    private static boolean sse;
    private static boolean sseMultiline;
    private static int pages;
    private static boolean endlessPages;
    private static List<String> tools = List.of("echo", "big", "env", "fail");
    private static long bigBytes = 4096;
    private static Path variantMarker;
    private static List<String> variantCsv;
    private static Path garbleOnce;
    private static Path wrongIdOnce;
    private static Path missingResultOnce;
    private static Path rpcErrorOnce;
    private static Path delayMarker;
    private static long delayMillis;
    private static Path stallBodyOnce;
    private static long stallBodyMillis;
    private static Path expireOnce;
    private static Path expireOn;
    private static Path redirectOnce;
    private static boolean notifications;
    private static Path listChangedOnce;
    private static List<String> listChangedCsv;
    private static Path garbleRefreshOnce;
    private static Path recordCallTo;
    private static Path recordCancelTo;
    private static Path recordDeleteTo;
    private static Path recordHeadersTo;
    private static Path recordGetTo;
    private static Path recordInitTo;

    private static volatile List<String> currentTools;
    private static volatile boolean garbleNextList;
    private static OutputStream notifyOut;

    private FixtureHttpServer() {
    }

    public static void main(String[] args) throws Exception {
        parse(args);
        currentTools = variantMarker != null && Files.exists(variantMarker) && variantCsv != null
            ? variantCsv : tools;
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/mcp", FixtureHttpServer::handle);
        server.start();
        Files.writeString(portFile, String.valueOf(server.getAddress().getPort()));
        Thread.currentThread().join();
    }

    private static void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port-file" -> portFile = Path.of(args[++i]);
                case "--session-id" -> sessionId = args[++i];
                case "--protocol-version" -> protocolVersion = args[++i];
                case "--sse" -> sse = true;
                case "--sse-multiline" -> sseMultiline = true;
                case "--tools" -> tools = csv(args[++i]);
                case "--pages" -> pages = Integer.parseInt(args[++i]);
                case "--endless-pages" -> endlessPages = true;
                case "--big-bytes" -> bigBytes = Long.parseLong(args[++i]);
                case "--variant-tools" -> {
                    variantMarker = Path.of(args[++i]);
                    variantCsv = csv(args[++i]);
                }
                case "--garble-once" -> garbleOnce = Path.of(args[++i]);
                case "--wrong-id-once" -> wrongIdOnce = Path.of(args[++i]);
                case "--missing-result-once" -> missingResultOnce = Path.of(args[++i]);
                case "--rpc-error-once" -> rpcErrorOnce = Path.of(args[++i]);
                case "--delay-once" -> {
                    delayMarker = Path.of(args[++i]);
                    delayMillis = Long.parseLong(args[++i]);
                }
                case "--stall-body-once" -> {
                    stallBodyOnce = Path.of(args[++i]);
                    stallBodyMillis = Long.parseLong(args[++i]);
                }
                case "--expire-once" -> expireOnce = Path.of(args[++i]);
                case "--expire-on" -> expireOn = Path.of(args[++i]);
                case "--redirect-once" -> redirectOnce = Path.of(args[++i]);
                case "--notifications" -> notifications = true;
                case "--list-changed-once" -> {
                    listChangedOnce = Path.of(args[++i]);
                    listChangedCsv = csv(args[++i]);
                }
                case "--garble-refresh-once" -> garbleRefreshOnce = Path.of(args[++i]);
                case "--record-call-to" -> recordCallTo = Path.of(args[++i]);
                case "--record-cancel-to" -> recordCancelTo = Path.of(args[++i]);
                case "--record-delete-to" -> recordDeleteTo = Path.of(args[++i]);
                case "--record-headers-to" -> recordHeadersTo = Path.of(args[++i]);
                case "--record-get-to" -> recordGetTo = Path.of(args[++i]);
                case "--record-init-to" -> recordInitTo = Path.of(args[++i]);
                default -> {
                    System.err.println("fixture: unknown flag " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (portFile == null) {
            System.err.println("fixture: --port-file is required");
            System.exit(2);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!"/mcp".equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "POST" -> handlePost(exchange);
                case "GET" -> handleGet(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "POST, GET, DELETE");
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("fixture: handler failure: " + e);
            e.printStackTrace(System.err);
        }
    }

    private static void handlePost(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = match(METHOD, body);
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        record(recordHeadersTo, "method=" + method + " session=" + (session == null ? "none" : session)
            + " version=" + (version == null ? "none" : version));

        if ("initialize".equals(method)) {
            record(recordInitTo, "initialize");
            if (sessionId != null) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            }
            sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body)
                + ",\"result\":{\"protocolVersion\":\"" + protocolVersion
                + "\",\"capabilities\":{\"tools\":{}},"
                + "\"serverInfo\":{\"name\":\"fixture-http\",\"version\":\"1\"}}}");
            return;
        }
        if ("notifications/initialized".equals(method)) {
            requireSession(exchange, session);
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        if ("notifications/cancelled".equals(method)) {
            requireSession(exchange, session);
            record(recordCancelTo, String.valueOf(matchLong(REQUEST_ID, body)));
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        if ("tools/list".equals(method)) {
            if (requireSession(exchange, session)) {
                return;
            }
            if (expireOn != null && Files.exists(expireOn)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (garbleNextList) {
                garbleNextList = false;
                sendRaw(exchange, "this-is-not-json");
                return;
            }
            sendToolsPage(exchange, body);
            return;
        }
        if ("tools/call".equals(method)) {
            if (requireSession(exchange, session)) {
                return;
            }
            handleCall(exchange, body);
            return;
        }
        if (requireSession(exchange, session)) {
            return;
        }
        sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body)
            + ",\"error\":{\"code\":-32601,\"message\":\"method not found: " + method + "\"}}");
    }

    private static void handleCall(HttpExchange exchange, String body) throws IOException {
        String tool = match(NAME, body);
        record(recordCallTo, tool == null ? "" : tool);
        String id = idOf(body);

        if (expireOn != null && !Files.exists(expireOn)) {
            arm(expireOn);
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (expireOn != null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (armOnce(expireOnce)) {
            if (variantMarker != null) {
                arm(variantMarker); // 失效后的重连应看到新清单（换代矩阵的确定性输入）
            }
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (armOnce(redirectOnce)) {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1/mcp-moved");
            exchange.sendResponseHeaders(307, -1);
            return;
        }
        if (armOnce(delayMarker)) {
            sleep(delayMillis);
        }
        if (armOnce(stallBodyOnce)) {
            stallBody(exchange, id, stallBodyMillis);
            return;
        }
        if (armOnce(garbleOnce)) {
            sendRaw(exchange, "this-is-not-json");
            return;
        }
        if (armOnce(wrongIdOnce)) {
            sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + (Long.parseLong(id) + 10000)
                + ",\"result\":" + callResult(tool, body) + "}");
            return;
        }
        if (armOnce(missingResultOnce)) {
            sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + "}");
            return;
        }
        if (armOnce(rpcErrorOnce)) {
            sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"error\":{\"code\":-32000,\"message\":\"fixture boom\"}}");
            return;
        }
        boolean swapCatalog = armOnce(listChangedOnce);
        boolean garbleRefresh = armOnce(garbleRefreshOnce);
        sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":"
            + callResult(tool, body) + "}");
        if (swapCatalog) {
            currentTools = listChangedCsv == null ? currentTools : listChangedCsv;
            sendListChanged();
        }
        if (garbleRefresh) {
            garbleNextList = true;
            sendListChanged();
        }
    }

    private static void handleGet(HttpExchange exchange) throws IOException {
        if (!notifications) {
            exchange.getResponseHeaders().set("Allow", "POST, DELETE");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId != null && !sessionId.equals(session)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        record(recordGetTo, "get");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        synchronized (NOTIFY_LOCK) {
            notifyOut = out;
            NOTIFY_LOCK.notifyAll();
        }
        try {
            synchronized (NOTIFY_LOCK) {
                while (notifyOut == out) {
                    NOTIFY_LOCK.wait();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(out);
        }
    }

    private static void handleDelete(HttpExchange exchange) throws IOException {
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        record(recordDeleteTo, session == null ? "none" : session);
        synchronized (NOTIFY_LOCK) {
            if (notifyOut != null) {
                closeQuietly(notifyOut);
                notifyOut = null;
                NOTIFY_LOCK.notifyAll();
            }
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private static void sendListChanged() {
        synchronized (NOTIFY_LOCK) {
            if (notifyOut == null) {
                System.err.println("fixture: list_changed dropped (no notification channel)");
                return;
            }
            try {
                writeEvent(notifyOut, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
            } catch (IOException e) {
                System.err.println("fixture: list_changed write failed: " + e.getMessage());
            }
        }
    }

    /** @return true = 已按会话缺失/不符回 404（调用方立即返回） */
    private static boolean requireSession(HttpExchange exchange, String session) throws IOException {
        if (sessionId == null || sessionId.equals(session)) {
            return false;
        }
        exchange.sendResponseHeaders(404, -1);
        return true;
    }

    private static void sendRpc(HttpExchange exchange, String json) throws IOException {
        if (sse) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if (sseMultiline && json.length() > 4_000_000) {
                    writeEventSplit(out, json); // 只拆超大响应：小响应保持合法 JSON 单行
                } else {
                    writeEvent(out, json);
                }
            }
        } else {
            sendRaw(exchange, json);
        }
    }

    /** 单事件两条 data: 行：每行都小于传输帧帽，累计超帽只能由事件级上限拦下。 */
    private static void writeEventSplit(OutputStream out, String data) throws IOException {
        int half = data.length() / 2;
        out.write(("event: message\ndata: " + data.substring(0, half)
            + "\ndata: " + data.substring(half) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendRaw(HttpExchange exchange, String raw) throws IOException {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
            sse ? "text/event-stream" : "application/json");
        if (sse) {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                writeEvent(out, raw);
            }
        } else {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** 正文阶段停滞：先回 200 + 半截正文（chunked，收尾时干净 EOF），客户端看门狗应闭流
     * 并按超时收口。若看门狗只置位不关流，客户端会等到 EOF 后按畸形响应失败。 */
    private static void stallBody(HttpExchange exchange, String id, long millis) throws IOException {
        byte[] prefix = ("{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(prefix);
        out.flush();
        sleep(millis);
        try {
            out.write("stalled".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 客户端已按超时关流：夹具不再关心
        }
        out.close();
    }

    private static void writeEvent(OutputStream out, String data) throws IOException {
        out.write(("event: message\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendToolsPage(HttpExchange exchange, String body) throws IOException {
        String id = idOf(body);
        if (endlessPages) {
            int page = cursorOf(body);
            sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                + toolJson("page-" + page) + "],\"nextCursor\":\"" + (page + 1) + "\"}}");
            return;
        }
        if (pages <= 0) {
            sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"result\":" + toolsJson() + "}");
            return;
        }
        List<String> entries = new ArrayList<>();
        for (String name : activeTools()) {
            entries.add(toolJson(name));
        }
        int per = Math.max(1, (entries.size() + pages - 1) / pages);
        int page = cursorOf(body);
        int from = Math.min(page * per, entries.size());
        int to = Math.min(entries.size(), from + per);
        boolean more = to < entries.size();
        String json = "{\"tools\":[" + String.join(",", entries.subList(from, to)) + "]"
            + (more ? ",\"nextCursor\":\"" + (page + 1) + "\"" : "") + "}";
        sendRpc(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + json + "}");
    }

    private static int cursorOf(String body) {
        String cursor = match(CURSOR, body);
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String toolJson(String name) {
        return "{\"name\":\"" + name
            + "\",\"description\":\"fixture " + name
            + "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"message\":{\"type\":\"string\"}}}}";
    }

    private static String toolsJson() {
        StringBuilder json = new StringBuilder("{\"tools\":[");
        boolean first = true;
        for (String name : activeTools()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(toolJson(name));
        }
        return json.append("]}").toString();
    }

    private static List<String> activeTools() {
        if (variantMarker != null && variantCsv != null && Files.exists(variantMarker)) {
            return variantCsv;
        }
        return currentTools;
    }

    private static String callResult(String tool, String body) {
        String message = match(MESSAGE, body);
        boolean isError = "fail".equals(tool);
        String text;
        if ("big".equals(tool)) {
            text = "x".repeat((int) Math.min(bigBytes, 16_000_000));
        } else if (isError) {
            text = "fixture failure";
        } else if ("echo".equals(tool)) {
            text = "echo: " + (message == null ? "" : message);
        } else {
            text = tool + ": " + (message == null ? "" : message);
        }
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + escape(text)
            + "\"}],\"isError\":" + isError + "}";
    }

    private static String idOf(String body) {
        String id = match(ID, body);
        return id == null ? "null" : id;
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static long matchLong(Pattern pattern, String text) {
        String value = match(pattern, text);
        return value == null ? -1 : Long.parseLong(value);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static List<String> csv(String text) {
        List<String> values = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static boolean armOnce(Path marker) {
        if (marker == null || Files.exists(marker)) {
            return false;
        }
        arm(marker);
        return true;
    }

    private static void arm(Path marker) {
        try {
            Files.writeString(marker, "armed");
        } catch (IOException e) {
            throw new IllegalStateException("fixture cannot arm " + marker, e);
        }
    }

    private static void record(Path file, String line) {
        if (file == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            try {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("fixture: record failed: " + e.getMessage());
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // 夹具收尾:流已被对端关闭,无补救路径
        }
    }
}
