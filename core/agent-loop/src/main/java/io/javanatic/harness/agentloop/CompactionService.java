package io.javanatic.harness.agentloop;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.CompactionSummary;

/**
 * 压缩能力入口(06/03:一个可选 capability,不是 loop 主干的一部分)。
 * Provider 负责完整事务:start 锁 → 摘要(维护性 LLM 调用,信封落审计事件)
 * → user/message(Replace)→ end 解锁;失败时 surface 不变、end 记 error。
 */
public interface CompactionService {

    /** 本服务的服务键。 */
    ServiceKey<CompactionService> KEY = new ServiceKey<>("compaction");

    /**
     * 压力判断:末次模型请求的 inputTokens 是否超过配置阈值
     * (it20 修正:此前实现取全日志 max() 高位水位,跨阈后每 step 复发压缩)。
     *
     * @param session 被驱动的会话(只读)
     * @return true 表示下次组装请求前应压缩
     */
    boolean shouldCompact(Session session);

    /**
     * 执行一次压缩事务(整段:锁/摘要/替换/解锁,事件全部落账)。
     * 保留尾部按估价累计(retainTokens),切点回退到 tool 配对边界;
     * 摘要截断(finish=LENGTH)或调用失败 → 重试有界后抛出,surface 不变。
     *
     * @param session 目标会话
     * @param turn    发起压缩的轮号(锁持有者标识)
     * @param route   摘要调用的路由(Provider 配置了独立模型时覆盖)
     * @param signal  取消信号
     * @return 审计事件(摘要/信封/盖写区间);无可压缩区间(tail 覆盖全部 surface)返回 null
     * @throws IllegalStateException 摘要失败时
     */
    CompactionSummary compact(Session session, int turn, LlmCallConfig route, AbortSignal signal);

    /**
     * 溢出恢复强制压缩:无视阈值,尽力压缩后返回(失败上抛由调用方决定重试)。
     *
     * @param session 目标会话
     * @param turn    轮号
     * @param route   路由
     * @param signal  取消信号
     * @return 审计事件;无可压缩区间返回 null(调用方不得重试)
     */
    CompactionSummary compactNow(Session session, int turn, LlmCallConfig route, AbortSignal signal);
}
