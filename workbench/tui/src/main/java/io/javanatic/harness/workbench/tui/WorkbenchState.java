package io.javanatic.harness.workbench.tui;

import java.util.Objects;

/**
 * 应用状态(停 2-S2)：视口 + 运行旗标的不变快照。迁移由纯函数 {@link #apply}
 * 计算:Quit 停跑、Switch 换视图(滚动复位、跟随保留)、Scroll/Page 在
 * [0, content-window] 内 clamp、跟随态滚动忽略。
 */
public record WorkbenchState(Viewport viewport, boolean running) {

    public WorkbenchState {
        Objects.requireNonNull(viewport, "viewport");
    }

    /** 初始态:timeline 视图置顶;follow 决定视口钉尾。 */
    public static WorkbenchState initial(boolean follow) {
        return new WorkbenchState(new Viewport(View.TIMELINE, 0, follow), true);
    }

    /** 纯状态迁移:command + 旧状态 + 内容几何 → 新状态。 */
    public static WorkbenchState apply(KeyCommand command, WorkbenchState state,
            int contentLines, int windowRows) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
        return switch (command) {
            case KeyCommand.Quit ignored -> new WorkbenchState(state.viewport(), false);
            case KeyCommand.Switch sw -> new WorkbenchState(
                    new Viewport(sw.view(), 0, state.viewport().follow()), true);
            case KeyCommand.Scroll scroll ->
                    move(state, scroll.delta(), contentLines, windowRows);
            case KeyCommand.Page page -> move(state,
                    page.direction() * Math.max(1, windowRows), contentLines, windowRows);
            case KeyCommand.Ignore ignored -> state;
        };
    }

    /** 滚动迁移:follow 忽略;其余在 [0, content-window] 内 clamp。 */
    private static WorkbenchState move(WorkbenchState state, int delta,
            int contentLines, int windowRows) {
        if (state.viewport().follow()) {
            return state;
        }
        int maxTop = Math.max(0, contentLines - windowRows);
        int top = Math.max(0, Math.min(state.viewport().scrollTop() + delta, maxTop));
        return new WorkbenchState(new Viewport(state.viewport().view(), top,
                state.viewport().follow()), true);
    }
}
