package io.javanatic.harness.examples.headless;

import io.javanatic.harness.interaction.approval.ApprovalAskPlugin;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ApprovalService.ApprovalRequest;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;
import io.javanatic.harness.workbench.rpc.ApprovalRelay;
import io.javanatic.harness.workbench.rpc.ApprovalRelay.Decision;
import io.javanatic.harness.workbench.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 每轮租约的取消、关联、恢复与真实 R2 执行路径，不接生产 host。 */
@Timeout(15)
class RelayApprovalPromptTest {
    private static final Id<Session> SESSION = Session.newId("prompt-contract");
    private static final int TURN = 7;
    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final ApprovalRequest REQUEST = new ApprovalRequest("probe", "probe {safe:summary}", "raw-only");

    @Test
    void preCancelledAskPublishesNothing() throws Exception {
        try (ApprovalRelay relay = new ApprovalRelay();
                RelayApprovalPrompt prompt = prompt(relay);
                var subscription = prompt.subscribe()) {
            assertThat(subscription.take().revision()).isZero();
            assertThatThrownBy(() -> prompt.ask(REQUEST, () -> {
                throw new AbortedException("already cancelled");
            })).isInstanceOf(AbortedException.class);
            assertThat(prompt.snapshot().revision()).isZero();
            assertThat(prompt.snapshot().pending()).isEmpty();
            assertThat(subscription.poll(Duration.ZERO)).isEmpty();
            assertThat(relay.pendingCount()).isZero();
        }
    }

