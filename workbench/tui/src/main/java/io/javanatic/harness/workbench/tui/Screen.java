package io.javanatic.harness.workbench.tui;

import java.util.List;
import java.util.Objects;

/**
 * 一次全屏渲染结果:恰好 {@code rows} 行屏幕文本(每行 ≤ cols),外加
 * 滚动元数据(按键面钳制下一次滚动输入用)。
 *
 * @param lines        屏幕行(行 0 = 状态栏;末行 = footer;其余 = 内容窗口切片+空行填充)
 * @param contentLines 当前视图内容总行数(滚动上界)
 * @param scrollTop    本次实际渲染的内容首行下标(钳制后的值)
 */
public record Screen(List<String> lines, int contentLines, int scrollTop) {

    /** 构造时冻结列表。 */
    public Screen {
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
    }
}
