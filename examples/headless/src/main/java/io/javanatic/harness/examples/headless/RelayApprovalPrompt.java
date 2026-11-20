package io.javanatic.harness.examples.headless;

import io.javanatic.harness.interaction.approval.ApprovalPrompt;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.tools.ApprovalService.ApprovalRequest;
import io.javanatic.harness.workbench.rpc.ApprovalRelay;
import io.javanatic.harness.workbench.rpc.ApprovalRelay.Outcome;
import io.javanatic.harness.workbench.rpc.ApprovalRelay.Request;
import io.javanatic.harness.workbench.rpc.RpcException;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每个 session/turn 独占且不可复用的审批租约，宿主须在该轮 finally 中关闭。
 * 关联由构造器显式传入，不推断工具虚线程的上下文；不拥有共享 relay。
 * 恢复面仅为实例内全量快照，revision 不是 R1 seq，也不是 SSE id。
 */
final class RelayApprovalPrompt implements ApprovalPrompt, AutoCloseable {
    private static final long POLL_MILLIS = 50;

    /** 不额外传递原始 arguments；summary 本身可能含参数，不是脱敏边界。 */
    record Pending(String requestId, Id<Session> session, int turn, String toolName, String summary) { }

    /** 不保留历史；完整替换客户端的 pending 列表。 */
    record Snapshot(long revision, List<Pending> pending) {
        Snapshot {
            pending = List.copyOf(pending);
        }
    }

    private record Active(Request request, CompletableFuture<Outcome> future, Pending pending) { }

    // AbortSignal 没有撤销监听 API；回调只持此小句柄的引用盒，ask finally 清空盒。
    private record Cancellation(ApprovalRelay relay, String requestId) { }

    private final Object lock = new Object();
    private final ApprovalRelay relay;
    private final Id<Session> session;
    private final int turn;
    private final Duration timeout;
    private final ConcurrentHashMap<String, Active> active = new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = new HashSet<>();
    private long revision;
    private boolean closed;

    RelayApprovalPrompt(ApprovalRelay relay, Id<Session> session, int turn, long configuredSeconds, boolean interactive) {
        this.relay = Objects.requireNonNull(relay);
        this.session = Objects.requireNonNull(session);
        this.turn = turn;
        this.timeout = ApprovalPrompt.effectiveIdleTimeout(configuredSeconds, interactive);
    }

    @Override
    public boolean ask(ApprovalRequest request, AbortSignal signal) {
        signal.checkAbort();
        Active pending = null;
        AtomicReference<Cancellation> cancellation = new AtomicReference<>();
        try {
            Active registered = register(request);
            pending = registered;
            cancellation.set(new Cancellation(relay, registered.request().id()));
            // 登记回调也可能同步执行，必须离开元数据锁；读快照同时排除已完成 future。
            registered.future().thenRun(() -> remove(registered.request().id()));
            signal.onCancel(() -> cancel(cancellation.get()));
            return await(registered, signal);
        } catch (RuntimeException e) {
            signal.checkAbort();
            throw e;
        } finally {
            try {
                cancel(cancellation.getAndSet(null));
            } finally {
                if (pending != null) {
                    remove(pending.request().id());
                }
            }
        }
    }

    private Active register(ApprovalRequest approval) {
        synchronized (lock) {
            checkOpen();
            Request request = relay.open(timeout);
            Active pending = new Active(request, request.outcome().toCompletableFuture(),
                    new Pending(request.id(), session, turn, approval.toolName(), approval.summary()));
            active.put(request.id(), pending);
            revision++;
            publishLocked();
            return pending;
        }
    }

    private boolean await(Active pending, AbortSignal signal) {
        while (true) {
            signal.checkAbort();
            synchronized (lock) {
                checkOpen();
            }
            Optional<Outcome> outcome = relay.poll(pending.request());
            if (outcome.isPresent()) {
                signal.checkAbort();
                synchronized (lock) {
                    checkOpen();
                    return switch (outcome.orElseThrow()) {
                        case ALLOWED -> true;
                        case REJECTED, EXPIRED -> false;
                        case CANCELLED -> throw new AbortedException("approval cancelled");
                    };
                }
            }
            try {
                // 只读 future 用作短等待/取消唤醒；deadline 裁决始终由 relay.poll 负责。
                pending.future().get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 默认 onCancel 是 no-op；无论有无监听都继续 checkAbort。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                signal.checkAbort();
                throw new AbortedException("approval wait interrupted");
            } catch (ExecutionException e) {
                signal.checkAbort();
                throw new IllegalStateException("approval outcome failure", e);
            }
        }
    }

