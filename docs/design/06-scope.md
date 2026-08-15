# 06 · Scope — Per-Agent 注册隔离

Scope 让一个 agent 拥有自己的工具集、prompt section、监听器，与其他 agent 隔离。这是 preset 组合、subagent delegation、per-agent persona 的基础。

## 1. 核心语义（移植自 dsh）

1. **注册是全局或 scoped**：全局注册对每个 agent 可见；scoped 注册只对一个 scope key 可见。
2. **两层，扁平**：scoped **不向子 agent 继承**——子树行为用 lineage 数据表达，不靠 scope 嵌套。
3. **Shadowing（同名覆盖）**：scoped 注册覆盖同名全局注册（最近 scope wins）。
4. **事件向上冒泡**：一个 agent（scope A）派发的事件，A 自己的 listener 和 A 祖先 scope 的 listener 收得到；A 子 scope 的 listener 收不到。
5. **Setup window**：创建 agent 时，在 agent/session 发布**前**通过 `setup` 回调注册 scoped world。

> **关键（与直觉相反）**：事件是**向上**冒泡，不是向下。因为"一个组合（父 scope）要能观察它下面所有 agent"，而"一个 agent 不该看到兄弟 agent"。

## 2. ScopeKey — 不透明身份

```java
// io.dsh.kernel.scope.ScopeKey
package io.dsh.kernel.scope;

import java.util.Objects;

/**
 * Scope 的不透明身份。身份比较（== / equals 委托给 token）。
 *
 * 对应 dsh 的 ScopeKey = object。
 * 约定：一个 live agent 是它自己 scope 的 key。
 */
public final class ScopeKey {

    private final Object token;
    private volatile ScopeKey parent;  // 父 scope（用于事件向上冒泡 + 链式 merge）

    ScopeKey(Object token) {
        this.token = Objects.requireNonNull(token);
    }

    public Object token() { return token; }
    public ScopeKey parent() { return parent; }

    void bindParent(ScopeKey p) {
        if (this.parent != null) throw new IllegalStateException("Parent already bound");
        // 环检测
        for (ScopeKey c = p; c != null; c = c.parent) {
            if (c == this) throw new IllegalStateException("Cycle in scope chain");
        }
        this.parent = p;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScopeKey s && s.token == this.token;
    }
    @Override
    public int hashCode() { return System.identityHashCode(token); }
    @Override
    public String toString() { return "Scope(" + token + ")"; }
}
```

**token 用 `Object`**（身份比较）。Agent 的 scope key 用 agent 对象本身作 token：`new ScopeKey(agent)`。

## 3. Scope 工具类 — 创建 scope + scoped context

```java
// io.dsh.kernel.scope.Scope
package io.dsh.kernel.scope;

public final class Scope {

    private static final WeakHashMap<ScopeKey, ScopeKey> allKeys = new WeakHashMap<>();
    private static final Map<Object, ScopeKey> carrierScopes = new ConcurrentHashMap<>();

    private Scope() {}

    /**
     * 创建一个 scope，返回其 scoped context。
     * scope 本质上是一个独立 fiber（生命周期域），context 上打了 scopeKey 标签。
     * 对应 dsh createScope。
     */
    public static ScopedContext create(Context parentCtx, ScopeKey key) {
        Fiber scopedFiber = parentCtx.fiber().spawnChild();
        scopedFiber.setScope(key);
        Context scopedCtx = scopedFiber.context();
        return new ScopedContext(scopedCtx, key, scopedFiber);
    }

    /** 绑定 scope 父子关系（一次性）。 */
    public static void bindParent(ScopeKey child, ScopeKey parent) {
        child.bindParent(parent);
    }

    /** 读 carrier 上的 scope 标签。 */
    public static ScopeKey scopeOf(Object carrier) {
        if (carrier instanceof Agent a) return carrierScopes.get(a);
        if (carrier instanceof Session s) return carrierScopes.get(s);
        return null;
    }

    /** 关联一个对象（Agent/Session）到其 scope（用于 scopeOf 查找）。 */
    public static void tag(Object obj, ScopeKey key) {
        carrierScopes.put(obj, key);
    }

    /**
     * 构造 scope filter：决定哪些 listener 收到某 scope 的事件。
     * 规则（对应 dsh scopeTarget）：
     * - listener 全局（scopeKey=null）→ 收所有
     * - listener scope 是 dispatch key 或其祖先 → 收（事件向上冒泡）
     * - 否则 → 不收
     */
    public static Predicate<ScopeKey> filterFor(ScopeKey dispatchKey) {
        if (dispatchKey == null) return any -> true;  // 全局事件
        return listenerScope -> {
            if (listenerScope == null) return true;  // 全局 listener
            for (ScopeKey c = dispatchKey; c != null; c = c.parent()) {
                if (c.equals(listenerScope)) return true;
            }
            return false;
        };
    }

    /** scope 链的祖先迭代（farthest-first，用于 merge）。 */
    public static List<ScopeKey> chainOf(ScopeKey key) {
        List<ScopeKey> chain = new ArrayList<>();
        for (ScopeKey c = key; c != null; c = c.parent()) chain.add(c);
        Collections.reverse(chain);  // farthest ancestor first
        return chain;
    }
}
```

