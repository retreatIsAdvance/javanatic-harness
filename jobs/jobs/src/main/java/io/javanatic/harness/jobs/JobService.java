package io.javanatic.harness.jobs;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.scope.ServiceKey;
import java.util.List;

/**
 * 后台任务 seam（0.4 第一步）。任务由所属会话持有：list 只见本会话任务，
 * 跨会话不可见。启动与终态各发一次 job/snapshot 全量快照事件（按 id fold 末值胜），
 * 输出块不事件化——经 read 游标读取，保证 R1 账面不被输出洪灌。
 * 重启后前生命期 Running 任务由 Provider 首次观测时标记 Lost 并入账，不冒充可续接。
 * 实现须可在虚拟线程调用；不认识的 id 抛 JobNotFoundException，不静默返回空。
 */
public interface JobService extends AutoCloseable {

    /** kernel ServiceKey 名（短稳定名，kebab-case 组约定为单词）。 */
    ServiceKey<JobService> KEY = new ServiceKey<>("jobs");

    /**
     * 启动后台任务；入参经 JobRequest 装配期校验，坏请求在此失败。
     * @param request 命令 / 工作目录 / 超时 / 输出上限
     * @return 启动即得的 Running 快照（id 已分配，事件已入账）
     */
    JobSnapshot start(JobRequest request);

    /**
     * 当前快照（任意状态）。
     * @param id 任务 id
     * @return 全量快照
     */
    JobSnapshot snapshot(Id<Job> id);

    /**
     * 从 fromByte 起读已捕获输出（闭区间起、开区间止），UTF-8 完整字符边界切片。
     * @param id 任务 id
     * @param fromByte 起始字节偏移（0 ≤ fromByte ≤ capturedBytes）
     * @param maxBytes 本片字节上限（正数）
     * @return 切片与下一游标
     */
    JobSlice read(Id<Job> id, long fromByte, int maxBytes);

    /**
     * 取消运行中任务（TERM→KILL 语义归 Provider）；终态任务返回 false，不复活。
     * @param id 任务 id
     * @return 是否发起了收敛
     */
    boolean cancel(Id<Job> id);

    /**
     * 本会话任务清单（含终态），按启动序。
     * @return 不可变快照列表
     */
    List<JobSnapshot> list();

    /**
     * 仅拥有 scope 调用；宿主退出清理（终止进程、终局入账）归 Provider 实现，
     * 借用者不得关闭。
     */
    @Override
    void close();
}
