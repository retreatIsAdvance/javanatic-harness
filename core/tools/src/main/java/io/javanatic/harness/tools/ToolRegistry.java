package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.ToolSchema;

import java.util.List;
import java.util.Optional;

/** 注册与 schema 来源：agent-loop 组装 LLM 请求的工具列表只从这里取（R2 锁 1）。 */
public interface ToolRegistry {

    /** 本服务的服务键。 */
    ServiceKey<ToolRegistry> KEY = new ServiceKey<>("tools");

    /**
     * @throws IllegalStateException 同名工具已注册
     * @return 注销凭据（工具插件挂自身 scope，R3）
     */
    Disposable register(ToolDefinition tool);

    /** 当前已注册工具的 schema（名称排序，确定性）。 */
    List<ToolSchema> schemas();

    /** @return 按名解析的工具定义 */
    Optional<ToolDefinition> resolve(String name);
}
