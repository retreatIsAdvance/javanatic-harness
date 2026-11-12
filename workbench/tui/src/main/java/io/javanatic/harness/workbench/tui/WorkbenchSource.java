package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.workbench.projection.SessionViews;

import java.io.IOException;

/**
 * 工作台数据源 seam(停 2-S2)：每次调用给一次完整投影快照。检视态 =
 * 落盘会话一次性 load 后的定值快照;跟随态 = 流式 tail 的每 poll 全量
 * 重投影(S3 CLI 接线时装配)。
 */
@FunctionalInterface
public interface WorkbenchSource {

    /** 当前快照(每轮循环调用一次;跟随态即轮询节拍)。 */
    SessionViews snapshot() throws IOException;
}
