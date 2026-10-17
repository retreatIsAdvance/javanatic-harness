package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpCatalogDriftException;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 工具消费插件（id "mcp-tool"，requires "tools" + "mcp-stdio"，it27 停 2）：
 * 已连接 server 的冻结清单 → 既有 {@code ScopedRegistry} 注册
 * （{@code mcp__<server>__<raw>} 命名；{@code approvalExempt=false} 恒真、
 * 无豁免出口）+ 每轮 REQUEST 检查点（先于 loop 的 {@code LlmRequestEvent}）：
 * 漂移（DRIFTED）落轮终（请重开会话）；首个请求落 mcp/catalog 事件；此后
 * 冻结清单一致零噪声、不一致 fail loud——清单事实在日志里，回放重建零联网
 * 零进程（R1/T4）。schema 映射白名单见 {@link McpSchemaMapping}（不支持构造
 * 该工具排除 + WARN）。
 */
public final class McpToolPlugin implements Plugin {

    private static final System.Logger LOG = System.getLogger("io.javanatic.harness.mcp.tool");

    @Override
    public String id() {
        return "mcp-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "mcp-stdio");
    }

    @Override
    public void apply(Scope scope) {
        McpClientService client = scope.require(McpClientService.KEY);
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        for (McpServerConnection connection : client.connections()) {
            for (McpToolDescriptor descriptor : connection.tools()) {
                String name = fullName(descriptor);
                McpSchemaMapping.Mapping mapping =
                    McpSchemaMapping.map(descriptor.inputSchemaJson(), name);
                for (String warning : mapping.warnings()) {
                    LOG.log(System.Logger.Level.WARNING, warning);
                }
                if (mapping.schema() == null) {
                    continue; // 排除即数据：不注册，理由已 WARN
                }
                ToolDefinition tool = ToolDefinition.of(name, descriptor.description(),
                    mapping.schema(), call(connection, descriptor.name()));
                scope.onClose(registry.register(scope, tool));
            }
        }
        // REQUEST 检查点：apply 期同步直订（挂载视图绑共享层，订阅先行）——派发
        // 先于 loop 的 llm/request 落账，漂移落轮终、清单首次落账 / 冻结一致零噪声。
        scope.onClose(scope.events().onWaterfall(AgentEvents.REQUEST, (carrier, args) -> {
            checkpoint(((Agent) carrier).session(), client);
            return args.next();
        }));
    }

    /** {@code mcp__<server>__<raw>}；配置校验与 tools/list 校验两处之外的廉价防御：含 "__" 即 fail loud。 */
    static String fullName(McpToolDescriptor descriptor) {
        if (descriptor.serverId().contains("__") || descriptor.name().contains("__")) {
            throw new IllegalStateException("mcp tool naming conflict: '__' in '"
                + descriptor.serverId() + "/" + descriptor.name() + "'");
        }
        return "mcp__" + descriptor.serverId() + "__" + descriptor.name();
    }

    /** 调用桥：raw 名与 wire 原文实参（ToolArgs 顶层原文，不经重序列化）直下 transport。 */
    private static ToolDefinition.Tool call(McpServerConnection connection, String rawName) {
        return (args, context) -> {
            McpCallResult result = connection.call(rawName, args.rawJson(), context.signal());
            return result.isError()
                ? ToolExecutionResult.error(result.content())
                : ToolExecutionResult.success(result.content());
        };
    }

    /** 轮首检查点：漂移优先（轮终语义——先于任何清单落账），随后清单对账。 */
    static void checkpoint(Session session, McpClientService client) {
        throwIfDrifted(client);
        syncCatalogLedger(session, entries(client));
    }

    /** DRIFTED（重连后清单不一致）→ 落轮终异常；普通不可用（FAILED/RECONNECTING）不拦（call 侧按错误结果降级）。 */
    static void throwIfDrifted(McpClientService client) {
        List<String> drifted = client.connections().stream()
            .filter(connection -> connection.state() == McpServerState.DRIFTED)
            .map(McpServerConnection::serverId)
            .toList();
        if (!drifted.isEmpty()) {
            throw new McpCatalogDriftException("mcp server '" + String.join("', '", drifted)
                + "' 清单与会话启动不一致，请重开会话");
        }
    }

    /** 已注册（映射成功）工具条目——排除工具不进账（fold 只重建注册面）；按 (serverId, name) 规范排序。 */
    static List<McpCatalogEvent.Entry> entries(McpClientService client) {
        List<McpCatalogEvent.Entry> entries = new ArrayList<>();
        for (McpServerConnection connection : client.connections()) {
            for (McpToolDescriptor descriptor : connection.tools()) {
                if (McpSchemaMapping.map(descriptor.inputSchemaJson(), fullName(descriptor))
                        .schema() != null) {
                    entries.add(new McpCatalogEvent.Entry(descriptor.serverId(), descriptor.name(),
                        descriptor.description(), descriptor.inputSchemaJson()));
                }
            }
        }
        entries.sort(Comparator.comparing(McpCatalogEvent.Entry::serverId)
            .thenComparing(McpCatalogEvent.Entry::name));
        return List.copyOf(entries);
    }

    /** 清单对账：从未有过 + 无工具 = 零噪声；首个请求落账；冻结一致零开销；不等 fail loud（请重开会话）。 */
    static void syncCatalogLedger(Session session, List<McpCatalogEvent.Entry> current) {
        Optional<McpCatalogEvent> latest = latest(session);
        if (latest.isEmpty() && current.isEmpty()) {
            return;
        }
        if (latest.isPresent() && latest.get().entries().equals(current)) {
            return;
        }
        if (latest.isEmpty()) {
            session.append(new McpCatalogEvent(System.currentTimeMillis(), current));
            return;
        }
        throw new IllegalStateException("mcp catalog 与会话启动不一致，请重开会话"
            + diffDetail(latest.get().entries(), current));
    }

    private static Optional<McpCatalogEvent> latest(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(McpCatalogEvent.class::isInstance)
            .map(McpCatalogEvent.class::cast)
            .reduce((first, second) -> second);
    }

    /** added/removed/changed（键 = serverId/name；changed = description 或 schema 不等）。 */
    private static String diffDetail(List<McpCatalogEvent.Entry> latest,
                                     List<McpCatalogEvent.Entry> current) {
        Map<String, McpCatalogEvent.Entry> before = byKey(latest);
        Map<String, McpCatalogEvent.Entry> after = byKey(current);
        List<String> added = after.keySet().stream().filter(key -> !before.containsKey(key)).toList();
        List<String> removed = before.keySet().stream().filter(key -> !after.containsKey(key)).toList();
        List<String> changed = after.keySet().stream()
            .filter(before::containsKey)
            .filter(key -> !before.get(key).description().equals(after.get(key).description())
                || !before.get(key).schema().equals(after.get(key).schema()))
            .toList();
        return "（added=" + added + ", removed=" + removed + ", changed=" + changed + "）";
    }

    private static Map<String, McpCatalogEvent.Entry> byKey(List<McpCatalogEvent.Entry> entries) {
        Map<String, McpCatalogEvent.Entry> byKey = new LinkedHashMap<>();
        for (McpCatalogEvent.Entry entry : entries) {
            byKey.put(entry.serverId() + "/" + entry.name(), entry);
        }
        return byKey;
    }
}
