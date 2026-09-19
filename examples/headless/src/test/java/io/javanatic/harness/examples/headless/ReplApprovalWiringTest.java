package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agentloop.AbortController;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 补充 6「REPL 审批合流」的接线验收：组合出的 stdin 人闸（{@code ApprovalPrompt.stdin()}
 * 在 ask 时读 System.in）经 {@link ReplApprovalInput} 代理收到行循环转交的裁决行
 * ——forward("y") 放行；行循环退出（close → EOF）按既有拒绝语义；
 * 取消撤出等待中的裁决（AbortedException 收敛、不落 error result），撤出后的行归 REPL。
 */
class ReplApprovalWiringTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void forwardedLineApprovesPendingToolCall() throws Exception {
        try (Runtime rt = booted()) {
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            InputStream original = System.in;
            ReplApprovalInput approvalIn = new ReplApprovalInput();
            System.setIn(approvalIn);
            try {
                CompletableFuture<ToolResultEvent> pending = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        pending.complete(execute(executor, rt));
                    } catch (Throwable t) {
                        pending.completeExceptionally(t);
                    }
                });
                awaitForward(approvalIn, "y");
                ToolResultEvent result = pending.get(10, TimeUnit.SECONDS);
                assertThat(result.block().isError()).isFalse();
                assertThat(result.block().content()).isEqualTo("ok");
            } finally {
                System.setIn(original);
            }
        }
    }

    @Test
    void closedChannelDeniesPendingToolCall() throws Exception {
        try (Runtime rt = booted()) {
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            InputStream original = System.in;
            ReplApprovalInput approvalIn = new ReplApprovalInput();
            System.setIn(approvalIn);
            try {
                approvalIn.close(); // 行循环已退出(EOF 或 /exit):未决裁决得 EOF
                ToolResultEvent result = execute(executor, rt);
                assertThat(result.block().isError()).isTrue();
                assertThat(result.block().content()).contains("denied by human gate");
            } finally {
                System.setIn(original);
            }
        }
    }

    @Test
    void cancelledPendingApprovalWithdrawsReaderAndLaterLinesGoToRepl() throws Exception {
        try (Runtime rt = booted()) {
            rt.root().require(ToolRegistry.KEY).register(rt.root(), echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            InputStream original = System.in;
            ReplApprovalInput approvalIn = new ReplApprovalInput();
            System.setIn(approvalIn);
            AbortController controller = new AbortController();
            try {
                Session session = Session.create(Session.newId("repl-cancel"), null, null);
                CompletableFuture<ToolResultEvent> pending = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        pending.complete(execute(executor, rt, session, controller.signal()));
                    } catch (Throwable t) {
                        pending.completeExceptionally(t);
                    }
                });
                awaitAwaiting(approvalIn);

                controller.cancel(new AgentCancelCause.User());

                assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AbortedException.class);
                awaitWithdrawn(approvalIn);
                assertThat(approvalIn.forward("hello")).isFalse();
                assertThat(types(session)).containsExactly("tool/call");
            } finally {
                System.setIn(original);
            }
        }
    }

    /** 轮询转交：ask 进入阻塞读之前 forward 返回 false（真实场景里人打字晚于问句）。 */
    private static void awaitForward(ReplApprovalInput approvalIn, String line)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!approvalIn.forward(line)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("审批 ask 10s 内未进入等待读");
            }
            Thread.sleep(5);
        }
    }

    /** 等 ask 真正把读挂上（等待态可见）再取消——覆盖「等待中取消」而非竞速。 */
    private static void awaitAwaiting(ReplApprovalInput approvalIn) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!approvalIn.awaitingApproval()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("审批 10s 内未进入等待");
            }
            Thread.sleep(5);
        }
    }

    /** 等撤出落定（等待态复位）——中断异步生效，复位后才谈行归属。 */
    private static void awaitWithdrawn(ReplApprovalInput approvalIn) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (approvalIn.awaitingApproval()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("撤出 10s 内未复位等待态");
            }
            Thread.sleep(5);
        }
    }

    private static List<String> types(Session session) {
        return session.events().stream().map(entry -> entry.event().type()).toList();
    }

    private Runtime booted() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            new String[] {"t", "--approval=ask"});
        AppBoot.BootOptions boot = new AppBoot.BootOptions(
            HeadlessMain.resolveProfile(null),
            HeadlessMain.buildOverlays(options, workspace, sessions), false, Policy.STANDARD);
        return AppBoot.boot(boot);
    }

    private ToolResultEvent execute(ToolExecutor executor, Runtime rt) {
        return execute(executor, rt, Session.create(Session.newId("repl-approval"), null, null),
            AbortSignal.never());
    }

    private ToolResultEvent execute(ToolExecutor executor, Runtime rt, Session session,
                                    AbortSignal signal) {
        return executor.execute(
            List.of(new ToolUseBlock(CallId.of("c1"), "echo", "{}")),
            session, 0, 0, rt.root(), signal).getFirst().event();
    }

    private static ToolDefinition echoTool() {
        return ToolDefinition.of("echo", "回显",
            new ValueSchema.Object("参数", Map.of()),
            (args, ctx) -> ToolExecutionResult.success("ok"));
    }
}
