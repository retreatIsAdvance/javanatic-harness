/*
 * it29 停 2 lsp/jdtls 测试夹具：单文件、零依赖、JDK 直跑（java LspFixtureServer.java [flags]）。
 * Content-Length 字节帧 LSP server（LSP 边界义务的最小对端）；傻正则解析（有意为之——
 * 夹具不做 JSON 库，保持单文件可直跑）。
 *
 * 默认：initialize → capabilities；shutdown → {} 后继续等 exit 通知再 exit(0)；其余请求 → result {}。
 * 一次性故障注入（marker 语义，同 mcp-fixture）：带 marker 的旗标在「marker 不存在」时
 * 创建 marker 并武装故障；重启（同 argv、marker 已在）转正常档。
 *   --pid-file P                  启动即写自身 pid 到 P
 *   --shutdown-null               shutdown 回 result:null（对照默认回 {}，D1）
 *   --tail-request-after-shutdown shutdown 应答后发 create 请求；收到应答 exit 0，否则 exit 3（D4）
 *   --create-before-initialize    initialize 前先发 create 请求并等应答，再回 initialize，然后 exit 0（D2）
 *   --publish-no-version URI      initialized 后发无 version 的 publishDiagnostics（D3）
 *   --publish-versioned URI VER   initialized 后发带 version 的 publishDiagnostics
 *   --publish-empty-on-init URI   initialized 后发空数组 publishDiagnostics（空数组 ≠ 无批次）
 *   --publish-on-open-delayed URI MS  didOpen 后延迟 MS 发一条诊断（截点归属：本次窗口外）
 *   --publish-many-on-open URI N  didOpen 后发一条含 N 项诊断的帧（单批超帽 = PROTOCOL 矩阵）
 *   --write-on-open P             didOpen 时把 "opened" 写入 P（窗口内改写的同步点，Stale 矩阵）
 *   --definition MODE             textDocument/definition 行为：location|link|null|ignore
 *   --references MODE             textDocument/references 行为：location|link|null|ignore
 *   --init-result MODE            initialize 应答 result 形状：null|scalar|no-caps|sync-none|sync-zero|
 *                                 sync-openclose-only|sync-change-only|sync-object（R3-4 矩阵）
 *   --unknown-request             initialized 后发 custom/request；收到 -32601 exit 0，否则 exit 3
 *   --notify-progress             initialize 应答后立即发一条 $/progress 通知（不等 initialized；D2 时序）
 *   --garble-once M               首个非初始化请求后回一帧非 JSON 垃圾
 *   --wrong-id-once M             首个非初始化请求应答 id+10000
 *   --error-once M                首个非初始化请求应答 error 成员
 *   --delay-once M ms             首个非初始化请求延迟 ms 再应答
 *   --record-cancel-to P          收到 $/cancelRequest 时把 id 写入 P
 *   --exit-after-first-response   应答 initialize 后立即 exit(0)
 *   --exit-after MS               应答 initialize 后延迟 MS 再 exit(0)（窗口期进程死亡矩阵）
 *   --delay-init MS               initialize 应答前延迟 MS（INITIALIZING 矩阵）
 *   --hang-on-exit                收到 exit 通知不退出，永久挂起（需 TERM/KILL 收）
 *   --hang-on-shutdown            收到 shutdown 不答，永久挂起（shutdown 超时矩阵）
 *   --tail-request-then-silence P shutdown 应答后发 create 请求；收到应答写 "answered" 到 P 后永久挂起（D4 产品面）
 *   --tail-request-delayed MS    shutdown 应答后延迟 MS 发 create 请求，永久挂起（排水到期未答矩阵）
 *   --ignore-term                 安装 TERM 忽略处理器（KILL 升级矩阵；需 --add-modules jdk.unsupported）
 */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LspFixtureServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern RESULT = Pattern.compile("\"result\"\\s*:");
    private static final Pattern URI_IN_FRAME = Pattern.compile("\"uri\"\\s*:\\s*\"([^\"]+)\"");
    private static final int TAIL_TIMEOUT_SECONDS = 5;
    private static final String DEFINITION_TARGET =
        "{\"uri\":\"file:///jdtls-fixture/Target.java\",\"range\":{\"start\":{\"line\":10,\"character\":4},"
        + "\"end\":{\"line\":10,\"character\":10}}}";
    private static final String DEFINITION_LINK =
        "[{\"targetUri\":\"file:///jdtls-fixture/Target.java\","
        + "\"targetRange\":{\"start\":{\"line\":10,\"character\":4},\"end\":{\"line\":10,\"character\":10}},"
        + "\"targetSelectionRange\":{\"start\":{\"line\":10,\"character\":4},"
        + "\"end\":{\"line\":10,\"character\":10}}}]";

    private static Path pidFile;
    private static boolean shutdownNull;
    private static boolean tailRequestAfterShutdown;
    private static boolean createBeforeInitialize;
    private static String publishNoVersionUri;
    private static String publishVersionedUri;
    private static int publishVersionedVersion;
    private static String publishEmptyOnInitUri;
    private static String publishOnOpenDelayedUri;
    private static long publishOnOpenDelayedMillis;
    private static String publishManyOnOpenUri;
    private static int publishManyCount;
    private static Path writeOnOpenTo;
    private static Path recordSyncTo;
    private static Path recordWireTo;
    private static Path publishRawOnOpen;
    private static Path locationResultFrom;
    private static Path publicationGate;
    private static Path nextPublication;
    private static Path publicationReceipt;
    private static String definitionMode;
    private static String referencesMode;
    private static String initResultMode;
    private static boolean unknownRequest;
    private static boolean notifyProgress;
    private static Path garbleOnce;
    private static Path wrongIdOnce;
    private static Path errorOnce;
    private static Path delayMarker;
    private static long delayMillis;
    private static Path recordCancelTo;
    private static boolean exitAfterFirstResponse;
    private static long exitAfterMillis = -1;
    private static long delayInitMillis;
    private static boolean hangOnExit;
    private static boolean hangOnShutdown;
    private static Path tailRequestThenSilenceTo;
    private static long tailRequestDelayedMillis = -1;
    private static boolean ignoreTerm;

    private static boolean garbleArmed;
    private static boolean wrongIdArmed;
    private static boolean errorArmed;
    private static boolean delayArmed;

    private LspFixtureServer() {
    }

    public static void main(String[] args) throws Exception {
        parse(args);
        if (ignoreTerm) {
            installTermIgnorer();
        }
        if (pidFile != null) {
            Files.writeString(pidFile, String.valueOf(ProcessHandle.current().pid()));
        }
        garbleArmed = garbleOnce != null && armOnce(garbleOnce);
        wrongIdArmed = wrongIdOnce != null && armOnce(wrongIdOnce);
        errorArmed = errorOnce != null && armOnce(errorOnce);
        delayArmed = delayMarker != null && armOnce(delayMarker);
        serve(System.in, System.out);
    }

    private static void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pid-file" -> pidFile = Path.of(args[++i]);
                case "--shutdown-null" -> shutdownNull = true;
                case "--tail-request-after-shutdown" -> tailRequestAfterShutdown = true;
                case "--create-before-initialize" -> createBeforeInitialize = true;
                case "--publish-no-version" -> publishNoVersionUri = args[++i];
                case "--publish-versioned" -> {
                    publishVersionedUri = args[++i];
                    publishVersionedVersion = Integer.parseInt(args[++i]);
                }
                case "--publish-empty-on-init" -> publishEmptyOnInitUri = args[++i];
                case "--publish-on-open-delayed" -> {
                    publishOnOpenDelayedUri = args[++i];
                    publishOnOpenDelayedMillis = Long.parseLong(args[++i]);
                }
                case "--publish-many-on-open" -> {
                    publishManyOnOpenUri = args[++i];
                    publishManyCount = Integer.parseInt(args[++i]);
                }
                case "--write-on-open" -> writeOnOpenTo = Path.of(args[++i]);
                case "--record-sync-to" -> recordSyncTo = Path.of(args[++i]);
                case "--record-wire-to" -> recordWireTo = Path.of(args[++i]);
                case "--publish-raw-on-open" -> publishRawOnOpen = Path.of(args[++i]);
                case "--location-result-from" -> locationResultFrom = Path.of(args[++i]);
                case "--publication-gate" -> {
                    publicationGate = Path.of(args[++i]);
                    nextPublication = Path.of(args[++i]);
                    publicationReceipt = Path.of(args[++i]);
                }
                case "--definition" -> definitionMode = args[++i];
                case "--references" -> referencesMode = args[++i];
                case "--init-result" -> initResultMode = args[++i];
                case "--unknown-request" -> unknownRequest = true;
                case "--notify-progress" -> notifyProgress = true;
                case "--garble-once" -> garbleOnce = Path.of(args[++i]);
                case "--wrong-id-once" -> wrongIdOnce = Path.of(args[++i]);
                case "--error-once" -> errorOnce = Path.of(args[++i]);
                case "--delay-once" -> {
                    delayMarker = Path.of(args[++i]);
                    delayMillis = Long.parseLong(args[++i]);
                }
                case "--record-cancel-to" -> recordCancelTo = Path.of(args[++i]);
                case "--exit-after-first-response" -> exitAfterFirstResponse = true;
                case "--exit-after" -> exitAfterMillis = Long.parseLong(args[++i]);
                case "--delay-init" -> delayInitMillis = Long.parseLong(args[++i]);
                case "--hang-on-exit" -> hangOnExit = true;
                case "--hang-on-shutdown" -> hangOnShutdown = true;
                case "--tail-request-then-silence" -> tailRequestThenSilenceTo = Path.of(args[++i]);
                case "--tail-request-delayed" -> tailRequestDelayedMillis = Long.parseLong(args[++i]);
                case "--ignore-term" -> ignoreTerm = true;
                default -> {
                    System.err.println("fixture: unknown flag " + args[i]);
                    System.exit(2);
                }
            }
        }
    }

    private static void installTermIgnorer() {
        sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {
            // 故意忽略：KILL 升级矩阵的对端
        });
    }

    /** marker 不存在 → 创建并返回 true（本次武装）；已存在 → false（重启档转正常）。 */
    private static boolean armOnce(Path marker) throws IOException {
        if (Files.exists(marker)) {
            return false;
        }
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "armed");
        return true;
    }

    private static void serve(InputStream in, OutputStream out) throws Exception {
        while (true) {
            String frame = readFrame(in);
            if (frame == null) {
                return;
            }
            if (recordWireTo != null) {
                Files.writeString(recordWireTo, frame + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            Matcher methodMatch = METHOD.matcher(frame);
            if (!methodMatch.find()) {
                continue;
            }
            String method = methodMatch.group(1);
            Matcher idMatch = ID.matcher(frame);
            long id = idMatch.find() ? Long.parseLong(idMatch.group(1)) : -1;
            switch (method) {
                case "initialized" -> {
                    if (publishNoVersionUri != null) {
                        sendFrame(out, publishFrame(publishNoVersionUri, -1));
                    }
                    if (publishVersionedUri != null) {
                        sendFrame(out, publishFrame(publishVersionedUri, publishVersionedVersion));
                    }
                    if (publishEmptyOnInitUri != null) {
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
                            + "\"params\":{\"uri\":\"" + publishEmptyOnInitUri + "\",\"diagnostics\":[]}}");
                    }
                    if (unknownRequest) {
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":900,\"method\":\"custom/request\","
                            + "\"params\":{}}");
                        if (awaitAnswer(out, in, 900, 2, true)) {
                            return; // 收到 -32601 → exit 0
                        }
                    }
                }
                case "$/cancelRequest" -> {
                    if (recordCancelTo != null) {
                        // 通知帧唯一 id 在 params 内：本 frame 上重新匹配（serve 层 idMatch 已消费）
                        Matcher cancelId = ID.matcher(frame);
                        if (cancelId.find()) {
                            Files.writeString(recordCancelTo, cancelId.group(1));
                        }
                    }
                }
                case "exit" -> {
                    if (hangOnExit) {
                        hangForever();
                    }
                    return;
                }
                case "initialize" -> {
                    if (delayInitMillis > 0) {
                        Thread.sleep(delayInitMillis);
                    }
                    if (createBeforeInitialize) {
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":901,"
                            + "\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"pre\"}}");
                        awaitAnswer(out, in, 901, 1, false);
                    }
                    sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":" + initResultPayload() + "}");
                    if (notifyProgress) {
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"method\":\"$/progress\",\"params\":{"
                            + "\"token\":\"t1\",\"value\":{\"kind\":\"begin\",\"title\":\"Building\"}}}");
                    }
                    if (exitAfterFirstResponse || createBeforeInitialize) {
                        return; // --create-before-initialize：应答 initialize 后退出（测试以 exit 0 断言）
                    }
                    if (exitAfterMillis >= 0) {
                        Thread.sleep(exitAfterMillis);
                        return;
                    }
                }
                case "shutdown" -> {
                    if (hangOnShutdown) {
                        hangForever();
                    }
                    sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + (shutdownNull ? ",\"result\":null}" : ",\"result\":{}}"));
                    if (tailRequestAfterShutdown) {
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":900,"
                            + "\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"tail\"}}");
                        if (awaitAnswer(out, in, 900, TAIL_TIMEOUT_SECONDS, false)) {
                            return; // 尾流请求得到应答 → exit 0
                        }
                    }
                    if (tailRequestThenSilenceTo != null) {
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":900,"
                            + "\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"tail\"}}");
                        if (awaitAnswer(out, in, 900, TAIL_TIMEOUT_SECONDS, false)) {
                            Files.writeString(tailRequestThenSilenceTo, "answered");
                        }
                        hangForever();
                    }
                    if (tailRequestDelayedMillis >= 0) {
                        Thread.sleep(tailRequestDelayedMillis);
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":900,"
                            + "\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"tail\"}}");
                        hangForever();
                    }
                    // 默认：继续循环等 exit 通知（真实 jdtls 语义：shutdown 应答后由 exit 收束）
                }
                case "textDocument/didOpen", "textDocument/didChange", "textDocument/didClose" -> {
                    if (recordSyncTo != null) {
                        Files.writeString(recordSyncTo, frame + "\n",
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                    if (!"textDocument/didOpen".equals(method)) {
                        continue;
                    }
                    Matcher uriMatch = URI_IN_FRAME.matcher(frame);
                    String uri = uriMatch.find() ? uriMatch.group(1) : "";
                    if (writeOnOpenTo != null) {
                        Files.writeString(writeOnOpenTo, "opened");
                    }
                    if (publishRawOnOpen != null) {
                        sendFrame(out, Files.readString(publishRawOnOpen));
                    }
                    if (publishManyOnOpenUri != null && publishManyOnOpenUri.equals(uri)) {
                        sendFrame(out, publishManyFrame(uri, publishManyCount));
                    }
                    if (publishOnOpenDelayedUri != null && publishOnOpenDelayedUri.equals(uri)) {
                        Thread.sleep(publishOnOpenDelayedMillis);
                        sendFrame(out, publishFrame(uri, -1));
                    }
                    if (publicationGate != null) {
                        long deadline = System.nanoTime() + 8_000_000_000L;
                        while (!Files.exists(publicationGate)) {
                            if (System.nanoTime() >= deadline) {
                                throw new IOException("publication gate timed out");
                            }
                            Thread.sleep(5);
                        }
                        sendFrame(out, Files.readString(nextPublication));
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":998,"
                            + "\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"fence\"}}");
                        awaitAnswer(out, in, 998, TAIL_TIMEOUT_SECONDS, false);
                        Files.writeString(publicationReceipt, "parsed");
                        publicationGate = null;
                    }
                }
                case "textDocument/definition" -> answerLocationRequest(out, id, definitionMode);
                case "textDocument/references" -> answerLocationRequest(out, id, referencesMode);
                default -> {
                    if (id < 0) {
                        continue; // 其余通知：不应答
                    }
                    if (delayArmed) {
                        delayArmed = false;
                        Thread.sleep(delayMillis);
                    }
                    if (garbleArmed) {
                        garbleArmed = false;
                        sendRaw(out, "this frame is not json {\n");
                        continue;
                    }
                    if (wrongIdArmed) {
                        wrongIdArmed = false;
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":" + (id + 10000) + ",\"result\":{}}");
                        continue;
                    }
                    if (errorArmed) {
                        errorArmed = false;
                        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"error\":{\"code\":-32000,\"message\":\"fixture boom\"}}");
                        continue;
                    }
                    sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
                }
            }
        }
    }

    /** initialize 应答 result 形状（--init-result MODE）；null = 默认 textDocumentSync:1。 */
    private static String initResultPayload() {
        if (initResultMode == null) {
            return "{\"capabilities\":{\"textDocumentSync\":1}}";
        }
        return switch (initResultMode) {
            case "null" -> "null";
            case "scalar" -> "42";
            case "no-caps" -> "{}";
            case "sync-none" -> "{\"capabilities\":{}}";
            case "sync-zero" -> "{\"capabilities\":{\"textDocumentSync\":0}}";
            case "sync-openclose-only" -> "{\"capabilities\":{\"textDocumentSync\":{\"openClose\":true}}}";
            case "sync-change-only" -> "{\"capabilities\":{\"textDocumentSync\":{\"change\":1}}}";
            case "sync-object" -> "{\"capabilities\":{\"textDocumentSync\":{\"openClose\":true,\"change\":2}}}";
            default -> throw new IllegalArgumentException("unknown init-result mode: " + initResultMode);
        };
    }

    /** 一条 publishDiagnostics 帧；version < 0 = 不带 version（D3 无 version 形状）。 */
    private static String publishFrame(String uri, int version) {
        String versionField = version >= 0 ? ",\"version\":" + version : "";
        return "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
            + "\"params\":{\"uri\":\"" + uri + "\"" + versionField
            + ",\"diagnostics\":[" + DIAGNOSTIC_ITEM + "]}}";
    }

    /** 单帧含 count 项诊断（单批超帽 = PROTOCOL 矩阵的真实溢出向量）。 */
    private static String publishManyFrame(String uri, int count) {
        StringBuilder body = new StringBuilder(count * 300);
        body.append("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",")
            .append("\"params\":{\"uri\":\"").append(uri).append("\",\"diagnostics\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(DIAGNOSTIC_ITEM);
        }
        return body.append("]}}").toString();
    }

    private static final String DIAGNOSTIC_ITEM =
        "{\"range\":{\"start\":{\"line\":4,\"character\":23},"
            + "\"end\":{\"line\":4,\"character\":25}},\"severity\":1,\"code\":\"16777233\","
            + "\"source\":\"Java\",\"message\":\"Type mismatch: cannot convert from int to String\"}";

    /** definition/references 行为：location=单个 Location、link=LocationLink 数组、null=result null、ignore=不答。 */
    private static void answerLocationRequest(OutputStream out, long id, String mode) throws IOException {
        if ("ignore".equals(mode)) {
            return; // 永不答：调用方按自身 deadline 收口
        }
        String result;
        if (locationResultFrom != null) {
            result = Files.readString(locationResultFrom);
        } else if ("null".equals(mode)) {
            result = "null";
        } else if ("link".equals(mode)) {
            result = DEFINITION_LINK;
        } else {
            result = DEFINITION_TARGET;
        }
        sendFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
    }

    /** 永久挂起（不退出、不关流）：由产品端 TERM/KILL 收。 */
    private static void hangForever() throws InterruptedException {
        while (true) {
            Thread.sleep(60_000);
        }
    }

    /**
     * 等 id 的应答；expectError 时校验 error.code=-32601，否则校验存在 result 成员。
     * 应答合规返回 true（是否退出由调用方决定）；超时 / EOF 直接 exit 3（exit 码是测试断言面）。
     */
    private static boolean awaitAnswer(OutputStream out, InputStream in, long expectedId,
                                       int timeoutSeconds, boolean expectError) throws Exception {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            String frame = readFrame(in);
            if (frame == null) {
                System.exit(3);
            }
            Matcher idMatch = ID.matcher(frame);
            if (!idMatch.find() || Long.parseLong(idMatch.group(1)) != expectedId) {
                continue;
            }
            if (expectError) {
                Matcher codeMatch = ERROR_CODE.matcher(frame);
                if (codeMatch.find() && "-32601".equals(codeMatch.group(1))) {
                    return true;
                }
            } else if (RESULT.matcher(frame).find()) {
                return true;
            }
        }
        System.exit(3);
        return false; // 不可达
    }

    private static String readFrame(InputStream in) throws IOException {
        long length = -1;
        int headerLines = 0;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return null;
            }
            headerLines++;
            if (headerLines > 32) {
                throw new IOException("fixture: too many header lines");
            }
            if (line.isEmpty()) {
                if (length < 0) {
                    throw new IOException("fixture: header lacks Content-Length");
                }
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("Content-Length".equalsIgnoreCase(name)) {
                    length = Long.parseLong(value);
                }
            }
        }
        byte[] body = new byte[(int) length];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n == -1) {
                throw new IOException("fixture: truncated frame body");
            }
            off += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = in.read()) != -1) {
            if (next == '\n') {
                if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) next);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void sendFrame(OutputStream out, String json) throws IOException {
        sendRaw(out, json);
    }

    private static void sendRaw(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }
}
