package io.javanatic.harness.session.event;

/**
 * 压缩事务的日志锁释放。error 非 null 表示失败尝试(surface 未变更)。
 *
 * @param turn  与配对 start 相同的持有者标识
 * @param error 失败说明(null = 成功)
 */
public record CompactionEnd(long time, int turn, String error) implements SessionEvent {

    @Override public String type() { return "compaction/end"; }

    @Override public boolean ignorable() { return true; }
}
