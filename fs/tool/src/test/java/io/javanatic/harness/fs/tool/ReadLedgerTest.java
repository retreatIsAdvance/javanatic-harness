package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读后修改保护折叠器（it21）：事实来源、callId 配对与边界（截断/失败/坏实参/路径写法）。
 * 纯 fold 直测——不建 Runtime，只喂事件。
 */
class ReadLedgerTest {

    private final Session session = Session.create(Session.newId("ledger"), null, null);

    private void call(String id, String tool, String args) {
        session.append(new ToolCallEvent(0, 0, 0, CallId.of(id), tool, args));
    }

    private void result(String id, String content, boolean isError) {
        session.append(new ToolResultEvent(0, 0, 0, new ToolResultBlock(CallId.of(id), content, isError),
            false, new SurfaceOp.Append(), null));
    }

    private ReadLedger fold() {
        return ReadLedger.fold(session.events());
    }

    private static Path path(String raw) {
        return Path.of(raw);
    }

    @Test
    void untruncatedReadResultIsTheFact() {
        call("c1", "fs_read", "{\"path\":\"/ws/f.txt\"}");
        result("c1", "hello", false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isEqualTo("hello");
    }

    @Test
    void writeFactComesFromArgumentsNotResult() {
        call("c1", "fs_write", "{\"path\":\"/ws/f.txt\",\"content\":\"v1\"}");
        result("c1", "written", false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isEqualTo("v1");
    }

    @Test
    void editFactIsTheEditedFullText() {
        call("c1", "fs_edit", "{\"path\":\"/ws/f.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}");
        result("c1", "b rest", false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isEqualTo("b rest");
    }

    @Test
    void truncatedReadResultIsNotAFact() {
        call("c1", "fs_read", "{\"path\":\"/ws/f.txt\"}");
        result("c1", "hell" + FsService.READ_TRUNCATED_MARKER, false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isNull();
    }

    @Test
    void errorResultIsNotAFact() {
        call("c1", "fs_read", "{\"path\":\"/ws/f.txt\"}");
        result("c1", "boom", true);
        call("c2", "fs_edit", "{\"path\":\"/ws/f.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}");
        result("c2", "not unique: 3 occurrences", true);
        assertThat(fold().factFor(path("/ws/f.txt"))).isNull();
    }

    @Test
    void latestFactWins() {
        call("c1", "fs_write", "{\"path\":\"/ws/f.txt\",\"content\":\"v1\"}");
        result("c1", "written", false);
        call("c2", "fs_read", "{\"path\":\"/ws/f.txt\"}");
        result("c2", "v2", false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isEqualTo("v2");
    }

    @Test
    void interleavedCallsPairByCallIdNotAdjacency() {
        call("a", "fs_read", "{\"path\":\"/ws/a.txt\"}");
        call("b", "fs_read", "{\"path\":\"/ws/b.txt\"}");
        result("b", "B", false);
        result("a", "A", false);
        assertThat(fold().factFor(path("/ws/a.txt"))).isEqualTo("A");
        assertThat(fold().factFor(path("/ws/b.txt"))).isEqualTo("B");
    }

    @Test
    void pathSpellingNormalizesToSameFact() {
        call("c1", "fs_write", "{\"path\":\"/ws/./f.txt\",\"content\":\"v\"}");
        result("c1", "written", false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isEqualTo("v");
        assertThat(fold().factFor(path("/ws/sub/../f.txt"))).isEqualTo("v");
    }

    @Test
    void unrelatedToolsAndUnpairedCallsAreIgnored() {
        call("s1", "shell_exec", "{\"command\":\"cat /ws/f.txt\"}");
        result("s1", "leak", false);
        call("c9", "fs_read", "{\"path\":\"/ws/unpaired.txt\"}");
        assertThat(fold().factFor(path("/ws/f.txt"))).isNull();
        assertThat(fold().factFor(path("/ws/unpaired.txt"))).isNull();
    }

    @Test
    void malformedHistoricalArgumentsSkipSafely() {
        // 日志是信任边界（08 §6）：坏 JSON / 缺字段 / 非法路径都只丢该条事实，不炸
        call("bad-json", "fs_read", "not json");
        result("bad-json", "whatever", false);
        call("missing-field", "fs_read", "{\"file\":\"/ws/f.txt\"}");
        result("missing-field", "whatever", false);
        call("bad-path", "fs_write", "{\"path\":\"\\u0000nul\",\"content\":\"v\"}");
        result("bad-path", "written", false);
        call("good", "fs_read", "{\"path\":\"/ws/f.txt\"}");
        result("good", "ok", false);
        assertThat(fold().factFor(path("/ws/f.txt"))).isEqualTo("ok");
    }

    @Test
    void foldStaysLinearOverLargeLogs() {
        // 长会话成本取证（it21 停点材料）：全日志扫描是否可接受——量级打印，不做时间断言
        for (int i = 0; i < 10_000; i++) {
            String id = "s" + i;
            call(id, "shell_exec", "{\"command\":\"echo " + i + "\"}");
            result(id, "out " + i, false);
        }
        call("r1", "fs_read", "{\"path\":\"/ws/f.txt\"}");
        result("r1", "v", false);

        long start = System.nanoTime();
        ReadLedger ledger = ReadLedger.fold(session.events());
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[it21] ReadLedger.fold over " + session.events().size()
            + " events: " + millis + " ms");

        assertThat(ledger.factFor(path("/ws/f.txt"))).isEqualTo("v");
    }
}
