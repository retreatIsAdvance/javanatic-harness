package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.session.message.MessageSource;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** setup window 事务(06 §5):scoped world 生效 / 失败回滚不发布 / dispose 事件与 flush。 */
class SetupWindowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private static final AgentOptions OPTIONS = new AgentOptions("replay", "m");

    private static final ConfigService NO_CONFIG = id -> Map.of();

    private static final class Rig implements AutoCloseable {
        final Runtime rt;
        final AgentRegistry agents;

        Rig() {
            rt = new Runtime();
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(),
                new ReplayPlugin(List.of(List.of(
                    new StreamChunk.Delta("ok"), new StreamChunk.Finish(FinishReason.STOP)))),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new AgentLoopPlugin(FIXED_CLOCK)));
            rt.root().provide(ConfigService.KEY, NO_CONFIG);
            agents = rt.root().require(AgentRegistry.KEY);
        }

        @Override
        public void close() {
            rt.close();
        }
    }

    private static ToolDefinition scopedTool() {
        return ToolDefinition.of("setup-tool", "setup window 注册",
            new ValueSchema.Object("参数", Map.of()),
            (a, c) -> ToolExecutionResult.success("scoped"));
    }

    @Test
    void setupRegistersScopedWorldVisibleOnlyToThatAgent() throws Exception {
        try (Rig rig = new Rig()) {
            CountDownLatch created = new CountDownLatch(1);
            rig.rt.root().events().onGlobal(AgentEvents.CREATED, (carrier, agent) -> created.countDown());

            AgentHandle handle = rig.agents.create(rig.rt.root(),
                new CreateAgentOptions(
                    Session.newId("sw1"), OPTIONS, null, null,
                    scope -> rig.rt.root().require(ToolRegistry.KEY).register(scope, scopedTool())));
            assertThat(created.await(2, TimeUnit.SECONDS)).isTrue();

            ToolRegistry tools = rig.rt.root().require(ToolRegistry.KEY);
            assertThat(tools.resolve(handle.agent().scope(), "setup-tool")).isPresent();
            assertThat(tools.schemas(rig.rt.root())).isEmpty(); // 对组合层不可见
            handle.disposeAndAwait();
        }
    }

    @Test
    void setupFailureRollsBackAndDoesNotPublish() throws Exception {
        try (Rig rig = new Rig()) {
            CountDownLatch created = new CountDownLatch(1);
            rig.rt.root().events().onGlobal(AgentEvents.CREATED, (carrier, agent) -> created.countDown());

            assertThatThrownBy(() -> rig.agents.create(rig.rt.root(),
                    new CreateAgentOptions(
                        Session.newId("sw2"), OPTIONS, null, null,
                        scope -> {
                            rig.rt.root().require(ToolRegistry.KEY).register(scope, scopedTool());
                            throw new IllegalStateException("preset broken");
                        })))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("preset broken");

            // 回滚:半注册的 scoped 工具随 agentScope 关闭消失;未发布:CREATED 未到、注册表无 agent
            assertThat(created.await(300, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(rig.rt.root().require(ToolRegistry.KEY).schemas(rig.rt.root())).isEmpty();
            assertThat(rig.agents.list()).isEmpty();
        }
    }

    @Test
    void disposeEmitsDisposedEventAndFlushesSession() throws Exception {
        try (Rig rig = new Rig()) {
            CountDownLatch disposed = new CountDownLatch(1);
            rig.rt.root().events().onGlobal(AgentEvents.DISPOSED, (carrier, agent) -> disposed.countDown());

            AgentHandle handle = rig.agents.create(rig.rt.root(),
                CreateAgentOptions.of(Session.newId("sw3"), OPTIONS));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("go",
                new MessageSource.User()));
            agent.whenIdle().join();
            handle.disposeAndAwait();

            assertThat(disposed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(agent.session().events().stream()
                .anyMatch(e -> "turn/end".equals(e.event().type()))).isTrue();
            assertThat(rig.agents.get(agent.id())).isNull();
        }
    }
}
