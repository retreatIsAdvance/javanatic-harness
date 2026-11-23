package io.javanatic.harness.examples.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.tools.ApprovalService.ApprovalRequest;
import io.javanatic.harness.workbench.rpc.RpcException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * H2 审批中继宿主的纯语义面：信号身份 → 每轮租约；未知信号失败关闭；
 * 会话 feed 归属与修订单调；关轮撤销；共享 relay 的结算错误码。
 */
class WebApprovalHubTest {

    private static final ApprovalRequest REQUEST =
        new ApprovalRequest("fs_read", "read note.txt", "{\"path\":\"note.txt\"}");

    @Test
    void unknownSignalFailsClosedWithoutRegisteringLease() {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal unknown = neverSignal();
        assertThat(hub.prompt().ask(REQUEST, unknown))
            .as("未知信号不登记、不等待——失败关闭")
            .isFalse();
        assertThat(hub.leaseCount()).isZero();
        assertThat(hub.snapshot("s1").pending()).isEmpty();
    }

    @Test
    void boundSignalPublishesPendingAndAllowSettles() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal signal = neverSignal();
        hub.bind(signal, "s1", Session.newId("s1"), 1);

        CompletableFuture<Boolean> asked =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, signal));
        WebApprovalHub.Snapshot pending = awaitPending(hub, "s1");
        assertThat(pending.pending()).hasSize(1);
        RelayApprovalPrompt.Pending entry = pending.pending().get(0);
        assertThat(entry.session().value()).isEqualTo("s1");
        assertThat(entry.turn()).isEqualTo(1);
        assertThat(entry.toolName()).isEqualTo("fs_read");

        hub.respond(entry.requestId(), true);
        assertThat(asked.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitEmpty(hub, "s1").pending()).isEmpty();
        assertThat(hub.snapshot("s1").revision()).isGreaterThan(pending.revision());
    }

    @Test
    void snapshotForUnboundOwnerDoesNotCreateFeed() {
        WebApprovalHub hub = new WebApprovalHub(0);
        for (String ghost : List.of("g1", "g2", "g3")) {
            assertThat(hub.snapshot(ghost).revision()).isZero();
        }
        assertThat(hub.feedCount()).as("查询不建 feed，宿主状态不随任意 session 无界增长").isZero();

        hub.bind(neverSignal(), "s1", Session.newId("s1"), 1);
        assertThat(hub.snapshot("s1").revision()).isZero();
        assertThat(hub.feedCount()).isEqualTo(1);
    }

    @Test
    void rejectSettlesFalse() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal signal = neverSignal();
        hub.bind(signal, "s1", Session.newId("s1"), 1);
        CompletableFuture<Boolean> asked =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, signal));
        String requestId = awaitPending(hub, "s1").pending().get(0).requestId();
        hub.respond(requestId, false);
        assertThat(asked.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void closeTurnCancelsPendingAndDrainsFeed() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal signal = neverSignal();
        hub.bind(signal, "s1", Session.newId("s1"), 1);
        CompletableFuture<Boolean> asked =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, signal));
        String requestId = awaitPending(hub, "s1").pending().get(0).requestId();

        hub.closeTurn("s1");
        assertThatThrownBy(() -> asked.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(AbortedException.class);
        assertThat(hub.snapshot("s1").pending()).isEmpty();
        assertThat(hub.leaseCount("s1")).isZero();
        // 关轮后的迟到结算 → 已取消（-32009）
        assertThatThrownBy(() -> hub.respond(requestId, true))
            .isInstanceOf(RpcException.class)
            .extracting(e -> ((RpcException) e).code())
            .isEqualTo(-32009);
    }

    @Test
    void feedsAreIsolatedPerOwningSession() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal a = neverSignal();
        AbortSignal b = neverSignal();
        hub.bind(a, "s1", Session.newId("s1"), 1);
        hub.bind(b, "s2", Session.newId("s2"), 1);
        CompletableFuture<Boolean> qa = CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, a));
        CompletableFuture<Boolean> qb = CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, b));

        String ida = awaitPending(hub, "s1").pending().get(0).requestId();
        String idb = awaitPending(hub, "s2").pending().get(0).requestId();
        assertThat(ida).isNotEqualTo(idb);
        assertThat(hub.snapshot("s1").pending()).map(RelayApprovalPrompt.Pending::requestId)
            .containsExactly(ida);
        assertThat(hub.snapshot("s2").pending()).map(RelayApprovalPrompt.Pending::requestId)
            .containsExactly(idb);

        hub.respond(ida, true);
        assertThat(qa.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(hub.snapshot("s2").pending()).hasSize(1);
        hub.respond(idb, true);
        assertThat(qb.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rebindingSameSignalKeepsOneLeaseForTheTurn() {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal signal = neverSignal();
        Id<Session> id = Session.newId("s1");
        assertThat(hub.bind(signal, "s1", id, 1)).isTrue();
        assertThat(hub.bind(signal, "s1", id, 1)).isFalse();
        assertThat(hub.bind(signal, "s1", id, 1)).isFalse();
        assertThat(hub.leaseCount("s1")).isEqualTo(1);
        assertThat(hub.leaseCount()).isEqualTo(1);
    }

    @Test
    void childLeaseKeepsItsOwnSessionMetadataInsideOwnerFeed() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal child = neverSignal();
        hub.bind(child, "parent", Session.newId("child-1"), 4);
        CompletableFuture<Boolean> asked =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, child));
        RelayApprovalPrompt.Pending entry = awaitPending(hub, "parent").pending().get(0);
        assertThat(entry.session().value()).as("子代元数据归属").isEqualTo("child-1");
        assertThat(hub.snapshot("child-1").pending()).isEmpty();
        hub.respond(entry.requestId(), true);
        assertThat(asked.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void closeSignalCancelsOnlyThatGenerationAndKeepsSiblingLeases() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal parent = neverSignal();
        AbortSignal child = neverSignal();
        hub.bind(parent, "parent", Session.newId("s1"), 1);
        hub.bind(child, "parent", Session.newId("child-1"), 4);
        CompletableFuture<Boolean> qParent =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, parent));
        CompletableFuture<Boolean> qChild =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, child));
        awaitPendingCount(hub, "parent", 2);

        hub.closeSignal(child);
        assertThatThrownBy(() -> qChild.get(5, TimeUnit.SECONDS))
            .as("子代租约被定向撤销")
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(AbortedException.class);
        assertThat(hub.snapshot("parent").pending()).hasSize(1);
        assertThat(hub.snapshot("parent").pending().get(0).session().value()).isEqualTo("s1");

        // 未绑定/已关闭的信号重复撤销是幂等空操作
        hub.closeSignal(child);
        hub.closeSignal(neverSignal());
        assertThat(hub.leaseCount("parent")).isEqualTo(1);

        // 父租约不受影响，仍可结算
        hub.respond(hub.snapshot("parent").pending().get(0).requestId(), true);
        assertThat(qParent.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void closeIsIdempotentAndCancelsEverything() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal signal = neverSignal();
        hub.bind(signal, "s1", Session.newId("s1"), 1);
        CompletableFuture<Boolean> asked =
            CompletableFuture.supplyAsync(() -> hub.prompt().ask(REQUEST, signal));
        awaitPending(hub, "s1");
        hub.close();
        hub.close();
        assertThatThrownBy(() -> asked.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(AbortedException.class);
        assertThat(hub.leaseCount()).isZero();
    }

    /**
     * 永不取消、但每次调用都是新实例的信号。不能复用 {@code AbortSignal.never()}：
     * 无捕获 lambda 被 JVM 复用为单例，同一测试里的两次调用拿到同一身份，
     * 会把独立会话误绑成同一租约。生产侧每轮新视图（AbortController）天然不同。
     */
    private static AbortSignal neverSignal() {
        return new AbortSignal() {
            @Override
            public void checkAbort() {
            }
        };
    }

    private static WebApprovalHub.Snapshot awaitPending(WebApprovalHub hub, String session)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            WebApprovalHub.Snapshot snapshot = hub.snapshot(session);
            if (!snapshot.pending().isEmpty()) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("pending approval did not appear for " + session);
    }

    private static WebApprovalHub.Snapshot awaitPendingCount(WebApprovalHub hub, String session, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            WebApprovalHub.Snapshot snapshot = hub.snapshot(session);
            if (snapshot.pending().size() == count) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("pending approval count " + count + " did not appear for " + session);
    }

    private static WebApprovalHub.Snapshot awaitEmpty(WebApprovalHub hub, String session)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            WebApprovalHub.Snapshot snapshot = hub.snapshot(session);
            if (snapshot.pending().isEmpty()) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("pending approval did not settle for " + session);
    }
}
