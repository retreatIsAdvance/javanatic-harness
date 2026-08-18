# 06 · Scope — Per-Agent 注册隔离

Scope 让一个 agent 拥有自己的工具集、prompt section、监听器，与其他 agent 隔离。这是 preset 组合、subagent delegation、per-agent persona 的基础。

> 本篇建立在 01 的统一 `Scope` 之上：生命周期、可见性、事件归属**共用同一个对象**。前版独立的 `ScopeKey` / `ScopedContext` / carrier 标签表已全部删除——那是三套概念时代的遗物。

## 1. 核心语义（移植自 dsh）

1. **注册是全局或 scoped**：全局注册（在组合层 scope 上）对每个 agent 可见；scoped 注册只在一个 agent scope 的子树内可见。
2. **Agent scope 扁平，不嵌套**：subagent 的 scope 挂在**组合层**下（与父 agent 是兄弟），不挂在父 agent scope 下——子树行为用 lineage 数据（会话事件）表达，不靠 scope 嵌套。隔离由此而来。
3. **Shadowing（同名覆盖）**：scoped 注册覆盖祖先层的同名注册（最近 scope wins）。
4. **事件向上冒泡**：agent scope 派发的事件，本 scope 与祖先 scope（组合层）的 listener 收得到；兄弟 agent scope 的 listener 收不到。机制在 [01 §5 passesFilter](01-kernel.md)。
5. **Setup window**：创建 agent 时，在 agent/session 发布**前**通过 `setup` 回调注册 scoped world（§5）。

> **关键（与直觉相反）**：事件**向上**冒泡，不向下。因为"一个组合要能观察它下面所有 agent"，而"一个 agent 不该看到兄弟 agent"。

## 2. 统一 Scope 下的概念映射

| 前版（三套概念） | 现在（01 统一 Scope） |
|---|---|
| `ScopeKey(Object token)` 不透明身份 | `Scope` 对象本身（身份 = 引用） |
| `Scope.create(ctx, key)` 建独立 fiber + 打标签 | `parent.child()`——一个调用 |
| `ScopedContext`（context + key + fiber 三元组） | 不存在：Scope 即全部 |
| `Scope.tag(obj, key)` / `scopeOf(carrier)` 标签表 | 不存在：dispatch 显式携带 origin Scope（`notify(key, origin, ...)`），无全局映射可泄漏 |
| `allKeys` WeakHashMap | 已删（死代码） |

**修掉的两类缺陷**：标签表（`tag`/`untag`）随 agent dispose 忘记清理即泄漏——现在没有这张表；事件的 scope 归属由派发点的显式参数给出，不再依赖"carrier 对象上碰巧能查到标签"。

## 3. ScopedLayers — per-scope 注册存储

per-agent 注册隔离的基础设施，1:1 移植 dsh 的 `ScopedLayers<L>`，key 从 ScopeKey 换成 `Scope`：

```java
// io.javanatic.harness.core.scope.ScopedLayers
/**
 * per-scope overlay 注册表。
 *
 * 一个 root（组合层）层 + 每个 agent scope 一个 overlay 层。
 * - peek(scope)：只看本 scope（chain-blind）
 * - chainLayers(scope)：祖先链层（root-first）
 * - merge(scope, picker)：沿祖先链合并，最近 scope 同名覆盖（shadowing）
 */
public final class ScopedLayers<L extends ScopeLayer> {

    private final L root;
    private final ConcurrentMap<Scope, L> scoped = new ConcurrentHashMap<>();
    private final Supplier<L> layerFactory;

    public ScopedLayers(Supplier<L> factory) {
        this.layerFactory = factory;
        this.root = factory.get();
    }

    /** 组合层（全局）。 */
    public L root() { return root; }

    /** 本 scope 自己的层（不存在则创建）。 */
    public L of(Scope scope) {
        if (scope == null) return root;
        return scoped.computeIfAbsent(scope, s -> {
            L layer = layerFactory.get();
            // 层随 scope 回收：agent dispose 即删除其 overlay（无泄漏）
            scope.onClose(() -> scoped.remove(scope, layer));
            return layer;
        });
    }

    /** peek：只看本 scope（不含祖先）。 */
    public L peek(Scope scope) { return scoped.get(scope); }

    /** 祖先链层（root-first）：[root ... 直接父] + 本 scope。 */
    public List<L> chainLayers(Scope scope) {
        List<L> chain = new ArrayList<>();
        chain.add(root);
        Deque<Scope> ancestors = new ArrayDeque<>();
        for (Scope c = scope; c != null; c = c.parent()) ancestors.push(c);
        for (Scope a : ancestors) {
            L layer = scoped.get(a);
            if (layer != null) chain.add(layer);
        }
        return chain;
    }

    /** 合并 effective map：root 先放，沿祖先链近端覆盖。最近 scope wins。 */
    public <K, V> Map<K, V> merge(Scope scope, Function<L, Map<K, V>> picker) {
        Map<K, V> result = new LinkedHashMap<>();
        for (L layer : chainLayers(scope)) result.putAll(picker.apply(layer));
        return Collections.unmodifiableMap(result);
    }
}
```

