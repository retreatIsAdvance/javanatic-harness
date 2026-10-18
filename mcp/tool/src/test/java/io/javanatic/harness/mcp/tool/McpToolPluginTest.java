package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
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
import io.javanatic.harness.session.event.LoggedEvent;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 注册面与轮上检查点：注册/排除告警、无豁免出口、调用桥 wire 原文、fail loud 两档、轮序与漂移轮终。 */
class McpToolPluginTest {

    private static final AgentOptions OPTIONS = new AgentOptions("replay", "test-model");

    private static final String ECHO_SCHEMA = """
        {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}""";

    private static McpToolDescriptor echo() {
        return Fakes.tool("alpha", "echo", "Echo text back", ECHO_SCHEMA);
    }

    /** 真实 loop 装配（对照 skill-tool 测试 rig）；单步 STOP 脚本，无工具轮。 */
    private static void bootLoop(Runtime rt, McpToolPlugin tool) {
        List<List<StreamChunk>> scripts = List.of(List.of(
            new StreamChunk.Delta("ok"), new StreamChunk.Finish(FinishReason.STOP)));
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new AgentPlugin(),
            new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
            new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
            new ApprovalAutoPlugin(), new ToolsPlugin(),
            new AgentLoopPlugin(Clock.systemUTC()),
            tool));
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

    /** 真实 loop：首轮 REQUEST 检查点先落 mcp/catalog、再落 llm/request（R1 锚序）。 */
    @Test
    void loopAppendsCatalogBeforeFirstRequest() {
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
            assertThat(types).contains("mcp/catalog"); // 先证在：缺失时 indexOf=-1 会空洞通过
            assertThat(types).contains("llm/request");
            assertThat(types.indexOf("mcp/catalog")).isLessThan(types.indexOf("llm/request"));

            McpCatalogEvent catalog = session.events().stream()
                .map(LoggedEvent::event)
                .filter(McpCatalogEvent.class::isInstance)
                .map(McpCatalogEvent.class::cast)
                .findFirst().orElseThrow();
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
            assertThat(types).contains("mcp/catalog");
            assertThat(types).contains("llm/request");
        }
    }
}
