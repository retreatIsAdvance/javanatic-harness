package io.javanatic.harness.session.persistence.jsonl;


import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;

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
            new TurnEnd(13, 1, new TurnEndReason.Completed()));
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
}
