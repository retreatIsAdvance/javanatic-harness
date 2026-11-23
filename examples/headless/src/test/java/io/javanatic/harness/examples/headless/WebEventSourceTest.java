package io.javanatic.harness.examples.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionTail;
import io.javanatic.harness.tools.ApprovalService.ApprovalRequest;
import io.javanatic.harness.workbench.rpc.SseEventSource;
import io.javanatic.harness.workbench.rpc.SseFrame;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SSE 接续契约（H2 宿主侧）：打开即钉死头部 + 原子排空；严格 seq；增量唤醒；
 * 空批不是 EOF；审批控制帧带会话归属与单调修订；撤销唤醒 EOF；404/500 分型。
 */
class WebEventSourceTest {

    @Test
    void openFixesHeadAndFiltersAfterSeq() throws Exception {
        ScriptedTail tail = new ScriptedTail();
        tail.push(new TurnStart(0, 1), new StepStart(0, 1, 0), new TurnStart(0, 2));
        WebEventSource source = new WebEventSource(tail, null);
        SseEventSource.Opened opened = source.open(Session.newId("s1"), 0);
        try {
            assertThat(opened.head()).isEqualTo(2);
            assertThat(seqOf(opened.next())).isEqualTo(1);
            assertThat(seqOf(opened.next())).isEqualTo(2);
            // 打开后再追加：头不漂移，增量按真实 seq 续出
            tail.push(new TurnStart(0, 3));
            assertThat(opened.head()).isEqualTo(2);
            assertThat(seqOf(opened.next())).isEqualTo(3);
        } finally {
            opened.close();
        }
    }

    @Test
    void afterSeqMinusOneIncludesZero() throws Exception {
        ScriptedTail tail = new ScriptedTail();
        tail.push(new TurnStart(0, 1), new TurnStart(0, 2));
        SseEventSource.Opened opened = new WebEventSource(tail, null).open(Session.newId("s1"), -1);
        try {
            assertThat(opened.head()).isEqualTo(1);
            assertThat(seqOf(opened.next())).isZero();
            assertThat(seqOf(opened.next())).isEqualTo(1);
        } finally {
            opened.close();
        }
    }

