package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpToolDescriptor;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 工具消费插件（id "mcp-tool"，requires "tools" + "mcp-registry"，it27.1）：
 * 已连接 server 的当前清单 → 既有 {@code ScopedRegistry} 注册
 * （{@code mcp__<server>__<raw>} 命名；raw 名不合法/超长时确定性归一为
 * {@code mcp__<server>__<清洗(raw)>_<hash8>} + WARN；{@code approvalExempt=false}
 * 恒真、无豁免出口）+ 每轮 REQUEST 检查点（先于 loop 的
 * {@code LlmRequestEvent}）落 mcp/catalog 事件；一致零噪声、不一致 fail loud——
 * 清单事实在日志里，回放重建零联网零进程（R1/T4）。schema 映射白名单见
 * {@link McpSchemaMapping}（不支持构造该工具排除 + WARN）。
 *
 * <p>it27.1 停 1 仅完成 transport 中立装配（消费面经注册表聚合）；清单代际的
 * 轮屏障换代（TURN_STOPPING / PRE_STEP）与失败路径在后续停点落地。
 */
public final class McpToolPlugin implements Plugin {

    private static final System.Logger LOG = System.getLogger("io.javanatic.harness.mcp.tool");

    /** 公开名上限（dsh publicToolName 同口径）。 */
    static final int MAX_PUBLIC_NAME_LENGTH = 64;

    /** 归一段哈希长度：sha256 前 8 位十六进制（32 bit 消歧）。 */
    private static final int HASH_HEX_LENGTH = 8;

    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern UNDERSCORE_RUN = Pattern.compile("_{2,}");

    /** 哈希输入的字段分隔（避免 server/raw 拼接二义）。 */
    private static final char HASH_SEPARATOR = '\0';

    @Override
    public String id() {
        return "mcp-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "mcp-registry");
    }

    @Override
    public void apply(Scope scope) {
        McpClientService client = scope.require(McpClientService.KEY);
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        for (McpServerConnection connection : client.connections()) {
            for (McpToolDescriptor descriptor : connection.tools()) {
                PublicName name = publicName(descriptor);
                if (name.normalized()) {
                    LOG.log(System.Logger.Level.WARNING, "mcp tool name normalized: "
                        + descriptor.serverId() + "/" + descriptor.name() + " -> " + name.value());
                }
                McpSchemaMapping.Mapping mapping =
                    McpSchemaMapping.map(descriptor.inputSchemaJson(), name.value());
                for (String warning : mapping.warnings()) {
                    LOG.log(System.Logger.Level.WARNING, warning);
                }
                if (mapping.schema() == null) {
                    continue; // 排除即数据：不注册，理由已 WARN
                }
                ToolDefinition tool = ToolDefinition.of(name.value(), descriptor.description(),
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

    /**
     * T2 增补公开名（确定性纯函数）：server id 含 "__" → fail loud（配置面已拦，
     * 消费面廉价防御）；raw 合法（非空、仅 {@code [A-Za-z0-9_-]}、不含 "__"、拼接
     * 后 ≤ {@value #MAX_PUBLIC_NAME_LENGTH}）→ 现状 {@code mcp__<server>__<raw>}；
     * 否则清洗（非法字符→'_'、连续 '_' 折叠、去首尾 '_'）+ {@code _<hash8>}，
     * hash8 = sha256(serverId \0 rawName) 前 8 位十六进制。超长时截断清洗段；
     * 前缀 + 哈希段已无空间（server id 过长）→ fail loud。归一结果固定记录在
     * mcp/catalog（fold 不依赖算法版本）。
     */
    static PublicName publicName(McpToolDescriptor descriptor) {
        if (descriptor.serverId().contains("__")) {
            throw new IllegalStateException("mcp tool naming conflict: '__' in server id '"
                + descriptor.serverId() + "'");
        }
        String prefix = "mcp__" + descriptor.serverId() + "__";
        String raw = descriptor.name();
        String joined = prefix + raw;
        if (!raw.isEmpty() && raw.indexOf("__") < 0
                && !INVALID_NAME_CHARS.matcher(raw).find()
                && joined.length() <= MAX_PUBLIC_NAME_LENGTH) {
            return new PublicName(joined, false);
        }
        String sanitized = UNDERSCORE_RUN.matcher(
            INVALID_NAME_CHARS.matcher(raw).replaceAll("_")).replaceAll("_");
        sanitized = stripUnderscores(sanitized);
        if (sanitized.isEmpty()) {
            sanitized = "tool"; // 全非法/空 raw 的确定性兜底
        }
        String suffix = "_" + hash8(descriptor.serverId(), raw);
        int available = MAX_PUBLIC_NAME_LENGTH - prefix.length() - suffix.length();
        if (available < 1) {
            throw new IllegalStateException("mcp tool public name exceeds "
                + MAX_PUBLIC_NAME_LENGTH + " chars before raw name: '"
                + descriptor.serverId() + "/" + raw + "'");
        }
        if (sanitized.length() > available) {
            sanitized = stripUnderscores(sanitized.substring(0, available));
        }
        return new PublicName(prefix + sanitized + suffix, true);
    }

    private static String stripUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String hash8(String serverId, String rawName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((serverId + HASH_SEPARATOR + rawName).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, HASH_HEX_LENGTH / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 公开名 + 是否发生归一（WARN 与测试观察面）。 */
    record PublicName(String value, boolean normalized) {

        PublicName {
            Objects.requireNonNull(value, "value");
        }
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

    /** 轮首检查点：清单对账（首个请求落账；一致零噪声；不一致 fail loud）。 */
    static void checkpoint(Session session, McpClientService client) {
        syncCatalogLedger(session, entries(client));
    }

    /** 已注册（映射成功）工具条目——排除工具不进账（fold 只重建注册面）；按 (serverId, name) 规范排序。 */
    static List<McpCatalogEvent.Entry> entries(McpClientService client) {
        List<McpCatalogEvent.Entry> entries = new ArrayList<>();
        for (McpServerConnection connection : client.connections()) {
            for (McpToolDescriptor descriptor : connection.tools()) {
                PublicName name = publicName(descriptor);
                if (McpSchemaMapping.map(descriptor.inputSchemaJson(), name.value())
                        .schema() != null) {
                    entries.add(new McpCatalogEvent.Entry(descriptor.serverId(), descriptor.name(),
                        name.value(), descriptor.description(), descriptor.inputSchemaJson()));
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

    /** added/removed/changed（键 = serverId/name；changed = publicName/description/schema 不等）。 */
    private static String diffDetail(List<McpCatalogEvent.Entry> latest,
                                     List<McpCatalogEvent.Entry> current) {
        Map<String, McpCatalogEvent.Entry> before = byKey(latest);
        Map<String, McpCatalogEvent.Entry> after = byKey(current);
        List<String> added = after.keySet().stream().filter(key -> !before.containsKey(key)).toList();
        List<String> removed = before.keySet().stream().filter(key -> !after.containsKey(key)).toList();
        List<String> changed = after.keySet().stream()
            .filter(before::containsKey)
            .filter(key -> !before.get(key).publicName().equals(after.get(key).publicName())
                || !before.get(key).description().equals(after.get(key).description())
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
