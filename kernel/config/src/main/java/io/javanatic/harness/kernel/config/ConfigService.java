package io.javanatic.harness.kernel.config;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.Map;

/**
 * 插件配置供给（07 §4）。boot 把解析后的行配置注册为实现；插件在 apply 里
 * 按自己的 id 取配置。默认值不在 ConfigService——可调参数的默认值是插件的
 * resolve 职责（08 §7），config 只携带「组合层明确给出的值」。
 */
public interface ConfigService {

    /** 本服务的服务键。 */
    ServiceKey<ConfigService> KEY = new ServiceKey<>("kernel.config");

    /**
     * @param pluginId 插件 id
     * @return 该插件的已解析配置；行未写 config 则空 Map（不是 null）
     */
    Map<String, Object> configFor(String pluginId);
}
