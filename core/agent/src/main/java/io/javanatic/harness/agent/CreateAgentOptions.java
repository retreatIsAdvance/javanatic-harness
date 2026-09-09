package io.javanatic.harness.agent;

import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.SessionEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 创建 agent 的输入。
 *
 * @param sessionId 会话 id（显式传入——测试可冻结、调用方可命名）
 * @param options   路由身份
 * @param seed      会话 seed 重放（null = 空会话）
 * @param header    存储元数据（null = fresh）
 * @param setup     setup window 回调（06 §5：agent scope 建立后、发布前执行——
 *                  挂 preset、scoped 工具等；失败即整体回滚且不发布。null = 无）
 */
public record CreateAgentOptions(Id<Session> sessionId, AgentOptions options,
                                 List<SessionEvent> seed, SessionHeader header,
                                 Consumer<Scope> setup) {

    /** @throws NullPointerException seed 为 null 数组外其余已校验;seed 归一不可变 */
    public CreateAgentOptions {
        seed = seed == null ? null : List.copyOf(seed);
    }

    /** 空会话便捷工厂（无 setup）。 */
    public static CreateAgentOptions of(Id<Session> sessionId, AgentOptions options) {
        return new CreateAgentOptions(sessionId, options, null, null, null);
    }
}