## 4. ScopedContext — 带 scope 的 context

```java
// io.dsh.kernel.scope.ScopedContext
package io.dsh.kernel.scope;

/**
 * 绑定了 ScopeKey 的 context。
 * 通过此 context 注册的 effect 归属该 scope，dispose 时回收。
 */
public final class ScopedContext {

    private final Context delegate;
    private final ScopeKey scopeKey;
    private final Fiber scopeFiber;

    ScopedContext(Context delegate, ScopeKey scopeKey, Fiber scopeFiber) {
        this.delegate = delegate;
        this.scopeKey = scopeKey;
        this.scopeFiber = scopeFiber;
    }

    public Context ctx() { return delegate; }
    public ScopeKey scopeKey() { return scopeKey; }

    /** 销毁 scope：回收所有 scoped 注册。 */
    public CompletableFuture<Void> dispose() {
        return scopeFiber.dispose();
    }
}
```

## 5. ScopedLayers — per-scope 注册存储

这是 per-agent 注册隔离的基础设施。1:1 移植 dsh 的 `ScopedLayers<L>`：

```java
// io.dsh.kernel.scope.ScopedLayers
package io.dsh.kernel.scope;

import java.util.*;

/**
 * per-scope overlay 注册表。
 *
 * 一个 global 层 + 每个 ScopeKey 一个 scoped overlay 层。
 * - peek(scope)：只看本 scope（chain-blind）
 * - chainLayers(scope)：祖先链层（farthest-first）
 * - merge(scope, picker)：global + 祖先链合并，最近 scope 同名覆盖
 *
 * 对应 dsh ScopedLayers<L>。
 */
public final class ScopedLayers<L extends ScopeLayer> {

    private final L global;
    private final Map<ScopeKey, L> scoped = new ConcurrentHashMap<>();
    private final java.util.function.Supplier<L> layerFactory;

    public ScopedLayers(java.util.function.Supplier<L> factory) {
        this.layerFactory = factory;
        this.global = factory.get();
    }

    /** 全局层。 */
    public L global() { return global; }

    /** 本 scope 自己的层（不存在则创建）。 */
    public L of(ScopeKey key) {
        if (key == null) return global;
        return scoped.computeIfAbsent(key, k -> layerFactory.get());
    }

    /** peek：只看本 scope（不继承祖先）。 */
    public L peek(ScopeKey key) {
        return scoped.get(key);
    }

    /** 祖先链层（farthest-first）：[全局祖先 ... 直接父] 本scope。 */
    public List<L> chainLayers(ScopeKey key) {
        List<L> chain = new ArrayList<>();
        chain.add(global);
        for (ScopeKey ancestor : Scope.chainOf(key)) {
            L layer = scoped.get(ancestor);
            if (layer != null) chain.add(layer);
        }
        return chain;
    }

    /**
     * 合并 effective map：先放 global，再按祖先链 farthest-first 覆盖。
     * 最近 scope 的同名条目胜出（shadowing）。
     */
    public <K, V> Map<K, V> merge(ScopeKey key, java.util.function.Function<L, Map<K, V>> picker) {
        Map<K, V> result = new LinkedHashMap<>();
        for (L layer : chainLayers(key)) {
            result.putAll(picker.apply(layer));
        }
        return Collections.unmodifiableMap(result);
    }

    /** scope 销毁时调用：删除其层。 */
    public void remove(ScopeKey key) {
        scoped.remove(key);
    }
}
```

### ScopeLayer 接口

```java
// io.dsh.kernel.scope.ScopeLayer
public interface ScopeLayer {
    // 标记接口：一个 scope 层持有若干注册（工具、prompt section、listener 等）
    // 具体实现见各 registry（ToolRegistryLayer、PromptSectionLayer 等）
}
```

## 6. 应用：ScopedToolRegistry

以工具注册为例，展示 ScopedLayers 如何实现 shadowing：

