package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.workbench.projection.SessionViews;

import java.io.IOException;

/**
 * 工作台数据源 seam(停 2-S2)：每次调用给一次完整投影快照。检视态 =
 * 落盘会话一次性 load 后的定值快照;跟随态 = 流式 tail 的每 poll 全量
 * 重投影(S3 CLI 接线时装配)。
 *
 * <p>{@link #close()} 释放底面资源(停 3 增)：跟随态 = 关闭 tail 游标
 * (装配点必须透传 {@code SessionTail.Follower.close()}——今天是空操作,
 * 为 W3 长驻消费面预留语义);检视态定值快照无资源,默认空实现。
 */
@FunctionalInterface
public interface WorkbenchSource extends AutoCloseable {

    /** 当前快照(每轮循环调用一次;跟随态即轮询节拍)。 */
    SessionViews snapshot() throws IOException;

    /** 释放底面资源;应用循环退出时由装配方调用(跟随态关 tail 游标)。 */
    default void close() throws IOException {
    }
}
