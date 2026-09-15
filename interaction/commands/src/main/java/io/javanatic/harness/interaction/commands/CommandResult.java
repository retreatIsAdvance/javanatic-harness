package io.javanatic.harness.interaction.commands;

import java.util.Objects;

/**
 * 命令处理结果。渲染进屏幕与日志（command/done detail），不进模型历史。
 */
public sealed interface CommandResult {

    /** 渲染给用户的文本（多行原样；尾部换行由适配器决定）。 */
    record Text(String content) implements CommandResult {

        public Text {
            Objects.requireNonNull(content, "content");
        }
    }

    /** 结束交互循环（适配器语义；不影响已落账事实）。 */
    record Quit() implements CommandResult {
    }
}
