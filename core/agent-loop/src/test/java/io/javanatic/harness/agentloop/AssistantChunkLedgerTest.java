package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * assistant/chunk 留痕的盘上与估价契约：ServiceLoader 发现的 AssistantChunkCodec
 * 逐块同序同量往返;chunk 不参与压缩估价(非 surface 事件估价恒 0,阈值压力只由
 * 装配后的大事件触发)。APPENDED 保序观察见 AgentLoopTest.appendedObserverReceivesChunksInOrder。
 */
class AssistantChunkLedgerTest {

    @TempDir
    Path root;

    /** chunk 风暴:文本增量×2 + tool-use 续帧(name=null) + 计量 + 收尾。 */
    private static List<AssistantChunkEvent> storm() {
        return List.of(
            new AssistantChunkEvent(10, 1, 0, new StreamChunk.Delta("今天")),
            new AssistantChunkEvent(11, 1, 0, new StreamChunk.Delta("天晴")),
            new AssistantChunkEvent(12, 1, 0,
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read", "{\"path\":")),
            new AssistantChunkEvent(13, 1, 0,
                new StreamChunk.DeltaToolUse(CallId.of("c1"), null, "\"a.txt\"}")),
            new AssistantChunkEvent(14, 1, 0,
                new StreamChunk.Usage(new TokenUsage(11, 22, 3))),
            new AssistantChunkEvent(15, 1, 0,
                new StreamChunk.Finish(FinishReason.TOOL_USE)));
    }

    @Test
    void everyChunkRoundTripsThroughDiskInOrder() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root)));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session live = store.create(rt.root(), Session.newId("storm"), CreateOptions.empty());
            for (AssistantChunkEvent chunk : storm()) {
                live.append(chunk);
            }
            store.flush(rt.root(), live);

            // 盘上拼写钉住(类型名即模块外引用键)
            String log = Files.readString(root.resolve("storm/log.jsonl"));
            assertThat(log).contains("\"type\":\"assistant/chunk\"")
                .contains("\"kind\":\"tool-use\"")
                .contains("\"kind\":\"finish\"");

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(live.id());
            assertThat(loaded.events()).containsExactlyElementsOf(storm());
        }
    }

    @Test
    void chunksDoNotCountTowardCompactionEstimate() {
        // 对照:同一估价器对真实大事件照常计价,只有 chunk 恒 0
        assertThat(CompactionPlugin.estimateTokens(new UserMessageEvent(1,
            UserMessage.of("一段足够长的用户文本内容", new MessageSource.User()),
            new SurfaceOp.Append(), null))).isPositive();
        for (AssistantChunkEvent chunk : storm()) {
            assertThat(CompactionPlugin.estimateTokens(chunk))
                .as("chunk 风暴不推高估价: %s", chunk.chunk())
                .isZero();
        }
    }
}