```java
// io.dsh.core.tools.ToolRegistryLayer
class ToolRegistryLayer implements ScopeLayer {
    final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    void put(ToolDefinition t) { tools.put(t.name(), t); }
    void remove(String name) { tools.remove(name); }
    Map<String, ToolDefinition> all() { return tools; }
}

// io.dsh.core.tools.ScopedToolRegistry
class ScopedToolRegistry implements ToolRegistry {

    private final ScopedLayers<ToolRegistryLayer> layers =
        new ScopedLayers<>(ToolRegistryLayer::new);
    private final Events events;

    @Override
    public Subscription register(ToolDefinition tool) {
        // 通过调用方 context 决定写哪层
        return register(tool, currentScope());
    }

    public Subscription register(ToolDefinition tool, ScopeKey scope) {
        ToolRegistryLayer layer = layers.of(scope);
        ToolDefinition prev = layer.tools.putIfAbsent(tool.name(), tool);
        if (prev != null && scope != null) {
            // scoped 层允许覆盖全局（shadowing）；scoped 覆盖 scoped 同 scope 抛错
            throw new IllegalStateException("Duplicate tool in scope: " + tool.name());
        }
        return new Subscription(() -> layer.remove(tool.name()));
    }

    /** 给 system prompt 组装用的 schemas：merge 后的 effective 工具集。 */
    @Override
    public List<ToolSchema> schemas(ScopeKey scope) {
        Map<String, ToolDefinition> effective = layers.merge(scope, l -> l.all());
        return effective.values().stream()
            .map(ToolDefinition::toSchema)
            .toList();
    }

    /** 执行时查找：scoped 覆盖 global。 */
    public Optional<ToolDefinition> resolve(String name, ScopeKey scope) {
        // 先查 scoped 层链（最近 scope wins）
        for (L layer : layers.chainLayers(scope)) { ... }
        Map<String, ToolDefinition> effective = layers.merge(scope, l -> l.all());
        return Optional.ofNullable(effective.get(name));
    }
}
```

## 7. Setup Window — agent 创建时组合 scoped world

```java
// io.dsh.core.agent.AgentFactory（在 agent-loop 模块）
class AgentLoopFactory implements AgentFactory {

    @Override
    public CompletableFuture<AgentHandle> create(Context ownerCtx, CreateAgentOptions opts) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. 创建 agent scope key（token = agent 对象占位）
            ScopeKey scopeKey = new ScopeKey(new Object());

            // 2. 创建 scoped context（独立 fiber）
            ScopedContext scopedCtx = Scope.create(ownerCtx, scopeKey);

            // 3. 调 setup：在发布前注册 scoped world
            try {
                opts.setup().accept(scopedCtx);
            } catch (Exception e) {
                scopedCtx.dispose().join();  // 回滚
                throw new RuntimeException("Setup failed", e);
            }

            // 4. 创建 session + agent（此刻未发布）
            Session session = sessions.create(scopedCtx.ctx(), opts.sessionId(), opts.sessionOptions());
            AgentImpl agent = new AgentImpl(scopedCtx.ctx(), session, opts.agentOptions(), scopeKey);

            // tag agent → scope（让 scopeOf(agent) 工作）
            Scope.tag(agent, scopeKey);

            // 5. 发布：emit agent/created, agent/session-start
            agentRegistry.register(agent);
            events.emit(AgentEvents.CREATED, agent, agent);
            events.emit(AgentEvents.SESSION_START, agent,
                new SessionStartPayload(SessionStartSource.STARTUP));

            // 6. 启动 driver（不立即 run，等第一次 input）
            // dispose 闭包：停 loop → 等 quiescence → 注销 → 移 session → dispose scope
            CompletableFuture<Void> dispose = () -> {
                agent.stop();
                agent.whenIdle().join();
                agentRegistry.unregister(agent);
                events.emit(AgentEvents.DISPOSED, agent, agent);
                scopedCtx.dispose();
            };

            return new AgentHandle(agent, dispose);
        }, virtualThreadExecutor);
    }
}
```

**事务语义**：setup 失败 → dispose scoped context（回滚所有 scoped 注册）→ 不发布 agent/session。对应 dsh 的 "setup rejection rolls the transaction back without publishing either id"。

## 8. Preset 组合 — per-session agent 能力集

Preset 是 per-session 的 agent 组合，从 `cordis.yml` 在 agent scope 下挂载一组 plugin 行：

