package io.javanatic.harness.sandbox.sandbox;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;

/**
 * 逐调用的沙箱策略解析服务：部署默认档（组合配置）+ 会话态修正
 * （如计划模式激活压只读）。解析是消费端的显式一步（08 §7）——
 * provider 视所得策略为全指定。
 */
public interface SandboxPolicyService {

    /** 本服务的服务键。 */
    ServiceKey<SandboxPolicyService> KEY = new ServiceKey<>("sandbox-policy");

    /**
     * @param session 发起调用的会话（只读——fold 日志）
     * @return 本次执行应携带的完整文件效果策略
     */
    SandboxPolicy resolve(Session session);
}
