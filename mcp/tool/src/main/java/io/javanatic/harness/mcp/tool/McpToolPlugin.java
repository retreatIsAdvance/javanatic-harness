package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.agentloop.PreStepDecision;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpCatalogSwapException;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerConnection;
import io.javanatic.harness.mcp.McpToolDescriptor;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 恒真、无豁免出口）。
 *
 * <p><b>换代屏障（T4 重裁）</b>：清单代际只在 {@link AgentEvents#TURN_STOPPING}
 * （轮排空、关轮前，同步顺序通知）与 {@link AgentEvents#PRE_STEP}（轮首、admit
 * 之前）比较并采纳——轮内绝不改注册面。REQUEST 阶段在 {@code AgentLoopImpl}
 * 中先读 {@code tools.schemas()} 再派发，任何 REQUEST 内换代都会让本次请求的
 * schema 与账本代际错位（R1）。换代先预构建完整新快照并全量预检（publicName /
 * schema 映射 / 快照内去重 / 与注册面现有非本代工具撞名），预检失败零中间态，
 * 旧代原样保活并抛 {@link McpCatalogSwapException}（当前轮 Error 收口；server
 * 不转 FAILED，下一 PRE_STEP 重试）。单线程轮屏障下无请求/工具派发，摘旧代与
 * 注册新代之间的空窗不可观察——免双代机制；并发多 agent 共享 root 注册面时的
 * 全局静默点不在本迭代（单 agent 顺序假设显式登记）。
 *
 * <p><b>R1 账本</b>：{@code mcp/catalog} 每代全量快照，PRE_STEP（admit 之前、
 * user/message 之前）落账；首个非空代落账、一致零噪声、退役空清单落账。
 * 事件形状不变（不加 generation 字段，日志序即代，fold 末值胜）。resume 时
 * 实时清单与账本对账：不同即作为新一代在首个屏障采纳并落账（不再要求重开会话）。
 *
 * <p><b>结果上限</b>：{@code maxResultBytes}（缺省 256 KiB，正数）在调用桥按
 * UTF-8 完整字符边界截断成功与工具级错误内容，尾附
 * {@value #TRUNCATED_MARKER}；截断是内容事实，不是错误。
 * <b>映射缓存</b>：publicName 与 schema 映射按 {@code (serverId, rawName, schema)}
 * 跨代复用，仅在换代时计算新增/变化项；缓存随采纳代裁剪（有界）。
 */
public final class McpToolPlugin implements Plugin {

    private static final System.Logger LOG = System.getLogger("io.javanatic.harness.mcp.tool");

    /** 公开名上限（dsh publicToolName 同口径）。 */
    static final int MAX_PUBLIC_NAME_LENGTH = 64;

    /** 结果上限缺省：256 KiB（与 fs {@code maxReadBytes} / shell {@code maxOutputBytes} 同口径）。 */
    static final int DEFAULT_MAX_RESULT_BYTES = 256 * 1024;

    /** 截断标记（与 {@code FsService.READ_TRUNCATED_MARKER} 同文本）。 */
    static final String TRUNCATED_MARKER = " (output truncated)";

    /** 归一段哈希长度：sha256 前 8 位十六进制（32 bit 消歧）。 */
    private static final int HASH_HEX_LENGTH = 8;

    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern UNDERSCORE_RUN = Pattern.compile("_{2,}");

    /** 哈希输入的字段分隔（避免 server/raw 拼接二义）。 */
    private static final char HASH_SEPARATOR = '\0';

    private final Integer explicitMaxResultBytes;

    /** 数据组合路径：结果上限从行配置解析（缺省 256 KiB）。 */
    public McpToolPlugin() {
        this.explicitMaxResultBytes = null;
    }

    /** @param maxResultBytes 结果上限（程序化组合的显式选择，正数） */
    public McpToolPlugin(int maxResultBytes) {
        if (maxResultBytes <= 0) {
            throw new IllegalArgumentException("maxResultBytes must be positive: " + maxResultBytes);
        }
        this.explicitMaxResultBytes = maxResultBytes;
    }

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
        Surface surface = new Surface(scope, registry, client, maxResultBytes(scope));
        surface.registerInitial();
        // PRE_STEP 直订（挂载视图绑共享层，订阅先行）：与首轮 admit 的相位由
        // 订阅先行保证，不经 agent 创建期的异步通知（it26 停 3 实证）。
        // 先让链完成裁决：被拒绝的 turn 不采纳、不落账（下一 accepted 轮补）。
        scope.onClose(scope.events().onWaterfall(AgentEvents.PRE_STEP, (carrier, args) -> {
            PreStepDecision decision = args.next();
            if (decision instanceof PreStepDecision.Enter) {
                surface.adopt(((Agent) carrier).session(), true);
            }
            return decision;
        }));
        // 轮末屏障：只换代，不落账（下一次 PRE_STEP 在 admit 之前落 pending 账）。
        scope.onClose(scope.events().on(AgentEvents.TURN_STOPPING, (carrier, payload) ->
            surface.adopt(((Agent) carrier).session(), false)));
    }

    /** 结果上限：显式 > 行配置 > 缺省；非正/超 int 范围 fail loud（组合期错误）。 */
    private int maxResultBytes(Scope scope) {
        if (explicitMaxResultBytes != null) {
            return explicitMaxResultBytes;
        }
        long configured = scope.resolve(ConfigService.KEY)
            .map(config -> ConfigValues.longValue(config.configFor(id()), id(),
                "maxResultBytes", DEFAULT_MAX_RESULT_BYTES))
            .orElse((long) DEFAULT_MAX_RESULT_BYTES);
        if (configured <= 0 || configured > Integer.MAX_VALUE) {
            throw new IllegalStateException("config 'maxResultBytes' for plugin '" + id()
                + "' must be a positive int: " + configured);
        }
        return (int) configured;
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

    /**
     * 结果上限：UTF-8 完整字符边界截断 + 标记。按码点累计字节（不整串编码，
     * 超大输出提前停止）；不截断时返回原串。标记不计入上限（与 fs 同口径）。
     */
    static String truncate(String content, int maxBytes) {
        Objects.requireNonNull(content, "content");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        int bytes = 0;
        int index = 0;
        while (index < content.length()) {
            int codePoint = content.codePointAt(index);
            int size = utf8Bytes(codePoint);
            if (bytes + size > maxBytes) {
                return content.substring(0, index) + TRUNCATED_MARKER;
            }
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return content;
    }

    /** UTF-8 编码长度（未配对代理按 3 计——宁早截，不越上限）。 */
    private static int utf8Bytes(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    /** 清单同步：一致零噪声；首个非空代与变更代落全量快照（日志序即代）。 */
    static void syncLedger(Session session, List<McpCatalogEvent.Entry> current) {
        Optional<McpCatalogEvent> latest = latest(session);
        if (latest.isPresent() && latest.get().entries().equals(current)) {
            return;
        }
        if (latest.isEmpty() && current.isEmpty()) {
            return; // 零 server 零工具：从未有过就不落账（零噪声）
        }
        session.append(new McpCatalogEvent(System.currentTimeMillis(), current));
    }

    private static Optional<McpCatalogEvent> latest(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(McpCatalogEvent.class::isInstance)
            .map(McpCatalogEvent.class::cast)
            .reduce((first, second) -> second);
    }

    /** 公开名 + 是否发生归一（WARN 与测试观察面）。 */
    record PublicName(String value, boolean normalized) {

        PublicName {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 单次装配的注册面状态（apply 内创建，监听器共享）：当前代句柄、账条目、
     * 代际签名与映射缓存。插件实例无可变状态（多 runtime 复用安全）。
     */
    private static final class Surface {

        private final Scope owner;
        private final ToolRegistry registry;
        private final McpClientService client;
        private final int maxResultBytes;
        private final Map<CacheKey, Cached> cache = new HashMap<>();
        private final Map<String, Disposable> handles = new LinkedHashMap<>();
        private List<McpCatalogEvent.Entry> entries = List.of();
        private List<String> signature = List.of();
        private Set<CacheKey> keys = Set.of();

        Surface(Scope owner, ToolRegistry registry, McpClientService client, int maxResultBytes) {
            this.owner = owner;
            this.registry = registry;
            this.client = client;
            this.maxResultBytes = maxResultBytes;
        }

        /** apply 期首代注册（注册面自装配起可用；账在首个 PRE_STEP 落）。 */
        void registerInitial() {
            Snapshot snapshot = build();
            for (Draft draft : snapshot.drafts()) {
                Disposable handle = registry.register(owner, definition(draft));
                owner.onClose(handle);
                handles.put(draft.entry().publicName(), handle);
            }
            adoptSnapshot(snapshot);
        }

        /**
         * 屏障采纳：代际签名变化才重建/换代；{@code appendLedger} 在 PRE_STEP
         * 且裁决 Enter 时为 true。换代失败抛 {@link McpCatalogSwapException}，
         * 旧代保活（缓存裁剪回旧代，下一屏障可重试）。
         */
        void adopt(Session session, boolean appendLedger) {
            if (!currentSignature().equals(signature)) {
                Snapshot next;
                try {
                    next = build();
                    precheckForeignCollisions(next);
                } catch (McpCatalogSwapException e) {
                    cache.keySet().retainAll(keys);
                    throw e;
                } catch (RuntimeException e) {
                    cache.keySet().retainAll(keys);
                    throw new McpCatalogSwapException(
                        "mcp catalog 无法采纳（旧代保活，下一轮重试）: " + e.getMessage(), e);
                }
                swap(next);
                adoptSnapshot(next);
            }
            if (appendLedger) {
                syncLedger(session, entries);
            }
        }

        /** 注册面当前代际签名（server 排序来自 client.connections）。 */
        private List<String> currentSignature() {
            List<String> current = new ArrayList<>();
            for (McpServerConnection connection : client.connections()) {
                current.add(connection.serverId() + "@" + connection.catalogGeneration());
            }
            return List.copyOf(current);
        }

        /**
         * 构建候选快照（纯读 + 缓存）：逐连接稳定读（代际前后一致才采纳，
         * 变化中重试 3 次后 fail loud）；publicName/schema 走缓存；快照内公开名
         * 去重。不触碰注册面——调用方预检通过后才 swap。
         */
        private Snapshot build() {
            List<String> candidateSignature = new ArrayList<>();
            List<Draft> drafts = new ArrayList<>();
            Set<CacheKey> candidateKeys = new LinkedHashSet<>();
            for (McpServerConnection connection : client.connections()) {
                Stable stable = stableCatalog(connection);
                candidateSignature.add(stable.serverId() + "@" + stable.generation());
                for (McpToolDescriptor descriptor : stable.tools()) {
                    CacheKey key = new CacheKey(descriptor.serverId(), descriptor.name(),
                        descriptor.inputSchemaJson());
                    candidateKeys.add(key);
                    Cached cached = cache.computeIfAbsent(key, ignored -> compute(descriptor));
                    if (cached.schema() == null) {
                        continue; // 排除即数据：不注册、不进账；理由已 WARN
                    }
                    drafts.add(new Draft(new McpCatalogEvent.Entry(
                        descriptor.serverId(), descriptor.name(), cached.publicName().value(),
                        descriptor.description(), descriptor.inputSchemaJson()),
                        cached.schema(), connection, descriptor.name()));
                }
            }
            drafts.sort(Comparator.comparing((Draft draft) -> draft.entry().serverId())
                .thenComparing(draft -> draft.entry().name()));
            Set<String> names = new LinkedHashSet<>();
            for (Draft draft : drafts) {
                if (!names.add(draft.entry().publicName())) {
                    throw new IllegalStateException("duplicate public tool name '"
                        + draft.entry().publicName() + "' in mcp catalog snapshot");
                }
            }
            List<McpCatalogEvent.Entry> candidateEntries = drafts.stream()
                .map(Draft::entry)
                .toList();
            return new Snapshot(List.copyOf(drafts), candidateEntries,
                List.copyOf(candidateSignature), Set.copyOf(candidateKeys));
        }

        /** 稳定读：代际前后一致才算同代快照（provider 异步发布，读一次拿全的补充防线）。 */
        private static Stable stableCatalog(McpServerConnection connection) {
            for (int attempt = 0; attempt < 3; attempt++) {
                long before = connection.catalogGeneration();
                List<McpToolDescriptor> tools = connection.tools();
                long after = connection.catalogGeneration();
                if (before == after) {
                    return new Stable(connection.serverId(), after, tools);
                }
            }
            throw new McpCatalogSwapException("mcp catalog is changing during barrier for server '"
                + connection.serverId() + "'; keep old generation and retry next turn");
        }

        /** 缓存未命中：publicName（归一 WARN 一次）+ schema 映射（排除/属性 WARN 一次）。 */
        private static Cached compute(McpToolDescriptor descriptor) {
            PublicName publicName = publicName(descriptor);
            if (publicName.normalized()) {
                LOG.log(System.Logger.Level.WARNING, "mcp tool name normalized: "
                    + descriptor.serverId() + "/" + descriptor.name() + " -> " + publicName.value());
            }
            McpSchemaMapping.Mapping mapping =
                McpSchemaMapping.map(descriptor.inputSchemaJson(), publicName.value());
            for (String warning : mapping.warnings()) {
                LOG.log(System.Logger.Level.WARNING, warning);
            }
            return new Cached(publicName, mapping.schema());
        }

        /** 预检：新代公开名不得与注册面现有非本代工具撞名（预检先于任何注册面改动）。 */
        private void precheckForeignCollisions(Snapshot next) {
            for (Draft draft : next.drafts()) {
                String name = draft.entry().publicName();
                if (handles.containsKey(name)) {
                    continue; // 本代自己的名字：换代即替换
                }
                registry.resolve(owner, name).ifPresent(existing -> {
                    throw new McpCatalogSwapException("mcp catalog 无法采纳：工具名 '" + name
                        + "' 与已注册工具 '" + existing.name() + "' 撞名（非本代 MCP 工具）");
                });
            }
        }

        /** 预检通过后的原子段：摘旧代句柄 → 注册新代（单线程屏障，空窗不可观察）。 */
        private void swap(Snapshot next) {
            List<Disposable> previous = new ArrayList<>(handles.values());
            handles.clear();
            for (Disposable handle : previous) {
                handle.close();
            }
            for (Draft draft : next.drafts()) {
                Disposable handle = registry.register(owner, definition(draft));
                owner.onClose(handle);
                handles.put(draft.entry().publicName(), handle);
            }
        }

        private void adoptSnapshot(Snapshot snapshot) {
            entries = snapshot.entries();
            signature = snapshot.signature();
            keys = snapshot.keys();
            cache.keySet().retainAll(keys);
        }

        /** 调用桥：raw 名与 wire 原文实参直下 transport；结果按字节上限截断（成功/错误同待遇）。 */
        private ToolDefinition definition(Draft draft) {
            return ToolDefinition.of(draft.entry().publicName(), draft.entry().description(),
                draft.schema(), (args, context) -> {
                    McpCallResult result = draft.connection()
                        .call(draft.rawName(), args.rawJson(), context.signal());
                    String content = truncate(result.content(), maxResultBytes);
                    return result.isError()
                        ? ToolExecutionResult.error(content)
                        : ToolExecutionResult.success(content);
                });
        }
    }

    /** 候选快照：排序后的 drafts（含 schema）、账条目、代际签名、缓存键集。 */
    private record Snapshot(List<Draft> drafts, List<McpCatalogEvent.Entry> entries,
                            List<String> signature, Set<CacheKey> keys) {
    }

    /** 单个待注册工具：账条目 + 映射 schema + 调用桥来源。 */
    private record Draft(McpCatalogEvent.Entry entry, ValueSchema schema,
                         McpServerConnection connection, String rawName) {
    }

    /** 稳定读结果（serverId / 代际 / 工具清单）。 */
    private record Stable(String serverId, long generation, List<McpToolDescriptor> tools) {
    }

    /** 缓存键：映射只由三元组决定（description 变化不影响映射结果）。 */
    private record CacheKey(String serverId, String name, String schema) {
    }

    /** 缓存值：公开名（含归一标记）+ 映射 schema（null = 排除）。 */
    private record Cached(PublicName publicName, ValueSchema schema) {
    }
}
