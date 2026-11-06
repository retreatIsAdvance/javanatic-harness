package io.javanatic.harness.delegation.local;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agentloop.AgentEvents;
import io.javanatic.harness.delegation.DelegationService;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.persistence.SessionPersistence;

/**
 * 本地委派插件（id "delegation-local"，it31 停 2）：provide DelegationService；
 * 绑定策略 = **首个 agent/created 即父**（服务自建的子代也触发 created，首绑后
 * 不再重绑——子代会话不经本服务父账）。行配置（B32）：{@code provider}（缺省
 * replay，keyless 测试面）/ {@code model}（缺省 m）/ {@code maxActive}（缺省 8，
 * 正整数）。scope 关闭 = 级联取消（树收口）；缺省挂载零委派零噪声（工具面停 3）。
 */
public final class LocalDelegationPlugin implements Plugin {

    static final String DEFAULT_PROVIDER = "replay";
    static final String DEFAULT_MODEL = "m";
    static final long DEFAULT_MAX_ACTIVE = 8L;

    @Override
    public String id() {
        return "delegation-local";
    }

    @Override
    public void apply(Scope scope) {
        String provider = DEFAULT_PROVIDER;
        String model = DEFAULT_MODEL;
        long maxActive = DEFAULT_MAX_ACTIVE;
        var config = scope.resolve(ConfigService.KEY).map(c -> c.configFor(id()));
        if (config.isPresent()) {
            provider = ConfigValues.stringValue(config.get(), id(), "provider", DEFAULT_PROVIDER);
            model = ConfigValues.stringValue(config.get(), id(), "model", DEFAULT_MODEL);
            maxActive = ConfigValues.longValue(config.get(), id(), "maxActive", DEFAULT_MAX_ACTIVE);
        }
        LocalDelegationService service = new LocalDelegationService(scope,
                scope.require(AgentRegistry.KEY), scope.require(SessionPersistence.KEY),
                provider, model, (int) maxActive);
        scope.provide(DelegationService.KEY, service);
        scope.onClose(service::close);
        scope.events().on(AgentEvents.CREATED,
                (carrier, agent) -> service.bindSession(((Agent) carrier).session()));
    }
}
