package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.mcp.McpClientService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MCP stdio 供应插件（id "mcp-stdio"，it27）：装配期解析行配置 servers[]
 * （id / command / args / env 白名单 / timeoutSeconds），拉起全部已配 server
 * 子进程（哪怕本轮不用；成本口径 07），以 {@link McpClientService} 提供连接面。
 * server 进程是 harness 的直接子进程、以用户全权运行（不入沙箱——治理边界
 * 是工具调用面而非进程面，见 module-info）；进程树随本 scope close 回收。
 * 断线有限重连（缺省 500ms→30s ×10 档），重连后清单与会话启动不一致即
 * DRIFTED 终态（T4：不静默降级，诊断「请重开会话」——会话内清单冻结 R1/T4）。
 */
public final class McpStdioPlugin implements Plugin {

    /** 重连退避档（发射间隔随档递增，上限 30s；数组长度 = 最大尝试次数）。 */
    private static final Duration[] DEFAULT_BACKOFF = {
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
        Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30),
        Duration.ofSeconds(30), Duration.ofSeconds(30)
    };

    private final Duration[] backoff;

    /** 数据组合路径：重连用文档化缺省退避档。 */
    public McpStdioPlugin() {
        this(DEFAULT_BACKOFF);
    }

    /** @param backoff 重连退避档（测试注伪短档提速重连矩阵；构造后外部修改不影响本实例） */
    McpStdioPlugin(Duration[] backoff) {
        this.backoff = Objects.requireNonNull(backoff, "backoff").clone();
    }

    @Override
    public String id() {
        return "mcp-stdio";
    }

    @Override
    public void apply(Scope scope) {
        List<McpServerConfig> servers = McpServerConfig.parseAll(
            scope.require(ConfigService.KEY).configFor(id()), id());
        List<StdioMcpConnection> started = new ArrayList<>(servers.size());
        // 先挂回收后启动:中途失败时已拉起的部分仍随 scope close 回收,不留孤儿进程
        scope.onClose(() -> started.forEach(StdioMcpConnection::close));
        for (McpServerConfig server : servers) {
            StdioMcpConnection connection = StdioMcpConnection.start(server, backoff);
            // 装配单线程是装载契约(PluginLoader.loadAllUnder 同步 apply,回滚/收尾在其返回后);
            // 并发 close 属框架误用面。本检查为廉价护栏(评审 F2):close 若已越过本循环,
            // 该进程不在 forEach 覆盖内——自行收口并 fail loud,不静默留孤儿
            if (scope.isClosed()) {
                connection.close();
                throw new IllegalStateException(
                    "mcp-stdio: scope closed during assembly; started connection rolled back");
            }
            started.add(connection);
        }
        scope.provide(McpClientService.KEY, new StdioClientService(started));
    }
}
