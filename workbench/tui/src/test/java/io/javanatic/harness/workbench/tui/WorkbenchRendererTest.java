package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.workbench.projection.SessionViews;
import io.javanatic.harness.workbench.projection.UsageSummary;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渲染器行为契约(停 2-S1)：全屏渲染 = 状态栏 + 内容窗口切片 + footer，
 * 恰好 rows 行、每行 ≤ cols 列；纯函数同输入必同输出。夹具见 {@link TuiFixtures}。
 */
class WorkbenchRendererTest {

    @Test
    void rendersTurnsViewWithStatusContentAndFooter() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(8, 80));

        assertThat(screen.lines()).containsExactly(
                TuiFixtures.STATUS_TURNS,
                "#0  completed  150ms  steps=3  tools=2  in=1200 out=800 reason=100",
                "#1  open  steps=1  tools=0  tokens=none",
                "", "", "", "",
                "1-5 视图 ↑/↓ PgUp/PgDn 滚动 q 退出");
        assertThat(screen.contentLines()).isEqualTo(2);
        assertThat(screen.scrollTop()).isZero();
    }

    @Test
    void rendersEmptySessionWithZeroEventsAndBlankContent() {
        SessionViews empty = new SessionViews(
                new SessionHeader(0, TuiFixtures.SID, 1L, null, 0, null),
                List.of(), List.of(), new UsageSummary(null, List.of()), List.of(), List.of());

        Screen screen = WorkbenchRenderer.render(empty, Viewport.top(View.TIMELINE), Size.of(6, 80));

        assertThat(screen.lines().get(0)).isEqualTo("session=tui-fixture  view=timeline  events=0");
        assertThat(screen.lines().get(1)).isEmpty();
        assertThat(screen.contentLines()).isZero();
    }

    @Test
    void timelineRendersUnknownEventByTypeOnly() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TIMELINE), Size.of(12, 80));

        assertThat(screen.lines().get(1)).isEqualTo("0  100  request/header  cwd=/ws date=2026-11-12T00:00:00Z");
        assertThat(screen.lines().get(7)).isEqualTo("6  160  turn/end  turn=0 reason=completed");
        assertThat(screen.lines().get(8)).isEqualTo("7  170  x/custom");
    }

    @Test
    void scrollClampsToTailWhenBeyondContent() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(),
                new Viewport(View.TIMELINE, 100, false), Size.of(6, 80));

        assertThat(screen.scrollTop()).isEqualTo(4);
        assertThat(screen.lines().get(1)).isEqualTo("4  140  tool/result  turn=0 step=1 ok chars=42 concludes=false");
        assertThat(screen.lines().get(4)).isEqualTo("7  170  x/custom");
        assertThat(screen.lines().get(5)).isEqualTo("1-5 视图 ↑/↓ PgUp/PgDn 滚动 q 退出  行 5-8/8");
    }

    @Test
    void scrollSlicesMiddleChunk() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(),
                new Viewport(View.TIMELINE, 2, false), Size.of(6, 96));

        assertThat(screen.scrollTop()).isEqualTo(2);
        assertThat(screen.lines().get(1)).isEqualTo("2  120  user/message  source=User chars=12");
        assertThat(screen.lines().get(4)).isEqualTo("5  150  assistant/message  turn=0 step=1 chars=30 usage=in=1200 out=800 reason=100");
        assertThat(screen.lines().get(5)).isEqualTo("1-5 视图 ↑/↓ PgUp/PgDn 滚动 q 退出  行 3-6/8");
    }

    @Test
    void truncatesEveryLineToColumns() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(8, 24));

        assertThat(screen.lines()).hasSize(8);
        assertThat(screen.lines()).allMatch(line -> line.length() <= 24);
        assertThat(screen.lines().get(0)).isEqualTo("session=tui-fixture  vie");
        assertThat(screen.lines().get(1)).isEqualTo("#0  completed  150ms  st");
    }

    @Test
    void followPinsToTailAndMarksStatusAndFooter() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(),
                new Viewport(View.TIMELINE, 0, true), Size.of(6, 80));

        assertThat(screen.scrollTop()).isEqualTo(4);
        assertThat(screen.lines().get(0)).isEqualTo("session=tui-fixture  view=timeline  events=8  [follow]");
        assertThat(screen.lines().get(1)).isEqualTo("4  140  tool/result  turn=0 step=1 ok chars=42 concludes=false");
        assertThat(screen.lines().get(5)).isEqualTo("[跟随中] 1-5 视图 q 退出");
    }

    @Test
    void toolsViewShowsPairsAndOrphans() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TOOLS), Size.of(6, 80));

        assertThat(screen.lines().get(0)).isEqualTo("session=tui-fixture  view=tools  events=8");
        assertThat(screen.lines().get(1)).isEqualTo(
                "turn=0 step=1 call seq=4 fs_read args={\"path\":\"/tmp\"} -> seq=6 ok 15ms");
        assertThat(screen.lines().get(2)).isEqualTo("turn=1 step=0 call seq=9 fs_write args={} -> 无结果");
        assertThat(screen.lines().get(3)).isEqualTo("turn=1 step=0 orphan seq=11 error (无对应调用)");
    }

    @Test
    void usageViewShowsTotalAndPerTurnIncludingUnreported() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.USAGE), Size.of(6, 80));

        assertThat(screen.lines().get(0)).isEqualTo("session=tui-fixture  view=usage  events=8");
        assertThat(screen.lines().get(1)).isEqualTo("total  in=1300 out=900 reason=100");
        assertThat(screen.lines().get(2)).isEqualTo("turn=0  in=1200 out=800 reason=100");
        assertThat(screen.lines().get(3)).isEqualTo("turn=1  (未报告)");
    }

    @Test
    void messagesViewShowsIndexedSourceAndText() {
        Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.MESSAGES), Size.of(5, 80));

        assertThat(screen.lines().get(0)).isEqualTo("session=tui-fixture  view=messages  events=8");
        assertThat(screen.lines().get(1)).isEqualTo("[1]  User  hi");
        assertThat(screen.lines().get(2)).isEqualTo("[2]  Model  hello");
    }

    @Test
    void eachViewRendersItsNameInStatus() {
        for (View view : View.values()) {
            Screen screen = WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(view), Size.of(6, 80));
            assertThat(screen.lines().get(0)).contains("view=" + view.displayName());
        }
    }

    @Test
    void degenerateSizesRenderGracefully() {
        assertThat(WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(0, 80)).lines())
                .isEmpty();
        assertThat(WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(1, 80)).lines())
                .containsExactly(TuiFixtures.STATUS_TURNS);
        assertThat(WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(2, 80)).lines())
                .containsExactly(TuiFixtures.STATUS_TURNS, "1-5 视图 ↑/↓ PgUp/PgDn 滚动 q 退出");
        assertThat(WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(3, 80)).lines())
                .hasSize(3);
        assertThat(WorkbenchRenderer.render(TuiFixtures.views(), Viewport.top(View.TURNS), Size.of(3, 0)).lines())
                .containsExactly("", "", "");
    }

    @Test
    void viewportAndSizeRejectInvalidState() {
        assertThatThrownBy(() -> new Viewport(null, 0, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Viewport(View.TURNS, -1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Size.of(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Size.of(10, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Screen(null, 0, 0)).isInstanceOf(NullPointerException.class);
    }
}
