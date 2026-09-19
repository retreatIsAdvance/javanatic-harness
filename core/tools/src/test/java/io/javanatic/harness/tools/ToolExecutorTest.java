package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.ServiceNotAvailableException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.AbortedException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 直接装配（绕过插件）：registry + 手选审批实现；close 收拢 Runtime。 */
    private static final class Rig implements AutoCloseable {
        final Runtime rt;
        final ScopedRegistry registry;
        final ToolExecutor executor;

        private Rig(ApprovalService approval) {
            this.rt = new Runtime();
            this.registry = new ScopedRegistry();
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

    /** 测试用可取消信号：cancel() 后 checkAbort 抛 AbortedException。 */
    private static final class TestSignal implements AbortSignal {
        private final AtomicBoolean aborted = new AtomicBoolean();

        void cancel() {
            aborted.set(true);
        }

        @Override
        public void checkAbort() {
            if (aborted.get()) {
                throw new AbortedException("test-cancel");
            }
        }
    }

    @Test
    void successPathPairsCallAndResultEvents() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"hello\"}")), session, 0, 0, rig.rt.root(),
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
            rig.registry.register(rig.rt.root(), bomb);
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(new ToolUseBlock(CallId.of("c1"), "bomb", "{\"path\":\"x\"}")),
                    session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("boom");
            assertThat(session.events()).hasSize(2); // 错误也成对落账
        }
    }

    @Test
    void preExecuteVetoDeniesWithoutExecution() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            rig.rt.root().events().onWaterfall(ToolEvents.PRE_EXECUTE, (carrier, args) ->
                ToolExecutionPlan.veto("policy"));
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("vetoed").contains("policy");
        }
    }

    @Test
    void approvalDenialBecomesErrorResult() {
        try (Rig rig = Rig.with(Approvals.deny())) {
            rig.registry.register(rig.rt.root(), echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("denied");
            assertThat(session.events()).hasSize(2);
        }
    }

    @Test
    void duplicateCallIdInBatchYieldsErrorResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            Session session = Session.create(Session.newId("t"), null, null);
            // 两个同 id 调用并行提交：谁先占用 callId 不确定，
            // 但必然恰一个执行成功、恰一个 Duplicate 错误，且都成对落账
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"a\"}"), call("c1", "{\"path\":\"b\"}")),
                    session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out).hasSize(2);
            assertThat(out.stream().filter(e -> e.event().block().isError()).count()).isEqualTo(1);
            assertThat(out.stream().filter(e -> !e.event().block().isError()).count()).isEqualTo(1);
            assertThat(out.stream().filter(e -> e.event().block().isError()).findFirst().orElseThrow()
                .event().block().content()).contains("Duplicate");
            assertThat(session.events()).hasSize(4); // 两个调用、两个结果，全部留痕
        }
    }

    @Test
    void unknownToolYieldsErrorResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(new ToolUseBlock(CallId.of("c1"), "ghost", "{}")),
                    session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out.getFirst().event().block().isError()).isTrue();
            assertThat(out.getFirst().event().block().content()).contains("Unknown tool");
        }
    }

    @Test
    void abortPropagatesInsteadOfBecomingErrorResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            rig.rt.root().events().onWaterfall(ToolEvents.PRE_EXECUTE, (carrier, args) -> {
                throw new AbortedException("cancelled");
            });
            Session session = Session.create(Session.newId("t"), null, null);
            assertThatThrownBy(() -> rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, rig.rt.root(), AbortSignal.never()))
                .isInstanceOf(AbortedException.class);
            assertThat(session.events()).hasSize(1); // 只有 tool/call——取消不伪造结果
        }
    }

    @Test
    void abortWaitsForAllToolsBeforePropagating() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean writerStopped = new AtomicBoolean();
        TestSignal signal = new TestSignal();
        ToolDefinition poller = ToolDefinition.of("poller", "轮询取消", ARGS, (args, ctx) -> {
            while (true) {
                ctx.signal().checkAbort();
                Thread.sleep(5);
            }
        });
        ToolDefinition writer = ToolDefinition.of("writer", "慢写", ARGS, (args, ctx) -> {
            writerStarted.countDown();
            releaseWriter.await();
            writerStopped.set(true);
            return ToolExecutionResult.success("written");
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), poller);
            rig.registry.register(rig.rt.root(), writer);
            Session session = Session.create(Session.newId("t"), null, null);
            AtomicBoolean stoppedAtPropagation = new AtomicBoolean();
            Thread batch = Thread.ofVirtual().start(() -> {
                try {
                    rig.executor.execute(List.of(
                            new ToolUseBlock(CallId.of("a"), "poller", "{\"path\":\"p\"}"),
                            new ToolUseBlock(CallId.of("b"), "writer", "{\"path\":\"w\"}")),
                        session, 0, 0, rig.rt.root(), signal);
                } catch (AbortedException e) {
                    stoppedAtPropagation.set(writerStopped.get());
                }
            });
            assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            signal.cancel();
            // writer 未停前不得传播：首异常即抛的旧语义会在此窗口内提前返回
            batch.join(100);
            assertThat(batch.isAlive()).isTrue();
            releaseWriter.countDown();
            batch.join(5_000);
            assertThat(batch.isAlive()).isFalse();
            assertThat(stoppedAtPropagation.get()).isTrue();
            assertThat(session.events().stream().map(LoggedEvent::type))
                .filteredOn("tool/call"::equals).hasSize(2);
            // 已完成工具成对落账（取消不抹除已发生的执行）；被取消者无 result
            assertThat(session.events().stream().map(LoggedEvent::type))
                .filteredOn("tool/result"::equals).hasSize(1);
        }
    }

    @Test
    void abortOutranksEarlierPlainFailureInInputOrder() {
        ToolDefinition bomb = ToolDefinition.of("bomb", "炸", ARGS, (args, ctx) -> {
            throw new AssertionError("boom");
        });
        ToolDefinition aborter = ToolDefinition.of("aborter", "取消", ARGS, (args, ctx) -> {
            throw new AbortedException("cancelled");
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), bomb);
            rig.registry.register(rig.rt.root(), aborter);
            Session session = Session.create(Session.newId("t"), null, null);
            // 输入序 0 是普通失败、1 是取消：取消优先——否则 turn 收敛成 Error 而非 Aborted
            assertThatThrownBy(() -> rig.executor.execute(List.of(
                    new ToolUseBlock(CallId.of("a"), "bomb", "{\"path\":\"x\"}"),
                    new ToolUseBlock(CallId.of("b"), "aborter", "{\"path\":\"x\"}")),
                session, 0, 0, rig.rt.root(), AbortSignal.never()))
                .isInstanceOf(AbortedException.class)
                .hasMessageContaining("cancelled");
        }
    }

    @Test
    void firstAbortInInputOrderIsThrown() {
        ToolDefinition first = ToolDefinition.of("first", "取消", ARGS, (args, ctx) -> {
            throw new AbortedException("first-abort");
        });
        ToolDefinition second = ToolDefinition.of("second", "取消", ARGS, (args, ctx) -> {
            throw new AbortedException("second-abort");
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), first);
            rig.registry.register(rig.rt.root(), second);
            Session session = Session.create(Session.newId("t"), null, null);
            assertThatThrownBy(() -> rig.executor.execute(List.of(
                    new ToolUseBlock(CallId.of("a"), "first", "{\"path\":\"x\"}"),
                    new ToolUseBlock(CallId.of("b"), "second", "{\"path\":\"x\"}")),
                session, 0, 0, rig.rt.root(), AbortSignal.never()))
                .isInstanceOf(AbortedException.class)
                .hasMessageContaining("first-abort");
        }
    }

    @Test
    void plainFailureSelectionStaysInputOrder() {
        ToolDefinition first = ToolDefinition.of("first", "炸", ARGS, (args, ctx) -> {
            throw new AssertionError("first-boom");
        });
        ToolDefinition second = ToolDefinition.of("second", "炸", ARGS, (args, ctx) -> {
            throw new AssertionError("second-boom");
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), first);
            rig.registry.register(rig.rt.root(), second);
            Session session = Session.create(Session.newId("t"), null, null);
            assertThatThrownBy(() -> rig.executor.execute(List.of(
                    new ToolUseBlock(CallId.of("a"), "first", "{\"path\":\"x\"}"),
                    new ToolUseBlock(CallId.of("b"), "second", "{\"path\":\"x\"}")),
                session, 0, 0, rig.rt.root(), AbortSignal.never()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool execution failed")
                .hasRootCauseMessage("first-boom");
        }
    }

    @Test
    void parallelExecutionPreservesInputOrder() {
        ToolDefinition slow = ToolDefinition.of("slow", "慢", ARGS, (args, ctx) -> {
            Thread.sleep(150);
            return ToolExecutionResult.success(args.readString("path"));
        });
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), slow);
            rig.registry.register(rig.rt.root(), echo());
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor.execute(
                List.of(new ToolUseBlock(CallId.of("a"), "slow", "{\"path\":\"first\"}"),
                    call("b", "{\"path\":\"second\"}")),
                session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out).extracting(e -> e.event().block().content())
                .containsExactly("first", "second"); // 同序，尽管 first 慢后完成
        }
    }

    @Test
    void postExecuteCanRewriteResult() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            rig.rt.root().events().onWaterfall(ToolEvents.POST_EXECUTE, (carrier, args) ->
                ToolExecutionResult.error("spilled"));
            Session session = Session.create(Session.newId("t"), null, null);
            List<LoggedEvent<ToolResultEvent>> out = rig.executor
                .execute(List.of(call("c1", "{\"path\":\"x\"}")), session, 0, 0, rig.rt.root(), AbortSignal.never());
            assertThat(out.getFirst().event().block().content()).isEqualTo("spilled");
        }
    }

    @Test
    void toolResultProjectsIntoDerivedMessages() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            Session session = Session.create(Session.newId("t"), null, null);
            rig.executor.execute(List.of(call("c1", "{\"path\":\"hello\"}")),
                session, 0, 0, rig.rt.root(), AbortSignal.never());
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
                .hasRootCauseInstanceOf(ServiceNotAvailableException.class)
                .getRootCause()
                .hasMessageContaining("approval");
        }
    }

    @Test
    void registryRejectsDuplicatesAndSchemasAreSorted() {
        try (Rig rig = Rig.with(Approvals.auto())) {
            rig.registry.register(rig.rt.root(), echo());
            assertThatThrownBy(() -> rig.registry.register(rig.rt.root(), echo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");
            rig.registry.register(rig.rt.root(), ToolDefinition.of("aaa", "first", ARGS, (a, c) ->
                ToolExecutionResult.success("x")));
            assertThat(rig.registry.schemas(rig.rt.root())).extracting(s -> s.name())
                .containsExactly("aaa", "echo"); // 名称排序，确定性
            assertThat(rig.registry.schemas(rig.rt.root()).get(1).parametersJson()).contains("\"path\"");
            assertThat(rig.registry.resolve(rig.rt.root(), "ghost")).isEmpty();
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
