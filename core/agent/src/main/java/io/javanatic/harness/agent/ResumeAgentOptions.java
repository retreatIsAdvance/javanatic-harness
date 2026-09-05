package io.javanatic.harness.agent;

import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;

/**
 * 恢复 agent 的输入：驱动既有会话（in-memory store 命中即恢复；
 * 持久化重载随 persistence 切片）。
 *
 * @param sessionId 既有会话 id
 * @param options   路由身份
 */
public record ResumeAgentOptions(Id<Session> sessionId, AgentOptions options) {
}
