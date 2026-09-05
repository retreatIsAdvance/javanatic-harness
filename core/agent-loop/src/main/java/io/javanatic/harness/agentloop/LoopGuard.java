package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;

/** 停止条件（R4：构造器强制——没有它组装不出 loop）。超限抛 {@link GuardRejectException}。 */
public interface LoopGuard {

    /** 本服务的服务键。 */
    ServiceKey<LoopGuard> KEY = new ServiceKey<>("loop-guard");

    /**
     * 预算检查：turn 开始时以 step=0 查轮数上限，每步以当前 step 查步数上限。
     *
     * @param session 被驱动的会话（未来 budget 档读 usage 累计）
     * @param turn    当前轮号（1 起）
     * @param step    当前步号（0 起）
     * @throws GuardRejectException 超出任一上限
     */
    void checkBudget(Session session, int turn, int step);

    /** 当前生效的上限（--verify 治理摘要读它，07 §6）。 */
    Limits limits();

    /** 上限词表（全部非零——07 production 档断言非零）。 */
    record Limits(int maxTurns, int maxStepsPerTurn) {

        /** @throws IllegalArgumentException 任一上限非正时 */
        public Limits {
            if (maxTurns <= 0 || maxStepsPerTurn <= 0) {
                throw new IllegalArgumentException("guard limits must be positive: " + maxTurns + "/" + maxStepsPerTurn);
            }
        }
    }
}
