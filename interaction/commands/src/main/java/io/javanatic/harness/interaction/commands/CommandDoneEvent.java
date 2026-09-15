package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.session.event.ExtensionEvent;

/**
 * 命令执行落定（log-only 扩展事件）：settle 落账，处理器异常路径 ok=false 且
 * detail 携摘要。ignorable=true 同 run。
 */
public record CommandDoneEvent(long time, String name, boolean ok, String detail) implements ExtensionEvent {

    @Override public String type() { return "command/done"; }

    @Override public boolean ignorable() { return true; }
}
