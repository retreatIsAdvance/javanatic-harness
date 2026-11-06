package io.javanatic.harness.delegation;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.scope.ServiceKey;
import java.util.List;

/**
 * 受控委派 seam（0.4 后半）。子代 = durable 会话上的 in-JVM Agent；权限继承为
 * spawn 时点显式快照（工具面 / 沙箱 / 审批档 / 预算——子只窄不宽，不追踪父后续
 * 变更；执法点在子 scope 装配期，结构性测试背书）。深度与容量双闸（maxDepth 缺省
 * 1、maxActive 缺省 8 预留-放行-还槽）与树总预算贯通归 Provider/装配面（停 2/3）。
 * 每次状态变化先事件入账（delegation/snapshot 全量快照、按 id fold 末值胜、
 * ignorable 不进 prompt 组装）后内存可见；子 token 计入树总预算。
 * 不认识的 id 抛 DelegationNotFoundException；非暂停态 resume 抛 IllegalStateException。
 */
public interface DelegationService extends AutoCloseable {

    /** kernel ServiceKey 名（短稳定名）。 */
    ServiceKey<DelegationService> KEY = new ServiceKey<>("delegation");

    /**
     * 派出子代（异步）：立即返回 Running 快照（子代名已分配、事件已入账）；
     * 终局与暂停经事件通知，不在本调用等待。
     * @param request 任务文本 / 工作目录 / 子预算
     * @return Running 快照（含 childSessionId 树链接）
     */
    DelegationSnapshot start(DelegationRequest request);

    /**
     * 答复暂停中的子代：NeedsParentInput → Running（问题与答复均逐字入账）。
     * @param id 委派 id
     * @param answer 父的答复文本（作为子代下一轮 user message）
     * @return 回到 Running 的快照
     */
    DelegationSnapshot resume(Id<Delegation> id, String answer);

    /**
     * 父取消子收敛：Running / NeedsParentInput 才收敛（子代轮中止、事件入账）；
     * 终态返回 false 不复活。
     * @param id 委派 id
     * @return 是否发起收敛
     */
    boolean cancel(Id<Delegation> id);

    /**
     * 当前快照（任意状态）。
     * @param id 委派 id
     * @return 全量快照
     */
    DelegationSnapshot snapshot(Id<Delegation> id);

    /**
     * 本父会话的委派清单（含终态与 Lost），按启动序。
     * @return 不可变快照列表
     */
    List<DelegationSnapshot> list();

    /**
     * 仅拥有 scope 调用：级联取消未终局子代（父会话收口 = 树收口）；
     * 借用者不得关闭。
     */
    @Override
    void close();
}
