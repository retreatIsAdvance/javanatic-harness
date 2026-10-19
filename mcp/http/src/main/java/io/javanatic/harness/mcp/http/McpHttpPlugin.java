package io.javanatic.harness.mcp.http;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.mcp.McpClientService;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MCP Streamable HTTP 供应插件（id "mcp-http"，it27.1）：装配期解析行配置
 * servers[]（id / url / headers / timeoutSeconds），建立全部会话（initialize
 * 协商 + tools/list 快照）并向 {@link McpClientService} 注册表逐条注册——
 * 与 mcp-stdio 可同时在场，消费面只经注册表聚合。零 server 即零噪声：不建
 * HTTP 客户端、不发请求、不注册工具。
 *
 * <p>客户端固定 {@link HttpClient.Redirect#NEVER}（不跟随重定向）；断线有限
 * 重连（缺省 500ms→30s ×10 档）；清单变化（通知 / 重连对账）递增代际，由消费面
 * 在轮屏障采纳（T4）。HTTP 客户端随本 scope close 回收。
 */
public final class McpHttpPlugin implements Plugin {

    /** 重连退避档（发射间隔随档递增，上限 30s；数组长度 = 最大尝试次数）。 */
    private static final Duration[] DEFAULT_BACKOFF = {
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
        Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30),
        Duration.ofSeconds(30), Duration.ofSeconds(30)
    };

    /** 建连超时（与单请求 timeout 分离；连接不可达时快速失败）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final Duration[] backoff;

    /** 数据组合路径：重连用文档化缺省退避档。 */
    public McpHttpPlugin() {
        this(DEFAULT_BACKOFF);
    }

    /** @param backoff 重连退避档（测试注伪短档提速重连矩阵；构造后外部修改不影响本实例） */
    McpHttpPlugin(Duration[] backoff) {
        this.backoff = Objects.requireNonNull(backoff, "backoff").clone();
    }

    @Override
    public String id() {
        return "mcp-http";
    }

    @Override
    public Set<String> requires() {
        return Set.of("mcp-registry");
    }

    @Override
    public void apply(Scope scope) {
        McpClientService client = scope.require(McpClientService.KEY);
        List<McpHttpServerConfig> servers = McpHttpServerConfig.parseAll(
            scope.require(ConfigService.KEY).configFor(id()), id());
        if (servers.isEmpty()) {
            return; // 零 server = 零噪声:不建客户端/线程/连接
        }
        HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        // 注册序与回收序相反:连接先关(DELETE/停通知),客户端最后收
        scope.onClose(httpClient::close);
        List<HttpMcpConnection> started = new ArrayList<>(servers.size());
        scope.onClose(() -> started.forEach(HttpMcpConnection::close));
        for (McpHttpServerConfig server : servers) {
            HttpMcpConnection connection = HttpMcpConnection.start(server, httpClient, backoff);
            // 装配单线程是装载契约(PluginLoader.loadAllUnder 同步 apply,回滚/收尾在其返回后);
            // 并发 close 属框架误用面。本检查为廉价护栏(与 mcp-stdio 同口径):close 若已越过
            // 本循环,该会话不在 forEach 覆盖内——自行收口并 fail loud,不静默留活会话
            if (scope.isClosed()) {
                connection.close();
                throw new IllegalStateException(
                    "mcp-http: scope closed during assembly; started connection rolled back");
            }
            started.add(connection);
            scope.onClose(client.register(connection)); // LIFO:先摘注册面,再收连接/会话
        }
    }
}
