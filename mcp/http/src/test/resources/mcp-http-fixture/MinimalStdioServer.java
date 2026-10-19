/*
 * 跨 transport 撞 id 测试用的最小 stdio MCP server：单文件、零依赖、JDK 直跑
 * (java MinimalStdioServer.java)。换行分帧 JSON-RPC；只应答 initialize 与
 * tools/list（空清单）——足以让 mcp-stdio 完成装配并注册连接。
 */
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MinimalStdioServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private MinimalStdioServer() {
    }

    public static void main(String[] args) throws Exception {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String id = idOf(line);
                String reply;
                if (line.contains("\"method\":\"initialize\"")) {
                    reply = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2026-07-28\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"minimal-stdio\",\"version\":\"1\"}}}";
                } else if (line.contains("\"method\":\"tools/list\"")) {
                    reply = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[]}}";
                } else {
                    continue; // 通知/其余方法不应答
                }
                out.println(reply);
            }
        }
    }

    private static String idOf(String line) {
        Matcher matcher = ID.matcher(line);
        return matcher.find() ? matcher.group(1) : "null";
    }
}
