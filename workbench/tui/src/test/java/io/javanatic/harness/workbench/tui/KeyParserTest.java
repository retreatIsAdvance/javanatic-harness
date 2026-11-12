package io.javanatic.harness.workbench.tui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按键解析契约(停 2-S2)：增量字节流 → KeyCommand。ESC 序列按状态机解码；
 * 未完成返回 null；未知尾巴 Ignore 并复位（后续按键不受污染）。按键词表：
 * 1-5 视图、↑/↓/PgUp/PgDn 滚动、q/Q/Ctrl-C 退出。
 */
class KeyParserTest {

    private static final int ESC = 27;
    private static final int CTRL_C = 3;

    private final KeyParser parser = new KeyParser();

    @Test
    void ansiArrowKeysMapToScroll() {
        assertThat(feed(ESC, '[', 'A')).isEqualTo(new KeyCommand.Scroll(-1));
        assertThat(feed(ESC, '[', 'B')).isEqualTo(new KeyCommand.Scroll(1));
    }

    @Test
    void applicationModeArrowKeysMapSameAsAnsi() {
        assertThat(feed(ESC, 'O', 'A')).isEqualTo(new KeyCommand.Scroll(-1));
        assertThat(feed(ESC, 'O', 'B')).isEqualTo(new KeyCommand.Scroll(1));
    }

    @Test
    void pageKeysMapToPageDirections() {
        assertThat(feed(ESC, '[', '5', '~')).isEqualTo(new KeyCommand.Page(-1));
        assertThat(feed(ESC, '[', '6', '~')).isEqualTo(new KeyCommand.Page(1));
    }

    @Test
    void digitKeysSwitchViewsInEnumOrder() {
        for (int i = 0; i < View.values().length; i++) {
            assertThat(parser.feed('1' + i)).isEqualTo(new KeyCommand.Switch(View.values()[i]));
        }
    }

    @Test
    void quitKeysAreQShiftQAndCtrlC() {
        assertThat(parser.feed('q')).isEqualTo(new KeyCommand.Quit());
        assertThat(parser.feed('Q')).isEqualTo(new KeyCommand.Quit());
        assertThat(parser.feed(CTRL_C)).isEqualTo(new KeyCommand.Quit());
    }

    @Test
    void incompleteSequencesReturnNull() {
        assertThat(parser.feed(ESC)).isNull();
        assertThat(parser.feed('[')).isNull();
        assertThat(parser.feed('5')).isNull();
    }

    @Test
    void unknownEscTailIsIgnoredAndResetsParser() {
        assertThat(feed(ESC, 'x')).isEqualTo(new KeyCommand.Ignore());
        assertThat(parser.feed('q')).isEqualTo(new KeyCommand.Quit());
    }

    @Test
    void unknownCsiTailIsIgnoredAndResetsParser() {
        assertThat(feed(ESC, '[', 'Z')).isEqualTo(new KeyCommand.Ignore());
        assertThat(parser.feed('1')).isEqualTo(new KeyCommand.Switch(View.TURNS));
    }

    @Test
    void interruptedPageSequenceResetsParser() {
        assertThat(feed(ESC, '[', '5', 'x')).isEqualTo(new KeyCommand.Ignore());
        assertThat(feed(ESC, '[', 'A')).isEqualTo(new KeyCommand.Scroll(-1));
    }

    @Test
    void escInsideSequenceAbortsWithIgnoreAndResets() {
        assertThat(feed(ESC, '[', ESC)).isEqualTo(new KeyCommand.Ignore());
        assertThat(parser.feed('q')).isEqualTo(new KeyCommand.Quit());
    }

    @Test
    void loneEscPressedTwiceIsIgnoredAndResets() {
        assertThat(feed(ESC, ESC)).isEqualTo(new KeyCommand.Ignore());
        assertThat(parser.feed('q')).isEqualTo(new KeyCommand.Quit());
    }

    @Test
    void ordinaryBytesAreIgnored() {
        assertThat(parser.feed('x')).isEqualTo(new KeyCommand.Ignore());
        assertThat(parser.feed('\r')).isEqualTo(new KeyCommand.Ignore());
        assertThat(parser.feed(0x7f)).isEqualTo(new KeyCommand.Ignore());
    }

    /** 逐字节喂入，返回序列末字节的解析结果。 */
    private KeyCommand feed(int... codes) {
        KeyCommand last = null;
        for (int code : codes) {
            last = parser.feed(code);
        }
        return last;
    }
}
