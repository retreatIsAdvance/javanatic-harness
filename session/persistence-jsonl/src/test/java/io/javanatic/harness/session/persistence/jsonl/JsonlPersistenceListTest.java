package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.RequestHeader;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.SessionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/** it22 会话列举:有界折叠(header + 首尾窗口 + 锁探针)、确定性序、截断、只读探针与 fail loud。 */
class JsonlPersistenceListTest {

    @TempDir
    Path root;

    /** 装齐 store + jsonl 的运行时(收拢即释放写者锁——列举从「静止盘」读)。 */
    private static Runtime runtime(Path root) {
        Runtime rt = new Runtime();
        new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
        return rt;
    }

    private static Session seed(Runtime rt, String id) {
        return rt.root().require(SessionStore.KEY).create(rt.root(), Session.newId(id),
            CreateOptions.empty());
    }

    /** 造三个会话(alpha 一事件 / beta 两事件含 cwd / gamma 仅 header),mtime 拉开次序。 */
    private static void fixtures(Path root) throws Exception {
        try (Runtime rt = runtime(root)) {
            Session alpha = seed(rt, "alpha");
            alpha.append(new TurnStart(1, 1));
            Session beta = seed(rt, "beta");
            beta.append(new TurnStart(1, 1));
            beta.append(new RequestHeader(2, "/w/beta", "2026-09-22"));
            seed(rt, "gamma"); // 无事件:header.json 之外无 log
        }
        Files.setLastModifiedTime(root.resolve("alpha/log.jsonl"), FileTime.fromMillis(3_000));
        Files.setLastModifiedTime(root.resolve("beta/log.jsonl"), FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(root.resolve("gamma/header.json"), FileTime.fromMillis(4_000));
    }

    @Test
    void listsByLastActivityDescendingWithEventsCwdAndLimit() throws Exception {
        fixtures(root);
        try (Runtime rt = runtime(root)) {
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

            SessionPersistence.Catalog catalog = persistence.list(10);
            assertThat(catalog.total()).isEqualTo(3);
            assertThat(catalog.sessions()).extracting(summary -> summary.id().value())
                .containsExactly("gamma", "alpha", "beta"); // mtime 降序:4s / 3s / 2s
            SessionSummary alpha = summary(catalog, "alpha");
            assertThat(alpha.eventCount()).isEqualTo(1);
            assertThat(alpha.cwd()).isEmpty(); // 无 request/header 事实 → 诚实留空
            SessionSummary beta = summary(catalog, "beta");
            assertThat(beta.eventCount()).isEqualTo(2);
            assertThat(beta.cwd()).contains("/w/beta");
            SessionSummary gamma = summary(catalog, "gamma");
            assertThat(gamma.eventCount()).isZero();
            assertThat(gamma.busy()).isFalse();
            assertThat(gamma.lastActivityMillis()).isEqualTo(4_000); // 无 log:退回头 mtime

            // 有界切片 + 总数:截断标记由消费方按 total 渲染
            SessionPersistence.Catalog bounded = persistence.list(2);
            assertThat(bounded.total()).isEqualTo(3);
            assertThat(bounded.sessions()).extracting(summary -> summary.id().value())
                .containsExactly("gamma", "alpha");
        }
    }

    @Test
    void sameActivityBreaksTiesById() throws Exception {
        try (Runtime rt = runtime(root)) {
            seed(rt, "b-session").append(new TurnStart(1, 1));
            seed(rt, "a-session").append(new TurnStart(1, 1));
        }
        Files.setLastModifiedTime(root.resolve("a-session/log.jsonl"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(root.resolve("b-session/log.jsonl"), FileTime.fromMillis(1_000));
        try (Runtime rt = runtime(root)) {
            assertThat(rt.root().require(SessionPersistence.KEY).list(10).sessions())
                .extracting(summary -> summary.id().value())
                .containsExactly("a-session", "b-session");
        }
    }

    @Test
    void tornTailAndOversizedFinalEventBothStillCounted() throws Exception {
        try (Runtime rt = runtime(root)) {
            Session torn = seed(rt, "torn");
            torn.append(new TurnStart(1, 1));
            torn.append(new RequestHeader(2, "/w", "2026-09-22"));
            Session big = seed(rt, "big");
            big.append(new TurnStart(1, 1));
            // 末条事件行 > 8 KiB 初始窗口(长工具结果/长消息的现实形状):窗口放大重读仍可折叠
            big.append(new RequestHeader(2, "x".repeat(20_000), "2026-09-22"));
        }
        Files.writeString(root.resolve("torn/log.jsonl"), "{\"seq\":2,\"type\":\"tu",
            StandardOpenOption.APPEND); // 崩溃在写中:半行无换行终止
        try (Runtime rt = runtime(root)) {
            SessionPersistence.Catalog catalog = rt.root().require(SessionPersistence.KEY).list(10);
            assertThat(summary(catalog, "torn").eventCount()).isEqualTo(2);
            assertThat(summary(catalog, "torn").cwd()).contains("/w");
            assertThat(summary(catalog, "big").eventCount()).isEqualTo(2);
        }
    }

    @Test
    void unusableTailFailsLoudInsteadOfReportingFalseCount() throws Exception {
        try (Runtime rt = runtime(root)) {
            seed(rt, "broken");
        }
        Files.writeString(root.resolve("broken/log.jsonl"), "{not json at all}\n");
        try (Runtime rt = runtime(root)) {
            assertThatThrownBy(() -> rt.root().require(SessionPersistence.KEY).list(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session log tail unreadable");
        }
    }

    @Test
    void busyFlagFollowsWriterLockAndProbeNeverCreatesLock() throws Exception {
        Path handMade = root.resolve("hand-made");
        Files.createDirectories(handMade);
        Files.writeString(handMade.resolve("header.json"),
            "{\"version\":0,\"id\":\"hand-made\",\"createdAt\":1,\"parentSession\":null,"
                + "\"seedLength\":0,\"manifest\":null}");
        try (Runtime rt = runtime(root)) {
            seed(rt, "active").append(new TurnStart(1, 1));
            SessionPersistence.Catalog catalog = rt.root().require(SessionPersistence.KEY).list(10);
            assertThat(summary(catalog, "active").busy()).isTrue(); // 本实例即写者
            assertThat(summary(catalog, "hand-made").busy()).isFalse();
            // 探针只探不改:空闲会话不被凭空造出锁文件
            assertThat(Files.exists(handMade.resolve(".writer.lock"))).isFalse();
        }
        try (Runtime rt = runtime(root)) {
            // 写者随 scope 收拢释放 → 同一会话转 idle
            SessionPersistence.Catalog catalog = rt.root().require(SessionPersistence.KEY).list(10);
            assertThat(summary(catalog, "active").busy()).isFalse();
        }
    }

    @Test
    void nonSessionChildrenAreIgnoredAndReadOnlyRootStaysAbsent() throws Exception {
        try (Runtime rt = runtime(root)) {
            seed(rt, "s1").append(new TurnStart(1, 1));
        }
        Files.writeString(root.resolve("stray.txt"), "not a session");
        Files.createDirectories(root.resolve("no-header-dir"));
        try (Runtime rt = runtime(root)) {
            SessionPersistence.Catalog catalog = rt.root().require(SessionPersistence.KEY).list(10);
            assertThat(catalog.total()).isEqualTo(1);
            assertThat(catalog.sessions()).extracting(summary -> summary.id().value())
                .containsExactly("s1");
        }
        // 根不存在 = 尚无会话(不是错误):列举不建目录,只读
        Path missing = root.resolve("missing-root");
        try (Runtime rt = runtime(missing)) {
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            assertThat(persistence.list(10).total()).isZero();
            assertThat(persistence.list(10).sessions()).isEmpty();
        }
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void nonPositiveLimitIsRejected() throws Exception {
        try (Runtime rt = runtime(root)) {
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            assertThatThrownBy(() -> persistence.list(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list limit must be positive");
            assertThatThrownBy(() -> persistence.list(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static SessionSummary summary(SessionPersistence.Catalog catalog, String id) {
        return catalog.sessions().stream()
            .filter(summary -> summary.id().value().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no summary for " + id));
    }
}
