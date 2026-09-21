package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.DurabilityException;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.CompactionEnd;
import io.javanatic.harness.session.event.CompactionStart;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.RequestHeader;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.WriterLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;









/** JSONL 往返、seq 校验、ignorable 决策、全事件落盘重载。 */
class JsonlPersistenceTest {

    @TempDir
    Path root;

    private Session liveSession(Runtime rt) {
        SessionStore store = rt.root().require(SessionStore.KEY);
        return store.create(rt.root(), Session.newId("s1"), CreateOptions.empty());
    }

    private static List<SessionEvent> fullHistory() {
        return List.of(
            new TurnStart(1, 1),
            new UserMessageEvent(2, UserMessage.of("任务", new MessageSource.User()), new SurfaceOp.Append(), null),
            new StepStart(3, 1, 0),
            new LlmRequestEvent(4, 1, 0, "p-sha", "t-sha", 0, 1, Map.of("temperature", "0.7")),
            new AssistantMessageEvent(5, 1, 0,
                new AssistantMessage(new MessageSource.Model("replay", "m"),
                    List.of(new TextBlock("先看文件"), new ToolUseBlock(CallId.of("c1"), "fs_read", "{}"))),
                new TokenUsage(11, 22, 3), new SurfaceOp.Append(), null),
            new ToolCallEvent(6, 1, 0, CallId.of("c1"), "fs_read", "{}"),
            new ToolResultEvent(7, 1, 0,
                new ToolResultBlock(CallId.of("c1"), "内容", false), false, new SurfaceOp.Append(), null),
            new StepEnd(8, 1, 0),
            new StepStart(9, 1, 1),
            new LlmRequestEvent(10, 1, 1, "p-sha", "t-sha", 0, 8, Map.of()),
            new AssistantMessageEvent(11, 1, 1,
                new AssistantMessage(new MessageSource.Model("replay", "m"),
                    List.of(new TextBlock("答案"))), null, new SurfaceOp.Append(), null),
            new StepEnd(12, 1, 1),
            new TurnEnd(13, 1, new TurnEndReason.Completed()),
            new CompactionStart(14, 1),
            new CompactionSummary(15, 1, "摘要", "replay", "m",
                new TokenUsage(100, 20, 0), 1, 6),
            new UserMessageEvent(16,
                new UserMessage(
                    new MessageSource.Compaction(),
                    List.of(new TextBlock("背景…"))),
                new SurfaceOp.Replace(1, 6),
                List.of(1L, 2L, 3L, 4L, 5L, 6L)),
            new CompactionEnd(17, 1, null),
            new RequestHeader(18, "/w", "2026-09-09"));
    }

