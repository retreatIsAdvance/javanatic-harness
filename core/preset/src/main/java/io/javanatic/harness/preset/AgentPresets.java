package io.javanatic.harness.preset;

import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;

/**
 * preset 服务(06 §6):list/resolve 扫描 roots;mount 在 setup window 内把
 * preset 行装载到 agent scope(publish 后调用属协议违例,由调用方保证)。
 */
public interface AgentPresets {

    /** 本服务的服务键。 */
    ServiceKey<AgentPresets> KEY = new ServiceKey<>("agentPresets");

    /** @return 全部可用 preset(按名排序) */
    List<AgentPreset> list();

    /**
     * @param id preset 名
     * @return 解析的 preset
     * @throws java.util.NoSuchElementException 不存在时
     */
    AgentPreset resolve(String id);

    /**
     * 把 preset 行装载到 agent scope(setup window 内调用)。
     *
     * @param agentScope agent 的作用域(setup 回调收到的那个)
     * @param id         preset 名
     * @return 挂载的 preset
     * @throws java.util.NoSuchElementException preset 或行引用的插件不存在时(缺失列全清单)
     */
    AgentPreset mount(Scope agentScope, String id);
}
