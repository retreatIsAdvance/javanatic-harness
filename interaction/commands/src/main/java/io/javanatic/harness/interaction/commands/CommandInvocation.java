package io.javanatic.harness.interaction.commands;

import java.util.Objects;

/**
 * 解析后的命令行：命令名 + 其余原文参数。
 *
 * @param name 命令名（已过名语法校验）
 * @param rawInput 名字之后的原始余文（保留空白，含前导空格；无参数为空串）
 */
public record CommandInvocation(String name, String rawInput) {

    public CommandInvocation {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rawInput, "rawInput");
    }
}
