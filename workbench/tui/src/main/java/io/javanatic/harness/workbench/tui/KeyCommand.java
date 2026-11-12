package io.javanatic.harness.workbench.tui;

/**
 * 按键命令封闭联合(停 2-S2)：按键解析器的唯一输出词表。数据载体无行为；
 * 迁移语义在 {@link WorkbenchState#apply}。
 */
public sealed interface KeyCommand {

    /** 切到指定视图（1-5 键）。 */
    record Switch(View view) implements KeyCommand {
    }

    /** 行级滚动：-1 上滚(↑)，+1 下滚(↓)。 */
    record Scroll(int delta) implements KeyCommand {
    }

    /** 页级滚动：-1 PgUp，+1 PgDn（步长由窗口行数决定，见状态迁移）。 */
    record Page(int direction) implements KeyCommand {
    }

    /** 退出工作台（q / Q / Ctrl-C）。 */
    record Quit() implements KeyCommand {
    }

    /** 词表外按键：解析器已复位，状态无变化。 */
    record Ignore() implements KeyCommand {
    }
}
