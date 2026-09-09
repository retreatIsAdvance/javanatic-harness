package io.javanatic.harness.session.event;

/**
 * 压缩事务的日志锁开头(dsh 形状,06/03:log-only 不进 surface)。
 * turn 标识持有者;崩溃中断 = 孤儿锁(start 无配对 end)可检测。
 *
 * @param turn 发起压缩的轮号
 */
public record CompactionStart(long time, int turn) implements SessionEvent {

    @Override public String type() { return "compaction/start"; }

    @Override public boolean ignorable() { return true; }
}
