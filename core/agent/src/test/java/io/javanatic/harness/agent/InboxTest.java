package io.javanatic.harness.agent;

import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Inbox 双队列语义：认领边界、排空、清空与并发 append 不丢失。 */
class InboxTest {

    private static UserMessage text(String body) {
        return UserMessage.of(body, new MessageSource.User());
    }

    @Test
    void turnClaimTakesExactlyOneOrdinaryMessage() {
        Inbox inbox = new Inbox();
        UserMessage first = text("first");
        UserMessage second = text("second");
        inbox.append(InboxTarget.NEXT_TURN, first);
        inbox.append(InboxTarget.NEXT_TURN, second);

        assertThat(inbox.claim(InboxTarget.NEXT_TURN)).containsExactly(first);
        assertThat(inbox.claim(InboxTarget.NEXT_TURN)).containsExactly(second);
        assertThat(inbox.claim(InboxTarget.NEXT_TURN)).isEmpty();
    }

    @Test
    void stepClaimDrainsAllSteeringAndInjectedInOrder() {
        Inbox inbox = new Inbox();
        UserMessage steer = text("steer");
        UserMessage injected = text("inject");
        UserMessage ordinary = text("ordinary");
        inbox.append(InboxTarget.NEXT_STEP, steer);
        inbox.append(InboxTarget.NEXT_STEP, injected);
        inbox.append(InboxTarget.NEXT_TURN, ordinary);

        assertThat(inbox.claim(InboxTarget.NEXT_STEP)).containsExactly(steer, injected);
        assertThat(inbox.claim(InboxTarget.NEXT_STEP)).isEmpty();
        // step 认领不碰普通排队
        assertThat(inbox.claim(InboxTarget.NEXT_TURN)).containsExactly(ordinary);
    }

    @Test
    void claimOnEmptyQueuesReturnsEmptyLists() {
        Inbox inbox = new Inbox();
        assertThat(inbox.claim(InboxTarget.NEXT_TURN)).isEmpty();
        assertThat(inbox.claim(InboxTarget.NEXT_STEP)).isEmpty();
    }

    @Test
    void clearEmptiesBothQueues() {
        Inbox inbox = new Inbox();
        inbox.append(InboxTarget.NEXT_TURN, text("a"));
        inbox.append(InboxTarget.NEXT_STEP, text("b"));
        inbox.clear();
        assertThat(inbox.nextTurn()).isEmpty();
        assertThat(inbox.nextStep()).isEmpty();
    }

    @Test
    void snapshotsAreCopiesNotLiveViews() {
        Inbox inbox = new Inbox();
        inbox.append(InboxTarget.NEXT_TURN, text("a"));
        List<UserMessage> snapshot = inbox.nextTurn();
        inbox.append(InboxTarget.NEXT_TURN, text("b"));
        assertThat(snapshot).hasSize(1);
    }

    @Test
    void concurrentAppendLosesNothing() throws Exception {
        Inbox inbox = new Inbox();
        int threads = 4;
        int perThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger targetFlip = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 目标随计数翻转：两队列都有并发写入
                        inbox.append(targetFlip.getAndIncrement() % 2 == 0
                            ? InboxTarget.NEXT_TURN : InboxTarget.NEXT_STEP, text("m" + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        List<UserMessage> all = new ArrayList<>(inbox.nextTurn());
        all.addAll(inbox.nextStep());
        assertThat(all).hasSize(threads * perThread);

        int drained = 0;
        while (!inbox.claim(InboxTarget.NEXT_TURN).isEmpty()) {
            drained++;
        }
        drained += inbox.claim(InboxTarget.NEXT_STEP).size();
        assertThat(drained).isEqualTo(threads * perThread);
    }
}
