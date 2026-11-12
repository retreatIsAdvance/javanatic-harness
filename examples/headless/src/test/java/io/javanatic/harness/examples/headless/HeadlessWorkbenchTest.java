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
import io.javanatic.harness.workbench.projection.SessionViews;
import io.javanatic.harness.workbench.tui.WorkbenchSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * it34 停 2-S3:--workbench CLI 接线(解析词表/互斥/usage)+ workbenchSource
 * 装配(检视 = load 投影一次定值快照 / 跟随 = 流式 tail 每 poll 全量重投影)。
 * 无 key 可跑(只读旁路在 API key 检查之前);真 TTY 面(全屏渲染/live tail
 * 实录)归停 3 真机冒烟,本类不触终端。
 */
class HeadlessWorkbenchTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    /** 词表面:--workbench 必配 --session;--follow 可选(缺省检视态);无 workbench 时 follow 关闭。 */
    @Test
    void workbenchFlagParsesWithSessionAndOptionalFollow() {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            new String[] {"--workbench", "--session=s1"});
        assertThat(options.workbench()).isTrue();
        assertThat(options.session()).isEqualTo("s1");
        assertThat(options.follow()).isFalse();
        HeadlessMain.RunnerOptions followed = HeadlessMain.parse(
            new String[] {"--workbench", "--session=s1", "--follow"});
        assertThat(followed.workbench()).isTrue();
        assertThat(followed.follow()).isTrue();
        // 既有检视面不受扰动:--session 单独出现仍是 stdout 五视图,不是工作台
        HeadlessMain.RunnerOptions plain = HeadlessMain.parse(new String[] {"--session=s1"});
        assertThat(plain.workbench()).isFalse();
        assertThat(plain.follow()).isFalse();
    }

    /** 配对闸:--follow 必配 --workbench;--workbench 必配 --session(显式＞隐式,不自动发现)。 */
    @Test
    void workbenchFlagVocabularyGates() {
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--follow", "--session=s1"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--follow 需与 --workbench");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--workbench"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workbench 需与 --session");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"--workbench", "--session="}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("参数缺值");
    }

    /** 互斥矩阵:工作台只读,沿 --session 先例(一次只做一件事,不猜意图)。 */
    @Test
    void workbenchFlagMutexMatrix() {
        assertThatThrownBy(() -> HeadlessMain.parse(
            new String[] {"任务", "--workbench", "--session=s1"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workbench 与任务文本互斥");
        assertThatThrownBy(() -> HeadlessMain.parse(
            new String[] {"--workbench", "--session=s1", "--resume=s2"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workbench 与 --resume 互斥");
        assertThatThrownBy(() -> HeadlessMain.parse(
            new String[] {"--workbench", "--session=s1", "--verify"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workbench 与 --verify 互斥");
        assertThatThrownBy(() -> HeadlessMain.parse(
            new String[] {"--workbench", "--session=s1", "--sessions"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workbench 与 --sessions 互斥");
        assertThatThrownBy(() -> HeadlessMain.parse(
            new String[] {"--workbench", "--session=s1", "--session-view=tools"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workbench 与 --session-view 互斥");
    }

    /** --help 点明工作台两态:检视态(五视图)与跟随态(live tail),读/写措辞不混。 */
    @Test
    void usageAdvertisesWorkbenchAndFollowModes() {
        assertThat(HeadlessMain.USAGE).contains("--workbench", "--follow",
            "只读工作台", "跟随态", "live tail");
    }

    /** 检视态 source:落盘会话 load → 投影一次,快照定值(重复 snapshot 同输入同输出)。 */
    @Test
    void workbenchSourceStaticProjectsOnDiskSession() throws Exception {
        try (Runtime rt = fixture("wb-1", "/w/wb")) {
            WorkbenchSource source = HeadlessMain.workbenchSource(rt.root(), "wb-1", false);
            SessionViews views = source.snapshot();
            assertThat(views.timeline()).hasSize(3);
            assertThat(views.timeline().get(0).type()).isEqualTo("turn/start");
            assertThat(views.turns()).hasSize(1);
            assertThat(source.snapshot().timeline()).hasSize(3);
        }
    }

    /** 未知 id 两态同 fail loud(load 与 tail open 同款 NoSuchElementException)。 */
    @Test
    void workbenchSourceUnknownSessionFailsLoudInBothModes() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            assertThatThrownBy(() -> HeadlessMain.workbenchSource(rt.root(), "nope", false))
                .isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> HeadlessMain.workbenchSource(rt.root(), "nope", true))
                .isInstanceOf(NoSuchElementException.class);
        }
    }

    /**
     * 跟随态 source:tail 无锁可读进行中会话(写者持锁时 load 即拒——两态对照),
     * 写者继续追加后下一次 snapshot 看到新事件(每 poll 全量重投影)。
     */
    @Test
    void workbenchSourceTailReadsLiveSessionWhileWriterHoldsLock() throws Exception {
        try (Runtime writer = new Runtime()) {
            new PluginLoader().loadAll(writer, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            Session live = writer.root().require(SessionStore.KEY)
                .create(writer.root(), Session.newId("wb-live"), CreateOptions.empty());
            live.append(new TurnStart(1, 1));
            try (Runtime observer = new Runtime()) {
                new PluginLoader().loadAll(observer, List.of(
                    new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
                WorkbenchSource tail = HeadlessMain.workbenchSource(observer.root(), "wb-live", true);
                assertThat(tail.snapshot().timeline()).hasSize(1);
                // 同会话检视态撞写者锁(占用即拒):跟随无锁、检视探锁,两态界限钉死
                assertThatThrownBy(() -> HeadlessMain.workbenchSource(observer.root(), "wb-live", false))
                    .isInstanceOf(WriterLockException.class);
                live.append(new TurnEnd(2, 1, new TurnEndReason.Completed()));
                assertThat(tail.snapshot().timeline()).hasSize(2);
            }
        }
    }

    /** 检视态 dispatch:未知 id exit 3,不触终端、不进 API key 检查(无 key 可跑)。 */
    @Test
    void workbenchUnknownSessionExitsThreeWithoutKeyOrTerminal() throws Exception {
        Result result = run("--workbench", "--session=nope");
        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
    }

    /** 检视态 dispatch:写者占用 exit 3(load 探锁即拒,不等待、不写、不触终端)。 */
    @Test
    void workbenchBusySessionInspectionExitsThreeWithoutWriting() throws Exception {
        try (Runtime holder = new Runtime()) {
            new PluginLoader().loadAll(holder, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            Session busy = holder.root().require(SessionStore.KEY)
                .create(holder.root(), Session.newId("wb-busy"), CreateOptions.empty());
            busy.append(new TurnStart(1, 1));

            Result result = run("--workbench", "--session=wb-busy");
            assertThat(result.exit()).isEqualTo(3);
            assertThat(result.stdout()).isEmpty();
        }
    }

    /** 造一个 3 事件落盘会话(保持 Runtime 开启):TurnStart + RequestHeader(cwd) + TurnEnd(Completed)。 */
    private Runtime fixture(String id, String cwd) throws Exception {
        Runtime rt = new Runtime();
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
        Session session = rt.root().require(SessionStore.KEY)
            .create(rt.root(), Session.newId(id), CreateOptions.empty());
        session.append(new TurnStart(1, 1));
        session.append(new RequestHeader(2, cwd, "2026-09-22"));
        session.append(new TurnEnd(3, 1, new TurnEndReason.Completed()));
        return rt;
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
    }
}
