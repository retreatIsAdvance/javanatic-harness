package io.javanatic.harness.session.event;

/**
 * 构造 seed 的末尾标记：之前的 seq 来自 seed（resume/fork/replay），本生命周期
 * 未产生它们。payload 为空——位置即语义。读取方找最后一个 end-seed；
 * seed 已以其结尾则构造时不重标（重开不增长日志）。仅 Session 构造器写入。
 */
public record SessionEndSeedEvent(long time) implements SessionEvent {
    @Override public String type() { return "session/end-seed"; }
}
