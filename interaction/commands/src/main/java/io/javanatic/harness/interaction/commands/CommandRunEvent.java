package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.session.event.ExtensionEvent;

/**
 * 命令执行开始（log-only 扩展事件）：先于处理器落账，与 command/done 成对。
 * ignorable=true：交互面遥测，未知读取方跳过不影响重建。
 */
public record CommandRunEvent(long time, String name, String rawInput) implements ExtensionEvent {

    @Override public String type() { return "command/run"; }

    @Override public boolean ignorable() { return true; }
}
