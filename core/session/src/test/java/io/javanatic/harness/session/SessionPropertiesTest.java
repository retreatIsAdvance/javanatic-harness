package io.javanatic.harness.session;

import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.TurnStart;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构性质（属性测试）：并发 append 下 seq 恒连续唯一。 */
class SessionPropertiesTest {

    @Property
    void concurrentAppendsProduceContiguousUniqueSeqs(
        @ForAll @IntRange(min = 1, max = 8) int threads,
        @ForAll @IntRange(min = 1, max = 40) int perThread) throws Exception {
        Session session = Session.create(Session.newId("p"), null, null);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        // 虚拟线程外再套固定池：同时争抢 append 锁
                        observed.add(session.append(new TurnStart(1, 0)).seq());
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long total = (long) threads * perThread;
        assertThat(observed).hasSize((int) total);
        assertThat(new HashSet<>(observed)).hasSize((int) total); // 无重复
        assertThat(session.seq()).isEqualTo(total);
        List<Long> expected = LongStream.range(0, total).boxed().toList();
        assertThat(session.events().stream().<Long>map(LoggedEvent::seq))
            .containsExactlyInAnyOrderElementsOf(expected); // 无跳号
    }
}
