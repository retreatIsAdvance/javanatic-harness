package io.javanatic.harness.examples.headless;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.RequestHeader;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.persistence.WriterLockException;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * it33 停 2:--session 只读检视的 CLI 接线程(真会话落盘 → load → 投影 → stdout)。
 * 无 key 可跑(只读旁路在 API key 检查之前,与 --sessions 同契约);未知 id 与
 * 写者占用同为 exit 3,消息文本经提取的 static helper 钉死(LOG 走真实
 * System.err,e2e 只断言 exit 码与 stdout)。
 */
class HeadlessSessionViewTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void sessionViewReadsOnDiskSessionKeyless() throws Exception {
        fixture("sv-1", "/w/sv");
        Result result = run("--session=sv-1"); // 无 key:只读旁路在 key 检查之前
        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.lines()).containsExactly(
            "session=sv-1  view=turns  events=3",
            "turn=1  completed  duration=2ms  steps=0  tools=0  tokens=none");

        Result timeline = run("--session=sv-1", "--session-view=timeline");
        assertThat(timeline.exit()).isZero();
        assertThat(timeline.lines()).containsExactly(
            "session=sv-1  view=timeline  events=3",
            "0  1  turn/start  turn=1",
            "1  2  request/header  cwd=/w/sv date=2026-09-22",
            "2  3  turn/end  turn=1 reason=completed");
    }

    @Test
    void unknownSessionIdExitsThreeWithRecoveryGuidance() throws Exception {
        Result result = run("--session=nope");
        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(HeadlessMain.unknownSessionText("nope", "--session=<id>"))
            .contains("nope").contains("--sessions").contains("--session=<id>");
    }

    @Test
    void busySessionInspectionExitsThreeWithoutWriting() throws Exception {
        // 同 JVM 第二写者:holder 保持写者锁到测试结束,检视 load 探测即拒(不等待、不写)
        try (Runtime holder = new Runtime()) {
            new PluginLoader().loadAll(holder, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            Session busy = holder.root().require(SessionStore.KEY)
                .create(holder.root(), Session.newId("sv-busy"), CreateOptions.empty());
            busy.append(new TurnStart(1, 1));

            Result result = run("--session=sv-busy");
            assertThat(result.exit()).isEqualTo(3);
            assertThat(result.stdout()).isEmpty();
        }
        WriterLockException lock = new WriterLockException(
            "session writer lock held by another writer in this JVM: /tmp/sv-busy/.writer.lock");
        assertThat(HeadlessMain.busySessionText("sv-busy", lock))
            .contains("写者锁冲突")
            .contains("session writer lock held")
            .contains("--session=sv-busy");
    }

    /** 造一个 3 事件落盘会话:TurnStart + RequestHeader(cwd) + TurnEnd(Completed)。 */
    private void fixture(String id, String cwd) throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            Session session = rt.root().require(SessionStore.KEY)
                .create(rt.root(), Session.newId(id), CreateOptions.empty());
            session.append(new TurnStart(1, 1));
            session.append(new RequestHeader(2, cwd, "2026-09-22"));
            session.append(new TurnEnd(3, 1, new TurnEndReason.Completed()));
        }
    }

    private Result run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(HeadlessMain.parse(args), workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String stdout, String stderr) {

        List<String> lines() {
            return stdout.lines().toList();
        }
    }
}
