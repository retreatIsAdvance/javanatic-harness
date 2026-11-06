package io.javanatic.harness.delegation;

import java.nio.file.Path;

/**
 * 委派启动请求。task 为交给子代的完整任务文本（子代以此为唯一任务来源）；
 * 工作目录须绝对路径（执行边界无默认，shell/jobs 同立场）；budgetTokens 为
 * 子代 token 上限（继承面「预算」项的收窄值——只能小于父的剩余预算，
 * 装配期由 Provider 校验）。工具面 / 沙箱 / 审批档的收窄经 Provider 行配置
 * 的继承快照（停 2），不在请求内逐项展开。
 */
public record DelegationRequest(String task, Path workingDirectory, long budgetTokens) {

    /** 启动即校验：坏请求在装配点失败，不进 Provider。 */
    public DelegationRequest {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must be non-blank");
        }
        if (workingDirectory == null || !workingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("workingDirectory must be absolute");
        }
        if (budgetTokens <= 0) {
            throw new IllegalArgumentException("budgetTokens must be positive");
        }
    }
}
