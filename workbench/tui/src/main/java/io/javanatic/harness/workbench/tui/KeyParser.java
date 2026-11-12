package io.javanatic.harness.workbench.tui;

/**
 * 按键解析器(停 2-S2)：增量字节流 → {@link KeyCommand} 的确定性状态机。
 * ESC 序列解码 ESC [ A/B(↑/↓)、ESC O A/B(应用模式)、ESC [ 5/6 ~(PgUp/PgDn);
 * 单字节 1-5/q/Q/Ctrl-C 直映。序列未完成返回 null;未知尾巴 Ignore 并复位,
 * 后续按键不受污染。词表外字节一律 Ignore。
 */
public final class KeyParser {

    private static final int ESC = 27;
    private static final int CTRL_C = 3;

    /** 解码阶段：BASE 单字节直映；ESC 后等 [ / O；CSI/SS3 等尾巴；TILDE 等 ~。 */
    private enum State { BASE, ESC, CSI, SS3, TILDE }

    private State state = State.BASE;
    private int pageDirection;

    public KeyParser() {
    }

    /** 喂一个字节;序列未完成返回 null,否则返回命令(含 Ignore)。 */
    public KeyCommand feed(int code) {
        return switch (state) {
            case BASE -> base(code);
            case ESC -> esc(code);
            case CSI -> csi(code);
            case SS3 -> ss3(code);
            case TILDE -> tilde(code);
        };
    }

    private KeyCommand base(int code) {
        if (code == ESC) {
            state = State.ESC;
            return null;
        }
        if (code >= '1' && code <= '5') {
            return new KeyCommand.Switch(View.values()[code - '1']);
        }
        if (code == 'q' || code == 'Q' || code == CTRL_C) {
            return new KeyCommand.Quit();
        }
        return new KeyCommand.Ignore();
    }

    private KeyCommand esc(int code) {
        if (code == '[') {
            state = State.CSI;
            return null;
        }
        if (code == 'O') {
            state = State.SS3;
            return null;
        }
        state = State.BASE;
        return new KeyCommand.Ignore();
    }

    private KeyCommand csi(int code) {
        if (code == 'A') {
            state = State.BASE;
            return new KeyCommand.Scroll(-1);
        }
        if (code == 'B') {
            state = State.BASE;
            return new KeyCommand.Scroll(1);
        }
        if (code == '5' || code == '6') {
            pageDirection = code == '5' ? -1 : 1;
            state = State.TILDE;
            return null;
        }
        state = State.BASE;
        return new KeyCommand.Ignore();
    }

    private KeyCommand ss3(int code) {
        if (code == 'A') {
            state = State.BASE;
            return new KeyCommand.Scroll(-1);
        }
        if (code == 'B') {
            state = State.BASE;
            return new KeyCommand.Scroll(1);
        }
        state = State.BASE;
        return new KeyCommand.Ignore();
    }

    private KeyCommand tilde(int code) {
        state = State.BASE;
        return code == '~' ? new KeyCommand.Page(pageDirection) : new KeyCommand.Ignore();
    }
}
