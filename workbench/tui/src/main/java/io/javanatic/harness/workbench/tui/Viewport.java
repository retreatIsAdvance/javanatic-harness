package io.javanatic.harness.workbench.tui;

import java.util.Objects;

/**
 * 工作台可变 UI 状态(按键面在两次渲染之间改动的全部状态):
 * 当前视图 + 滚动位置 + 跟随开关。渲染器据此确定内容窗口切片;
 * {@code follow=true} 时滚动钉尾(忽略 scrollTop)。
 *
 * @param scrollTop 内容首行下标(≥0);超出内容时渲染器钳制到合法窗口
 */
public record Viewport(View view, int scrollTop, boolean follow) {

    /** @throws NullPointerException view 为 null 时;@throws IllegalArgumentException scrollTop 为负时 */
    public Viewport {
        Objects.requireNonNull(view, "view");
        if (scrollTop < 0) {
            throw new IllegalArgumentException("scrollTop 不得为负: " + scrollTop);
        }
    }

    /** 检视态初始视口(视图 + 顶部 + 非跟随)。 */
    public static Viewport top(View view) {
        return new Viewport(view, 0, false);
    }
}
