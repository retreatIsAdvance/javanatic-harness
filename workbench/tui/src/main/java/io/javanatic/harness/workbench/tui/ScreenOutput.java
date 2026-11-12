package io.javanatic.harness.workbench.tui;

import java.util.List;

/**
 * 屏幕输出 seam(停 2-S2)：draw 收「恰好一屏」的行面(帧封装属端子薄壳),
 * size 供渲染器取端子几何。真实现为 {@link TermIo} 的 JLine 委托。
 */
public interface ScreenOutput {

    /** 画一屏(行面由渲染器产出,端子侧自行封装 ANSI 帧)。 */
    void draw(List<String> lines);

    /** 当前端子几何(渲染布局的输入)。 */
    Size size();
}