    @Test
    void emptyBatchIsNotEof() throws Exception {
        ScriptedTail tail = new ScriptedTail();
        SseEventSource.Opened opened = new WebEventSource(tail, null).open(Session.newId("s1"), -1);
        CompletableFuture<Optional<SseFrame>> next =
            CompletableFuture.supplyAsync(() -> {
                try {
                    return opened.next();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        Thread.sleep(150);
        assertThat(next).as("空批只是暂无事件，不是 EOF").isNotDone();
        opened.close();
        assertThat(next.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void closeUnblocksAndThenYieldsEof() throws Exception {
        ScriptedTail tail = new ScriptedTail();
        SseEventSource.Opened opened = new WebEventSource(tail, null).open(Session.newId("s1"), -1);
        opened.close();
        opened.close();
        assertThat(opened.next()).isEmpty();
    }

    @Test
    void controlFramesCarryOwnerSessionRevisionAndPending() throws Exception {
        ScriptedTail tail = new ScriptedTail();
        WebApprovalHub hub = new WebApprovalHub(0);
        AbortSignal signal = neverSignal();
        hub.bind(signal, "s1", Session.newId("s1"), 1);
        SseEventSource.Opened opened =
            new WebEventSource(tail, hub).open(Session.newId("s1"), -1);
        try {
            SseFrame baseline = opened.next().orElseThrow();
            assertThat(((JsonValue.Str) controlObj(baseline).fields().get("kind")).value())
                .isEqualTo("approval");
            assertThat(((JsonValue.Str) controlObj(baseline).fields().get("session")).value())
                .isEqualTo("s1");
            assertThat(((JsonValue.Arr) controlObj(baseline).fields().get("pending")).items()).isEmpty();

            ApprovalRequest request = new ApprovalRequest("fs_read", "read", "{}");
            CompletableFuture<Boolean> asked =
                CompletableFuture.supplyAsync(() -> hub.prompt().ask(request, signal));
            SseFrame control = awaitControl(opened);
            assertThat(pendingField(control, "requestId")).isNotBlank();
            long firstRevision = controlObj(control).get("revision").asLong();
            assertThat(firstRevision).isPositive();

            String requestId = pendingField(control, "requestId");
            hub.respond(requestId, true);
            assertThat(asked.get(5, TimeUnit.SECONDS)).isTrue();
            SseFrame settled = awaitControl(opened);
            long nextRevision = controlObj(settled).get("revision").asLong();
            assertThat(nextRevision).isGreaterThan(firstRevision);
            assertThat(((JsonValue.Arr) controlObj(settled).fields().get("pending")).items()).isEmpty();
        } finally {
            opened.close();
        }
    }

    @Test
    void unknownSessionMapsTo404AndDamageMapsTo500() {
        ScriptedTail missing = new ScriptedTail();
        missing.openFailure = new NoSuchElementException("absent");
        assertThatThrownBy(() -> new WebEventSource(missing, null).open(Session.newId("x"), -1))
            .isInstanceOfSatisfying(SseEventSource.HttpFailureException.class,
                e -> assertThat(e.status()).isEqualTo(404));

        ScriptedTail broken = new ScriptedTail();
        broken.openFailure = new IllegalStateException("seq gap");
        assertThatThrownBy(() -> new WebEventSource(broken, null).open(Session.newId("x"), -1))
            .isInstanceOfSatisfying(SseEventSource.HttpFailureException.class,
                e -> assertThat(e.status()).isEqualTo(500));
    }

    @Test
    void persistedFramesKeepStrictOrderAcrossBatches() throws Exception {
        ScriptedTail tail = new ScriptedTail();
        tail.push(new TurnStart(0, 1));
        SseEventSource.Opened opened = new WebEventSource(tail, null).open(Session.newId("s1"), -1);
        try {
            List<Long> seqs = new ArrayList<>();
            seqs.add(seqOf(opened.next()));
            tail.push(new StepStart(0, 1, 0), new TurnStart(0, 2));
            seqs.add(seqOf(opened.next()));
            seqs.add(seqOf(opened.next()));
            assertThat(seqs).containsExactly(0L, 1L, 2L);
        } finally {
            opened.close();
        }
    }

    // ────────── 夹具 ──────────

    /** 永不取消且每次新实例；不直接用 never()（无捕获 lambda 被 JVM 复用成单例）。 */
    private static AbortSignal neverSignal() {
        return new AbortSignal() {
            @Override
            public void checkAbort() {
            }
        };
    }

    private static long seqOf(Optional<SseFrame> frame) {
        SseFrame.Persisted persisted = (SseFrame.Persisted) frame.orElseThrow();
        return persisted.logged().seq();
    }

    private static JsonValue.Obj controlObj(SseFrame frame) {
        return ((SseFrame.Control) frame).data();
    }

    private static String pendingField(SseFrame frame, String field) {
        JsonValue.Arr pending = (JsonValue.Arr) controlObj(frame).fields().get("pending");
        JsonValue.Obj first = (JsonValue.Obj) pending.items().get(0);
        return ((JsonValue.Str) first.fields().get(field)).value();
    }

    private static SseFrame awaitControl(SseEventSource.Opened opened) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SseFrame> frame = opened.next();
            if (frame.isPresent() && frame.orElseThrow() instanceof SseFrame.Control) {
                return frame.orElseThrow();
            }
        }
        throw new AssertionError("approval control frame did not arrive");
    }

    /** 脚本化只读跟随面：批次队列驱动「已有/增量/空批」，open 失败可注入。 */
    private static final class ScriptedTail implements SessionTail {
        private final BlockingQueue<List<SessionEvent>> batches = new LinkedBlockingQueue<>();
        volatile RuntimeException openFailure;

        void push(SessionEvent... events) {
            batches.add(List.of(events));
        }

        @Override
        public Follower open(Id<Session> id) {
            if (openFailure != null) {
                throw openFailure;
            }
            return new Follower() {
                private boolean closed;

                @Override
                public SessionHeader header() {
                    return null;
                }

                @Override
                public List<SessionEvent> nextEvents() {
                    if (closed) {
                        throw new IllegalStateException("follower closed");
                    }
                    try {
                        List<SessionEvent> batch = batches.poll(20, TimeUnit.MILLISECONDS);
                        return batch == null ? List.of() : batch;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }
}
