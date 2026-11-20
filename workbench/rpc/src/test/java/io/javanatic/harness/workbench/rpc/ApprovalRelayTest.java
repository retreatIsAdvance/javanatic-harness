package io.javanatic.harness.workbench.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javanatic.harness.workbench.rpc.ApprovalRelay.Decision;
import io.javanatic.harness.workbench.rpc.ApprovalRelay.Outcome;
import io.javanatic.harness.workbench.rpc.ApprovalRelay.Request;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

/**
 * 审批一次性结算状态机(it38 停 1,T3):首答结算、重复/迟到拒绝、超时与取消
 * 收敛、未知 id 拒绝;300s 真时钟不在本类——等待界归 ApprovalPrompt 单源,
 * 这里用毫秒级截止证明同一代码路径。
 */
@Timeout(20)
class ApprovalRelayTest {

    @Test
    void allowSettlesOnceAndDuplicateIsRejected() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);

        relay.settle(request.id(), Decision.ALLOW);

        assertThat(relay.await(request)).isEqualTo(Outcome.ALLOWED);
        assertThat(relay.pendingCount()).isZero();
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32008);
    }

    @Test
    void observerCannotForgeAnAllowOutcome() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);
        request.outcome().toCompletableFuture().complete(Outcome.ALLOWED);
        relay.settle(request.id(), Decision.REJECT);
        assertThat(relay.await(request)).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void rejectMapsToRejectedOutcome() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);
        relay.settle(request.id(), Decision.REJECT);
        assertThat(relay.await(request)).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void unknownRequestIsRejected() {
        ApprovalRelay relay = new ApprovalRelay();
        assertRpcCode(() -> relay.settle("missing", Decision.ALLOW), -32007);
        assertRpcCode(() -> relay.expire("missing"), -32007);
    }

    @Test
    void timeoutExpiresAndLateAllowIsDiscarded() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ofMillis(50));

        assertThat(relay.await(request)).isEqualTo(Outcome.EXPIRED);
        assertThat(relay.pendingCount()).isZero();
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32009);
    }

    @Test
    void cancelConvergesPendingRequest() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);
        relay.cancel(request.id());
        assertThat(relay.await(request)).isEqualTo(Outcome.CANCELLED);
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32009);
    }

    @Test
    void expireIsIdempotentAndKeepsFirstOutcome() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);
        relay.settle(request.id(), Decision.ALLOW);
        relay.expire(request.id());
        assertThat(relay.await(request)).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void collidingIdFailsLoud() {
        Iterator<String> ids = List.of("same", "same").iterator();
        AtomicInteger calls = new AtomicInteger();
        ApprovalRelay relay = new ApprovalRelay(() -> {
            calls.incrementAndGet();
            return ids.next();
        });
        relay.open(Duration.ZERO);
        assertThatThrownBy(() -> relay.open(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void timeoutAndAllowRaceMustAgreeOnOneTerminalOutcome() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 600; i++) {
                AtomicLong clock = new AtomicLong();
                ApprovalRelay relay = new ApprovalRelay(() -> "race", clock::get);
                Request request = relay.open(Duration.ofNanos(10));
                long respondingAt = 9 + i % 3;
                clock.set(respondingAt);
                CountDownLatch start = new CountDownLatch(1);
                var waiting = executor.submit(() -> {
                    start.await();
                    return relay.await(request);
                });
                var deciding = executor.submit(() -> {
                    start.await();
                    try {
                        relay.settle(request.id(), Decision.ALLOW);
                        return 0;
                    } catch (RpcException e) {
                        return e.code();
                    }
                });
                start.countDown();
                Outcome observed = waiting.get(5, TimeUnit.SECONDS);
                int reply = deciding.get(5, TimeUnit.SECONDS);
                assertThat(observed).isEqualTo(request.outcome().toCompletableFuture().join());
                assertThat(observed).isEqualTo(respondingAt < 10 ? Outcome.ALLOWED : Outcome.EXPIRED);
                assertThat(reply).isEqualTo(respondingAt < 10 ? 0 : -32009);
            }
        }
    }

    @Test
    void anotherRelayCannotAwaitARequestItDidNotIssue() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);
        relay.settle(request.id(), Decision.ALLOW);
        assertThatThrownBy(() -> new ApprovalRelay().await(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentObserversCanInspectPendingRequestsWithoutDeadlock() {
        ApprovalRelay relay = new ApprovalRelay();
        Request left = relay.open(Duration.ZERO);
        Request right = relay.open(Duration.ZERO);
        CountDownLatch callbacksStarted = new CountDownLatch(2);
        Runnable observe = () -> {
            callbacksStarted.countDown();
            try {
                assertThat(callbacksStarted.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertThat(relay.pendingCount()).isZero();
        };
        var leftObserved = left.outcome().thenRun(observe).toCompletableFuture();
        var rightObserved = right.outcome().thenRun(observe).toCompletableFuture();
        // 虚拟线程避免旧实现的锁循环让测试 JVM 无法退出；不占用公共池。
        Thread.ofVirtual().start(() -> relay.settle(left.id(), Decision.ALLOW));
        Thread.ofVirtual().start(() -> relay.cancel(right.id()));
        assertThat(CompletableFuture.allOf(leftObserved, rightObserved))
                .succeedsWithin(Duration.ofSeconds(5));
    }

    @Test
    void registeredDeadlineRejectsAtBoundaryWithoutAnyWaiter() {
        AtomicLong clock = new AtomicLong(100);
        ApprovalRelay relay = new ApprovalRelay(() -> "deadline", clock::get);
        Request request = relay.open(Duration.ofNanos(10));
        clock.set(110);
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32009);
        assertThat(relay.await(request)).isEqualTo(Outcome.EXPIRED);
        assertRpcCode(() -> relay.settle(request.id(), Decision.REJECT), -32009);
    }

    @Test
    void responseBeforeDeadlineIsKeptAfterDeadline() {
        AtomicLong clock = new AtomicLong(100);
        ApprovalRelay relay = new ApprovalRelay(() -> "before", clock::get);
        Request request = relay.open(Duration.ofNanos(10));
        clock.set(109);
        relay.settle(request.id(), Decision.ALLOW);
        clock.set(111);
        assertThat(relay.await(request)).isEqualTo(Outcome.ALLOWED);
        assertThat(relay.await(request)).isEqualTo(Outcome.ALLOWED);
        assertRpcCode(() -> relay.settle(request.id(), Decision.REJECT), -32008);
    }

    @Test
    void delayedWaitDoesNotRestartDeadlineAcrossNanoTimeWraparound() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
        ApprovalRelay relay = new ApprovalRelay(() -> "wrapped", clock::get);
        Request request = relay.open(Duration.ofNanos(10));
        clock.addAndGet(11);
        assertThat(relay.await(request)).isEqualTo(Outcome.EXPIRED);
        assertThat(relay.await(request)).isEqualTo(Outcome.EXPIRED);
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32009);
    }

    @Test
    void concurrentRespondersHaveOneWinnerAndReplayCannotChangeIt() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 500; i++) {
                ApprovalRelay relay = new ApprovalRelay();
                Request request = relay.open(Duration.ZERO);
                CountDownLatch start = new CountDownLatch(1);
                var allow = executor.submit(() -> {
                    start.await();
                    return respond(relay, request, Decision.ALLOW);
                });
                var reject = executor.submit(() -> {
                    start.await();
                    return respond(relay, request, Decision.REJECT);
                });
                start.countDown();
                int allowed = allow.get(5, TimeUnit.SECONDS);
                int rejected = reject.get(5, TimeUnit.SECONDS);
                assertThat(List.of(allowed, rejected)).containsExactlyInAnyOrder(0, -32008);
                assertThat(relay.await(request)).isEqualTo(allowed == 0 ? Outcome.ALLOWED : Outcome.REJECTED);
                assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32008);
                assertRpcCode(() -> relay.settle(request.id(), Decision.REJECT), -32008);
            }
        }
    }

    @Test
    void terminalRetentionIsBoundedAndOldHandleStillObservesItsOutcome() {
        AtomicInteger ids = new AtomicInteger();
        ApprovalRelay relay = new ApprovalRelay(() -> "bounded-" + ids.incrementAndGet());
        Request oldest = relay.open(Duration.ZERO);
        relay.settle(oldest.id(), Decision.REJECT);
        for (int i = 0; i < 4095; i++) {
            Request request = relay.open(Duration.ZERO);
            relay.settle(request.id(), Decision.ALLOW);
        }
        assertRpcCode(() -> relay.settle(oldest.id(), Decision.ALLOW), -32008);
        Request last = relay.open(Duration.ZERO);
        relay.settle(last.id(), Decision.ALLOW);
        assertRpcCode(() -> relay.settle(oldest.id(), Decision.ALLOW), -32007);
        assertThat(relay.await(oldest)).isEqualTo(Outcome.REJECTED);
        assertRpcCode(() -> relay.settle("bounded-4097", Decision.REJECT), -32008);
    }

    @Test
    void tombstoneExpiresAfterTenMinutesWithoutChangingOldDecision() {
        AtomicLong clock = new AtomicLong();
        ApprovalRelay relay = new ApprovalRelay(() -> "ttl", clock::get);
        Request request = relay.open(Duration.ZERO);
        relay.cancel(request.id());
        clock.set(Duration.ofSeconds(599).toNanos());
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32009);
        clock.set(Duration.ofSeconds(600).toNanos());
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32007);
        assertThat(relay.await(request)).isEqualTo(Outcome.CANCELLED);
    }

    @Test
    void twoRespondersAfterDeadlineBothLoseToExpiry() throws Exception {
        AtomicLong clock = new AtomicLong();
        ApprovalRelay relay = new ApprovalRelay(() -> "late-race", clock::get);
        Request request = relay.open(Duration.ofNanos(10));
        clock.set(11);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var allow = executor.submit(() -> {
                start.await();
                return respond(relay, request, Decision.ALLOW);
            });
            var reject = executor.submit(() -> {
                start.await();
                return respond(relay, request, Decision.REJECT);
            });
            start.countDown();
            assertThat(allow.get(5, TimeUnit.SECONDS)).isEqualTo(-32009);
            assertThat(reject.get(5, TimeUnit.SECONDS)).isEqualTo(-32009);
            assertThat(relay.await(request)).isEqualTo(Outcome.EXPIRED);
        }
    }

    @Test
    void closeCancelsPendingAndPreventsRegistration() {
        ApprovalRelay relay = new ApprovalRelay();
        Request request = relay.open(Duration.ZERO);
        relay.close();
        relay.close();
        assertThat(relay.await(request)).isEqualTo(Outcome.CANCELLED);
        assertThat(relay.pendingCount()).isZero();
        assertThatThrownBy(() -> relay.open(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32007);
    }

    @Test
    void pollUsesRegisteredDeadlineWithoutWaitingOrResettingIt() {
        AtomicLong clock = new AtomicLong();
        try (ApprovalRelay relay = new ApprovalRelay(() -> "polled", clock::get)) {
            Request request = relay.open(Duration.ofNanos(10));
            clock.set(9);
            assertThat(relay.poll(request)).isEmpty();
            assertThat(relay.poll(request)).isEmpty();
            clock.set(10);
            assertThat(relay.poll(request)).contains(Outcome.EXPIRED);
            assertThat(request.outcome().toCompletableFuture()).isCompletedWithValue(Outcome.EXPIRED);
            assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32009);
            clock.set(Duration.ofSeconds(601).toNanos());
            assertRpcCode(() -> relay.settle(request.id(), Decision.ALLOW), -32007);
            assertThat(relay.poll(request)).contains(Outcome.EXPIRED);
        }
    }

    @Test
    void pollRejectsAnotherRelaysHandleEvenWhenAlreadySettled() {
        try (ApprovalRelay relay = new ApprovalRelay(); ApprovalRelay other = new ApprovalRelay()) {
            Request request = relay.open(Duration.ZERO);
            relay.settle(request.id(), Decision.REJECT);
            assertThatThrownBy(() -> other.poll(request)).isInstanceOf(IllegalArgumentException.class);
            assertThat(relay.poll(request)).contains(Outcome.REJECTED);
        }
    }

    @Test
    void pollNotifiesObserversOutsideRelayLock() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger ids = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                ApprovalRelay relay = new ApprovalRelay(() -> "poll-" + ids.incrementAndGet(), clock::get)) {
            Request first = relay.open(Duration.ofNanos(10));
            Request second = relay.open(Duration.ofNanos(10));
            CountDownLatch callbacks = new CountDownLatch(2);
            Runnable observe = () -> {
                callbacks.countDown();
                try {
                    assertThat(callbacks.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(relay.pendingCount()).isZero();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            };
            var firstObserved = first.outcome().thenRun(observe).toCompletableFuture();
            var secondObserved = second.outcome().thenRun(observe).toCompletableFuture();
            clock.set(10);
            var firstPoll = executor.submit(() -> relay.poll(first));
            var secondPoll = executor.submit(() -> relay.poll(second));
            try {
                assertThat(firstPoll.get(5, TimeUnit.SECONDS)).contains(Outcome.EXPIRED);
                assertThat(secondPoll.get(5, TimeUnit.SECONDS)).contains(Outcome.EXPIRED);
                CompletableFuture.allOf(firstObserved, secondObserved).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static int respond(ApprovalRelay relay, Request request, Decision decision) {
        try {
            relay.settle(request.id(), decision);
            return 0;
        } catch (RpcException e) {
            return e.code();
        }
    }

    private static void assertRpcCode(Runnable action, int code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(RpcException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }
}
