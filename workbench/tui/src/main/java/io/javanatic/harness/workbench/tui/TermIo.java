package io.javanatic.harness.workbench.tui;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * 端子薄壳(停 2-S2)：JLine 4 的唯一触点。open() 建系统终端并进 raw 模式
 * (真 TTY 面,停 3 冒烟验收);单测面 = 委托 + 尺寸回退(0 取 80×24)+ 帧封装
 * (home + 每行清至行尾 + CRLF,纯函数)。close 委托端子关闭(restore 属性)。
 */
public final class TermIo implements KeyInput, ScreenOutput, AutoCloseable {

    /** 尺寸回退行数(端子报 0 时)。 */
    private static final int FALLBACK_ROWS = 24;

    /** 尺寸回退列数(端子报 0 时)。 */
    private static final int FALLBACK_COLS = 80;

    private static final String ANSI_HOME = "\u001B[H";
    private static final String ANSI_CLEAR_EOL = "\u001B[K";

    private final Terminal terminal;

    public TermIo(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    /** 打开系统终端并进 raw 模式(真实 TTY;仅停 3 冒烟调用)。 */
    public static TermIo open() throws IOException {
        Terminal terminal = systemTerminal();
        terminal.enterRawMode();
        return new TermIo(terminal);
    }

    @Override
    public int read() throws IOException {
        return terminal.reader().read();
    }

    @Override
    public int read(long timeoutMillis) throws IOException {
        return terminal.reader().read(timeoutMillis);
    }

    @Override
    public void draw(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        terminal.writer().print(frame(lines));
        terminal.writer().flush();
    }

    @Override
    public Size size() {
        org.jline.terminal.Size size = terminal.getSize();
        if (size.getRows() == 0 || size.getColumns() == 0) {
            return Size.of(FALLBACK_ROWS, FALLBACK_COLS);
        }
        return Size.of(size.getRows(), size.getColumns());
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }

    /** 帧封装(纯函数)：home + 每行「行内容 + 清至行尾 + CRLF」。 */
    static String frame(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        StringBuilder out = new StringBuilder(ANSI_HOME);
        for (String line : lines) {
            out.append(line).append(ANSI_CLEAR_EOL).append("\r\n");
        }
        return out.toString();
    }

    /** TerminalBuilder 仅为 open() 使用;声明在此防止误以为无第三方触点。 */
    private static Terminal systemTerminal() throws IOException {
        return TerminalBuilder.builder().system(true).build();
    }
}
