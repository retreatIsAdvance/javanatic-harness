package io.javanatic.harness.session;

import io.javanatic.harness.session.event.ExtensionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session.append 并发契约（并行工具执行把每个工具线程变成写者，见 append Javadoc）：
 * N 线程并发 append——全部落账、seq 连续无重复、观察者逐事件恰好一次、重入拒绝。
 */
class SessionConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 200;

    /** 廉价的 log-only 扩展事件（并发契约与事件类型无关）。 */
    private record Probe(long time, int thread, int index) implements ExtensionEvent {
        @Override public String type() { return "test/probe"; }
    }

    @Test
    void concurrentAppendsAreAllLoggedWithContiguousSeq() throws Exception {
        AtomicInteger observerSeen = new AtomicInteger();
        AtomicInteger reentryRejections = new AtomicInteger();
        List<Session.Observer> observers = List.of((session, event) -> {
            observerSeen.incrementAndGet();
            // 契约：观察者不得再 append（重入拒绝）——并发下同样成立
            try {
                session.append(new Probe(1, -1, -1));
            } catch (IllegalStateException expected) {
                reentryRejections.incrementAndGet();
            }
        });
        Session session = new Session(Session.newId("concurrent"), null, null, null, observers);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < PER_THREAD; i++) {
                        session.append(new Probe(1, thread, i));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        // 全部落账 + seq 连续（每次 append 原子、monitor 建全序、无重复 seq）
        int total = THREADS * PER_THREAD;
        assertThat(session.events()).hasSize(total);
        for (int i = 0; i < total; i++) {
            assertThat(session.events().get(i).seq()).isEqualTo(i);
        }
        assertThat(session.seq()).isEqualTo(total);
        // 观察者逐事件恰好通知一次；每次通知内重入均被拒绝
        assertThat(observerSeen.get()).isEqualTo(total);
        assertThat(reentryRejections.get()).isEqualTo(total);
    }
}
