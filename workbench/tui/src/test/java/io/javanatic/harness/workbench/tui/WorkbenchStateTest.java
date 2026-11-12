package io.javanatic.harness.workbench.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态迁移契约(停 2-S2)：{@link WorkbenchState#apply} 是纯函数——命令 +
 * 旧状态 + 内容几何 → 新状态。滚动在 [0, content-window] 内 clamp；
 * follow 时滚动/翻页忽略；视图切换复位滚动但保留跟随旗标。
 */
class WorkbenchStateTest {

    private static final int CONTENT_LINES = 10;
    private static final int WINDOW_ROWS = 4;
    private static final int MAX_TOP = CONTENT_LINES - WINDOW_ROWS;

    @Test
    void initialStartsTimelineTopAndRunning() {
        WorkbenchState state = WorkbenchState.initial(false);

        assertThat(state.running()).isTrue();
        assertThat(state.viewport().view()).isEqualTo(View.TIMELINE);
        assertThat(state.viewport().scrollTop()).isZero();
        assertThat(state.viewport().follow()).isFalse();
    }

    @Test
    void initialCarriesFollowFlag() {
        assertThat(WorkbenchState.initial(true).viewport().follow()).isTrue();
    }

    @Test
    void quitStopsRunningAndKeepsViewport() {
        WorkbenchState state = WorkbenchState.initial(false);

        WorkbenchState next = applyAt(new KeyCommand.Quit(), state);

        assertThat(next.running()).isFalse();
        assertThat(next.viewport()).isEqualTo(state.viewport());
    }

    @Test
    void switchChangesViewResetsScrollKeepsFollow() {
        WorkbenchState state = new WorkbenchState(new Viewport(View.TIMELINE, 5, true), true);

        WorkbenchState next = applyAt(new KeyCommand.Switch(View.TOOLS), state);

        assertThat(next.viewport().view()).isEqualTo(View.TOOLS);
        assertThat(next.viewport().scrollTop()).isZero();
        assertThat(next.viewport().follow()).isTrue();
        assertThat(next.running()).isTrue();
    }

    @Test
    void scrollMovesDownAndUpWithinBounds() {
        WorkbenchState state = WorkbenchState.initial(false);

        WorkbenchState down = applyAt(new KeyCommand.Scroll(1), state);
        assertThat(down.viewport().scrollTop()).isEqualTo(1);

        WorkbenchState up = applyAt(new KeyCommand.Scroll(-1), down);
        assertThat(up.viewport().scrollTop()).isZero();
    }

    @Test
    void scrollClampsAtTopAndTail() {
        WorkbenchState state = WorkbenchState.initial(false);

        assertThat(applyAt(new KeyCommand.Scroll(-5), state).viewport().scrollTop())
                .isZero();
        assertThat(applyAt(new KeyCommand.Scroll(100), state).viewport().scrollTop())
                .isEqualTo(MAX_TOP);
    }

    @Test
    void scrollIsIgnoredWhileFollowing() {
        WorkbenchState state = WorkbenchState.initial(true);

        assertThat(applyAt(new KeyCommand.Scroll(1), state)).isEqualTo(state);
    }

    @Test
    void pageStepsByWindowRows() {
        WorkbenchState state = WorkbenchState.initial(false);

        assertThat(applyAt(new KeyCommand.Page(1), state).viewport().scrollTop())
                .isEqualTo(WINDOW_ROWS);
    }

    @Test
    void pageClampsAtTail() {
        WorkbenchState mid = new WorkbenchState(new Viewport(View.TIMELINE, WINDOW_ROWS, false), true);

        assertThat(applyAt(new KeyCommand.Page(1), mid).viewport().scrollTop())
                .isEqualTo(MAX_TOP);
    }

    @Test
    void pageUpReturnsByWindowRows() {
        WorkbenchState atTail = new WorkbenchState(new Viewport(View.TIMELINE, MAX_TOP, false), true);

        assertThat(applyAt(new KeyCommand.Page(-1), atTail).viewport().scrollTop())
                .isEqualTo(MAX_TOP - WINDOW_ROWS);
    }

    @Test
    void pageIgnoresWhileFollowing() {
        WorkbenchState state = WorkbenchState.initial(true);

        assertThat(applyAt(new KeyCommand.Page(1), state)).isEqualTo(state);
    }

    @Test
    void degenerateWindowPagesByOne() {
        WorkbenchState next = WorkbenchState.apply(
                new KeyCommand.Page(1), WorkbenchState.initial(false), CONTENT_LINES, 0);

        assertThat(next.viewport().scrollTop()).isEqualTo(1);
    }

    @Test
    void ignoreLeavesStateUnchanged() {
        WorkbenchState state = WorkbenchState.initial(false);

        assertThat(applyAt(new KeyCommand.Ignore(), state)).isEqualTo(state);
    }

    @Test
    void applyRejectsNullCommandOrState() {
        assertThatThrownBy(() -> WorkbenchState.apply(null, WorkbenchState.initial(false), CONTENT_LINES, WINDOW_ROWS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkbenchState.apply(new KeyCommand.Ignore(), null, CONTENT_LINES, WINDOW_ROWS))
                .isInstanceOf(NullPointerException.class);
    }

    private static WorkbenchState applyAt(KeyCommand command, WorkbenchState state) {
        return WorkbenchState.apply(command, state, CONTENT_LINES, WINDOW_ROWS);
    }
}
