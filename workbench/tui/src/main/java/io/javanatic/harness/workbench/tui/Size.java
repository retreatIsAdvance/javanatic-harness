package io.javanatic.harness.workbench.tui;

/**
 * 端子窗口尺寸(行 × 列)。渲染器输出恰好 {@code rows} 行、每行至多
 * {@code cols} 列;零尺寸是合法输入(输出为空)。
 */
public record Size(int rows, int cols) {

    /** @throws IllegalArgumentException 任一维为负时 */
    public Size {
        if (rows < 0 || cols < 0) {
            throw new IllegalArgumentException("size 不得为负: " + rows + "x" + cols);
        }
    }

    /** 终端常规尺寸的便捷构造(测试夹具用)。 */
    public static Size of(int rows, int cols) {
        return new Size(rows, cols);
    }
}
