package io.javanatic.harness.session.persistence;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;

import java.util.Objects;
import java.util.Optional;

/**
 * 会话列举摘要(只读视图,it22):由 header.json + 日志首尾有界读 + 写者锁探针折叠,
 * 供会话列举面渲染——不解码全量日志,列举成本与会话体积解耦。
 *
 * @param id                 会话身份
 * @param createdAt          创建时刻(header;epoch millis)
 * @param lastActivityMillis 最后活动时刻(日志 mtime;无日志取 header mtime)
 * @param eventCount         事件数(尾部有界读折叠:最后一条完整事件的 seq + 1;空日志为 0)
 * @param cwd                工作目录(首个 request/header 事实;种子/异常日志为空)
 * @param busy               是否有活跃写者(写者锁被占;探针只探不改)
 */
public record SessionSummary(Id<Session> id, long createdAt, long lastActivityMillis,
                             long eventCount, Optional<String> cwd, boolean busy) {

    /** @throws NullPointerException id/cwd 为 null;IllegalArgumentException 计数为负时 */
    public SessionSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cwd, "cwd");
        if (createdAt < 0 || lastActivityMillis < 0 || eventCount < 0) {
            throw new IllegalArgumentException("negative session summary value: createdAt="
                + createdAt + " lastActivity=" + lastActivityMillis + " eventCount=" + eventCount);
        }
    }
}