```yaml
# ~/.harness/presets/coder/cordis.yml
name: coder
description: A coding-focused agent
plugins:
  - plugin: io.dsh.bundle.base           # 基础能力
  - plugin: io.dsh.fs.tool               # 文件工具
  - plugin: io.dsh.shell.tool            # shell 工具
  - plugin: io.dsh.lsp.tool              # LSP 工具
  - plugin: io.dsh.fs.observation-policy # 观察策略
config:
  fs:
    workspaceRoot: ${cwd}
  sandbox:
    mode: landlock
```

```java
// io.dsh.preset.AgentPresets —— ctx.agentPresets
public interface AgentPresets {

    ServiceKey<AgentPresets> KEY = new ServiceKey<>("agentPresets");

    /** 列出所有 preset（扫描 roots）。 */
    CompletableFuture<List<AgentPreset>> list();

    /** 解析一个 preset。 */
    CompletableFuture<AgentPreset> resolve(String id);

    /**
     * 在 agent scope 下挂载一个 preset 的 plugin 行。
     * 在 setup(agentCtx) 中调用。
     */
    CompletableFuture<AgentPreset> mount(Context agentCtx, String id);

    /**
     * 让 child agent 继承 parent 的 standing composition（不重读 roster）。
     * 对应 dsh composeFrom。
     */
    String composeFrom(Context childCtx, Context parentCtx);
}
```

**mount 实现**：读 preset 的 `cordis.yml`，在 agent scope fiber 下逐个 `plugin.apply(scopedCtx)`。因为是在 scoped fiber 下注册，所有工具/prompt 都 scope-bound，agent dispose 时自动回收。

## 9. 事件 scope 过滤的实际工作方式

回顾 [01-kernel.md Events.passesFilter](01-kernel.md)：

```java
// dispatch 时
ScopeKey carrierScope = Scope.scopeOf(carrier);  // 从 carrier（Agent/Session）读 scope
for (Registration<?> reg : regs) {
    if (!passesFilter(reg, carrierScope)) continue;
    reg.listener().handle(carrier, payload);
}

// passesFilter
private boolean passesFilter(Registration<?> reg, ScopeKey carrierScope) {
    if (carrierScope == null || reg.global || reg.scopeKey == null) return true;
    // 沿 carrierScope 祖先链查找 listener.scopeKey
    for (ScopeKey c = carrierScope; c != null; c = c.parent()) {
        if (c.equals(reg.scopeKey)) return true;
    }
    return false;
}
```

**场景**：
- Agent A (scope=A, parent=root) 派发 `agent/status`。
- 全局 listener（`scopeKey=null`）：✅ 收。
- A 自己的 scoped listener（`scopeKey=A`）：✅ 收（A 在 A 的祖先链里）。
- root 组合的 listener（`scopeKey=root`）：✅ 收（root 是 A 的祖先）。
- 兄弟 Agent B 的 scoped listener（`scopeKey=B`）：❌ 不收。

这实现了"组合能观察下属 agent，agent 看不到兄弟"。

## 10. Restriction — per-scope 过滤全局工具集

```java
// io.dsh.core.tools.ToolRegistry
public interface ToolRegistry {
    /** 限制某 scope 的全局工具集（交集）。 */
    Subscription restrict(ScopeKey scope, Predicate<String> filter);
}
```

一个 restriction 过滤 **全局** 工具集（scoped 注册在过滤后合并）。被过滤掉的工具在 prompt 里消失**且**拒绝执行——与不存在的工具无法区分。

**用途**：一个"只读"agent preset restrict 掉所有写工具（`fs_write`, `fs_edit`, `bash`, ...）。

## 11. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| `ScopeKey = object` | `ScopeKey(Object token)` | 身份比较 |
| `createScope(ctx, key)` | `Scope.create(ctx, key)` | 独立 fiber + 打标签 |
| `scopeOf(ctx)` | `Scope.scopeOf(carrier)` | 从 carrier 读 |
| `scopeTarget` filter | `Scope.filterFor(key)` | 向上冒泡 |
| `ScopedLayers<L>` | `ScopedLayers<L>` | 1:1 |
| `peek` / `chainLayers` / `merge` | 同名方法 | 1:1 |
| shadowing（最近 scope wins）| `merge` 的 putAll 顺序 | 1:1 |
| Setup window | `CreateAgentOptions.setup` | 发布前组合 |
| `composeFrom`（不重读 roster）| `AgentPresets.composeFrom` | 同 standing composition |
| `tools.restrict(scope, filter)` | `ToolRegistry.restrict` | 全局集交集 |
| `composeFrom` 父子绑定 | `Scope.bindParent` | 一次性，环检测 |
