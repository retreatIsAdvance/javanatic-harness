package io.javanatic.harness.session;

import io.javanatic.harness.kernel.brand.Id;

/**
 * 日志之外的存储元数据（会话身份、格式版本、seed 血缘）。
 * 不参与事件日志与投影；持久化 seam 在加载边界校验它。
 *
 * @param parentSession fork 来源会话（null = 无）
 */
public record SessionHeader(int version, Id<Session> id, long createdAt,
                            Id<Session> parentSession, int seedLength) {

    /** 在盘格式版本（unreleased 钉在 0：无兼容承诺，不兼容日志拒绝加载）。 */
    public static final int FORMAT_VERSION = 0;

    /** 新会话的最小头。 */
    static SessionHeader fresh(Id<Session> id) {
        return new SessionHeader(FORMAT_VERSION, id, System.currentTimeMillis(), null, 0);
    }
}