    @Test
    void pollingCancelsWhenOnCancelUsesDefaultNoOp() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AbortSignal signal = () -> {
            if (cancelled.get()) {
                throw new AbortedException("poll cancelled");
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay);
                var subscription = prompt.subscribe()) {
            subscription.take();
            var answer = executor.submit(() -> prompt.ask(REQUEST, signal));
            var pending = nextPending(subscription);
            cancelled.set(true);
            assertAborted(answer);
            assertThat(relay.pendingCount()).isZero();
            assertThat(prompt.snapshot().pending()).isEmpty();
            assertLate(relay, pending.requestId());
        }
    }

    @Test
    void listenerRegistrationCanObservePublishedRequestAndCancelSynchronously() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay)) {
            AtomicReference<String> issued = new AtomicReference<>();
            AbortSignal signal = new AbortSignal() {
                @Override
                public void checkAbort() {
                    if (cancelled.get()) {
                        throw new AbortedException("listener cancelled");
                    }
                }

                @Override
                public void onCancel(Runnable action) {
                    issued.set(prompt.snapshot().pending().getFirst().requestId());
                    cancelled.set(true);
                    action.run();
                    assertThat(relay.pendingCount()).isZero();
                }
            };
            assertThatThrownBy(() -> prompt.ask(REQUEST, signal)).isInstanceOf(AbortedException.class);
            assertThat(prompt.snapshot().pending()).isEmpty();
            assertLate(relay, issued.get());
        }
    }

    @Test
    void cancellationWinsEvenWhenAllowHasJustSettled() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay)) {
            AbortSignal signal = new AbortSignal() {
                @Override
                public void checkAbort() {
                    if (cancelled.get()) {
                        throw new AbortedException("cancel after allow");
                    }
                }

                @Override
                public void onCancel(Runnable action) {
                    relay.settle(prompt.snapshot().pending().getFirst().requestId(), Decision.ALLOW);
                    cancelled.set(true);
                }
            };
            assertThatThrownBy(() -> prompt.ask(REQUEST, signal)).isInstanceOf(AbortedException.class);
            assertThat(prompt.snapshot().pending()).isEmpty();
        }
    }

    @Test
    void retainedCancellationCallbackIsHarmlessAfterRelayRetiresTheRequest() {
        AtomicReference<Runnable> listener = new AtomicReference<>();
        try (ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay)) {
            AbortSignal signal = new AbortSignal() {
                @Override
                public void checkAbort() { }

                @Override
                public void onCancel(Runnable action) {
                    listener.set(action);
                    relay.settle(prompt.snapshot().pending().getFirst().requestId(), Decision.ALLOW);
                }
            };
            assertThat(prompt.ask(REQUEST, signal)).isTrue();
            relay.close(); // 清墓碑后与 TTL 淘汰相同：未知 id 不能从旧取消监听冒出来。
            listener.get().run();
            listener.get().run();
            assertThat(prompt.snapshot().pending()).isEmpty();
        }
    }

    @Test
    void listenerFailureStillWithdrawsThePendingRequest() {
        try (ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay)) {
            AbortSignal signal = new AbortSignal() {
                @Override
                public void checkAbort() { }

                @Override
                public void onCancel(Runnable action) {
                    throw new IllegalStateException("registration failed");
                }
            };
            assertThatThrownBy(() -> prompt.ask(REQUEST, signal))
                    .isInstanceOf(IllegalStateException.class).hasMessage("registration failed");
            assertThat(relay.pendingCount()).isZero();
            assertThat(prompt.snapshot().pending()).isEmpty();
        }
    }

    @Test
    void closeCancelsOutstandingRequestsAndRejectsNewAsksWithoutClosingSharedRelay() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay);
                var subscription = prompt.subscribe()) {
            subscription.take();
            var answer = executor.submit(() -> prompt.ask(REQUEST, AbortSignal.never()));
            var pending = nextPending(subscription);
            prompt.close();
            prompt.close();
            assertAborted(answer);
            assertLate(relay, pending.requestId());
            assertThat(subscription.poll(WAIT).orElseThrow().pending()).isEmpty();
            assertThatThrownBy(() -> prompt.ask(REQUEST, AbortSignal.never())).isInstanceOf(AbortedException.class);
            assertThat(relay.pendingCount()).isZero();
            var independent = relay.open(Duration.ZERO);
            relay.settle(independent.id(), Decision.ALLOW);
            assertThat(relay.await(independent)).isEqualTo(ApprovalRelay.Outcome.ALLOWED);
        }
    }

    @Test
    void snapshotsCarryLeaseMetadataAndReconnectWithoutSettledRequests() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay);
                var subscription = prompt.subscribe()) {
            assertThat(subscription.take().pending()).isEmpty();
            var answer = executor.submit(() -> prompt.ask(REQUEST, AbortSignal.never()));
            var pending = nextPending(subscription);
            assertThat(UUID.fromString(pending.requestId()).toString()).isEqualTo(pending.requestId());
            assertThat(pending.session()).isEqualTo(SESSION);
            assertThat(pending.turn()).isEqualTo(7);
            assertThat(pending.toolName()).isEqualTo("probe");
            assertThat(pending.summary()).isEqualTo("probe {safe:summary}");
            try (var reconnect = prompt.subscribe()) {
                var snapshot = reconnect.take();
                assertThat(snapshot.revision()).isEqualTo(1);
                assertThat(snapshot.pending()).containsExactly(pending);
                assertThatThrownBy(() -> snapshot.pending().clear()).isInstanceOf(UnsupportedOperationException.class);
                relay.settle(pending.requestId(), Decision.REJECT);
                assertThat(prompt.snapshot().pending()).isEmpty();
                var removed = reconnect.poll(WAIT).orElseThrow();
                assertThat(removed.revision()).isEqualTo(2);
                assertThat(removed.pending()).isEmpty();
                assertThat(answer.get(3, TimeUnit.SECONDS)).isFalse();
            }
            try (var reconnect = prompt.subscribe()) {
                assertThat(reconnect.take().pending()).isEmpty();
            }
        }
    }

    @Test
    void slowSubscriberReceivesOnlyLatestCompleteSnapshot() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay);
                var slow = prompt.subscribe(); var observer = prompt.subscribe()) {
            observer.take();
            var first = executor.submit(() -> prompt.ask(REQUEST, AbortSignal.never()));
            var left = nextPending(observer);
            var second = executor.submit(() -> prompt.ask(REQUEST, AbortSignal.never()));
            var both = observer.poll(WAIT).orElseThrow();
            assertThat(both.pending()).hasSize(2);
            var right = both.pending().stream().filter(p -> !p.requestId().equals(left.requestId())).findFirst().orElseThrow();
            relay.settle(left.requestId(), Decision.ALLOW);
            relay.settle(right.requestId(), Decision.REJECT);
            assertThat(first.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(3, TimeUnit.SECONDS)).isFalse();
            var latest = slow.poll(WAIT).orElseThrow();
            assertThat(latest.revision()).isEqualTo(4);
            assertThat(latest.pending()).isEmpty();
            assertThat(slow.poll(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void subscriptionCloseWakesWaiterAndReceivesNoFurtherChanges() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay);
                var subscription = prompt.subscribe(); var observer = prompt.subscribe()) {
            subscription.take();
            observer.take();
            CountDownLatch taking = new CountDownLatch(1);
            var waiting = executor.submit(() -> {
                taking.countDown();
                return subscription.take();
            });
            assertThat(taking.await(3, TimeUnit.SECONDS)).isTrue();
            subscription.close();
            assertThat(waiting.get(3, TimeUnit.SECONDS).pending()).isEmpty();
            var answer = executor.submit(() -> prompt.ask(REQUEST, AbortSignal.never()));
            var pending = nextPending(observer);
            assertThat(subscription.poll(Duration.ZERO)).isEmpty();
            relay.settle(pending.requestId(), Decision.REJECT);
            assertThat(answer.get(3, TimeUnit.SECONDS)).isFalse();
            assertThat(subscription.poll(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void leaseCloseWakesSnapshotWaiterAndNewSubscriberSeesEmptyFinalSnapshot() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(); RelayApprovalPrompt prompt = prompt(relay);
                var subscription = prompt.subscribe()) {
            subscription.take();
            var waiting = executor.submit(subscription::take);
            prompt.close();
            assertThat(waiting.get(3, TimeUnit.SECONDS).pending()).isEmpty();
            try (var reconnect = prompt.subscribe()) {
                assertThat(reconnect.take().pending()).isEmpty();
                assertThat(reconnect.poll(Duration.ZERO)).isEmpty();
            }
        }
    }

    @Test
    void subscribingRacesWithOpeningWithoutLosingThePendingSnapshot() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); ApprovalRelay relay = new ApprovalRelay()) {
            for (int i = 0; i < 100; i++) {
                try (RelayApprovalPrompt prompt = prompt(relay)) {
                    CountDownLatch start = new CountDownLatch(1);
                    var answer = executor.submit(() -> {
                        start.await();
                        return prompt.ask(REQUEST, AbortSignal.never());
                    });
                    var subscribing = executor.submit(() -> {
                        start.await();
                        return prompt.subscribe();
                    });
                    start.countDown();
                    try (var subscription = subscribing.get(3, TimeUnit.SECONDS)) {
                        var pending = nextPending(subscription);
                        relay.settle(pending.requestId(), Decision.REJECT);
                        assertThat(answer.get(3, TimeUnit.SECONDS)).isFalse();
                    }
                }
                assertThat(relay.pendingCount()).isZero();
            }
        }
    }

    @Test
    void closeRacesWithAskWithoutLeakingAnyPendingRequest() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); ApprovalRelay relay = new ApprovalRelay()) {
            for (int i = 0; i < 100; i++) {
                try (RelayApprovalPrompt prompt = prompt(relay); var subscription = prompt.subscribe()) {
                    subscription.take();
                    CountDownLatch start = new CountDownLatch(1);
                    var answer = executor.submit(() -> {
                        start.await();
                        return prompt.ask(REQUEST, AbortSignal.never());
                    });
                    var closing = executor.submit(() -> {
                        start.await();
                        prompt.close();
                        return true;
                    });
                    start.countDown();
                    closing.get(3, TimeUnit.SECONDS);
                    assertAborted(answer);
                    assertThat(prompt.snapshot().pending()).isEmpty();
                    assertThat(subscription.poll(WAIT).orElseThrow().pending()).isEmpty();
                    assertThat(relay.pendingCount()).isZero();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void humanDecisionControlsRealToolExecutor(Decision decision) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Session session = Session.create(SESSION, null, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); ApprovalRelay relay = new ApprovalRelay();
                RelayApprovalPrompt prompt = prompt(relay); var subscription = prompt.subscribe(); Runtime rt = new Runtime()) {
            subscription.take();
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin(), new ApprovalAskPlugin(prompt), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), ToolDefinition.of(
                    "probe", "计数探针", new ValueSchema.Object("参数", Map.of()), (args, context) -> {
                        executions.incrementAndGet();
                        return ToolExecutionResult.success("executed");
                    }));
            var execution = executor.submit(() -> rt.root().require(ToolExecutor.KEY).execute(
                    List.of(new ToolUseBlock(CallId.of("decision-probe"), "probe", "{}")),
                    session, TURN, 0, rt.root(), AbortSignal.never()).getFirst().event());
            var pending = nextPending(subscription);
            assertThat(pending.session()).isEqualTo(SESSION);
            assertThat(pending.turn()).isEqualTo(7);
            relay.settle(pending.requestId(), decision);
            var result = execution.get(3, TimeUnit.SECONDS);
            boolean allowed = decision == Decision.ALLOW;
            assertThat(executions.get()).isEqualTo(allowed ? 1 : 0);
            assertThat(result.block().isError()).isEqualTo(!allowed);
            assertThat(result.block().content()).contains(allowed ? "executed" : "denied by human gate");
        }
    }

    @Test
    void cancellationPropagatesThroughRealToolExecutorWithoutExecutingTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        AbortSignal signal = () -> {
            if (cancelled.get()) {
                throw new AbortedException("tool turn cancelled");
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); ApprovalRelay relay = new ApprovalRelay();
                Runtime rt = new Runtime(); RelayApprovalPrompt prompt = prompt(relay); var subscription = prompt.subscribe()) {
            subscription.take();
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin(), new ApprovalAskPlugin(prompt), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), ToolDefinition.of(
                    "probe", "取消探针", new ValueSchema.Object("参数", Map.of()), (args, context) -> {
                        executions.incrementAndGet();
                        return ToolExecutionResult.success("executed");
                    }));
            var execution = executor.submit(() -> rt.root().require(ToolExecutor.KEY).execute(
                    List.of(new ToolUseBlock(CallId.of("cancel-probe"), "probe", "{}")),
                    Session.create(SESSION, null, null), TURN, 0, rt.root(), signal));
            var pending = nextPending(subscription);
            cancelled.set(true);
            assertAborted(execution);
            assertThat(executions.get()).isZero();
            assertThat(prompt.snapshot().pending()).isEmpty();
            assertThat(relay.pendingCount()).isZero();
            assertLate(relay, pending.requestId());
        }
    }

    private static RelayApprovalPrompt prompt(ApprovalRelay relay) {
        return new RelayApprovalPrompt(relay, SESSION, TURN, 0, false);
    }

    private static RelayApprovalPrompt.Pending nextPending(RelayApprovalPrompt.Subscription subscription) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            assertThat(remaining).isPositive();
            var snapshot = subscription.poll(Duration.ofNanos(remaining)).orElseThrow();
            if (!snapshot.pending().isEmpty()) {
                return snapshot.pending().getFirst();
            }
        }
    }

    private static void assertAborted(Future<?> answer) {
        assertThatThrownBy(() -> answer.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(AbortedException.class);
    }

    private static void assertLate(ApprovalRelay relay, String id) {
        assertThatThrownBy(() -> relay.settle(id, Decision.ALLOW)).isInstanceOfSatisfying(RpcException.class,
                error -> assertThat(error.code()).isEqualTo(-32009));
    }
}
