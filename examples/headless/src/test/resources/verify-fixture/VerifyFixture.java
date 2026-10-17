/*
 * it27 停 3 验收夹具：--verify 摘要面的最小 MCP stdio server（单文件、零依赖、
 * JDK 直跑：java VerifyFixture.java）。initialize 协商 + tools/list 单工具 echo，
 * 不写 stderr——用于断言「启用组合下 MCP 工具注册且全数非豁免、零噪声」。
 * 完整故障注入矩阵在 mcp/stdio 的测试夹具（FixtureServer.java）。
 */
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VerifyFixture {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

    private VerifyFixture() {
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            Matcher method = METHOD.matcher(line);
            Matcher id = ID.matcher(line);
            if (!method.find() || !id.find()) {
                continue; // 通知（notifications/initialized…）：无 id，单向
            }
            long requestId = Long.parseLong(id.group(1));
            switch (method.group(1)) {
                case "initialize" -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + requestId
                    + ",\"result\":{\"protocolVersion\":\"2026-07-28\","
                    + "\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"verify-fixture\",\"version\":\"1\"}}}");
                case "tools/list" -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + requestId
                    + ",\"result\":{\"tools\":[{\"name\":\"echo\","
                    + "\"description\":\"verify fixture echo\","
                    + "\"inputSchema\":{\"type\":\"object\","
                    + "\"properties\":{\"message\":{\"type\":\"string\"}}}}]}}");
                default -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + requestId
                    + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
            }
            out.flush();
        }
    }
}
