package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.workbench.projection.SessionViews;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 应用循环(停 2-S2)：渲染 → 帧未变不重画 → 定时读键 → 解析 → 迁移。
 * 三个 seam(source/input/output)全部注入,headless 可测;EOF 与 Quit
 * 均以 0 退出,IOException 原样上抛。pollMillis 兼作输入读超时与源轮询
 * 节拍(跟随态 = 每次读超时后重投影 live tail)。
 */
public final class WorkbenchApp {

    private WorkbenchApp() {
    }

    /** 跑循环至退出;正常退出返回 0。 */
    public static int run(WorkbenchSource source, KeyInput input, ScreenOutput output,
            long pollMillis, boolean follow) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (pollMillis <= 0) {
            throw new IllegalArgumentException("pollMillis 必须为正: " + pollMillis);
        }
        KeyParser parser = new KeyParser();
        WorkbenchState state = WorkbenchState.initial(follow);
        List<String> drawn = null;
        while (state.running()) {
            SessionViews views = source.snapshot();
            Screen screen = WorkbenchRenderer.render(views, state.viewport(), output.size());
            if (!screen.lines().equals(drawn)) {
                output.draw(screen.lines());
                drawn = screen.lines();
            }
            int code = input.read(pollMillis);
            if (code == KeyInput.EOF) {
                return 0;
            }
            if (code == KeyInput.READ_EXPIRED) {
                continue;
            }
            KeyCommand command = parser.feed(code);
            if (command != null) {
                state = WorkbenchState.apply(command, state, screen.contentLines(),
                        WorkbenchRenderer.windowRows(output.size()));
            }
        }
        return 0;
    }
}
