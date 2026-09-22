package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.ToolSchema;

import java.util.List;
import java.util.Optional;

/** 注册与 schema 来源：agent-loop 组装 LLM 请求的工具列表只从这里取（R2 锁 1）。 */
public interface ToolRegistry {

    /** 本服务的服务键。 */
    ServiceKey<ToolRegistry> KEY = new ServiceKey<>("tools");

    /**
     * 注册到 owner 的层（06 §4：层键 = registrationScope——root 挂载对每个
     * agent 可见，agent 挂载仅本 agent 子树可见；同名跨层 shadowing）。
     *
     * @param owner 注册归属（插件传自己的 apply scope 即可）
     * @param tool 工具定义
     * @throws IllegalStateException 同层同名工具已注册（配置错误）
     * @return 注销凭据（层随 owner 关闭兜底回收，R3）
     */
    Disposable register(Scope owner, ToolDefinition tool);

    /** 该 scope 可见的工具 schema（层合并 shadowing，名称排序确定性；agent-loop 组装请求的唯一来源，R2）。 */
    List<ToolSchema> schemas(Scope scope);

    /** 该 scope 可见的工具定义（名称排序，同 {@link #schemas} 的层合并口径；治理自述读它，R4）。 */
    List<ToolDefinition> definitions(Scope scope);

    /** @return 该 scope 可见范围内按名解析的工具定义 */
    Optional<ToolDefinition> resolve(Scope scope, String name);
}
