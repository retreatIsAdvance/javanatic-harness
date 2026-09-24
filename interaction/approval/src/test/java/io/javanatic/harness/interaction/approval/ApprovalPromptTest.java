package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.tools.ApprovalService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * it22 S-c 等待界：非交互终端空闲上限（超时按拒绝 + 可行动文案）、0/负 = 不设限、
 * 上限期间取消仍优先（AbortedException）、EOF 即时拒绝不变、有效值口径单源
 * （显式秒数优先,缺省按终端形态）。
 */
class ApprovalPromptTest {

    private InputStream originalIn;
    private PrintStream originalErr;

    @BeforeEach
    void swapStreams() {
        originalIn = System.in;
        originalErr = System.err;
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setErr(originalErr);
    }

    @Test
    void idleTimeoutDeniesWithActionableText() throws Exception {
        System.setIn(new BlockingStdin());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        long start = System.nanoTime();
        // 异步取结果:上限失效的变体只会卡住,不允测试进程挂死(卡住 = 5s 超时判红)
        CompletableFuture<Boolean> outcome = askAsync(ApprovalPrompt.stdin(Duration.ofMillis(150)),
            AbortSignal.never());
        assertThat(outcome.get(5, TimeUnit.SECONDS)).as("超时按拒绝（fail-closed）").isFalse();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).as("有界等待,不挂死").isLessThan(5_000);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("等待超时", "150ms", "--approval=auto|deny", "--approval-timeout=");
    }

    @Test
    void zeroAndNegativeIdleTimeoutMeanUnbounded() throws Exception {
        System.setIn(new SlowStdin(200, "y\n"));
        assertThat(askAsync(ApprovalPrompt.stdin(Duration.ZERO), AbortSignal.never()).get(5, TimeUnit.SECONDS))
            .as("0 = 不设限:迟到 200ms 的 y 仍放行").isTrue();
        System.setIn(new SlowStdin(200, "y\n"));
        assertThat(askAsync(ApprovalPrompt.stdin(Duration.ofMillis(50)), AbortSignal.never())
            .get(5, TimeUnit.SECONDS))
            .as("50ms 上限:迟到 200ms 的 y 已在超时按拒绝").isFalse();
        System.setIn(new SlowStdin(200, "y\n"));
        assertThat(askAsync(ApprovalPrompt.stdin(Duration.ofMillis(-5)), AbortSignal.never())
            .get(5, TimeUnit.SECONDS))
            .as("负值 = 不设限").isTrue();
    }

    @Test
    void eofDeniesImmediatelyEvenWithLongBound() throws Exception {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        long start = System.nanoTime();
        assertThat(askAsync(ApprovalPrompt.stdin(Duration.ofSeconds(300)), AbortSignal.never())
            .get(5, TimeUnit.SECONDS)).as("EOF 即时拒绝,不等上限").isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void cancelDuringBoundedWaitStillAborts() {
        System.setIn(new BlockingStdin());
        AtomicInteger checks = new AtomicInteger();
        AbortSignal signal = () -> {
            if (checks.incrementAndGet() >= 2) {
                throw new AbortedException("test-cancel");
            }
        };
        CompletableFuture<Boolean> outcome =
            askAsync(ApprovalPrompt.stdin(Duration.ofSeconds(30)), signal);
        assertThatThrownBy(() -> outcome.get(5, TimeUnit.SECONDS))
            .as("上限期间取消优先,不误判为超时拒绝")
            .hasRootCauseInstanceOf(AbortedException.class);
    }

    @Test
    void effectiveIdleTimeoutPrefersConfiguredThenTerminalShape() {
        assertThat(ApprovalPrompt.effectiveIdleTimeout(-1, false))
            .as("非交互缺省 300s").isEqualTo(Duration.ofSeconds(300));
        assertThat(ApprovalPrompt.effectiveIdleTimeout(-1, true))
            .as("交互缺省不设限").isEqualTo(Duration.ZERO);
        assertThat(ApprovalPrompt.effectiveIdleTimeout(0, false))
            .as("显式 0 = 不设限（覆盖非交互缺省）").isEqualTo(Duration.ZERO);
        assertThat(ApprovalPrompt.effectiveIdleTimeout(45, true))
            .as("显式秒数优先").isEqualTo(Duration.ofSeconds(45));
    }

    private static ApprovalService.ApprovalRequest request() {
        return new ApprovalService.ApprovalRequest("bash", "rm -rf /", "{}");
    }

    /** 虚拟线程里跑 ask 并取结果:卡住的实现只会让 get 超时判红,不拖死测试进程。 */
    private static CompletableFuture<Boolean> askAsync(ApprovalPrompt prompt, AbortSignal signal) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                outcome.complete(prompt.ask(request(), signal));
            } catch (Throwable t) {
                outcome.completeExceptionally(t);
            }
        });
        return outcome;
    }

    /** 永不返回的 stdin（持开的管道形）:等待中的读线程只由中断撤出。 */
    private static final class BlockingStdin extends InputStream {

        private final CountDownLatch block = new CountDownLatch(1);

        @Override
        public int read() {
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            return -1;
        }
    }

    /** 延迟应答 stdin:首读睡 delayMillis 再给 payload——迟到回答的确定性形状。 */
    private static final class SlowStdin extends InputStream {

        private final long delayMillis;
        private final byte[] payload;
        private int offset;
        private boolean delayed;

        SlowStdin(long delayMillis, String text) {
            this.delayMillis = delayMillis;
            this.payload = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (!delayed) {
                delayed = true;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (offset >= payload.length) {
                return -1;
            }
            int n = Math.min(len, payload.length - offset);
            System.arraycopy(payload, offset, b, off, n);
            offset += n;
            return n;
        }
    }
}
