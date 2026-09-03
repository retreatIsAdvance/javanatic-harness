package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R2 核心：四路径成对落账、去重、未知工具、否决/改写、abort 传播、并行保序。 */
class ToolExecutorTest {

    private static final ValueSchema.Object ARGS = new ValueSchema.Object("args",
        java.util.Map.of("path", new ValueSchema.Str("文件路径")));

    private static ToolDefinition echo() {
        return ToolDefinition.of("echo", "回显 path", ARGS,
            (args, ctx) -> ToolExecutionResult.success(args.readString("path")));
    }

    private static ToolUseBlock call(String id, String args) {
        return new ToolUseBlock(CallId.of(id), "echo", args);
    }

    /** 直接装配（绕过插件）：registry + 手选审批实现。 */
    /** 直接装配（绕过插件）：registry + 手选审批实现；close 收拢 Runtime。 */
    private static final class Rig implements AutoCloseable {
        final Runtime rt;
        final RegistryImpl registry;
        final ToolExecutor executor;

        private Rig(ApprovalService approval) {
            this.rt = new Runtime();
            this.registry = new RegistryImpl();
            this.executor = new ToolExecutorImpl(registry, approval, rt.events(), rt.root());
        }

        static Rig with(ApprovalService approval) {
            return new Rig(approval);
        }

        @Override
        public void close() {
            rt.close();
        }
    }

    @Test
    void successPathPairsCallAndResultEvents() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"hello\"}")), session, 0, 0,
                    AbortSignal.never());
            assertThat(out).hasSize(1);
            assertThat(out.getFirst().event().block().isError()).isFalse();
            assertThat(out.getFirst().event().block().content()).isEqualTo("hello");
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsExactly("tool/call", "tool/result"); // 成对留痕（R2 锁 3）
        }
    }

    @Test
    void toolFailureBecomesErrorResultNotException() {
        ToolDefinition bomb = ToolDefinition.of("bomb", "炸", ARGS, (args, ctx) -> {
            throw new IllegalStateException("boom");
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(bomb);
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(new ToolUseBlock(CallId.of("c1"), "bomb", "{\"path\":\"x\"}")),
                    session, 0, 0, AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("boom");
            assertThat(session.events()).hasSize(2); // 错误也成对落账
        }
    }

    @Test
    void preExecuteVetoDeniesWithoutExecution() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            rig.rt.root().events().onWaterfall(ToolEvents.PRE_EXECUTE, (carrier, args) ->
                ToolExecutionPlan.veto("policy"));
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("vetoed").contains("policy");
        }
    }

    @Test
    void approvalDenialBecomesErrorResult() {
        try (Rig rig = Rig.with(Approvals.deny())) {
            rig.registry.register(echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("denied");
            assertThat(session.events()).hasSize(2);
        }
    }

    @Test
    void duplicateCallIdInBatchYieldsErrorResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"a\"}"), call("c1", "{\"path\":\"b\"}")),
                    session, 0, 0, AbortSignal.never());
            assertThat(out).hasSize(2);
            assertThat(out.getFirst().event().block().content()).isEqualTo("a");
            assertThat(out.get(1).event().block().isError()).isTrue();
            assertThat(out.get(1).event().block().content()).contains("Duplicate");
        }
    }

    @Test
    void unknownToolYieldsErrorResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(new ToolUseBlock(CallId.of("c1"), "ghost", "{}")),
                    session, 0, 0, AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("Unknown tool");
        }
    }

    @Test
    void abortPropagatesInsteadOfBecomingErrorResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            rig.rt.root().events().onWaterfall(ToolEvents.PRE_EXECUTE, (carrier, args) -> {
                throw new AbortedException("cancelled");
            });
            Session session = Session.create(Session.newId("t"), null, null);
            assertThatThrownBy(() -> rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, AbortSignal.never()))
                .isInstanceOf(AbortedException.class);
            assertThat(session.events()).hasSize(1); // 只有 tool/call——取消不伪造结果
        }
    }

    @Test
    void parallelExecutionPreservesInputOrder() {
        ToolDefinition slow = ToolDefinition.of("slow", "慢", ARGS, (args, ctx) -> {
            Thread.sleep(150);
            return ToolExecutionResult.success(args.readString("path"));
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(slow);
            rig.registry.register(echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor.execute(
                List.of(new ToolUseBlock(CallId.of("a"), "slow", "{\"path\":\"first\"}"),
                    call("b", "{\"path\":\"second\"}")),
                session, 0, 0, AbortSignal.never());
            assertThat(out).extracting(e -> e.event().block().content())
                .containsExactly("first", "second"); // 同序，尽管 first 慢后完成
        }
    }

    @Test
    void postExecuteCanRewriteResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            rig.rt.root().events().onWaterfall(ToolEvents.POST_EXECUTE, (carrier, args) ->
                ToolExecutionResult.error("spilled"));
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, AbortSignal.never());
            assertThat(out.getFirst().event().block().content()).isEqualTo("spilled");
        }
    }

    @Test
    void toolResultProjectsIntoDerivedMessages() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            Session session = Session.create(Session.newId("t"), null, null);
            rig.executor.execute(List.of(call("c1", "{\"path\":\"hello\"}")),
                session, 0, 0, AbortSignal.never());
            List<Message> messages = session.deriveMessages();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().source())
                .isEqualTo(new MessageSource.Tool(CallId.of("c1")));
            assertThat(((ToolResultBlock) messages.getFirst().content().getFirst()).content())
                .isEqualTo("hello");
        }
    }

    @Test
    void pluginAssemblyRequiresApprovalFirst() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new ApprovalAutoPlugin(), new ToolsPlugin()));
            assertThat(rt.root().resolve(ToolExecutor.KEY)).isPresent();
            assertThat(rt.root().resolve(ToolRegistry.KEY)).isPresent();
        }
        try (Runtime rt = new Runtime()) {
            // 缺审批提供者：apply 时 fail loud（R4 组合责任），插件整体回滚
            assertThatThrownBy(() ->
                new PluginLoader().loadAll(rt, List.of(new ToolsPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back")
                .hasRootCauseInstanceOf(io.javanatic.harness.kernel.scope.ServiceNotAvailableException.class)
                .getRootCause()
                .hasMessageContaining("approval");
        }
    }

    @Test
    void registryRejectsDuplicatesAndSchemasAreSorted() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(echo());
            assertThatThrownBy(() -> rig.registry.register(echo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");
            rig.registry.register(ToolDefinition.of("aaa", "first", ARGS, (a, c) ->
                ToolExecutionResult.success("x")));
            assertThat(rig.registry.schemas()).extracting(s -> s.name())
                .containsExactly("aaa", "echo"); // 名称排序，确定性
            assertThat(rig.registry.schemas().get(1).parametersJson()).contains("\"path\"");
            assertThat(rig.registry.resolve("ghost")).isEmpty();
        }
    }

    @Test
    void toolArgsValidationRejectsBadModelJson() {
        assertThatThrownBy(() -> ToolArgs.parse("not json", ARGS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolArgs.parse("{\"wrong\":1}", ARGS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required argument: path");
        assertThatThrownBy(() -> ToolArgs.parse("{\"path\":42}", ARGS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a string");
    }
}
