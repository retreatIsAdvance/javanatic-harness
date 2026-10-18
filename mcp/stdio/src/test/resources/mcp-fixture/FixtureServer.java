/*
 * it27 mcp/stdio 测试夹具：单文件、零依赖、JDK 直跑（java FixtureServer.java [flags]）。
 * 换行分帧 JSON-RPC 2.0 最小 server；傻正则解析（有意为之——夹具不做 JSON 库，
 * 保持单文件可直跑，停 3 jlink 真跑复用本文件）。
 *
 * 默认工具：echo / big / env / fail。
 *
 * 一次性故障注入（marker 语义）：带 marker 的旗标在「marker 不存在」时创建 marker
 * 并武装故障；重启（同 argv、marker 已在）转正常档——一个进程文件支撑
 * 崩溃恢复/重连对账/耗尽/畸形帧全矩阵，无需改配置。
 *   --pid-file P              启动即写自身 pid 到 P
 *   --stderr-lines N          启动即向 stderr 打 N 行 fixture-stderr-i
 *   --tools csv               替换默认工具清单
 *   --big-bytes N             big 工具返回的文本长度（默认 4096；夹具上限 16,000,000）
 *   --protocol-version V      initialize 协商返回的版本（默认 2026-07-28）
 *   --page-size K             tools/list 按 K 分页（nextCursor 跟进用）
 *   --endless-pages           tools/list 永远给 nextCursor（client 页数上限用）
 *   --dup-tools               tools/list 追加重复名 echo（重名 fail loud 用）
 *   --bad-name                tools/list 追加 bad__name（消费面归一 + WARN 用）
 *   --missing-schema          echo 条目省略 inputSchema（缺必填字段用）
 *   --rich-schema             echo 条目返回富 inputSchema（嵌套/数组/可选字段；停 3 真跑用）
 *   --exit-immediately        启动即 exit(0)（装配失败用）
 *   --exit-immediately-if M   marker 已在 → 启动即 exit(0)；不存在 → 建 marker 正常跑
 *   --exit-after-list-once M  marker 刚建 → 应答首个 tools/list 后 exit(0)
 *   --garble-once M           marker 刚建 → 首个 tools/call 应答一行非 JSON 垃圾
 *   --wrong-id-once M         marker 刚建 → 首个 tools/call 应答错 id（id+10000）
 *   --missing-result-once M   marker 刚建 → 首个 tools/call 应答缺 result/error
 *   --rpc-error-once M        marker 刚建 → 首个 tools/call 应答 error 成员
 *   --delay-once M ms         marker 刚建 → 首个 tools/call 延迟 ms 后再应答
 *   --record-cancel-to P      收到 notifications/cancelled 时把 requestId 写入 P（取消链路观测）
 *   --variant-tools M csv     marker 已在 → 工具清单换 csv（重连换代用）；刚建 → 默认
 *   --list-changed-once M csv marker 刚建 → 首个 tools/call 应答后发 tools/list_changed，
 *                             并把清单换成 csv（通知驱动换代用）
 *   --garble-refresh-once M   marker 刚建 → 首个 tools/call 应答后发 tools/list_changed，
 *                             且随后首个 tools/list 返回非 JSON 垃圾（刷新畸形→重连用）
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class FixtureServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CURSOR = Pattern.compile("\"cursor\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\"\\s*:\\s*(\\d+)");

    /** 停 3 jlink 真跑：白名单全构造（string/integer/number/boolean/array/object、required 与可选项）。 */
    private static final String RICH_SCHEMA = "{\"type\":\"object\",\"description\":\"echo args\","
        + "\"properties\":{"
        + "\"message\":{\"type\":\"string\",\"description\":\"text to echo\"},"
        + "\"repeat\":{\"type\":\"integer\",\"description\":\"repeat count\"},"
        + "\"ratio\":{\"type\":\"number\",\"description\":\"scale\"},"
        + "\"loud\":{\"type\":\"boolean\",\"description\":\"uppercase\"},"
        + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"labels\"},"
        + "\"nested\":{\"type\":\"object\",\"properties\":{\"inner\":{\"type\":\"string\"}},"
        + "\"description\":\"wrapper\"}},"
        + "\"required\":[\"message\"]}";

    private static List<String> tools = List.of("echo", "big", "env", "fail");
    private static String protocolVersion = "2026-07-28";
    private static long bigBytes = 4096;
    private static int pageSize;
    private static boolean endlessPages;
    private static boolean dupTools;
    private static boolean badName;
    private static boolean missingSchema;
    private static boolean richSchema;
    private static Path pidFile;
    private static int stderrLines;
    private static boolean exitImmediately;
    private static Path exitImmediatelyIf;
    private static Path exitAfterListOnce;
    private static Path garbleOnce;
    private static Path wrongIdOnce;
    private static Path missingResultOnce;
    private static Path rpcErrorOnce;
    private static Path delayMarker;
    private static long delayMillis;
    private static Path variantMarker;
    private static List<String> variantCsv;
    private static Path recordCancelTo;
    private static Path listChangedOnce;
    private static List<String> listChangedCsv;
    private static Path garbleRefreshOnce;

    private static boolean exitAfterListArmed;
    private static boolean garbleArmed;
    private static boolean wrongIdArmed;
    private static boolean missingResultArmed;
    private static boolean rpcErrorArmed;
    private static boolean delayArmed;
    private static boolean listChangedArmed;
    private static boolean garbleRefreshArmed;
    private static boolean garbleNextList;

    private FixtureServer() {
    }

    public static void main(String[] args) throws Exception {
        parse(args);
        if (pidFile != null) {
            Files.writeString(pidFile, String.valueOf(ProcessHandle.current().pid()));
        }
        for (int i = 0; i < stderrLines; i++) {
            System.err.println("fixture-stderr-" + i);
        }
        if (exitImmediately) {
            System.exit(0);
        }
        if (exitImmediatelyIf != null && !armOnce(exitImmediatelyIf)) {
            System.exit(0);
        }
        exitAfterListArmed = exitAfterListOnce != null && armOnce(exitAfterListOnce);
        garbleArmed = garbleOnce != null && armOnce(garbleOnce);
        wrongIdArmed = wrongIdOnce != null && armOnce(wrongIdOnce);
        missingResultArmed = missingResultOnce != null && armOnce(missingResultOnce);
        rpcErrorArmed = rpcErrorOnce != null && armOnce(rpcErrorOnce);
        delayArmed = delayMarker != null && armOnce(delayMarker);
        listChangedArmed = listChangedOnce != null && armOnce(listChangedOnce);
        garbleRefreshArmed = garbleRefreshOnce != null && armOnce(garbleRefreshOnce);
        if (variantMarker != null && !armOnce(variantMarker)) {
            tools = variantCsv; // 重启档:清单换形(重连换代的来源)
        }
        serve();
    }

    private static void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pid-file" -> pidFile = Path.of(args[++i]);
                case "--stderr-lines" -> stderrLines = Integer.parseInt(args[++i]);
                case "--tools" -> tools = csv(args[++i]);
                case "--big-bytes" -> bigBytes = Long.parseLong(args[++i]);
                case "--protocol-version" -> protocolVersion = args[++i];
                case "--page-size" -> pageSize = Integer.parseInt(args[++i]);
                case "--endless-pages" -> endlessPages = true;
                case "--dup-tools" -> dupTools = true;
                case "--bad-name" -> badName = true;
                case "--missing-schema" -> missingSchema = true;
                case "--rich-schema" -> richSchema = true;
                case "--exit-immediately" -> exitImmediately = true;
                case "--exit-immediately-if" -> exitImmediatelyIf = Path.of(args[++i]);
                case "--exit-after-list-once" -> exitAfterListOnce = Path.of(args[++i]);
                case "--garble-once" -> garbleOnce = Path.of(args[++i]);
                case "--wrong-id-once" -> wrongIdOnce = Path.of(args[++i]);
                case "--missing-result-once" -> missingResultOnce = Path.of(args[++i]);
                case "--rpc-error-once" -> rpcErrorOnce = Path.of(args[++i]);
                case "--delay-once" -> {
                    delayMarker = Path.of(args[++i]);
                    delayMillis = Long.parseLong(args[++i]);
                }
                case "--record-cancel-to" -> recordCancelTo = Path.of(args[++i]);
                case "--variant-tools" -> {
                    variantMarker = Path.of(args[++i]);
                    variantCsv = csv(args[++i]);
                }
                case "--list-changed-once" -> {
                    listChangedOnce = Path.of(args[++i]);
                    listChangedCsv = csv(args[++i]);
                }
                case "--garble-refresh-once" -> garbleRefreshOnce = Path.of(args[++i]);
                default -> {
                    System.err.println("fixture: unknown flag " + args[i]);
                    System.exit(2);
                }
            }
        }
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

    private static void serve() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            Matcher methodMatch = METHOD.matcher(line);
            if (!methodMatch.find()) {
                continue; // 无 method:无法归属的报文,忽略
            }
            if (recordCancelTo != null && "notifications/cancelled".equals(methodMatch.group(1))) {
                Matcher requestIdMatch = REQUEST_ID.matcher(line);
                if (requestIdMatch.find()) {
                    Files.writeString(recordCancelTo, requestIdMatch.group(1));
                }
                continue;
            }
            Matcher idMatch = ID.matcher(line);
            if (!idMatch.find()) {
                continue; // 通知(initialized/cancelled…):单向,忽略
            }
            long id = Long.parseLong(idMatch.group(1));
            switch (methodMatch.group(1)) {
                case "initialize" -> send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"protocolVersion\":\"" + protocolVersion
                    + "\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}");
                case "tools/list" -> {
                    send(out, toolsListJson(id, line));
                    if (exitAfterListArmed) {
                        exitAfterListArmed = false;
                        System.exit(0); // 首轮:会话建立后立刻死亡(崩溃恢复/漂移/耗尽矩阵的起点)
                    }
                }
                case "tools/call" -> handleCall(out, id, line);
                default -> send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":-32601,\"message\":\"method not found: " + methodMatch.group(1) + "\"}}");
            }
        }
    }

    private static void handleCall(PrintWriter out, long id, String line) throws InterruptedException {
        int paramsAt = line.indexOf("\"params\"");
        String name = "";
        if (paramsAt >= 0) {
            Matcher nameMatch = NAME.matcher(line.substring(paramsAt));
            if (nameMatch.find()) {
                name = nameMatch.group(1);
            }
        }
        if (delayArmed) {
            delayArmed = false;
            Thread.sleep(delayMillis);
        }
        if (garbleArmed) {
            garbleArmed = false;
            out.print("this line is not json {\n");
            out.flush();
            return;
        }
        if (wrongIdArmed) {
            wrongIdArmed = false;
            respond(out, id + 10000, name, line);
            return;
        }
        if (missingResultArmed) {
            missingResultArmed = false;
            send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id + "}"); // 既无 result 也无 error
            return;
        }
        if (rpcErrorArmed) {
            rpcErrorArmed = false;
            send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"error\":{\"code\":-32000,\"message\":\"fixture boom\"}}");
            return;
        }
        respond(out, id, name, line);
        if (listChangedArmed || garbleRefreshArmed) {
            boolean notify = listChangedArmed || garbleRefreshArmed;
            if (listChangedArmed) {
                listChangedArmed = false;
                if (listChangedCsv != null) {
                    tools = new ArrayList<>(listChangedCsv); // 刷新重取时看到新清单
                }
            }
            if (garbleRefreshArmed) {
                garbleRefreshArmed = false;
                garbleNextList = true; // 通知后的首个 tools/list 报文畸形
            }
            if (notify) {
                send(out, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
            }
        }
    }

    private static void respond(PrintWriter out, long id, String name, String line) {
        switch (name) {
            case "echo" -> {
                Matcher messageMatch = MESSAGE.matcher(line);
                String message = messageMatch.find() ? messageMatch.group(1) : "";
                send(out, result(id, "echo: " + message, false));
            }
            case "big" -> send(out, result(id,
                "x".repeat((int) Math.min(bigBytes, 16_000_000)), false));
            case "env" -> send(out, result(id, envDump(), false));
            case "fail" -> send(out, result(id, "fixture failure", true));
            default -> send(out, result(id, "unknown tool: " + name, true));
        }
    }

    private static String result(long id, String text, boolean isError) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
            + esc(text) + "\"}],\"isError\":" + isError + "}}";
    }

    private static String toolsListJson(long id, String line) {
        if (garbleNextList) {
            garbleNextList = false;
            return "this line is not json {";
        }
        Matcher cursorMatch = CURSOR.matcher(line);
        if (endlessPages) {
            int page = cursorMatch.find() ? Integer.parseInt(cursorMatch.group(1)) : 1;
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                + toolEntry("endless-" + page, false) + "],\"nextCursor\":\"" + (page + 1) + "\"}}";
        }
        int start = cursorMatch.find() ? Integer.parseInt(cursorMatch.group(1)) : 0;
        List<String> names = new ArrayList<>(tools);
        if (dupTools) {
            names.add("echo");
        }
        if (badName) {
            names.add("bad__name");
        }
        int end = pageSize > 0 ? Math.min(start + pageSize, names.size()) : names.size();
        StringBuilder json = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
            .append(",\"result\":{\"tools\":[");
        for (int i = start; i < end; i++) {
            if (i > start) {
                json.append(',');
            }
            json.append(toolEntry(names.get(i), missingSchema && "echo".equals(names.get(i))));
        }
        json.append(']');
        if (end < names.size()) {
            json.append(",\"nextCursor\":\"").append(end).append('"');
        }
        return json.append("}}").toString();
    }

    private static String toolEntry(String name, boolean omitSchema) {
        String schema = richSchema && "echo".equals(name) ? RICH_SCHEMA : "{\"type\":\"object\"}";
        return "{\"name\":\"" + name + "\",\"description\":\"fixture tool " + name + "\""
            + (omitSchema ? "" : ",\"inputSchema\":" + schema) + "}";
    }

    private static void send(PrintWriter out, String json) {
        out.print(json);
        out.print('\n');
        out.flush();
    }

    private static String envDump() {
        return System.getenv().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("\n"));
    }

    private static List<String> csv(String value) {
        return List.of(value.split(","));
    }

    private static String esc(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }
}