```java
/** 标记接口：一个层持有若干注册（工具、prompt section 等）。实现见各 registry。 */
public interface ScopeLayer {}
```

## 4. 应用：ScopedToolRegistry

```java
// io.javanatic.harness.core.tools.ScopedToolRegistry
class ScopedToolRegistry implements ToolRegistry {

    private final ScopedLayers<ToolRegistryLayer> layers =
        new ScopedLayers<>(ToolRegistryLayer::new);

    @Override
    public Disposable register(Scope owner, ToolDefinition tool) {
        ToolRegistryLayer layer = layers.of(owner);
        ToolDefinition prev = layer.tools.putIfAbsent(tool.name(), tool);
        if (prev != null) {
            // 同层重复注册 fail loud——无论 root 层还是 scoped 层（修正前版 root 层静默忽略）
            throw new IllegalStateException(
                "Duplicate tool '" + tool.name() + "' in scope (existing: " + prev + ")");
        }
        return new Disposable(() -> layer.remove(tool.name()), () -> {});
    }

    /** 给 system prompt 组装用的 schemas（agent-loop 的唯一来源，R2）。 */
    @Override
    public List<ToolSchema> schemas(Scope scope) {
        return layers.merge(scope, l -> l.all()).values().stream()
            .map(ToolDefinition::toSchema)
            .toList();
    }

    /** 执行时查找：scoped 覆盖 root（merge 顺序即 shadowing）。 */
    public Optional<ToolDefinition> resolve(Scope scope, String name) {
        return Optional.ofNullable(layers.merge(scope, l -> l.all()).get(name));
    }
}
```

**覆盖语义的归属**：同名工具在**不同层**是 shadowing（scoped 盖住组合层，merge 顺序天然实现）；在**同一层**是配置错误（fail loud）。两类情况两种结果，不再混淆。

## 5. Setup Window — agent 创建时组合 scoped world

```java
// io.javanatic.harness.agentloop.AgentLoopFactory
class AgentLoopFactory implements AgentFactory {

    @Override
    public AgentHandle create(Scope composition, CreateAgentOptions opts) {
        // 1. agent scope：组合层的子 scope（与其他 agent 是兄弟，§1.2）
        Scope agentScope = composition.child();

        // 2. setup window：在发布前注册 scoped world
        try {
            opts.setup().accept(agentScope);   // 挂 preset、scoped 工具等（§6）
        } catch (Exception e) {
            agentScope.close();                // R3 回滚原语：半注册全部回收
            throw new IllegalStateException("Setup failed and rolled back", e);
        }

        // 3. 创建 session + agent（此刻未发布）
        Session session = sessions.create(agentScope, opts.sessionId(), opts.sessionOptions());
        AgentLoopImpl agent = new AgentLoopImpl(agentScope, session, /* 治理依赖，04 §4 */ ...);

        // 4. 发布：notify agent/created、agent/session-start
        agents.register(agent);
        events.notify(AgentEvents.CREATED, agentScope, agent, agent);

        // 5. dispose capability：停 loop → 静止 → 注销 → flush → 回收 scope
        //    （链式构建，不是 lambda 冒充 future——修正前版编译错误）
        CompletableFuture<Void> dispose = CompletableFuture.runAsync(
            () -> { agent.cancel(AgentCancelCause.disposed(), CancelOptions.keepInbox());
                    agent.whenIdle().join();
                    agents.unregister(agent);
                    events.notify(AgentEvents.DISPOSED, agentScope, agent, agent);
                    sessions.flush(agentScope, session).join();
                    agentScope.close(); },
            virtualThreads);

        return new AgentHandle(agent, dispose);
    }
}
```

