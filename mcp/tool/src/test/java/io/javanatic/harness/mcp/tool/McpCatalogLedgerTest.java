package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentStatus;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.Inbox;
import io.javanatic.harness.agent.InboxTarget;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.agentloop.PreStepDecision;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ToolSchema;
import io.javanatic.harness.mcp.McpCatalogSwapException;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 换代屏障与清单账（it27.1 停 3）：PRE_STEP 换代/首代落账、TURN_STOPPING 只换代、
 * 拒绝轮不采纳不落账、退役空清单落账、预检失败旧代保活 + 可重试、resume 对账
 * 采纳（不再要求重开会话）+ codec/jsonl 往返 + R1 折叠重建（只凭账、零联网零进程）。
 */
class McpCatalogLedgerTest {

    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");

    private static final String ECHO_SCHEMA = """
        {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}""";

    private static final List<UserMessage> CLAIMED =
        List.of(UserMessage.of("hi", new MessageSource.User()));

    /** 直派 PRE_STEP（F6 先例：不建 agent，向共享层同步派发——插件订阅恒绑共享层）。 */
    private static void preStep(Runtime rt, Session session, PreStepDecision decision) {
        rt.events().waterfall(AgentEvents.PRE_STEP, rt.root(), agentOf(session),
            List.of(CLAIMED, 1, AbortSignal.never()), ignored -> decision);
    }

    private static void accept(Runtime rt, Session session) {
        preStep(rt, session, new PreStepDecision.Enter(CLAIMED));
    }

    /** 直派 TURN_STOPPING（同步 notifyOrdered，异常传播）。 */
    private static void turnStopping(Runtime rt, Session session) {
        rt.events().notifyOrdered(AgentEvents.TURN_STOPPING, rt.root(), agentOf(session),
            new AgentEvents.TurnStopping(1));
    }

