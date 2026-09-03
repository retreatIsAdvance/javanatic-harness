package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.Objects;

/**
 * 审批服务（R4 固定 stage）：副作用放行前的人工/策略裁决。
 * 三模式真实现属 interaction 切片；本 Definition 随其第一个强制消费者
 * （ToolExecutor 构造器）落地在 core/tools。
 */
public interface ApprovalService {

    /** 本服务的服务键。 */
    ServiceKey<ApprovalService> KEY = new ServiceKey<>("approval");

    /**
     * @param request 审批请求
     * @throws ApprovalDeniedException 拒绝（调用方转 error result，turn 不炸）
     */
    void require(ApprovalRequest request);

    /** 一次审批请求：工具名、人读摘要、原始实参。 */
    record ApprovalRequest(String toolName, String summary, String arguments) {

        /** @throws NullPointerException 任一字段为 null 时 */
        public ApprovalRequest {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(arguments, "arguments");
        }
    }
}
