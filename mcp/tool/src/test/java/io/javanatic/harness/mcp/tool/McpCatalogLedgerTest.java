package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentStatus;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.Inbox;
import io.javanatic.harness.agent.InboxTarget;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.ToolSchema;
import io.javanatic.harness.mcp.McpCatalogDriftException;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 清单账四态（零噪声 / 首轮落账 / 冻结一致 / 不一致 fail loud）+ 漂移优先 +
 * codec/jsonl 往返 + R1 折叠重建（只凭账、零联网零进程）。
 */
class McpCatalogLedgerTest {

    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");

    private static final String ECHO_SCHEMA = """
        {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}""";

    /** 直派 REQUEST（F6 先例：不建 agent，向共享层同步派发——插件订阅恒绑共享层）。 */
    private static void dispatch(Runtime rt, Session session) {
        rt.events().waterfall(AgentEvents.REQUEST, rt.root(), agentOf(session),
            List.of(0, 0, AbortSignal.never()),
            ignored -> new LlmCallConfig(OPTIONS.provider(), OPTIONS.model()));
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

    @Test
    void noServersNoToolsStaysSilent() {
        try (Runtime rt = runtimeWith(new Fakes.FakeClient())) {
            Session session = Session.create(Session.newId("silent"), null, null);
            dispatch(rt, session);
            assertThat(catalogs(session)).isEmpty(); // 零噪声：不拉进程、不落事件、不注册工具
            assertThat(rt.root().require(ToolRegistry.KEY).definitions(rt.root())).isEmpty();
        }
    }

    @Test
    void firstRequestAppendsFrozenCatalogAndEqualStaysSilent() {
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA),
            Fakes.tool("alpha", "broken", "uses oneOf", "{\"type\": \"object\", \"oneOf\": []}"));
        try (Runtime rt = runtimeWith(new Fakes.FakeClient(alpha))) {
            Session session = Session.create(Session.newId("first"), null, null);
            dispatch(rt, session);
            dispatch(rt, session); // 冻结一致：二次派发零新增（每轮检查对 R1 落账面稳定）

            assertThat(catalogs(session)).hasSize(1);
            // 排除工具不进账（fold 只重建注册面）；schema 为 wire 原文
            assertThat(catalogs(session).getFirst().entries()).containsExactly(
                new McpCatalogEvent.Entry("alpha", "echo", "Echo text back", ECHO_SCHEMA));
        }
    }

    /** 不等变体一：账上有、现场无（重开会话 + 配置变更）→ fail loud。 */
    @Test
    void nonEmptyLedgerAgainstEmptyClientFailsLoud() {
        Fakes.FakeClient client = new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA)));
        try (Runtime rt = runtimeWith(client)) {
            Session session = Session.create(Session.newId("removed"), null, null);
            dispatch(rt, session);
            client.connections(List.of());
            assertThatThrownBy(() -> dispatch(rt, session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请重开会话")
                .hasMessageContaining("removed=[alpha/echo]");
            assertThat(catalogs(session)).hasSize(1); // 不落新账
        }
    }

    /** 不等变体二：同名条目内容变（description/schema 键 changed）→ fail loud。 */
    @Test
    void changedEntryFailsLoud() {
        Fakes.FakeClient client = new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "before", ECHO_SCHEMA)));
        try (Runtime rt = runtimeWith(client)) {
            Session session = Session.create(Session.newId("changed"), null, null);
            dispatch(rt, session);
            client.connections(List.of(new Fakes.FakeConnection("alpha",
                Fakes.tool("alpha", "echo", "after", ECHO_SCHEMA))));
            assertThatThrownBy(() -> dispatch(rt, session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请重开会话")
                .hasMessageContaining("changed=[alpha/echo]");
        }
    }

    /** 漂移优先：DRIFTED 与清单不等同时在场时，落轮终异常胜出（不落账、不静默）。 */
    @Test
    void driftWinsOverLedgerMismatch() {
        Fakes.FakeClient client = new Fakes.FakeClient(new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA)));
        try (Runtime rt = runtimeWith(client)) {
            Session session = Session.create(Session.newId("drift"), null, null);
            dispatch(rt, session);
            // 现场漂移侧：同名 + 新增工具（对账本会判不等——但漂移必须先胜出）
            Fakes.FakeConnection drifted = new Fakes.FakeConnection("alpha",
                Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA),
                Fakes.tool("alpha", "extra", "new since start", "{\"type\": \"object\"}"));
            drifted.state(McpServerState.DRIFTED);
            client.connections(List.of(drifted));
            assertThatThrownBy(() -> dispatch(rt, session))
                .isInstanceOf(McpCatalogDriftException.class) // 若对账先判会抛 ISE——类型断言即优先级断言
                .hasMessageContaining("alpha")
                .hasMessageContaining("请重开会话");
            assertThat(catalogs(session)).hasSize(1);
        }
    }

    @Test
    void catalogEventRoundTripsThroughCodec() {
        McpCatalogEvent original = new McpCatalogEvent(42, List.of(
            new McpCatalogEvent.Entry("alpha", "echo", "Echo text back", ECHO_SCHEMA),
            new McpCatalogEvent.Entry("beta", "ping", "ping", "{\"type\": \"object\"}")));
        McpCatalogCodec codec = new McpCatalogCodec();
        assertThat(codec.type()).isEqualTo("mcp/catalog");
        assertThat(codec.read(codec.write(original))).isEqualTo(original);
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
            dispatch(rt, session);
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
            dispatch(rt, session);
            List<McpCatalogEvent.Entry> entries = catalogs(session).getFirst().entries();
            assertThat(entries).extracting(McpCatalogEvent.Entry::serverId)
                .containsExactly("alpha", "beta"); // 规范排序（serverId, name）

            // fold 面：零联网零进程——不触碰 client，只读账条目重映射
            List<ToolSchema> folded = entries.stream()
                .map(entry -> {
                    McpToolDescriptor descriptor = new McpToolDescriptor(entry.serverId(),
                        entry.name(), entry.description(), entry.schema());
                    ValueSchema schema = McpSchemaMapping
                        .map(entry.schema(), McpToolPlugin.fullName(descriptor)).schema();
                    return new ToolSchema(McpToolPlugin.fullName(descriptor), entry.description(),
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
