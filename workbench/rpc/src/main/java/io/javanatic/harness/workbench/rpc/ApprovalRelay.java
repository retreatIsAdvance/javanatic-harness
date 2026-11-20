package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 审批一次性结算：登记时固定单调 deadline，截止瞬间起 fail-closed，不因重连重置。
 * 活跃表只持未决请求；终态墓碑至多 4096 个、保留 10 分钟，淘汰后 -32007。
 * 所有状态迁移同锁线性化，future 一律锁外通知；没有伴生线程。
 * 超时 Duration 由宿主 ApprovalPrompt 单源计算，本类不另设默认等待界。
 */
public final class ApprovalRelay implements AutoCloseable {
    private static final int MAX_RETIRED = 4096;
    private static final long RETENTION_NANOS = Duration.ofMinutes(10).toNanos();

    /** UI 裁决词表；取消归宿主。 */
    public enum Decision { ALLOW, REJECT }

    /** 唯一终态，成功结算后不被迟来的截止或取消覆盖。 */
    public enum Outcome { ALLOWED, REJECTED, EXPIRED, CANCELLED }

    /** relay 签发的不可伪造句柄；观察副本不授予结算权。 */
    public static final class Request {
        private final Object owner;
        private final String id;
        private final Entry entry;

        private Request(Object owner, String id, Entry entry) {
            this.owner = owner;
            this.id = id;
            this.entry = entry;
        }

        public String id() {
            return id;
        }

        public CompletionStage<Outcome> outcome() {
            return entry.future.minimalCompletionStage();
        }
    }

    private static final class Entry {
        private final String id;
        private final CompletableFuture<Outcome> future = new CompletableFuture<>();
        private final boolean bounded;
        private final long deadline;
        private volatile Outcome done;

        Entry(String id, Duration timeout, long now) {
            this.id = id;
            bounded = !timeout.isZero() && !timeout.isNegative();
            deadline = bounded ? now + timeout.toNanos() : 0;
        }
    }

    private record Tombstone(Outcome outcome, long retiredAt) { }

    private final Object owner = new Object();
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Tombstone> retired = new ConcurrentHashMap<>();
    // FIFO 索引与两表同归 owner 锁；不保存 Request/future/参数载荷。
    private final ArrayDeque<String> retirementOrder = new ArrayDeque<>();
    private final Supplier<String> ids;
    private final LongSupplier clock;
    private boolean closed;

    public ApprovalRelay() {
        this(() -> UUID.randomUUID().toString(), System::nanoTime);
    }

    ApprovalRelay(Supplier<String> ids) {
        this(ids, System::nanoTime);
    }

