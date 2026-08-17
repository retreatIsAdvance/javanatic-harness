package io.javanatic.harness.kernel.events;

import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Events 两模式派发：模式配对 fail loud、waterfall 链语义、冒泡过滤、scope 回收订阅。 */
class EventsTest {

    record Ping(String text) {}

    static final EventKey<Ping> PING = EventKey.notify("kernel-test.ping", Ping.class);
    static final EventKey<String> GATE = EventKey.waterfall("kernel-test.gate", String.class);

    @Test
    void modeMismatchFailsLoudOnSubscribeAndDispatch() {
        try (Runtime rt = new Runtime()) {
            Scope root = rt.root();
            assertThatThrownBy(() -> root.events().on(GATE, (carrier, payload) -> {}))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> root.events().onWaterfall(PING, (carrier, args) -> null))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> rt.events().notify(GATE, root, this, "x"))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void notifyIsFireAndForget() throws Exception {
        try (Runtime rt = new Runtime()) {
            CountDownLatch latch = new CountDownLatch(2);
            rt.root().events().onGlobal(PING, (carrier, payload) -> latch.countDown());
            rt.events().notify(PING, rt.root(), this, new Ping("go"));
            rt.events().notify(PING, rt.root().child(), this, new Ping("go"));
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void notifyOrderedPropagatesInOrderAndStopsOnFailure() {
        try (Runtime rt = new Runtime()) {
            List<String> seen = new ArrayList<>();
            rt.root().events().onGlobal(PING, (carrier, payload) -> seen.add("first"));
            rt.root().events().onGlobal(PING, (carrier, payload) -> {
                throw new IllegalStateException("boom");
            });
            rt.root().events().onGlobal(PING, (carrier, payload) -> seen.add("third"));
            assertThatThrownBy(() -> rt.events().notifyOrdered(PING, rt.root(), this, new Ping("x")))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(seen).containsExactly("first");
        }
    }

    @Test
    void notifyAndWaitJoinsAllListeners() throws Exception {
        try (Runtime rt = new Runtime()) {
            AtomicInteger done = new AtomicInteger();
            for (int i = 0; i < 5; i++) {
                rt.root().events().onGlobal(PING, (carrier, payload) -> {
                    Thread.sleep(10);
                    done.incrementAndGet();
                });
            }
            rt.events().notifyAndWait(PING, rt.root(), this, new Ping("x")).join();
            assertThat(done.get()).isEqualTo(5);
        }
    }

    @Test
    void waterfallRunsInSubscriptionOrderAndReturnsTail() {
        try (Runtime rt = new Runtime()) {
            List<String> calls = new ArrayList<>();
            rt.root().events().onWaterfall(GATE, (carrier, args) -> {
                calls.add("first:" + args.args().getFirst());
                return args.next();
            });
            rt.root().events().onWaterfall(GATE, (carrier, args) -> {
                calls.add("second:" + args.args().getFirst());
                return "tail:" + args.args().getFirst();
            });
            String result = rt.events().waterfall(GATE, rt.root(), this, List.of("in"), none -> "inner");
            assertThat(result).isEqualTo("tail:in");
            assertThat(calls).containsExactly("first:in", "second:in");
        }
    }

    @Test
    void waterfallArgRewriteFlowsDownstream() {
        try (Runtime rt = new Runtime()) {
            rt.root().events().onWaterfall(GATE, (carrier, args) -> args.next("rewritten"));
            rt.root().events().onWaterfall(GATE, (carrier, args) -> "saw:" + args.args().getFirst());
            String result = rt.events().waterfall(GATE, rt.root(), this, List.of("original"), none -> "inner");
            assertThat(result).isEqualTo("saw:rewritten");
        }
    }

    @Test
    void waterfallNextInvokedTwiceThrows() {
        try (Runtime rt = new Runtime()) {
            rt.root().events().onWaterfall(GATE, (carrier, args) -> {
                args.next();
                return args.next();
            });
            assertThatThrownBy(() -> rt.events().waterfall(GATE, rt.root(), this, List.of("x"), none -> "inner"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("waterfall next() invoked twice");
        }
    }

    @Test
    void waterfallShortCircuitSkipsRestAndInner() {
        try (Runtime rt = new Runtime()) {
            AtomicInteger skipped = new AtomicInteger();
            rt.root().events().onWaterfall(GATE, (carrier, args) -> "mine");
            rt.root().events().onWaterfall(GATE, (carrier, args) -> {
                skipped.incrementAndGet();
                return "not-reached";
            });
            String result = rt.events().waterfall(GATE, rt.root(), this, List.of("x"), none -> {
                skipped.incrementAndGet();
                return "inner";
            });
            assertThat(result).isEqualTo("mine");
            assertThat(skipped.get()).isZero();
        }
    }

    @Test
    void firstOfReturnsFirstNonNullAndEmptyWhenAllDecline() {
        try (Runtime rt = new Runtime()) {
            rt.root().events().onWaterfall(GATE, (carrier, args) -> args.next());
            rt.root().events().onWaterfall(GATE, (carrier, args) -> "answer");
            assertThat(rt.events().firstOf(GATE, rt.root(), this, List.of("q"))).contains("answer");

            try (Runtime other = new Runtime()) {
                other.root().events().onWaterfall(GATE, (carrier, args) -> args.next());
                assertThat(other.events().firstOf(GATE, other.root(), this, List.of("q"))).isEmpty();
            }
        }
    }

    @Test
    void scopeSubscriptionReceivesDescendantOriginsOnly() throws Exception {
        try (Runtime rt = new Runtime()) {
            AtomicInteger received = new AtomicInteger();
            Scope parent = rt.root().child();
            parent.events().on(PING, (carrier, payload) -> received.incrementAndGet());
            Scope child = parent.child();
            Scope grandchild = child.child();
            Scope sibling = rt.root().child();

            rt.events().notifyAndWait(PING, grandchild, this, new Ping("up")).join();
            rt.events().notifyAndWait(PING, sibling, this, new Ping("peer")).join();
            // 冒泡：后代 origin 命中订阅；兄弟 origin 不命中
            assertThat(received.get()).isEqualTo(1);
        }
    }

    @Test
    void scopeCloseUnsubscribesItsListeners() throws Exception {
        try (Runtime rt = new Runtime()) {
            AtomicInteger received = new AtomicInteger();
            Scope subscriber = rt.root().child();
            subscriber.events().onGlobal(PING, (carrier, payload) -> received.incrementAndGet());
            subscriber.close();
            rt.events().notifyAndWait(PING, rt.root(), this, new Ping("after-close")).join();
            assertThat(received.get()).isZero();
        }
    }
}
