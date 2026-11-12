package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.persistence.SessionTail;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * header.json 原子写竞态（停 1 评审 P3）：backfill 非原子写时，并发 open()
 * 在「文件已建、内容未落」窗口读到空/撕裂 header → 未分类解析异常。原子写
 * （temp + ATOMIC_MOVE）下文件只有「不存在 / 完整」两态 ⇒ open() 的失败面
 * 收敛为 NoSuchElementException（可重试）。夹具 = 双 Runtime：rt 连续建会话
 * （backfill 流）、rt2 观察者 spin open 最新 id。
 */
class HeaderAtomicWriteTest {

    @TempDir
    Path root;

    private static void loadAll(Runtime rt, Path root) {
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
    }

    @Test
    void concurrentOpenDuringBackfillNeverSeesTornHeader() throws Exception {
        try (Runtime rt = new Runtime()) {
            loadAll(rt, root);
            SessionStore store = rt.root().require(SessionStore.KEY);
            try (Runtime rt2 = new Runtime()) {
                loadAll(rt2, root);
                SessionTail tail = rt2.root().require(SessionTail.KEY);
                AtomicReference<Id<Session>> latest = new AtomicReference<>();
                AtomicBoolean done = new AtomicBoolean();
                ConcurrentLinkedQueue<Throwable> violations = new ConcurrentLinkedQueue<>();

                Thread writer = new Thread(() -> {
                    try {
                        for (int i = 0; i < 1000; i++) {
                            Id<Session> id = Session.newId("race-" + i);
                            latest.set(id);
                            store.create(rt.root(), id, CreateOptions.empty());
                        }
                    } catch (Throwable t) {
                        violations.add(t);
                    } finally {
                        done.set(true);
                    }
                });
                writer.start();

                int opened = 0;
                while (!done.get() || opened == 0) {
                    Id<Session> id = latest.get();
                    if (id == null) {
                        if (done.get()) {
                            break; // 写者一个会话都没建出（异常已在 violations）
                        }
                        continue;
                    }
                    try (SessionTail.Follower follower = tail.open(id)) {
                        opened++;
                    } catch (NoSuchElementException notYet) {
                        // 「尚不在盘上」——原子写下唯一合法失败态（可重试）
                    } catch (Throwable torn) {
                        violations.add(torn);
                    }
                }
                writer.join(30_000);
                assertThat(writer.isAlive()).isFalse();

                assertThat(violations).isEmpty(); // 撕裂/未分类异常 = 非原子写暴露
                assertThat(opened).isPositive();
                // 终态确定性补检：最后一个会话的 header 完整可读
                try (SessionTail.Follower follower = tail.open(latest.get())) {
                    assertThat(follower.header().id()).isEqualTo(latest.get());
                }
            }
        }
    }
}
