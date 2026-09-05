package io.javanatic.harness.agent;

import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.SessionEvent;

import java.util.List;

/**
 * 创建 agent 的输入。
 *
 * @param sessionId 会话 id（显式传入——测试可冻结、调用方可命名）
 * @param options   路由身份
 * @param seed      会话 seed 重放（null = 空会话）
 * @param header    存储元数据（null = fresh）
 */
public record CreateAgentOptions(Id<Session> sessionId, AgentOptions options,
                                 List<SessionEvent> seed, SessionHeader header) {

    /** 空会话便捷工厂。 */
    public static CreateAgentOptions of(Id<Session> sessionId, AgentOptions options) {
        return new CreateAgentOptions(sessionId, options, null, null);
    }
}
