package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;

/**
 * 系统提示词组装服务（AgentLoop 构造器强制依赖，R4）。
 * assemble 只读日志事实：contributor 从 session 派生内容，不读环境时钟——
 * 同一日志状态必然组装出同一提示词（R1 可重建的前提）。
 */
public interface SystemPromptService {

    /** 本服务的服务键。 */
    ServiceKey<SystemPromptService> KEY = new ServiceKey<>("system-prompt");

    /**
     * 注册一个贡献段。
     *
     * @param section 贡献段
     * @throws NullPointerException section 为 null 时
     * @return 注销凭据（插件挂自身 scope，R3）
     */
    Disposable register(PromptSection section);

    /**
     * 组装当前系统提示词全文。
     *
     * @param session 被驱动的会话（只读）
     * @return 全文；无贡献段时空串（loop 侧转译为无系统提示词请求）
     */
    String assemble(Session session);
}
