package io.javanatic.harness.workbench.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(5)
class SseBufferTest {
    @Test
    void sixtyFifthQueuedFrameAbortsInsteadOfDroppingAndContinuing() throws Exception {
        SseBuffer buffer = new SseBuffer();
        for (int i = 0; i < 64; i++) {
            assertThat(buffer.offer(new byte[]{(byte) i})).isTrue();
        }
        assertThat(buffer.offer(new byte[]{64})).isFalse();
        assertThat(buffer.offer(new byte[]{65})).isFalse();
        assertThat(buffer.poll(Duration.ZERO)).isInstanceOf(SseBuffer.End.class);
    }

    @Test
    void aggregateEncodedByteBudgetIsEightMiB() throws Exception {
        SseBuffer buffer = new SseBuffer();
        assertThat(buffer.offer(new byte[4 * 1024 * 1024])).isTrue();
        assertThat(buffer.offer(new byte[4 * 1024 * 1024])).isTrue();
        assertThat(buffer.offer(new byte[1])).isFalse();
        assertThat(buffer.poll(Duration.ZERO)).isInstanceOf(SseBuffer.End.class);
    }

    @Test
    void oversizedSingleFrameAlsoAborts() throws Exception {
        SseBuffer buffer = new SseBuffer();
        assertThat(buffer.offer(new byte[8 * 1024 * 1024 + 1])).isFalse();
        assertThat(buffer.poll(Duration.ZERO)).isInstanceOf(SseBuffer.End.class);
    }

    @Test
    void dequeueReleasesBudgetAndEofDrainsInOrder() throws Exception {
        SseBuffer buffer = new SseBuffer();
        byte[] first = new byte[8 * 1024 * 1024];
        assertThat(buffer.offer(first)).isTrue();
        assertThat(((SseBuffer.Data) buffer.poll(Duration.ZERO)).bytes()).isSameAs(first);
        assertThat(buffer.offer(new byte[]{2})).isTrue();
        assertThat(buffer.offer(new byte[]{3})).isTrue();
        buffer.finish();
        assertThat(buffer.offer(new byte[]{4})).isFalse();
        assertThat(((SseBuffer.Data) buffer.poll(Duration.ZERO)).bytes()).containsExactly((byte) 2);
        assertThat(((SseBuffer.Data) buffer.poll(Duration.ZERO)).bytes()).containsExactly((byte) 3);
        assertThat(buffer.poll(Duration.ZERO)).isInstanceOf(SseBuffer.End.class);
    }

    @Test
    void idleDiffersFromEofAndAbortWakesWaiter() throws Exception {
        SseBuffer buffer = new SseBuffer();
        assertThat(buffer.poll(Duration.ZERO)).isInstanceOf(SseBuffer.Idle.class);
        AtomicReference<SseBuffer.Result> result = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                result.set(buffer.poll(Duration.ofMinutes(1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (waiter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            // 只有 poll 的 timedWait 能进入此状态；先 countDown 不能证明已在等待。
            assertThat(waiter.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            buffer.close();
            waiter.join(1000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOf(SseBuffer.End.class);
        } finally {
            buffer.close();
            waiter.interrupt();
            waiter.join(1000);
        }
    }
}
