package io.javanatic.harness.workbench.tui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Attributes;
import org.jline.terminal.impl.AbstractTerminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.utils.ColorPalette;
import org.jline.utils.NonBlockingReader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端子薄壳契约(停 2-S2)：TermIo 只做委托与两件真逻辑——尺寸回退(0 取
 * 80×24)与帧封装(home + 每行清至行尾 + CRLF)。seam 常量与 JLine 实况
 * 钉死同一值。真实 TTY 面(TermIo.open/enterRawMode)不在单测范围,
 * 由停 3 真机冒烟验收。
 */
class TermIoTest {

    @Test
    void seamConstantsMatchJlineReality() {
        assertThat(KeyInput.EOF).isEqualTo(NonBlockingReader.EOF);
        assertThat(KeyInput.READ_EXPIRED).isEqualTo(NonBlockingReader.READ_EXPIRED);
    }

    @Test
    void frameEmitsHomePerLineClearEolAndCrlf() {
        assertThat(TermIo.frame(List.of("a", "b")))
                .isEqualTo("\u001B[Ha\u001B[K\r\nb\u001B[K\r\n");
        assertThat(TermIo.frame(List.of()))
                .isEqualTo("\u001B[H");
    }

    @Test
    void drawWritesFramedLinesToTerminalWriter() throws Exception {
        FakeTerminal terminal = new FakeTerminal(24, 80);
        TermIo io = new TermIo(terminal);

        io.draw(List.of("x"));

        assertThat(terminal.written()).isEqualTo("\u001B[Hx\u001B[K\r\n");
    }

    @Test
    void sizeFallsBackWhenTerminalReportsZero() throws Exception {
        assertThat(new TermIo(new FakeTerminal(0, 0)).size()).isEqualTo(Size.of(24, 80));
        assertThat(new TermIo(new FakeTerminal(40, 100)).size()).isEqualTo(Size.of(40, 100));
    }

    @Test
    void readDelegatesAndPropagatesEofAndExpiry() throws Exception {
        FakeTerminal terminal = new FakeTerminal(24, 80);
        terminal.readerScript('a', KeyInput.EOF, KeyInput.READ_EXPIRED);
        TermIo io = new TermIo(terminal);

        assertThat(io.read()).isEqualTo('a');
        assertThat(io.read(300L)).isEqualTo(KeyInput.EOF);
        assertThat(io.read(300L)).isEqualTo(KeyInput.READ_EXPIRED);
    }

    @Test
    void closeDelegatesToTerminal() throws Exception {
        FakeTerminal terminal = new FakeTerminal(24, 80);

        new TermIo(terminal).close();

        assertThat(terminal.closed).isTrue();
    }

    @Test
    void constructorRejectsNullTerminal() {
        assertThatThrownBy(() -> new TermIo(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void drawRejectsNullLines() throws Exception {
        assertThatThrownBy(() -> new TermIo(new FakeTerminal(24, 80)).draw(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 假端子：继承 AbstractTerminal 兜底大部分面，只覆写薄壳触到的点。 */
    private static final class FakeTerminal extends AbstractTerminal {

        private final int rows;
        private final int cols;
        private final StringWriter buffer = new StringWriter();
        private final PrintWriter writer = new PrintWriter(buffer, true);
        private ScriptedReader reader = new ScriptedReader();
        private boolean closed;

        private FakeTerminal(int rows, int cols) throws IOException {
            super("fake", "fake");
            this.rows = rows;
            this.cols = cols;
        }

        private void readerScript(int... script) {
            this.reader = new ScriptedReader(script);
        }

        private String written() {
            return buffer.toString();
        }

        @Override
        public NonBlockingReader reader() {
            return reader;
        }

        @Override
        public PrintWriter writer() {
            return writer;
        }

        @Override
        public org.jline.terminal.Size getSize() {
            return new org.jline.terminal.Size(cols, rows);
        }

        @Override
        public void setSize(org.jline.terminal.Sized size) {
            // 假端子不跟踪尺寸变化
        }

        @Override
        public InputStream input() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream output() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public boolean canPauseResume() {
            return false;
        }

        @Override
        public void pause() {
            // 无暂停能力
        }

        @Override
        public void pause(boolean wait) {
            // 无暂停能力
        }

        @Override
        public void resume() {
            // 无暂停能力
        }

        @Override
        public boolean paused() {
            return false;
        }

        @Override
        public Attributes getAttributes() {
            return new Attributes();
        }

        @Override
        public void setAttributes(Attributes attributes) {
            // 假端子不跟踪属性
        }

        @Override
        public ColorPalette getPalette() {
            return ColorPalette.DEFAULT;
        }

        @Override
        public TerminalProvider getProvider() {
            return null;
        }

        @Override
        public SystemStream getSystemStream() {
            return null;
        }

        @Override
        protected void doClose() {
            closed = true;
        }
    }

    /** 脚本读器：按脚本顺序发字节，耗尽后 EOF。 */
    private static final class ScriptedReader extends NonBlockingReader {

        private final int[] script;
        private int next;

        private ScriptedReader(int... script) {
            this.script = script;
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            return next < script.length ? script[next++] : EOF;
        }

        @Override
        public int readBuffered(char[] b, int off, int len, long timeout) {
            int c = read(timeout, false);
            if (c < 0 || len == 0) {
                return c;
            }
            b[off] = (char) c;
            return 1;
        }
    }
}
