package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.workbench.projection.SessionViews;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用循环契约(停 2-S2)：渲染 → 帧未变不重画 → 定时读键 → 解析 → 迁移。
 * EOF/Quit 退出返回 0；READ_EXPIRED 进入下一轮（跟随态靠它轮询源）。
 * 假件：ScriptedInput 脚本字节流、RecordingOutput 录帧、StaticSource 定序快照。
 */
class WorkbenchAppTest {

    private static final long POLL_MS = 300L;
    private static final Size TERMINAL = Size.of(6, 80);
    private static final int READ_EXPIRED = KeyInput.READ_EXPIRED;

    @Test
    void drawsInitialFrameThenQuitsOnQ() throws Exception {
        ScriptedInput input = new ScriptedInput('q');
        RecordingOutput output = new RecordingOutput();

        int exit = WorkbenchApp.run(new StaticSource(TuiFixtures.views()), input, output, POLL_MS, false);

        assertThat(exit).isZero();
        assertThat(output.draws).containsExactly(frame(TuiFixtures.views(), false));
    }

    @Test
    void eofExitsAfterInitialDraw() throws Exception {
        ScriptedInput input = new ScriptedInput();
        RecordingOutput output = new RecordingOutput();

        int exit = WorkbenchApp.run(new StaticSource(TuiFixtures.views()), input, output, POLL_MS, false);

        assertThat(exit).isZero();
        assertThat(output.draws).containsExactly(frame(TuiFixtures.views(), false));
    }

    @Test
    void readExpiredLoopsWithoutRedrawing() throws Exception {
        ScriptedInput input = new ScriptedInput(READ_EXPIRED, 'q');
        RecordingOutput output = new RecordingOutput();
        StaticSource source = new StaticSource(TuiFixtures.views());

        WorkbenchApp.run(source, input, output, POLL_MS, false);

        assertThat(source.calls).isEqualTo(2);
        assertThat(output.draws).containsExactly(frame(TuiFixtures.views(), false));
    }

    @Test
    void followRedrawsOnlyWhenSnapshotChangesAndReadsWithTimeout() throws Exception {
        ScriptedInput input = new ScriptedInput(READ_EXPIRED, READ_EXPIRED, READ_EXPIRED, 'q');
        RecordingOutput output = new RecordingOutput();
        StaticSource source = new StaticSource(TuiFixtures.views(), TuiFixtures.viewsV2(), TuiFixtures.viewsV2());

        WorkbenchApp.run(source, input, output, POLL_MS, true);

        assertThat(source.calls).isEqualTo(4);
        assertThat(output.draws).containsExactly(
                frame(TuiFixtures.views(), true), frame(TuiFixtures.viewsV2(), true));
        assertThat(input.timeoutReads).isEqualTo(4);
        assertThat(input.noTimeoutReads).isZero();
    }

    @Test
    void downArrowScrollsViewAndRerenders() throws Exception {
        ScriptedInput input = new ScriptedInput(27, '[', 'B', 'q');
        RecordingOutput output = new RecordingOutput();

        WorkbenchApp.run(new StaticSource(TuiFixtures.views()), input, output, POLL_MS, false);

        assertThat(output.draws).hasSize(2);
        assertThat(output.draws.get(1).get(1)).isEqualTo("1  110  turn/start  turn=0");
    }

    @Test
    void viewSwitchRerendersNewView() throws Exception {
        ScriptedInput input = new ScriptedInput('2', 'q');
        RecordingOutput output = new RecordingOutput();

        WorkbenchApp.run(new StaticSource(TuiFixtures.views()), input, output, POLL_MS, false);

        assertThat(output.draws).hasSize(2);
        assertThat(output.draws.get(1).get(0)).contains("view=tools");
    }

    @Test
    void quitStopsReadingImmediately() throws Exception {
        ScriptedInput input = new ScriptedInput('q', '1');
        RecordingOutput output = new RecordingOutput();

        WorkbenchApp.run(new StaticSource(TuiFixtures.views()), input, output, POLL_MS, false);

        assertThat(input.remaining()).isEqualTo(1);
        assertThat(output.draws).hasSize(1);
    }

    @Test
    void runRejectsNonPositivePollMillis() {
        assertThatThrownBy(() -> WorkbenchApp.run(
                        new StaticSource(TuiFixtures.views()), new ScriptedInput(), new RecordingOutput(), 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 期望帧 = 渲染器对同一输入的直接输出（app 不掺内容，只负责「何时画」）。 */
    private static List<String> frame(SessionViews views, boolean follow) {
        return WorkbenchRenderer.render(views, new Viewport(View.TIMELINE, 0, follow), TERMINAL)
                .lines();
    }

    /** 脚本字节流：耗尽返回 EOF；统计两种 read 变体的调用次数。 */
    private static final class ScriptedInput implements KeyInput {

        private final int[] script;
        private int next;
        private int timeoutReads;
        private int noTimeoutReads;

        private ScriptedInput(int... script) {
            this.script = script;
        }

        @Override
        public int read() {
            noTimeoutReads++;
            return poll();
        }

        @Override
        public int read(long timeoutMillis) {
            timeoutReads++;
            return poll();
        }

        private int poll() {
            return next < script.length ? script[next++] : EOF;
        }

        private int remaining() {
            return script.length - next;
        }
    }

    /** 录帧输出：记录每次 draw 的行面快照。 */
    private static final class RecordingOutput implements ScreenOutput {

        private final List<List<String>> draws = new ArrayList<>();

        @Override
        public void draw(List<String> lines) {
            draws.add(List.copyOf(lines));
        }

        @Override
        public Size size() {
            return TERMINAL;
        }
    }

    /** 定序快照源：按构造顺序发放，末快照之后重复末快照；统计调用次数。 */
    private static final class StaticSource implements WorkbenchSource {

        private final List<SessionViews> snapshots;
        private int calls;

        private StaticSource(SessionViews... snapshots) {
            this.snapshots = List.of(snapshots);
        }

        @Override
        public SessionViews snapshot() throws IOException {
            return snapshots.get(Math.min(calls++, snapshots.size() - 1));
        }
    }
}
