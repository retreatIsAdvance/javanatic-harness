package io.javanatic.harness.workbench.tui;

/**
 * 工作台视图词表(与 CLI {@code --session-view=} 五视图一一对应:
 * turns|tools|usage|timeline|messages)。显示名即 CLI 词表名;
 * 按键映射与解析属按键面(停 2-S2)。
 */
public enum View {

    TURNS("turns"),
    TOOLS("tools"),
    USAGE("usage"),
    TIMELINE("timeline"),
    MESSAGES("messages");

    private final String displayName;

    View(String displayName) {
        this.displayName = displayName;
    }

    /** 视图显示名(与 CLI 词表同串,状态栏与 footer 用)。 */
    public String displayName() {
        return displayName;
    }
}