    @Test
    void fullHistoryRoundTripsThroughDisk() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            for (SessionEvent event : fullHistory()) {
                live.append(event);
            }

            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            SessionPersistence.Loaded loaded = persistence.load(live.id());
            assertThat(loaded.events()).containsExactlyElementsOf(fullHistory());
            assertThat(loaded.header().id()).isEqualTo(live.id());
            assertThat(live.deriveMessages()).isEqualTo(
                Session.create(live.id(), loaded.events(), loaded.header()).deriveMessages());
        }
    }

    @Test
    void headerAndLogFilesExist() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Aborted("user")));

            Path dir = root.resolve("s1");
            assertThat(Files.readString(dir.resolve("header.json"))).contains("\"id\":\"s1\"");
            assertThat(Files.readAllLines(dir.resolve("log.jsonl"))).hasSize(2);
            assertThat(Files.readAllLines(dir.resolve("log.jsonl")).getFirst())
                .contains("\"seq\":0").contains("\"type\":\"turn/start\"");
        }
    }

    @Test
    void loadMissingSessionFailsLoud() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            assertThatThrownBy(() -> persistence.load(Session.newId("nope")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("nope");
        }
    }

    @Test
    void brokenSeqRejectsLoad() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

            // 手工制造跳号:删掉第一行(余下首行 seq=1,期望索引 0)
            Path log = root.resolve("s1/log.jsonl");
            List<String> lines = Files.readAllLines(log);
            assertThat(lines).hasSize(2);
            Files.write(log, lines.subList(1, lines.size()));
            assertThatThrownBy(() -> persistence.load(live.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seq broken");
        }
    }

    @Test
    void errorReasonFailureKindRoundTripsThroughDisk() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1,
                new TurnEndReason.Error("boom", FailureKind.RATE_LIMIT)));

            // 盘上拼写钉住(lowercase-hyphen 即格式)
            assertThat(Files.readString(root.resolve("s1/log.jsonl")))
                .contains("\"failureKind\":\"rate-limit\"");
            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(live.id());
            assertThat(loaded.events()).containsExactly(
                new TurnStart(1, 1),
                new TurnEnd(2, 1, new TurnEndReason.Error("boom", FailureKind.RATE_LIMIT)));
        }
    }

    @Test
    void oldLogMissingFailureKindReadsAsUnknown() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            liveSession(rt);
            // 升级前写入的日志:reason 无 failureKind 字段
            Files.writeString(root.resolve("s1/log.jsonl"),
                "{\"seq\":0,\"type\":\"turn/start\",\"ignorable\":false,"
                    + "\"data\":{\"time\":1,\"turn\":1}}\n"
                    + "{\"seq\":1,\"type\":\"turn/end\",\"ignorable\":false,"
                    + "\"data\":{\"time\":2,\"turn\":1,"
                    + "\"reason\":{\"kind\":\"error\",\"message\":\"boom\"}}}\n");

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(Session.newId("s1"));
            assertThat(loaded.events()).containsExactly(
                new TurnStart(1, 1),
                new TurnEnd(2, 1, new TurnEndReason.Error("boom", FailureKind.UNKNOWN)));
        }
    }

    @Test
    void unknownIgnorableEventSkipsOnLoad() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

            // 追加一行 ignorable 未知类型 + 一行非 ignorable 未知类型
            Path log = root.resolve("s1/log.jsonl");
            Files.writeString(log, "{\"seq\":1,\"type\":\"future/thing\",\"ignorable\":true,"
                + "\"data\":{\"time\":1}}\n", StandardOpenOption.APPEND);
            Files.writeString(log, "{\"seq\":2,\"type\":\"future/blocked\",\"ignorable\":false,"
                + "\"data\":{\"time\":2}}\n", StandardOpenOption.APPEND);
            assertThatThrownBy(() -> persistence.load(live.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("future/blocked");
        }
    }

    @Test
    void writeSideWithoutCodecFailsLoudOnSave() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            // append 观察者异常按 session 契约 contained(记日志不炸 append);
            // 无 codec 的 fail loud 在 save() 直调路径显形
            live.append(new ExtensionEvent() {
                @Override public long time() {
                    return 1;
                }

                @Override public String type() {
                    return "custom/unknown";
                }
            });
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            assertThatThrownBy(() -> persistence.save(live))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no codec registered");
        }
    }

    @Test
    void tornTailPartialLineTruncatedOnLoad() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

            // 模拟半行写入被杀:末尾追加无换行终止的残片
            Path log = root.resolve("s1/log.jsonl");
            Files.writeString(log, "{\"seq\":2,\"type\":\"turn/st", StandardOpenOption.APPEND);

            SessionPersistence.Loaded loaded = persistence.load(live.id());
            assertThat(loaded.events()).hasSize(2);
            String healed = Files.readString(log);
            assertThat(healed).doesNotContain("{\"seq\":2").endsWith("\n");
            assertThat(Files.readAllLines(log)).hasSize(2);
        }
    }

    @Test
    void completeTailMissingNewlineHealedOnLoad() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            live.append(new TurnStart(3, 1));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

            // 完整信封但缺换行终止(写入截断于 '\n' 前):补换行即完整
            Path log = root.resolve("s1/log.jsonl");
            List<String> lines = Files.readAllLines(log);
            assertThat(lines).hasSize(3);
            Files.writeString(log, String.join("\n", lines));

            SessionPersistence.Loaded loaded = persistence.load(live.id());
            assertThat(loaded.events()).hasSize(3);
            assertThat(Files.readAllLines(log)).hasSize(3);
            assertThat(Files.readString(log)).endsWith("\n");
        }
    }

    @Test
    void interiorCorruptionStaysFailLoud() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);

            // 中部行破损(换行终止完好):修复不插手,load fail loud
            Path log = root.resolve("s1/log.jsonl");
            List<String> lines = Files.readAllLines(log);
            lines.set(0, "{broken");
            Files.writeString(log, String.join("\n", lines) + "\n");
            assertThatThrownBy(() -> persistence.load(live.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
        }
    }

    @Test
    void repairThenResumeContinuesFromLastCompleteLine() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            // 杀于半行:第三行残片无换行终止
            Files.writeString(root.resolve("s1/log.jsonl"), "{\"seq\":2,\"type\":\"tu",
                StandardOpenOption.APPEND);
        }
        // 第二进程(resume 协议):load 修复 → 重建会话 → 续写新事件
        try (Runtime rt2 = new Runtime()) {
            new PluginLoader().loadAll(rt2, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            SessionPersistence persistence = rt2.root().require(SessionPersistence.KEY);
            SessionPersistence.Loaded loaded = persistence.load(Session.newId("s1"));
            assertThat(loaded.events()).hasSize(2);
            Session resumed = rt2.root().require(SessionStore.KEY).create(rt2.root(), Session.newId("s1"),
                new CreateOptions(loaded.events(), loaded.header()));
            resumed.append(new TurnStart(3, 1));

            SessionPersistence.Loaded after = persistence.load(Session.newId("s1"));
            // 盘上事件数 == 内存会话事件数(含 create 的 seed 收尾事件)
            assertThat(after.events()).hasSize(resumed.events().size());
            assertThat(Files.readAllLines(root.resolve("s1/log.jsonl"))).hasSize(resumed.events().size());
        }
    }

    @Test
    void flushBarrierSafeWithoutLogAndAfterEvents() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            SessionStore store = rt.root().require(SessionStore.KEY);

            // 零事件(无 log 文件)flush:不炸;已有事件 flush:不炸且日志完好
            store.flush(rt.root(), live);
            live.append(new TurnStart(1, 1));
            store.flush(rt.root(), live);
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));

            SessionPersistence.Loaded loaded = rt.root().require(SessionPersistence.KEY).load(live.id());
            assertThat(loaded.events()).hasSize(2);
            assertThat(Files.readAllLines(root.resolve("s1/log.jsonl"))).hasSize(2);
        }
    }

    @Test
    void flushBarrierIgnoresSessionsNeverPersistedHere() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            // 直构会话(未经 store):本实例无写者——屏障无管辖(等价无 listener),
            // 不假证已落盘也不误报;也不为该会话凭空建目录
            Session direct = Session.create(Session.newId("direct"), null, null);
            direct.append(new TurnStart(1, 1));
            rt.root().require(SessionStore.KEY).flush(rt.root(), direct);
            assertThat(Files.exists(root.resolve("direct"))).isFalse();
        }
    }

    @Test
    void flushBarrierExposesSwallowedWriteFailure() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            SessionStore store = rt.root().require(SessionStore.KEY);
            live.append(new TurnStart(1, 1));

            // 只读日志:后续写失败被 append 契约 contained(记日志不炸 append)
            Path log = root.resolve("s1/log.jsonl");
            log.toFile().setWritable(false);
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            assertThat(live.events()).hasSize(2); // 内存照记账

            // 对账是写失败的第一观察点:落盘数落后 seq → barrier fail loud(DISK 通道)
            assertThatThrownBy(() -> store.flush(rt.root(), live))
                .isInstanceOf(DurabilityException.class)
                .hasMessageContaining("flush barrier failed")
                .hasMessageContaining("durability barrier mismatch");
            assertThat(Files.readAllLines(log)).hasSize(1);
        }
    }

    @Test
    void gapWriteAfterSwallowedFailureIsRejectedSoBarrierStaysRed() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            SessionStore store = rt.root().require(SessionStore.KEY);

            Path log = root.resolve("s1/log.jsonl");
            live.append(new TurnStart(1, 1)); // 盘上 1 行
            log.toFile().setWritable(false);
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed())); // 写失败被 contained

            // 恢复写入后续写:seq 跳号会被拒(contained)——洞不许被写实、
            // 对账不许被追平(否则 barrier 给出假的"已耐久"结论,load 才炸)
            log.toFile().setWritable(true);
            live.append(new TurnStart(3, 1));
            assertThat(Files.readAllLines(log)).hasSize(1);
            assertThatThrownBy(() -> store.flush(rt.root(), live))
                .isInstanceOf(DurabilityException.class)
                .hasMessageContaining("durability barrier mismatch");
        }
    }

    @Test
    void secondWriterOnSameSessionFailsLoudAndFirstWriterUnaffected() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));

            try (Runtime rt2 = new Runtime()) {
                new PluginLoader().loadAll(rt2, List.of(
                    new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
                // 第二实例（同 JVM）争写：占用即拒（OverlappingFileLockException → fail loud）
                Session detached = Session.create(Session.newId("s1"), null, null);
                assertThatThrownBy(() -> rt2.root().require(SessionPersistence.KEY).save(detached))
                    .isInstanceOf(WriterLockException.class)
                    .hasMessageContaining("writer lock held");
            }

            // 首写者不受扰：照常追加，barrier 追平
            live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
            rt.root().require(SessionStore.KEY).flush(rt.root(), live);
            assertThat(Files.readAllLines(root.resolve("s1/log.jsonl"))).hasSize(2);
        }
    }

    @Test
    void foreignLoadRefusedWhileWriterActive() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));

            try (Runtime rt2 = new Runtime()) {
                new PluginLoader().loadAll(rt2, List.of(
                    new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
                // 探测锁被首写者占用：load 拒绝（不等待、不修复、不读取）
                assertThatThrownBy(() ->
                    rt2.root().require(SessionPersistence.KEY).load(Session.newId("s1")))
                    .isInstanceOf(WriterLockException.class)
                    .hasMessageContaining("writer lock held");
            }
            assertThat(Files.exists(root.resolve("s1/.writer.lock"))).isTrue();
        }
    }

    @Test
    void disposedWriterReleasesLockAndProbeDoesNotLinger() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Scope owner = rt.root().child();
            Session live = rt.root().require(SessionStore.KEY)
                .create(owner, Session.newId("s1"), CreateOptions.empty());
            live.append(new TurnStart(1, 1));

            owner.close(); // DISPOSED → writer.close() → 写者锁释放

            SessionPersistence persistence = rt.root().require(SessionPersistence.KEY);
            assertThat(persistence.load(Session.newId("s1")).events()).hasSize(1);
            // 探测锁随 load 归还：可重复 load
            assertThat(persistence.load(Session.newId("s1")).events()).hasSize(1);
        }
    }

    @Test
    void runtimeCloseReleasesWriterLockForNextProcess() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            live.append(new TurnStart(1, 1));
        }
        // scope 收拢即释放（此后的「下一进程」可 load——无 DISPOSED 也需释放）
        try (Runtime rt2 = new Runtime()) {
            new PluginLoader().loadAll(rt2, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            SessionPersistence.Loaded loaded =
                rt2.root().require(SessionPersistence.KEY).load(Session.newId("s1"));
            assertThat(loaded.events()).hasSize(1);
        }
    }

    @Test
    void surfaceSourceSeqsRoundTripInBothStates() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            Session live = liveSession(rt);
            List<SessionEvent> history = List.of(
                new TurnStart(1, 1),
                new UserMessageEvent(2, UserMessage.of("改口", new MessageSource.User()),
                    new SurfaceOp.Append(), List.of(0L)),
                new StepStart(3, 1, 0),
                new AssistantMessageEvent(4, 1, 0,
                    new AssistantMessage(new MessageSource.Model("replay", "m"),
                        List.of(new TextBlock("答"))),
                    null, new SurfaceOp.Append(), List.of(0L, 2L)),
                new ToolCallEvent(5, 1, 0, CallId.of("c1"), "fs_read", "{}"),
                new ToolResultEvent(6, 1, 0,
                    new ToolResultBlock(CallId.of("c1"), "结果未知，可自行核验", true),
                    false, new SurfaceOp.Append(), List.of(0L)),
                new UserMessageEvent(7, UserMessage.of("旧态", new MessageSource.User()),
                    new SurfaceOp.Append(), null),
                new AssistantMessageEvent(8, 1, 0,
                    new AssistantMessage(new MessageSource.Model("replay", "m"),
                        List.of(new TextBlock("旧态答"))),
                    null, new SurfaceOp.Append(), null),
                new ToolResultEvent(9, 1, 0,
                    new ToolResultBlock(CallId.of("c2"), "旧态结果", false),
                    false, new SurfaceOp.Append(), null),
                new StepEnd(10, 1, 0),
                new TurnEnd(11, 1, new TurnEndReason.Completed()));
            for (SessionEvent event : history) {
                live.append(event);
            }

            // 键恰出现 3 次:带 seqs 的三事件写出、不带者无键(absence 面在盘上钉住)
            String log = Files.readString(root.resolve("s1/log.jsonl"));
            assertThat(log.split("\"sourceEventSeqs\"", -1)).hasSize(4);
            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(live.id());
            assertThat(loaded.events()).containsExactlyElementsOf(history);
        }
    }

    @Test
    void oldLogWithoutSourceSeqsKeyReadsAsNull() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            liveSession(rt);
            // 升级前写入的 surface 事件:无 sourceEventSeqs 键 → 读回 null(v0 天然兼容)
            Files.writeString(root.resolve("s1/log.jsonl"),
                "{\"seq\":0,\"type\":\"turn/start\",\"ignorable\":false,"
                    + "\"data\":{\"time\":1,\"turn\":1}}\n"
                    + "{\"seq\":1,\"type\":\"user/message\",\"ignorable\":false,"
                    + "\"data\":{\"time\":2,\"message\":{\"source\":{\"kind\":\"user\"},"
                    + "\"content\":[{\"kind\":\"text\",\"text\":\"旧\"}]}}}\n");

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(Session.newId("s1"));
            assertThat(loaded.events()).containsExactly(
                new TurnStart(1, 1),
                new UserMessageEvent(2, UserMessage.of("旧", new MessageSource.User()),
                    new SurfaceOp.Append(), null));
        }
    }
}
