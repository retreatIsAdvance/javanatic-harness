package io.javanatic.harness.interaction.commands;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一条 slash 命令：名（{@code [a-z0-9_-]+}）、一行摘要、处理器。
 * 注册由 {@link CommandRegistry} 承担（重复名 fail loud）；处理器同步执行，
 * 结果只进屏幕与日志，不进模型历史。
 */
public record Command(String name, String summary, Handler handler) {

    /** 名语法（与 {@link CommandRegistry#parseCommand} 同一口径）。 */
    static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    public Command {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(handler, "handler");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("command name must match [a-z0-9_-]+: " + name);
        }
        if (summary.isBlank()) {
            throw new IllegalArgumentException("command summary must be non-empty: " + name);
        }
    }

    /** 命令处理器：同步、短时；异常由适配器渲染为失败提示（本层不吞）。 */
    @FunctionalInterface
    public interface Handler {

        /** @param invocation 解析后的命令行 @return 处理结果 */
        CommandResult execute(CommandInvocation invocation);
    }
}
