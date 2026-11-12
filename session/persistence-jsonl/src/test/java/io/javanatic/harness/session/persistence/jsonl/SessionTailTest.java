package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.SessionTail;
import io.javanatic.harness.session.persistence.WriterLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读流式 tail seam 四契约(停 1 设计档 stop1-seam-design.md):无锁 / 不修复撕裂尾 /
 * 完整行纪律 / seq 连续。夹具 = 双 Runtime 共享 root:rt 持写者(或已关),rt2 外来观察者。
 */
class SessionTailTest {

    @TempDir
    Path root;

    private Session liveSession(Runtime rt) {
        SessionStore store = rt.root().require(SessionStore.KEY);
        return store.create(rt.root(), Session.newId("s1"), CreateOptions.empty());
    }

    private static void loadAll(Runtime rt, Path root) {
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
    }

    private SessionTail.Follower open(Runtime rt) throws Exception {
        return rt.root().require(SessionTail.KEY).open(Session.newId("s1"));
    }

    /** 未知类型的 ignorable 完整行(ExtensionEvent 包裹,data.time=42;seq 1 = turn/start(seq 0)之后的下一条)。 */
    private static final String IGNORABLE_SEQ_1 =
        "{\"seq\":1,\"type\":\"x/custom\",\"ignorable\":true,\"data\":{\"time\":42}}\n";

    @Test
    void busySessionReadsViaTailWhileLoadIsRefused() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));

            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                // 契约①对照面:同一会话 load() 撞写者锁(占用即拒),tail 无锁可读
                assertThatThrownBy(() -> rt2.root().require(SessionPersistence.KEY)
                    .load(Session.newId("s1")))
                    .isInstanceOf(WriterLockException.class)
                    .hasMessageContaining("writer lock held");
                SessionTail.Follower follower = open(rt2);
                assertThat(follower.nextEvents()).extracting(e -> e.type())
                    .containsExactly("turn/start", "turn/end");
                follower.close();
            }
        }
    }

    @Test
    void tailDoesNotCreateWriterLock() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
        }
        Path lockFile = root.resolve("s1/.writer.lock");
        Files.deleteIfExists(lockFile); // 模拟外来拷贝目录:无锁文件
        assertThat(Files.exists(lockFile)).isFalse();

        try (Runtime rt2 = new Runtime()) {
            loadAll(rt2, root);
            SessionTail.Follower follower = open(rt2);
            assertThat(follower.nextEvents()).hasSize(1);
            follower.close();
        }
        // 契约①铁证:WriterLock.acquire 会 CREATE 锁文件——读后仍不存在 = 从未获取
        assertThat(Files.exists(lockFile)).isFalse();
    }

    @Test
    void tornTailIsToleratedNotRepaired() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
        }
        Path log = root.resolve("s1/log.jsonl");
        Files.writeString(log, "{\"seq\":2,", StandardOpenOption.APPEND); // 撕裂残片
        byte[] before = Files.readAllBytes(log);

        try (Runtime rt2 = new Runtime()) {
            loadAll(rt2, root);
            SessionTail.Follower follower = open(rt2);
            // 契约②+③:只交付完整行;残片不交付、不抛
            assertThat(follower.nextEvents()).extracting(e -> e.type())
                .containsExactly("turn/start", "turn/end");
            assertThat(follower.nextEvents()).isEmpty(); // 残片尚不可用
            follower.close();
        }
        // 契约②铁证:零写——文件字节逐字不变(load 会修,tail 不修)
        assertThat(Files.readAllBytes(log)).isEqualTo(before);
    }

    @Test
    void tornTailBecomesAvailableNextPollWithoutThrow() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));

            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                Path log = root.resolve("s1/log.jsonl");
                Files.writeString(log, "{\"seq\":1,\"type\":\"x/custom\"",
                    StandardOpenOption.APPEND); // 残片上半(turn/start 为 seq 0,下一条 = 1)
                SessionTail.Follower follower = open(rt2);
                // 契约③:末行不完整 = 尚不可用,不得上抛 IllegalStateException
                assertThat(follower.nextEvents()).extracting(e -> e.type())
                    .containsExactly("turn/start");
                // 残片补齐为完整行:下轮交付
                Files.writeString(log, ",\"ignorable\":true,\"data\":{\"time\":42}}\n",
                    StandardOpenOption.APPEND);
                List<SessionEvent> next =
                    follower.nextEvents();
                assertThat(next).hasSize(1);
                assertThat(next.get(0)).isInstanceOf(ExtensionEvent.class);
                assertThat(next.get(0).type()).isEqualTo("x/custom");
                follower.close();
            }
        }
    }

    @Test
    void blankLinesAreSkippedWithoutConsumingSeq() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            Files.writeString(root.resolve("s1/log.jsonl"),
                "\n" + IGNORABLE_SEQ_1, StandardOpenOption.APPEND);

            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                SessionTail.Follower follower = open(rt2);
                assertThat(follower.nextEvents()).extracting(e -> e.type())
                    .containsExactly("turn/start", "x/custom"); // 空行跳过、seq 照连续
                follower.close();
            }
        }
    }

    @Test
    void seqGapFailsLoud() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            Files.writeString(root.resolve("s1/log.jsonl"),
                "{\"seq\":5,\"type\":\"x/custom\",\"ignorable\":true,\"data\":{\"time\":42}}\n",
                StandardOpenOption.APPEND);

            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                SessionTail.Follower follower = open(rt2);
                assertThatThrownBy(follower::nextEvents)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("seq broken");
                follower.close();
            }
        }
    }

    @Test
    void sessionNotOnDiskThrowsNoSuchElement() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            assertThatThrownBy(() ->
                rt.root().require(SessionTail.KEY).open(Session.newId("nope")))
                .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void headerMatchesLoadOnIdleSession() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
        }
        try (Runtime rt2 = new Runtime()) {
            loadAll(rt2, root);
            Id<Session> sid = Session.newId("s1");
            SessionTail.Follower follower = open(rt2);
            SessionPersistence.Loaded loaded =
                rt2.root().require(SessionPersistence.KEY).load(sid);
            assertThat(follower.header()).isEqualTo(loaded.header());
            follower.close();
        }
    }

    @Test
    void incrementalDeliveryOnlyNoReplay() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));

            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                SessionTail.Follower follower = open(rt2);
                assertThat(follower.nextEvents()).extracting(e -> e.type())
                    .containsExactly("turn/start");
                assertThat(follower.nextEvents()).isEmpty(); // 无新事件 = 空
                live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
                // 只交付增量,不重放已交付
                assertThat(follower.nextEvents()).extracting(e -> e.type())
                    .containsExactly("turn/end");
                follower.close();
            }
        }
    }

    @Test
    void reopenReplaysFromStart() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));

            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                SessionTail.Follower follower = open(rt2);
                assertThat(follower.nextEvents()).hasSize(2);
                follower.close();
                // 游标随 close 丢弃:重开 = 从头全量重放
                SessionTail.Follower reopened = open(rt2);
                assertThat(reopened.nextEvents()).extracting(e -> e.type())
                    .containsExactly("turn/start", "turn/end");
                reopened.close();
            }
        }
    }
}
