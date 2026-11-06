package io.javanatic.harness.delegation;

/** 子代失败成因（Failed 态携带），与「需要父输入」「被取消」正交——问不是败：NeedsParentInput 是独立暂停态，绝不混入本枚举。 */
public enum DelegationFailureReason {
    /** 子代模型调用失败（含反复重试后仍不可用）。 */
    MODEL_ERROR,
    /** 子代执行失败（工具/组合/任务自身报错走完终局）。 */
    EXECUTION_ERROR,
    /** 子代 token 预算耗尽（树总预算的子份额，停 2 贯通）。 */
    BUDGET_EXHAUSTED
}