    private static void cancel(Cancellation cancellation) {
        if (cancellation == null) {
            return;
        }
        try {
            cancellation.relay().cancel(cancellation.requestId());
        } catch (RpcException e) {
            if (e.code() != -32007) {
                throw e;
            }
            // 墓碑已淘汰或共享 relay 已关闭：本租约签发请求的幂等回收，无剩余待撤销项。
        }
    }

    /** 权威读面与 subscribe 共用同一把锁；settle 完成后不再返回旧 pending。 */
    Snapshot snapshot() {
        synchronized (lock) {
            if (pruneCompletedLocked()) {
                publishLocked();
            }
            return snapshotLocked();
        }
    }

    /** 初始快照与登记原子化，无先取快照再订阅的丢失窗口。 */
    Subscription subscribe() {
        synchronized (lock) {
            Snapshot initial = snapshot();
            Subscription subscription = new Subscription(initial);
            if (closed) {
                subscription.finish(initial);
            } else {
                subscriptions.add(subscription);
            }
            return subscription;
        }
    }

    private void remove(String id) {
        synchronized (lock) {
            if (active.remove(id) != null) {
                revision++;
                publishLocked();
            }
        }
    }

    private boolean pruneCompletedLocked() {
        int before = active.size();
        active.values().removeIf(pending -> pending.future().isDone());
        int removed = before - active.size();
        revision += removed;
        return removed != 0;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(revision, active.values().stream().map(Active::pending).toList());
    }

    private void publishLocked() {
        pruneCompletedLocked();
        Snapshot snapshot = snapshotLocked();
        // 自有容量 1 邮箱，只覆盖引用并唤醒，不运行外部回调、不等待消费者。
        subscriptions.forEach(subscription -> subscription.offer(snapshot));
    }

    private void checkOpen() {
        if (closed) {
            throw new AbortedException("approval lease closed");
        }
    }

    @Override
    public void close() {
        List<Active> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = List.copyOf(active.values());
            revision += active.size();
            active.clear();
            Snapshot last = snapshotLocked();
            subscriptions.forEach(subscription -> subscription.finish(last));
            subscriptions.clear();
        }
        // cancel 会完成 future 并运行观察者；不得带着租约锁进入 relay。
        pending.forEach(item -> cancel(new Cancellation(relay, item.request().id())));
    }

    /** 容量 1 的合并邮箱；结束时送最后空快照并唤醒等待者，撤销后不接收更新。 */
    final class Subscription implements AutoCloseable {
        private Snapshot latest;
        private boolean ended;

        private Subscription(Snapshot initial) {
            latest = initial;
        }

        private synchronized void offer(Snapshot snapshot) {
            if (!ended) {
                latest = snapshot;
                notifyAll();
            }
        }

        private synchronized void finish(Snapshot last) {
            if (!ended) {
                ended = true;
                latest = last;
                notifyAll();
            }
        }

        /** 最后快照只消费一次；结束且已排空时 fail loud，不永久等待。 */
        synchronized Snapshot take() throws InterruptedException {
            while (latest == null && !ended) {
                wait();
            }
            if (latest == null) {
                throw new IllegalStateException("approval subscription closed");
            }
            Snapshot result = latest;
            latest = null;
            return result;
        }

        /** 0 为非阻塞读；到期或结束且排空返回空。 */
        synchronized Optional<Snapshot> poll(Duration timeout) throws InterruptedException {
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("negative subscription timeout");
            }
            long remaining = timeout.toNanos();
            long deadline = System.nanoTime() + remaining;
            while (latest == null && !ended && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = deadline - System.nanoTime();
            }
            Snapshot result = latest;
            latest = null;
            return Optional.ofNullable(result);
        }

        @Override
        public void close() {
            synchronized (lock) {
                subscriptions.remove(this);
                finish(new Snapshot(revision, List.of()));
            }
        }
    }
}