    private static Runtime runtimeWith(McpClientService client) {
        Runtime rt = new Runtime();
        rt.root().provide(McpClientService.KEY, client);
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
            new McpToolPlugin()));
        return rt;
    }

    private static List<McpCatalogEvent> catalogs(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(McpCatalogEvent.class::isInstance)
            .map(McpCatalogEvent.class::cast)
            .toList();
    }

    private static List<String> mcpToolNames(Runtime rt) {
        return rt.root().require(ToolRegistry.KEY).definitions(rt.root()).stream()
            .map(ToolDefinition::name)
            .filter(name -> name.startsWith("mcp__"))
            .toList();
    }

    @Test
    void noServersNoToolsStaysSilent() {
        try (Runtime rt = runtimeWith(new Fakes.FakeClient())) {
            Session session = Session.create(Session.newId("silent"), null, null);
            accept(rt, session);
            accept(rt, session);
            assertThat(catalogs(session)).isEmpty(); // 零噪声：不拉进程、不落事件、不注册工具
            assertThat(rt.root().require(ToolRegistry.KEY).definitions(rt.root())).isEmpty();
        }
    }

    @Test
    void firstPreStepAppendsFullSnapshotAndEqualStaysSilent() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA),
            Fakes.tool("alpha", "broken", "uses oneOf", "{\"type\": \"object\", \"oneOf\": []}"));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("first"), null, null);
            accept(rt, session);
            accept(rt, session); // 同代一致：二次 PRE_STEP 零新增

            assertThat(catalogs(session)).hasSize(1);
            // 排除工具不进账（fold 只重建注册面）；schema 为 wire 原文
            assertThat(catalogs(session).getFirst().entries()).containsExactly(new McpCatalogEvent.Entry(
                "alpha", "echo", "mcp__alpha__echo", "Echo text back", ECHO_SCHEMA));
        }
    }

    /** PRE_STEP 采纳新代：注册面替换 + 账追加（不再 fail loud 要求重开会话）。 */
    @Test
    void preStepAdoptsNewGenerationAndAppendsLedger() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("grow"), null, null);
            accept(rt, session);

            alpha.catalog(
                Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA),
                Fakes.tool("alpha", "ping", "Ping", "{\"type\": \"object\"}"));
            accept(rt, session);

            assertThat(catalogs(session)).hasSize(2); // 每代全量快照；日志序即代
            assertThat(catalogs(session).getLast().entries())
                .extracting(McpCatalogEvent.Entry::name)
                .containsExactly("echo", "ping");
            assertThat(mcpToolNames(rt))
                .containsExactly("mcp__alpha__echo", "mcp__alpha__ping");
        }
    }

    /** TURN_STOPPING 只换代；pending 账由下一个 PRE_STEP（admit 之前）落。 */
    @Test
    void turnStoppingSwapsSurfaceButLedgerWaitsForNextPreStep() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("stopping"), null, null);
            accept(rt, session);

            alpha.catalog(Fakes.tool("alpha", "ping", "Ping", "{\"type\": \"object\"}"));
            turnStopping(rt, session);
            assertThat(mcpToolNames(rt)).containsExactly("mcp__alpha__ping"); // 旧代已摘
            assertThat(catalogs(session)).hasSize(1); // 账等 PRE_STEP

            accept(rt, session);
            assertThat(catalogs(session)).hasSize(2);
            assertThat(catalogs(session).getLast().entries())
                .extracting(McpCatalogEvent.Entry::name)
                .containsExactly("ping");
        }
    }

    /** 被拒绝的 turn 不采纳、不落账（下一 accepted 轮补）。 */
    @Test
    void rejectedTurnDoesNotAdoptOrAppend() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("reject"), null, null);
            accept(rt, session);

            alpha.catalog(Fakes.tool("alpha", "ping", "Ping", "{\"type\": \"object\"}"));
            preStep(rt, session, new PreStepDecision.Reject("no work"));
            assertThat(mcpToolNames(rt)).containsExactly("mcp__alpha__echo");
            assertThat(catalogs(session)).hasSize(1);

            accept(rt, session);
            assertThat(mcpToolNames(rt)).containsExactly("mcp__alpha__ping");
            assertThat(catalogs(session)).hasSize(2);
        }
    }

    @Test
    void removedServerAdoptsEmptyRetirementLedger() {
        Fakes.FakeClient client = new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA)));
        try (Runtime rt = runtimeWith(client)) {
            Session session = Session.create(Session.newId("retire"), null, null);
            accept(rt, session);

            client.connections(List.of());
            accept(rt, session);

            assertThat(catalogs(session)).hasSize(2);
            assertThat(catalogs(session).getLast().entries()).isEmpty(); // 退役落空账
            assertThat(mcpToolNames(rt)).isEmpty();
        }
    }

    @Test
    void changedEntryAdoptsNewGeneration() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "before", ECHO_SCHEMA));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("changed"), null, null);
            accept(rt, session);

            alpha.catalog(Fakes.tool("alpha", "echo", "after", ECHO_SCHEMA));
            accept(rt, session);

            assertThat(catalogs(session)).hasSize(2);
            assertThat(catalogs(session).getLast().entries())
                .extracting(McpCatalogEvent.Entry::description)
                .containsExactly("after");
            assertThat(rt.root().require(ToolRegistry.KEY).resolve(rt.root(), "mcp__alpha__echo"))
                .get().extracting(ToolDefinition::description).isEqualTo("after");
        }
    }

    /** 预检失败：旧代保活 + McpCatalogSwapException + 不落新账；修复后下一轮重试成功。 */
    @Test
    void swapPrecheckFailureKeepsOldGenerationAndRetriesNextTurn() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("conflict"), null, null);
            accept(rt, session);

            // 非本代工具占住新代公开名（同层注册面）
            var foreign = rt.root().require(ToolRegistry.KEY).register(rt.root(),
                ToolDefinition.of("mcp__alpha__ping", "foreign", new ValueSchema.Object("", Map.of()),
                    (args, context) -> ToolExecutionResult.success("x")));
            alpha.catalog(
                Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA),
                Fakes.tool("alpha", "ping", "Ping", "{\"type\": \"object\"}"));

            assertThatThrownBy(() -> accept(rt, session))
                .isInstanceOf(McpCatalogSwapException.class)
                .hasMessageContaining("撞名");
            assertThat(mcpToolNames(rt)) // 旧代原样保活（foreign 仍在，新代未落地）
                .containsExactly("mcp__alpha__echo", "mcp__alpha__ping");
            assertThat(rt.root().require(ToolRegistry.KEY).resolve(rt.root(), "mcp__alpha__ping"))
                .get().extracting(ToolDefinition::description).isEqualTo("foreign");
            assertThat(catalogs(session)).hasSize(1); // 不落新账
            assertThat(alpha.state()).isEqualTo(McpServerState.CONNECTED);

            foreign.close(); // 冲突解除：下一 PRE_STEP 重试采纳
            accept(rt, session);
            assertThat(catalogs(session)).hasSize(2);
            assertThat(rt.root().require(ToolRegistry.KEY).resolve(rt.root(), "mcp__alpha__ping"))
                .get().extracting(ToolDefinition::description).isEqualTo("Ping");
        }
    }

    /** resume：账与实时清单不同 → 首个 PRE_STEP 作为新一代采纳并落账（不要求重开会话）。 */
    @Test
    void resumeWithChangedCatalogAdoptsNewGeneration() {
        Id<Session> id = Session.newId("resumed");
        Fakes.FakeClient first = new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA)));
        List<SessionEvent> seed;
        try (Runtime rt = runtimeWith(first)) {
            Session session = Session.create(id, null, null);
            accept(rt, session);
            seed = session.events().stream()
                .<SessionEvent>map(LoggedEvent::event)
                .toList();
        }

        Fakes.FakeClient second = new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "ping", "Ping", "{\"type\": \"object\"}")));
        try (Runtime rt = runtimeWith(second)) {
            Session resumed = rt.root().require(SessionStore.KEY)
                .create(rt.root(), id, new CreateOptions(seed, null));
            accept(rt, resumed);
            assertThat(catalogs(resumed)).hasSize(2);
            assertThat(catalogs(resumed).getLast().entries())
                .extracting(McpCatalogEvent.Entry::name)
                .containsExactly("ping");
        }
    }

    @Test
    void catalogEventRoundTripsThroughCodec() {
        McpCatalogEvent original = new McpCatalogEvent(42, List.of(
            new McpCatalogEvent.Entry("alpha", "echo", "mcp__alpha__echo",
                "Echo text back", ECHO_SCHEMA),
            new McpCatalogEvent.Entry("beta", "ping", "mcp__beta__ping",
                "ping", "{\"type\": \"object\"}")));
        McpCatalogCodec codec = new McpCatalogCodec();
        assertThat(codec.type()).isEqualTo("mcp/catalog");
        assertThat(codec.read(codec.write(original))).isEqualTo(original);
    }

    /** 归一化之前的账（无 publicName）无法无损重建注册面：codec fail loud，不猜算法。 */
    @Test
    void codecRejectsPreNormalizationLedger() {
        JsonValue.Obj legacy = JsonValue.object()
            .set("time", 1L)
            .set("entries", new JsonValue.Arr(List.of(JsonValue.object()
                .set("serverId", "alpha")
                .set("name", "echo")
                .set("description", "d")
                .set("schema", "{\"type\": \"object\"}")
                .build())))
            .build();
        assertThatThrownBy(() -> new McpCatalogCodec().read(legacy))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("publicName");
    }

    @TempDir
    Path root;

    @Test
    void catalogEventRoundTripsThroughJsonl() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
                Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA))));
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root),
                new ApprovalAutoPlugin(), new ToolsPlugin(), new McpToolPlugin()));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("durable"), CreateOptions.empty());
            accept(rt, session);
            store.flush(rt.root(), session);
            McpCatalogEvent appended = catalogs(session).getFirst();

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(session.id());
            assertThat(loaded.events()).containsExactly(appended);
        }
    }

    /** R1 折叠重建：只凭账条目（map + toJson 唯一序列化点）重建工具面 = 注册侧逐字段相等。 */
    @Test
    void foldRebuildsRegisteredSurfaceFromLedgerOnly() {
        Fakes.FakeClient client = new Fakes.FakeClient(
            new Fakes.FakeConnection("beta", Fakes.tool("beta", "ping", "ping", "{\"type\": \"object\"}")),
            new Fakes.FakeConnection("alpha",
                Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA),
                Fakes.tool("alpha", "broken", "uses oneOf", "{\"type\": \"object\", \"oneOf\": []}")));
        try (Runtime rt = runtimeWith(client)) {
            Session session = Session.create(Session.newId("fold"), null, null);
            accept(rt, session);
            List<McpCatalogEvent.Entry> entries = catalogs(session).getFirst().entries();
            assertThat(entries).extracting(McpCatalogEvent.Entry::serverId)
                .containsExactly("alpha", "beta"); // 规范排序（serverId, name）

            // fold 面：零联网零进程——不触碰 client，只读账条目重映射（publicName 直接复用）
            List<ToolSchema> folded = entries.stream()
                .map(entry -> {
                    ValueSchema schema = McpSchemaMapping
                        .map(entry.schema(), entry.publicName()).schema();
                    return new ToolSchema(entry.publicName(), entry.description(),
                        ValueSchema.toJson(schema));
                })
                .sorted(Comparator.comparing(ToolSchema::name))
                .toList();
            List<ToolSchema> registered = rt.root().require(ToolRegistry.KEY).schemas(rt.root());
            assertThat(folded).isEqualTo(registered);
        }
    }

    /** 最小 agent 桩：检查点只消费 session()（直派用例的 carrier）。 */
    private static Agent agentOf(Session session) {
        return new Agent() {
            @Override public Id<Session> id() {
                return session.id();
            }

            @Override public AgentOptions options() {
                return OPTIONS;
            }

            @Override public Session session() {
                return session;
            }

            @Override public Inbox inbox() {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override public AgentStatus status() {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override public Scope scope() {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override public void send(UserMessage message, InboxTarget target, boolean wakeup) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override public void cancel(AgentCancelCause cause, CancelOptions options) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override public CompletableFuture<Void> whenIdle() {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override public <T> CompletableFuture<T> runMaintenance(Supplier<T> task) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
    }
}
