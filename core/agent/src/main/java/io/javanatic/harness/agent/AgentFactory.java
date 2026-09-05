package io.javanatic.harness.agent;

import io.javanatic.harness.kernel.scope.Scope;

/**
 * agent 工厂（loop 插件注册；Registry 消费）。
 */
public interface AgentFactory {

    /**
     * 创建并接线一个新 agent（新会话）。
     *
     * @param owner    拥有方 scope（agent scope 派生自它）
     * @param options  创建输入
     * @return agent 句柄（dispose 链由工厂组合构建）
     */
    AgentHandle create(Scope owner, CreateAgentOptions options);

    /**
     * 恢复驱动一个既有会话。
     *
     * @param owner   拥有方 scope
     * @param options 恢复输入
     * @return agent 句柄
     */
    AgentHandle resume(Scope owner, ResumeAgentOptions options);
}