**事务语义**：setup 失败 → `agentScope.close()` 回滚所有 scoped 注册 → 不发布 agent/session。对应 dsh 的 "setup rejection rolls the transaction back without publishing either id"。发布后的一切注册仍挂 agentScope，dispose 兜底回收（09 §7 teardown 顺序）。

## 6. Preset 组合 — per-session agent 能力集

Preset 是 per-session 的 agent 组合，从 preset 文件在 agent scope 下挂载一组 plugin 行：

```yaml
# ~/.harness/presets/coder/preset.yml
name: coder
description: A coding-focused agent
rows:
  - plugin: fs-tool
  - plugin: shell-tool
  - plugin: lsp-tool            # 留接口档，见 05 §9
  - plugin: fs-observation-policy
config:
  fs:
    workspaceRoot: ${cwd}
  sandbox:
    mode: landlock
```

```java
// io.javanatic.harness.preset.AgentPresets
public interface AgentPresets {

    ServiceKey<AgentPresets> KEY = new ServiceKey<>("agentPresets");

    /** 列出所有 preset（扫描 roots）。 */
    List<AgentPreset> list();

    /** 解析一个 preset。 */
    AgentPreset resolve(String id);

    /**
     * 在 agent scope 下挂载一个 preset 的 plugin 行。
     * 只能在 setup window（§5 的 step 2）里调用——发布后挂载会绕过事务语义。
     */
    AgentPreset mount(Scope agentScope, String id);

    /**
     * 让 child agent 继承 parent 的 standing composition（不重读 roster）。
     * 对应 dsh composeFrom：delegation 关系进会话事件，scope 仍挂组合层（§1.2）。
     */
    String composeFrom(Scope childScope, Scope parentScope);
}
```

**mount 实现**：读 preset 文件，经 `PluginLoader.loadAll(agentScope, rows)` 在 agent scope 下逐插件加载（沿用 01 §7 的原子回滚）。工具/prompt 注册因此 scope-bound，agent dispose 自动回收。

## 7. 事件 scope 过滤的实际工作方式

机制在 [01 §5](01-kernel.md)：订阅记录归属 scope，dispatch 显式携带 origin scope，`passesFilter` 沿 origin 的祖先链匹配订阅 scope。场景：

- Agent A（scope=A，parent=组合层 C）派发 `agent/status`：
  - 全局订阅（root 上 `onGlobal`）：✅ 收。
  - A 自己的 scoped 订阅（A 上 `on`）：✅ 收（A 在自己的祖先链里）。
  - 组合层 C 的订阅（C 上 `on`）：✅ 收（C 是 A 的祖先）。
  - 兄弟 Agent B 的 scoped 订阅：❌ 不收（B 不在 A 的祖先链上）。

这就是"组合能观察下属所有 agent，agent 看不到兄弟"。

## 8. Restriction — per-scope 过滤全局工具集

```java
public interface ToolRegistry {
    /** 限制某 scope 的全局工具集（交集）。 */
    Disposable restrict(Scope scope, Predicate<String> filter);
}
```

restriction 过滤**组合层**工具集（scoped 注册在过滤后合并）。被过滤掉的工具在 prompt 里消失**且**拒绝执行——与不存在的工具无法区分（不给模型"看得见调不着"的幻觉）。

用途：一个"只读" preset restrict 掉所有写工具（`fs_write`、`fs_edit`、`bash`……）。

## 9. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| `ScopeKey = object` | 统一 `Scope`（01）| 身份即对象引用 |
| `createScope(ctx, key)` | `composition.child()` | 一个调用 |
| `scopeOf(ctx)` / carrier 标签 | dispatch 显式 origin 参数 | 无标签表可泄漏 |
| `ScopedLayers<L>` | `ScopedLayers<L>`（key=Scope）| 层随 scope close 自动删除 |
| `peek` / `chainLayers` / `merge` | 同名方法 | 1:1 |
| shadowing（最近 scope wins）| merge 的覆盖顺序；**同层重复 fail loud** | 修正静默忽略 |
| scope 扁平（子 agent 不嵌套）| agent scope 皆挂组合层，兄弟关系 | lineage 走会话事件 |
| Setup window | `CreateAgentOptions.setup`（发布前，失败回滚）| 事务语义 |
| `composeFrom`（不重读 roster）| `AgentPresets.composeFrom` | standing composition |
| `tools.restrict(scope, filter)` | `ToolRegistry.restrict` | 全局集交集 |
