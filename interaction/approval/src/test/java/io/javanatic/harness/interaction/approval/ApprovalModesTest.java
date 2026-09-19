package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.tools.ApprovalService;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三模式语义:mode 自述、ask 放行/拒绝、deny 全拒、缺省拒绝;取消等待不落拒绝。 */
class ApprovalModesTest {

    private static ToolDefinition echoTool() {
        return ToolDefinition.of("echo", "回显",
            new ValueSchema.Object("参数", Map.of()),
            (args, ctx) -> ToolExecutionResult.success("ok"));
    }

    @Test
    void askApprovesThroughInjectedPromptAndLogsDenials() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAskPlugin((request, signal) -> {
                    asked.incrementAndGet();
                    return true;
                }),
                new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            assertThat(rt.root().require(ApprovalService.KEY).mode())
                .isEqualTo(ApprovalService.Mode.HUMAN_GATE);
            assertThat(execute(executor, rt).block().isError()).isFalse();
            assertThat(asked.get()).isEqualTo(1);
        }
    }

    @Test
    void askDenialBecomesErrorResultNotCrash() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAskPlugin((request, signal) -> false), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            var result = execute(executor, rt);
            assertThat(result.block().isError()).isTrue();
            assertThat(result.block().content()).contains("denied by human gate");
        }
    }

    @Test
    void denyAllRejectsEverythingWithModeSelfReport() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalDenyPlugin(), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            assertThat(rt.root().require(ApprovalService.KEY).mode())
                .isEqualTo(ApprovalService.Mode.DENY_ALL);
            var result = execute(executor, rt);
            assertThat(result.block().isError()).isTrue();
            assertThat(result.block().content()).contains("denied by policy");
        }
    }

    @Test
    void stdinPromptRejectsOnEofAndNonYes() throws Exception {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(ApprovalPrompt.stdin().ask(new ApprovalService.ApprovalRequest(
                "bash", "rm -rf /", "{}"), AbortSignal.never())).isFalse();
            System.setIn(new ByteArrayInputStream(new byte[0]));
            assertThat(ApprovalPrompt.stdin().ask(new ApprovalService.ApprovalRequest(
                "bash", "rm -rf /", "{}"), AbortSignal.never())).isFalse();
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void cancelDuringApprovalWaitAbortsWithoutErrorResult() throws Exception {
        CountDownLatch asking = new CountDownLatch(1);
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAskPlugin((request, signal) -> {
                    asking.countDown(); // fake 通道:进入等待即挂起,只由信号撤出
                    while (true) {
                        signal.checkAbort();
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }),
                new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            TestSignal signal = new TestSignal();
            Session session = Session.create(Session.newId("approval-cancel"), null, null);
            CompletableFuture<ToolResultEvent> outcome = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    outcome.complete(executor.execute(
                        List.of(new ToolUseBlock(CallId.of("c1"), "echo", "{}")),
                        session, 0, 0, rt.root(), signal).getFirst().event());
                } catch (Throwable t) {
                    outcome.completeExceptionally(t);
                }
            });

            assertThat(asking.await(10, TimeUnit.SECONDS)).as("审批已进入等待").isTrue();
            signal.cancel();

            assertThatThrownBy(() -> outcome.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AbortedException.class);
            // 取消不落 error result:尝试留痕(tool/call)之外无结果事件
            assertThat(types(session)).containsExactly("tool/call");
        }
    }

    @Test
    void stdinPromptAbortsOnCancelWhileWaiting() throws Exception {
        InputStream original = System.in;
        System.setIn(new BlockingStdin());
        AtomicInteger checks = new AtomicInteger();
        AbortSignal signal = () -> {
            if (checks.incrementAndGet() >= 2) {
                throw new AbortedException("test-cancel");
            }
        };
        try {
            assertThatThrownBy(() -> ApprovalPrompt.stdin().ask(new ApprovalService.ApprovalRequest(
                "bash", "rm -rf /", "{}"), signal))
                .isInstanceOf(AbortedException.class);
        } finally {
            System.setIn(original);
        }
    }

    private ToolResultEvent execute(ToolExecutor executor, Runtime rt) {
        return executor.execute(
            List.of(new ToolUseBlock(CallId.of("c1"), "echo", "{}")),
            Session.create(Session.newId("a"), null, null),
            0, 0, rt.root(), AbortSignal.never()).getFirst().event();
    }

    private static List<String> types(Session session) {
        return session.events().stream().map(entry -> entry.event().type()).toList();
    }

    /** 手动取消信号:置位后 checkAbort 抛 AbortedException。 */
    private static final class TestSignal implements AbortSignal {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void checkAbort() {
            if (cancelled.get()) {
                throw new AbortedException("test-cancel");
            }
        }

        void cancel() {
            cancelled.set(true);
        }
    }

    /** 阻塞到被中断为止的 stdin(中断 → EOF:模拟可撤回通道)。 */
    private static final class BlockingStdin extends InputStream {

        private final CountDownLatch block = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            return -1;
        }
    }
}