    /** 包内测试缝；生产固定 System.nanoTime，不开放全局可变时钟。 */
    ApprovalRelay(Supplier<String> ids, LongSupplier clock) {
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    /** @return 登记请求；0/负 Duration 不设限，正值转换溢出则 fail loud。 */
    public Request open(Duration timeout) {
        synchronized (owner) {
            if (closed) {
                throw new IllegalStateException("approval relay closed");
            }
            prune();
            String id = Objects.requireNonNull(ids.get());
            Entry entry = new Entry(id, Objects.requireNonNull(timeout), clock.getAsLong());
            if (retired.containsKey(id) || entries.putIfAbsent(id, entry) != null) {
                throw new IllegalStateException("duplicate approval request id: " + id);
            }
            return new Request(owner, id, entry);
        }
    }

    /** @throws RpcException -32007 未知；-32009 过期/取消；-32008 已结算 */
    public void settle(String requestId, Decision decision) {
        Objects.requireNonNull(decision);
        Entry entry;
        int error;
        synchronized (owner) {
            prune();
            entry = entries.get(requestId);
            if (entry == null) {
                throw missingOrTerminal(requestId);
            }
            expireIfDue(entry);
            error = terminalError(entry.done);
            if (error == 0) {
                finish(entry, decision == Decision.ALLOW ? Outcome.ALLOWED : Outcome.REJECTED);
            }
        }
        notifyOutcome(entry);
        if (error != 0) {
            throw terminalException(requestId, error);
        }
    }

    /** 显式过期；已终结不覆盖。 */
    public void expire(String requestId) {
        terminate(requestId, Outcome.EXPIRED);
    }

    /** 宿主取消；已终结不覆盖。 */
    public void cancel(String requestId) {
        terminate(requestId, Outcome.CANCELLED);
    }

    private void terminate(String id, Outcome outcome) {
        Entry entry;
        synchronized (owner) {
            prune();
            entry = entries.get(id);
            if (entry == null) {
                if (retired.containsKey(id)) {
                    return;
                }
                throw missingOrTerminal(id);
            }
            finish(entry, outcome);
        }
        notifyOutcome(entry);
    }

    /** 调用方必须持有 owner；状态迁移和从活跃表移除是同一原子动作。 */
    private void finish(Entry entry, Outcome outcome) {
        if (entry.done != null) {
            return;
        }
        entry.done = outcome;
        entries.remove(entry.id, entry);
        retired.put(entry.id, new Tombstone(outcome, clock.getAsLong()));
        retirementOrder.addLast(entry.id);
        prune();
    }

    private void expireIfDue(Entry entry) {
        if (entry.done == null && entry.bounded && clock.getAsLong() - entry.deadline >= 0) {
            finish(entry, Outcome.EXPIRED);
        }
    }

    private static void notifyOutcome(Entry entry) {
        Outcome result = entry.done;
        if (result != null) {
            entry.future.complete(result);
        }
    }

    /** 非阻塞检查登记 deadline；未决返回空，完成通知仍在锁外。 */
    public Optional<Outcome> poll(Request request) {
        if (request.owner != owner) {
            throw new IllegalArgumentException("request belongs to another relay");
        }
        Entry entry = request.entry;
        synchronized (owner) {
            expireIfDue(entry);
        }
        notifyOutcome(entry);
        return Optional.ofNullable(entry.done);
    }

    /** 等待登记时的截止；句柄不依赖墓碑，回收后仍可观察原终态。 */
    public Outcome await(Request request) {
        if (request.owner != owner) {
            throw new IllegalArgumentException("request belongs to another relay");
        }
        Entry entry = request.entry;
        while (true) {
            long remaining;
            synchronized (owner) {
                expireIfDue(entry);
                remaining = entry.bounded ? entry.deadline - clock.getAsLong() : Long.MAX_VALUE;
            }
            notifyOutcome(entry);
            try {
                return entry.future.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 调度只负责唤醒；是否过期仍以登记 deadline 的单调时钟判断。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (owner) {
                    finish(entry, Outcome.CANCELLED);
                }
                notifyOutcome(entry);
                throw new RpcException(-32603, "interrupted");
            } catch (ExecutionException e) {
                throw new IllegalStateException("approval outcome failure", e);
            }
        }
    }

    /** 活跃表实际大小，不把墓碑计为活跃请求。 */
    public int pendingCount() {
        synchronized (owner) {
            return entries.size();
        }
    }

    private void prune() {
        long now = clock.getAsLong();
        while (!retirementOrder.isEmpty()) {
            String first = retirementOrder.getFirst();
            if (retired.size() <= MAX_RETIRED && now - retired.get(first).retiredAt() < RETENTION_NANOS) {
                break;
            }
            retirementOrder.removeFirst();
            retired.remove(first);
        }
    }

    private RpcException missingOrTerminal(String id) {
        Tombstone tombstone = retired.get(id);
        return tombstone == null
                ? new RpcException(-32007, "approval request not found", data(id))
                : terminalException(id, terminalError(tombstone.outcome()));
    }

    private static int terminalError(Outcome outcome) {
        if (outcome == null) {
            return 0;
        }
        return switch (outcome) {
            case EXPIRED, CANCELLED -> -32009;
            case ALLOWED, REJECTED -> -32008;
        };
    }

    private static RpcException terminalException(String id, int code) {
        return new RpcException(code, code == -32009 ? "approval expired" : "approval already settled", data(id));
    }

    /** 阻止新登记，取消未决并清空表；通知在锁外，close 幂等。 */
    @Override
    public void close() {
        List<Entry> pending;
        synchronized (owner) {
            closed = true;
            pending = List.copyOf(entries.values());
            pending.forEach(entry -> finish(entry, Outcome.CANCELLED));
            retired.clear();
            retirementOrder.clear();
        }
        pending.forEach(ApprovalRelay::notifyOutcome);
    }

    private static ObjectNode data(String requestId) {
        ObjectNode node = RpcJson.node();
        node.put("requestId", requestId);
        return node;
    }
}
