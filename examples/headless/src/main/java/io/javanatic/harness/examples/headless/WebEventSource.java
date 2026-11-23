package io.javanatic.harness.examples.headless;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionTail;
import io.javanatic.harness.workbench.rpc.SseEventSource;
import io.javanatic.harness.workbench.rpc.SseFrame;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * serve 面真实 SSE 日志源（it38 T4 修约）：打开即原子接续已验证日志头，按真实
 * seq 严格续出 afterSeq 之后的事件；审批控制帧取当前 owner feed 的完整快照。
 *
 * <p>无丢失窗口：open 先排空「打开前已落盘」的全部完整行（跟随面自身不交付撕裂
 * 尾，空批即「追平」），head 在排空后固定；此后增量按 follower 批次继续。空批永远
 * 不是 EOF——{@link Opened#next()} 阻塞等待下一帧，只有 close 才是永久 EOF。
 * 日志破损/跟随面关闭异常在 200 之后不得静默变 EOF：上抛由连接层具名终结。
 *
 * <p>seq 由读侧从 0 计数（跟随面交付裸事件，seq 不在事件上）；重开即全量重放，
 * 重连以 Last-Event-ID 过滤。控制帧不占 seq、不设 SSE id。
 */
final class WebEventSource implements SseEventSource {

    private static final long IDLE_MILLIS = 20;

    private final SessionTail tail;
    private final WebApprovalHub hub;

    /**
     * @param tail 只读跟随面（serve 组合的 persistence-jsonl 行提供）
     * @param hub  审批 feed；null = 无写面（永不产生控制帧）
     */
    WebEventSource(SessionTail tail, WebApprovalHub hub) {
        this.tail = Objects.requireNonNull(tail, "tail");
        this.hub = hub;
    }

    @Override
    public Opened open(Id<Session> session, long afterSeq) {
        Objects.requireNonNull(session, "session");
        SessionTail.Follower follower;
        try {
            follower = tail.open(session);
        } catch (NoSuchElementException e) {
            throw new HttpFailureException(404, "session not found: " + session.value());
        } catch (IOException | RuntimeException e) {
            throw new HttpFailureException(500, "session log unreadable: " + reason(e));
        }
        long nextSeq = 0;
        List<SseFrame> backlog = new ArrayList<>();
        try {
            while (true) {
                List<SessionEvent> batch = follower.nextEvents();
                if (batch.isEmpty()) {
                    break;
                }
                for (SessionEvent event : batch) {
                    if (nextSeq > afterSeq) {
                        backlog.add(new SseFrame.Persisted(new LoggedEvent<>(nextSeq, event)));
                    }
                    nextSeq++;
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(follower);
            throw new HttpFailureException(500, "session log unreadable: " + reason(e));
        }
        return new Connection(session.value(), follower, backlog, nextSeq);
    }

    private static void closeQuietly(SessionTail.Follower follower) {
        try {
            follower.close();
        } catch (RuntimeException ignored) {
            // 打开失败路径：只保留首因
        }
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 单连接游标：排空后的固定头 + 增量跟随 + 控制帧轮询。 */
    private final class Connection implements Opened {
        private final Object lock = new Object();
        private final String owner;
        private final SessionTail.Follower follower;
        private final ArrayDeque<SseFrame> queued;
        private final long head;
        private long nextSeq;
        private long controlRevision = Long.MIN_VALUE;
        private boolean closed;

        Connection(String owner, SessionTail.Follower follower, List<SseFrame> backlog, long nextSeq) {
            this.owner = owner;
            this.follower = follower;
            this.queued = new ArrayDeque<>(backlog);
            this.head = nextSeq - 1;
            this.nextSeq = nextSeq;
        }

        @Override
        public long head() {
            return head;
        }

        @Override
        public Optional<SseFrame> next() throws InterruptedException {
            while (true) {
                synchronized (lock) {
                    if (closed) {
                        return Optional.empty();
                    }
                    if (!queued.isEmpty()) {
                        return Optional.of(queued.pollFirst());
                    }
                }
                Optional<SseFrame> control = pollControl();
                if (control.isPresent()) {
                    return control;
                }
                List<SessionEvent> batch;
                try {
                    batch = follower.nextEvents();
                } catch (IOException | RuntimeException e) {
                    synchronized (lock) {
                        if (closed) {
                            return Optional.empty();
                        }
                    }
                    throw new IllegalStateException("SSE source follower failed", e);
                }
                if (!batch.isEmpty()) {
                    synchronized (lock) {
                        for (SessionEvent event : batch) {
                            queued.addLast(new SseFrame.Persisted(new LoggedEvent<>(nextSeq, event)));
                            nextSeq++;
                        }
                    }
                    continue;
                }
                synchronized (lock) {
                    if (closed) {
                        return Optional.empty();
                    }
                    lock.wait(IDLE_MILLIS);
                }
            }
        }

        private Optional<SseFrame> pollControl() {
            if (hub == null) {
                return Optional.empty();
            }
            WebApprovalHub.Snapshot snapshot = hub.snapshot(owner);
            synchronized (lock) {
                if (closed || snapshot.revision() == controlRevision) {
                    return Optional.empty();
                }
                controlRevision = snapshot.revision();
                return Optional.of(new SseFrame.Control(control(snapshot)));
            }
        }

        private JsonValue.Obj control(WebApprovalHub.Snapshot snapshot) {
            List<JsonValue> pending = new ArrayList<>();
            for (RelayApprovalPrompt.Pending entry : snapshot.pending()) {
                pending.add(JsonValue.object()
                    .set("requestId", entry.requestId())
                    .set("session", entry.session().value())
                    .set("turn", entry.turn())
                    .set("toolName", entry.toolName())
                    .set("summary", entry.summary())
                    .build());
            }
            return JsonValue.object()
                .set("kind", "approval")
                .set("session", owner)
                .set("revision", snapshot.revision())
                .set("pending", new JsonValue.Arr(pending))
                .build();
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                queued.clear();
                lock.notifyAll();
            }
            try {
                follower.close();
            } catch (RuntimeException ignored) {
                // 撤销路径：关闭跟随面失败不掩盖调用方语义
            }
        }
    }
}
