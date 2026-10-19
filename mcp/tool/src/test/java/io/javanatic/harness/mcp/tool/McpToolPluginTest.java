package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.mcp.McpCallResult;
import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerState;
import io.javanatic.harness.mcp.McpToolDescriptor;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.RenderIntent;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 注册面、轮屏障换代（轮内延后/失败保活）、结果上限与映射缓存。 */
class McpToolPluginTest {

    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");

    private static final String ECHO_SCHEMA = """
        {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}""";

    private static McpToolDescriptor echo() {
        return Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA);
    }

    private static List<StreamChunk> stop(String text) {
        return List.of(new StreamChunk.Delta(text), new StreamChunk.Finish(FinishReason.STOP));
    }

    private static List<StreamChunk> toolUse(String name, String arguments) {
        return List.of(
            new StreamChunk.DeltaToolUse(CallId.of("c1"), name, arguments),
            new StreamChunk.Finish(FinishReason.TOOL_USE));
    }

    /** 真实 loop 装配（对照 skill-tool 测试 rig）；单步 STOP 脚本，无工具轮。 */
    private static void bootLoop(Runtime rt, McpToolPlugin tool) {
        bootLoop(rt, tool, List.of(stop("ok")));
    }

    /** 多脚本 loop 装配（按 LLM 调用序消费）。 */
    private static void bootLoop(Runtime rt, McpToolPlugin tool, List<List<StreamChunk>> scripts) {
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new AgentPlugin(),
            new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
            new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
            new ApprovalAutoPlugin(), new ToolsPlugin(),
            new AgentLoopPlugin(Clock.systemUTC()),
            tool));
    }

    /** 只装注册面（直派 ToolExecutor 的用例）。 */
    private static Runtime runtimeWith(McpToolPlugin plugin, McpClientService client,
                                       ConfigService config) {
        Runtime rt = new Runtime();
        rt.root().provide(McpClientService.KEY, client);
        if (config != null) {
            rt.root().provide(ConfigService.KEY, config);
        }
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(), plugin));
        return rt;
    }

    /** 工具调用结果（内容 + 错误标记）。 */
    private record ToolResult(String content, boolean error) {
    }

    private static final AtomicInteger CALLS = new AtomicInteger();

    private static ToolResult execute(Runtime rt, Session session, String name) {
        var results = rt.root().require(ToolExecutor.KEY).execute(List.of(
            new ToolUseBlock(CallId.of("c-" + CALLS.incrementAndGet()), name, "{}")),
            session, 0, 0, rt.root(), AbortSignal.never());
        return new ToolResult(results.getFirst().event().block().content(),
            results.getFirst().event().block().isError());
    }

    private static List<String> mcpToolNames(Runtime rt) {
        return rt.root().require(ToolRegistry.KEY).definitions(rt.root()).stream()
            .map(ToolDefinition::name)
            .filter(name -> name.startsWith("mcp__"))
            .toList();
    }

    private static List<LlmRequestEvent> requests(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(LlmRequestEvent.class::isInstance)
            .map(LlmRequestEvent.class::cast)
            .toList();
    }

    private static List<McpCatalogEvent> catalogs(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(McpCatalogEvent.class::isInstance)
            .map(McpCatalogEvent.class::cast)
            .toList();
    }

    private static List<TurnEndReason> turnReasons(Session session) {
        return session.events().stream()
            .map(LoggedEvent::event)
            .filter(TurnEnd.class::isInstance)
            .map(TurnEnd.class::cast)
            .map(TurnEnd::reason)
            .toList();
    }

    @Test
    void pluginRegistersMappedToolsAndWarnsOnExcluded() {
        try (Runtime rt = new Runtime();
             Fakes.LogCapture logs = new Fakes.LogCapture("io.javanatic.harness.mcp.tool")) {
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(
                new Fakes.FakeConnection("alpha", echo(),
                    Fakes.tool("alpha", "broken", "uses oneOf", """
                        {"type": "object", "oneOf": []}""")),
                new Fakes.FakeConnection("beta", Fakes.tool("beta", "ok", "fine", """
                    {"type": "object", "properties": {"n": {"type": "number"}}}"""))));
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new McpToolPlugin()));

            List<ToolDefinition> mcpTools = rt.root().require(ToolRegistry.KEY)
                .definitions(rt.root()).stream()
                .filter(definition -> definition.name().startsWith("mcp__"))
                .toList();
            assertThat(mcpTools).extracting(ToolDefinition::name)
                .containsExactly("mcp__alpha__echo", "mcp__beta__ok"); // 排除工具缺席
            // 无豁免出口：恒 GENERIC + 要求审批
            assertThat(mcpTools).allMatch(definition -> !definition.approvalExempt())
                .allMatch(definition -> definition.render() == RenderIntent.GENERIC);
            assertThat(logs.warnings()).anyMatch(
                warning -> warning.contains("mcp__alpha__broken excluded: unsupported construct: oneOf"));
        }
    }

    @Test
    void toolCallBridgesRawNameAndRawArguments() {
        try (Runtime rt = new Runtime()) {
            Fakes.FakeConnection connection = new Fakes.FakeConnection("alpha", echo());
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(connection));
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new McpToolPlugin()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("call"), null, null);

            String raw = "{\"text\": \"hi\",  \"extra\": [1,2]}"; // 原文（含空白）逐字节透传
            var results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("c1"), "mcp__alpha__echo", raw)),
                session, 0, 0, rt.root(), AbortSignal.never());
            assertThat(results.getFirst().event().block().isError()).isFalse();
            assertThat(results.getFirst().event().block().content()).isEqualTo("ok");
            assertThat(connection.lastToolName()).isEqualTo("echo"); // raw 名（非前缀全名）
            assertThat(connection.lastArgumentsJson()).isEqualTo(raw); // wire 原文

            connection.next(McpCallResult.error("boom"));
            var failed = executor.execute(List.of(
                new ToolUseBlock(CallId.of("c2"), "mcp__alpha__echo", "{\"text\":\"x\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());
            assertThat(failed.getFirst().event().block().isError()).isTrue();
            assertThat(failed.getFirst().event().block().content()).isEqualTo("boom");
        }
    }

    /** T2 增补：raw 含 "__" 不再拒收——确定性归一 + WARN；注册面用公开名、调用桥用 raw 名。 */
    @Test
    void dunderRawNamesNormalizeDeterministicallyAndRemainCallable() {
        try (Runtime rt = new Runtime();
             Fakes.LogCapture logs = new Fakes.LogCapture("io.javanatic.harness.mcp.tool")) {
            Fakes.FakeConnection connection = new Fakes.FakeConnection("beta",
                Fakes.tool("beta", "a__b", "bad", "{\"type\": \"object\"}"));
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(
                new Fakes.FakeConnection("alpha",
                    Fakes.tool("alpha", "ok", "fine", "{\"type\": \"object\"}")),
                connection));
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new McpToolPlugin()));

            assertThat(rt.root().require(ToolRegistry.KEY).definitions(rt.root()).stream()
                .map(ToolDefinition::name).filter(name -> name.startsWith("mcp__")))
                .containsExactly("mcp__alpha__ok", "mcp__beta__a_b_ff2d9f4e");
            assertThat(logs.warnings()).anyMatch(warning -> warning.contains(
                "mcp tool name normalized: beta/a__b -> mcp__beta__a_b_ff2d9f4e"));

            Session session = Session.create(Session.newId("normalized"), null, null);
            var results = rt.root().require(ToolExecutor.KEY).execute(List.of(
                new ToolUseBlock(CallId.of("c1"), "mcp__beta__a_b_ff2d9f4e", "{}")),
                session, 0, 0, rt.root(), AbortSignal.never());
            assertThat(results.getFirst().event().block().isError()).isFalse();
            assertThat(connection.lastToolName()).isEqualTo("a__b"); // raw 名直下 transport
        }
    }

    /** 归一纯函数：合法名零变化；非法/超长确定性有界；server id 过长/含 "__" fail loud。 */
    @Test
    void publicNameIsPureBoundedAndFailLoudOnImpossiblePrefix() {
        McpToolDescriptor clean = Fakes.tool("alpha", "echo", "d", "{\"type\": \"object\"}");
        assertThat(McpToolPlugin.publicName(clean))
            .isEqualTo(new McpToolPlugin.PublicName("mcp__alpha__echo", false));

        McpToolDescriptor invalid = Fakes.tool("alpha", "My Tool.v2", "d", "{\"type\": \"object\"}");
        McpToolPlugin.PublicName normalized = McpToolPlugin.publicName(invalid);
        assertThat(normalized.value()).isEqualTo("mcp__alpha__My_Tool_v2_4d1308b1");
        assertThat(normalized.normalized()).isTrue();
        // 名字只由 server/raw 决定（description/schema 不参与）
        assertThat(McpToolPlugin.publicName(Fakes.tool("alpha", "My Tool.v2", "other",
            "{\"type\": \"object\"}"))).isEqualTo(normalized);
        assertThat(McpToolPlugin.publicName(Fakes.tool("alpha", "My_Tool_v2", "d",
            "{\"type\": \"object\"}")).value()).isNotEqualTo(normalized.value()); // 合法名不加哈希

        String longPublic = McpToolPlugin.publicName(
            Fakes.tool("alpha", "x".repeat(80), "d", "{\"type\": \"object\"}")).value();
        assertThat(longPublic).hasSize(64)
            .startsWith("mcp__alpha__")
            .endsWith("_1cca3f0b");

        assertThatThrownBy(() -> McpToolPlugin.publicName(
            Fakes.tool("a".repeat(60), "x", "d", "{\"type\": \"object\"}")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds 64 chars");
        assertThatThrownBy(() -> McpToolPlugin.publicName(
            Fakes.tool("bad__id", "x", "d", "{\"type\": \"object\"}")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'__' in server id");
    }

    /** 撞名（预注册同层同名）fail loud：apply 失败包插件 id，根因是注册面拒绝。 */
    @Test
    void duplicateToolNameFailsLoud() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(McpClientService.KEY,
                new Fakes.FakeClient(new Fakes.FakeConnection("alpha", echo())));
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(),
                ToolDefinition.of("mcp__alpha__echo", "occupied", new ValueSchema.Object("", Map.of()),
                    (args, context) -> ToolExecutionResult.success("x")));

            Throwable thrown = catchThrowable(
                () -> new PluginLoader().loadAll(rt, List.of(new McpToolPlugin())));
            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp-tool");
            assertThat(thrown.getCause()).hasMessageContaining("already registered");
        }
    }

    /** 真实 loop：首代账在 PRE_STEP 落，先于 user/message 与 llm/request（R1 锚序）。 */
    @Test
    void loopAppendsCatalogAtPreStepBeforeUserMessageAndRequest() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(McpClientService.KEY,
                new Fakes.FakeClient(new Fakes.FakeConnection("alpha", echo())));
            bootLoop(rt, new McpToolPlugin());

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("e2e"), OPTIONS));
            Session session = handle.agent().session();
            handle.agent().followup(UserMessage.of("hi", new MessageSource.User()));
            handle.agent().whenIdle().join();

            List<String> types = session.events().stream().map(LoggedEvent::type).toList();
            assertThat(types).contains("mcp/catalog", "user/message", "llm/request");
            assertThat(types.indexOf("mcp/catalog")).isLessThan(types.indexOf("user/message"));
            assertThat(types.indexOf("user/message")).isLessThan(types.indexOf("llm/request"));

            McpCatalogEvent catalog = catalogs(session).getFirst();
            assertThat(catalog.entries()).containsExactly(new McpCatalogEvent.Entry(
                "alpha", "echo", "mcp__alpha__echo", "Echo text back", ECHO_SCHEMA));
            assertThat(rt.root().require(ToolRegistry.KEY).schemas(rt.root()))
                .extracting(schema -> schema.name())
                .contains("mcp__alpha__echo");
        }
    }

    /** 普通不可用（FAILED）不牵连轮：轮内照常落账（call 侧才降级为错误结果）。 */
    @Test
    void failedStateDoesNotEndTurn() {
        try (Runtime rt = new Runtime()) {
            Fakes.FakeConnection connection = new Fakes.FakeConnection("alpha", echo());
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(connection));
            bootLoop(rt, new McpToolPlugin());

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("failed"), OPTIONS));
            Session session = handle.agent().session();
            connection.state(McpServerState.FAILED);
            handle.agent().followup(UserMessage.of("hi", new MessageSource.User()));
            handle.agent().whenIdle().join();

            List<String> types = session.events().stream().map(LoggedEvent::type).toList();
            assertThat(types).contains("mcp/catalog", "llm/request");
        }
    }

    /**
     * 轮内清单变化延后：工具调用中换代 → 同轮后续 step 仍用旧 schema（指纹相同）；
     * TURN_STOPPING 换代，下一轮 PRE_STEP 落账、请求指纹才变。
     */
    @Test
    void midTurnCatalogChangeDefersSwapUntilTurnStopping() {
        try (Runtime rt = new Runtime()) {
            Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha", echo());
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(alpha));
            bootLoop(rt, new McpToolPlugin(), List.of(
                toolUse("mcp__alpha__echo", "{\"text\":\"x\"}"),
                stop("done"),
                stop("done2")));
            alpha.onNextCall(() -> alpha.catalog(
                Fakes.tool("alpha", "echo", "Echo v2", ECHO_SCHEMA)));

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("mid-turn"), OPTIONS));
            Session session = handle.agent().session();
            handle.agent().followup(UserMessage.of("one", new MessageSource.User()));
            handle.agent().whenIdle().join();

            assertThat(requests(session)).hasSize(2); // 同轮两个 step 均用旧代 schema
            assertThat(requests(session).get(0).toolsSchemaSha256())
                .isEqualTo(requests(session).get(1).toolsSchemaSha256());
            assertThat(catalogs(session)).hasSize(1); // 轮内不落账
            assertThat(rt.root().require(ToolRegistry.KEY).resolve(rt.root(), "mcp__alpha__echo"))
                .get().extracting(ToolDefinition::description).isEqualTo("Echo v2"); // 轮末已换代

            handle.agent().followup(UserMessage.of("two", new MessageSource.User()));
            handle.agent().whenIdle().join();

            assertThat(requests(session)).hasSize(3);
            assertThat(requests(session).get(2).toolsSchemaSha256())
                .isNotEqualTo(requests(session).get(0).toolsSchemaSha256());
            assertThat(catalogs(session)).hasSize(2); // pending 账在新轮 PRE_STEP 落
            assertThat(catalogs(session).getLast().entries())
                .extracting(McpCatalogEvent.Entry::description)
                .containsExactly("Echo v2");
        }
    }

    /** TURN_STOPPING 预检失败：当前轮 Error、旧代保活、server 不转 FAILED；下一轮重试仍失败。 */
    @Test
    void swapFailureAtTurnStoppingEndsTurnAndNextTurnRetries() {
        try (Runtime rt = new Runtime()) {
            Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha", echo());
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(alpha));
            bootLoop(rt, new McpToolPlugin(), List.of(
                toolUse("mcp__alpha__echo", "{\"text\":\"x\"}"),
                stop("done")));
            alpha.onNextCall(() -> alpha.catalog(echo(),
                Fakes.tool("alpha", "ping", "Ping", "{\"type\": \"object\"}")));
            // 非本代工具占住新代公开名
            rt.root().require(ToolRegistry.KEY).register(rt.root(),
                ToolDefinition.of("mcp__alpha__ping", "foreign", new ValueSchema.Object("", Map.of()),
                    (args, context) -> ToolExecutionResult.success("x")));

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("swap-fail"), OPTIONS));
            Session session = handle.agent().session();
            handle.agent().followup(UserMessage.of("one", new MessageSource.User()));
            handle.agent().whenIdle().join();

            assertThat(turnReasons(session)).hasSize(1)
                .allMatch(reason -> reason instanceof TurnEndReason.Error);
            assertThat(catalogs(session)).hasSize(1); // 首代账已在 PRE_STEP 落
            assertThat(rt.root().require(ToolRegistry.KEY).resolve(rt.root(), "mcp__alpha__ping"))
                .get().extracting(ToolDefinition::description).isEqualTo("foreign"); // 新代未落地
            assertThat(alpha.state()).isEqualTo(McpServerState.CONNECTED); // 清单错不是进程错

            handle.agent().followup(UserMessage.of("two", new MessageSource.User()));
            handle.agent().whenIdle().join();

            assertThat(turnReasons(session)).hasSize(2)
                .allMatch(reason -> reason instanceof TurnEndReason.Error);
            assertThat(catalogs(session)).hasSize(1); // 重试失败仍不落新账
            assertThat(mcpToolNames(rt)).containsExactly("mcp__alpha__echo", "mcp__alpha__ping");
        }
    }

    /** 256 KiB 结果上限：UTF-8 完整字符边界 + 标记；成功与工具级错误同待遇。 */
    @Test
    void resultCapTruncatesAtCompleteUtf8BoundaryAndKeepsErrorFlag() {
        Fakes.FakeConnection connection = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "raw", "d", "{\"type\": \"object\"}"));
        try (Runtime rt = runtimeWith(new McpToolPlugin(5), new Fakes.FakeClient(connection), null)) {
            Session session = Session.create(Session.newId("cap"), null, null);

            connection.next(McpCallResult.success("ab你好cd"));
            ToolResult ok = execute(rt, session, "mcp__alpha__raw");
            assertThat(ok.content()).isEqualTo("ab你" + McpToolPlugin.TRUNCATED_MARKER);
            assertThat(ok.error()).isFalse();

            connection.next(McpCallResult.error("ab你好cd"));
            ToolResult failed = execute(rt, session, "mcp__alpha__raw");
            assertThat(failed.content()).isEqualTo("ab你" + McpToolPlugin.TRUNCATED_MARKER);
            assertThat(failed.error()).isTrue(); // 截断是内容事实，不改错误标记

            connection.next(McpCallResult.success("abcde")); // 恰好上限：不截断、无标记
            assertThat(execute(rt, session, "mcp__alpha__raw").content()).isEqualTo("abcde");
        }
    }

    /** 上限行配置覆盖 + 非法值组合期 fail loud。 */
    @Test
    void resultCapComesFromPluginConfigAndInvalidFailsLoud() {
        Fakes.FakeConnection connection = new Fakes.FakeConnection("alpha",
            Fakes.tool("alpha", "raw", "d", "{\"type\": \"object\"}"));
        connection.next(McpCallResult.success("abcdef"));
        try (Runtime rt = runtimeWith(new McpToolPlugin(), new Fakes.FakeClient(connection),
            id -> id.equals("mcp-tool") ? Map.of("maxResultBytes", 4) : Map.of())) {
            Session session = Session.create(Session.newId("config-cap"), null, null);
            assertThat(execute(rt, session, "mcp__alpha__raw").content())
                .isEqualTo("abcd" + McpToolPlugin.TRUNCATED_MARKER);
        }

        try (Runtime rt = new Runtime()) {
            rt.root().provide(McpClientService.KEY,
                new Fakes.FakeClient(new Fakes.FakeConnection("alpha", echo())));
            rt.root().provide(ConfigService.KEY,
                id -> id.equals("mcp-tool") ? Map.of("maxResultBytes", 0) : Map.of());
            Throwable thrown = catchThrowable(() -> new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new McpToolPlugin())));
            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp-tool");
            assertThat(thrown.getCause()).hasMessageContaining("maxResultBytes");
        }
    }

    /** 映射缓存：同一 (serverId, raw, schema) 跨代只告警一次（排除 + 归一各一）。 */
    @Test
    void mappingAndNormalizationWarningsEmitOnceAcrossGenerations() {
        McpToolDescriptor broken = Fakes.tool("alpha", "broken", "uses oneOf",
            "{\"type\": \"object\", \"oneOf\": []}");
        McpToolDescriptor normalized = Fakes.tool("alpha", "a__b", "bad", "{\"type\": \"object\"}");
        Fakes.FakeConnection alpha = new Fakes.FakeConnection("alpha", broken, normalized);
        try (Runtime rt = new Runtime();
             Fakes.LogCapture logs = new Fakes.LogCapture("io.javanatic.harness.mcp.tool")) {
            rt.root().provide(McpClientService.KEY, new Fakes.FakeClient(alpha));
            bootLoop(rt, new McpToolPlugin(), List.of(stop("one"), stop("two")));

            AgentHandle handle = rt.root().require(AgentRegistry.KEY)
                .create(rt.root(), CreateAgentOptions.of(Session.newId("cache"), OPTIONS));
            Session session = handle.agent().session();
            handle.agent().followup(UserMessage.of("one", new MessageSource.User()));
            handle.agent().whenIdle().join();

            // 换代但排除/归一工具的原 schema 不变 → 缓存复用，不重复告警
            alpha.catalog(broken, normalized,
                Fakes.tool("alpha", "fresh", "fresh", "{\"type\": \"object\"}"));
            handle.agent().followup(UserMessage.of("two", new MessageSource.User()));
            handle.agent().whenIdle().join();

            assertThat(logs.warnings().stream()
                .filter(warning -> warning.contains("mcp__alpha__broken excluded")).count())
                .isEqualTo(1);
            assertThat(logs.warnings().stream()
                .filter(warning -> warning.contains("mcp tool name normalized: alpha/a__b")).count())
                .isEqualTo(1);
            assertThat(catalogs(session)).hasSize(2);
        }
    }
}
