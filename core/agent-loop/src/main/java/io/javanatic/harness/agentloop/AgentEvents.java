package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.AgentStatus;
import io.javanatic.harness.kernel.events.EventKey;
import io.javanatic.harness.llm.LlmCallConfig;

/** agent 域的事件键（loop 固定触发的五类扩展点；订阅 bind 到挂载子树）。 */
public final class AgentEvents {

    /** 驱动状态迁移（payload = 新状态；NOTIFY，异步）。 */
    public static final EventKey<AgentStatus> STATUS = EventKey.notify("agent/status", AgentStatus.class);

    /** 本批 admitted 输入的裁决（waterfall，default=Enter(原批)）。args=[本批消息, turn, signal]。 */
    public static final EventKey<PreStepDecision> PRE_STEP =
        EventKey.waterfall("agent/pre-step", PreStepDecision.class);

    /** 一次模型调用的配置改写（waterfall，default=options）。args=[turn, step, signal]。 */
    public static final EventKey<LlmCallConfig> REQUEST =
        EventKey.waterfall("agent/request", LlmCallConfig.class);

    /** 模型调用失败的处理裁决（firstOf；无人拦截 = 不重试，turn 以 Error 收口）。args=[turn, step, 异常]。 */
    public static final EventKey<RequestErrorDecision> REQUEST_ERROR =
        EventKey.waterfall("agent/request-error", RequestErrorDecision.class);

    /** turn 排空完毕、关轮前（NOTIFY，同步顺序派发；listener 可 steer() 复活循环）。payload=turn。 */
    public static final EventKey<TurnStopping> TURN_STOPPING =
        EventKey.notify("agent/turn-stopping", TurnStopping.class);

    private AgentEvents() {
    }

    /** turn 停止通知负载。 */
    public record TurnStopping(int turn) {
    }

    /** 失败处理裁决：允许对当前 step 重试的最大次数（0 = 等价不拦截）。 */
    public record RequestErrorDecision(int maxRetries) {

        /** @throws IllegalArgumentException maxRetries 为负时 */
        public RequestErrorDecision {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
            }
        }
    }
}
