# 01 · Kernel — Context / Fiber / Events

Kernel 是 Cordis 的 JVM 等价物。它提供四个原语：

1. **`Context`** —— 服务仓库 + 事件总线 + 生命周期挂载点。
2. **`Fiber`** —— 一个生命周期容器（Cordis fiber 的等价物），持有 effect 栈、inject 依赖、scope key。
3. **`Events`** —— 五种 dispatch 模式（emit / waterfall / parallel / serial / bail）。
4. **`Scope`** —— effect 栈与 closeable 回收。

目标行数：**整个 kernel < 1500 行 Java**（不含测试）。

---

## 1. ServiceKey — 类型品牌的服务标识

dsh 用 `symbol` 做 service key，靠 `ReflectService.store` 的 symbol→impl 映射隔离。JH 用泛型 phantom type：

```java
// io.dsh.kernel.context.ServiceKey
package io.dsh.kernel.context;

/**
 * 类型品牌的服务标识。结构上是 name + isolate，类型上是 T 的载体。
 * 泛型 T 仅供编译器在 {@link Context#provide} / {@link Context#get} 处做类型检查，
 * 运行时不参与 equals/hashCode（erasure）。
 *
 * @param name    服务名（全局唯一，等价 cordis 的 string key）
 * @param isolate 隔离标签；同 name 不同 isolate 是独立服务（等价 cordis isolate label）
 */
public record ServiceKey<T>(String name, String isolate) {

    /** 默认 isolate 为 "main"。 */
    public ServiceKey(String name) {
        this(name, "main");
    }

    @Override
    public String toString() {
        return isolate.equals("main") ? name : name + "@" + isolate;
    }
}
```

**为什么不用 `Class<T>` 做 key？**
dsh 的 `ctx.tools` 之类服务是**一个 Service 接口的多个实现共存**（tool registry 有多个 provider 注册器）。用 `Class<T>` 会把 key 和类型绑死，无法表达"同接口、不同 isolate"的隔离。`ServiceKey<T>` 解耦 name 和类型，由 `provide` 的调用方保证 `T` 与 impl 匹配。

**isolate 的用途**：Preset 组合中，一个 agent scope 内挂载的服务对其他 agent 不可见（等价 dsh 的 `isolate` realm）。通过 `ServiceKey("fs", "agent-42")` 实现隔离命名空间。

---

## 2. Context — 服务仓库 + 事件总线

```java
// io.dsh.kernel.context.Context
package io.dsh.kernel.context;

import java.util.concurrent.ConcurrentHashMap;

public final class Context {

    private final ServiceRegistry services;
    private final Events events;
    private final Fiber fiber;      // 拥有此 Context 的 fiber
    private final ScopeKey scopeKey; // 此 Context 所属 scope（可 null = 全局）

    Context(Fiber fiber, ScopeKey scopeKey) {
        this.fiber = fiber;
        this.scopeKey = scopeKey;
        this.services = fiber.runtime().registry();
        this.events = fiber.runtime().events();
    }

    // ────────── 服务 ──────────

    /**
     * 注册服务。返回 Subscription，close 即注销（走 fiber effect 栈，按序回收）。
     * 重复注册同 key 抛 IllegalStateException（fail loud）。
     */
    public <T> Subscription provide(ServiceKey<T> key, T impl) {
        return fiber.addEffect(() -> services.register(key, impl, fiber));
    }

    /**
     * 查找服务。沿 fiber 父链向上查找，直到第一个提供者。
     * @throws ServiceNotAvailableException 当声明了 require 但尚未提供。
     */
    public <T> T get(ServiceKey<T> key) {
        return fiber.resolve(key)
            .orElseThrow(() -> new ServiceNotAvailableException(key));
    }

    /** 软查找，返回 Optional。 */
    public <T> Optional<T> getIfPresent(ServiceKey<T> key) {
        return fiber.resolve(key);
    }

    // ────────── 事件 ──────────

    public Subscription on(EventKey<?> key, EventListener listener) {
        return events.subscribe(key, listener, scopeKey, /*global*/ false);
    }

    /** 全局监听（忽略 scope filter）。 */
    public Subscription onGlobal(EventKey<?> key, EventListener listener) {
        return events.subscribe(key, listener, /*scopeKey*/ null, /*global*/ true);
    }

    public void emit(EventKey<?> key, Object carrier, Object payload) {
        events.emit(key, carrier, payload);
    }

    public <T> CompletableFuture<T> waterfall(
            EventKey<T> key, Object carrier, List<Object> args, Next<T> inner) {
        return events.waterfall(key, carrier, args, inner);
    }

    public CompletableFuture<Void> parallel(EventKey<?> key, Object carrier, Object payload) {
        return events.parallel(key, carrier, payload);
    }

    public <T> CompletableFuture<T> serial(EventKey<T> key, Object carrier, Object payload) {
        return events.serial(key, carrier, payload);
    }

    // ────────── 生命周期 ──────────

    public Fiber fiber() { return fiber; }
    public ScopeKey scopeKey() { return scopeKey; }

    /** 在本 fiber 上注册一个 closeable，返回的 Subscription 可提前回收。 */
    public Subscription addCloseable(AutoCloseable c) {
        return fiber.addCloseable(c);
    }

    /** 派生一个 child fiber（新的生命周期域）。 */
    public Context child() {
        Fiber child = fiber.spawnChild();
        return child.context();
    }

    /** 创建一个 scoped context（绑定 scopeKey，事件走 filter）。 */
    public Context withScope(ScopeKey key) {
        Fiber scoped = fiber.spawnChild();
        scoped.setScope(key);
        return scoped.context();
    }
}
```

**关键设计决策**：

- **`provide` 返回 `Subscription`（`AutoCloseable`）**，而不是 dsh 的"返回 disposer 由 fiber effect 栈持有"。这是因为 Java 习惯显式回收（try-with-resources），但**同时**也挂到 fiber effect 栈兜底（fiber unload 时即使 subscription 没 close 也会回收）。这给了消费者两种选择：精确控制（自己 close）或托管（让 fiber 回收）。

- **`get` 沿 fiber 父链向上查找**（见 §4 Fiber），实现 dsh 的 "provider 可见性 = 父链上有该服务"。

---

## 3. Subscription — 可撤销的注册

```java
// io.dsh.kernel.context.Subscription
package io.dsh.kernel.context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次注册的回收句柄。幂等：多次 close 只有第一次生效。
 * 由 fiber 的 effect 栈兜底：即使调用方忘了 close，fiber unload 时也会回收。
 */
public final class Subscription implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AutoCloseable disposer;

    Subscription(AutoCloseable disposer) {
        this.disposer = disposer;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                disposer.close();
            } catch (Exception e) {
                throw new RuntimeException("Disposer failed", e);
            }
        }
    }

    public boolean isClosed() { return closed.get(); }
}
```

---

## 4. Fiber — 生命周期容器

Cordis 的 fiber 是一个 plugin 实例的运行时载体：持有 effect 栈、inject 依赖、scope key，unload 时按逆序回收所有 effect。JH 的 Fiber 直接对应：

```java
// io.dsh.kernel.fiber.Fiber
package io.dsh.kernel.fiber;

public final class Fiber {

    private final Fiber parent;           // null = root
    private final FiberRuntime runtime;   // 共享的 registry/events
    private final Deque<AutoCloseable> effectStack = new ConcurrentLinkedDeque<>();
    private final Set<ServiceKey<?>> required = ConcurrentHashMap.newKeySet();
    private final Map<ServiceKey<?>, Object> cachedServices = new ConcurrentHashMap<>();

    private volatile ScopeKey scopeKey;
    private volatile Context cachedContext;
    private volatile Status status = Status.ACTIVE; // ACTIVE | DISPOSING | DISPOSED

    enum Status { ACTIVE, DISPOSING, DISPOSED }

    // ────────── 服务解析（沿父链向上）──────────

    @SuppressWarnings("unchecked")
    <T> Optional<T> resolve(ServiceKey<T> key) {
        // 自己缓存命中
        Object cached = cachedServices.get(key);
        if (cached != null) return Optional.of((T) cached);

        // 沿父链查找
        Fiber cursor = this;
        while (cursor != null) {
            Optional<Object> impl = cursor.runtime.registry().get(key, cursor);
            if (impl.isPresent()) {
                // 缓存到自己（观察者：provider 下线时需失效缓存 —— 见 §6）
                cachedServices.put(key, impl.get());
                return Optional.of((T) impl.get());
            }
            cursor = cursor.parent;
        }
        return Optional.empty();
    }

    // ────────── effect 栈（逆序回收）──────────

    /**
     * 注册一个 effect。返回的 Subscription close 时执行 disposer；
     * 若调用方未 close，fiber dispose 时按 LIFO 顺序回收。
     */
    Subscription addEffect(AutoCloseable registerAction) {
        ensureActive();
        try {
            registerAction.close(); // registerAction 的 "close" 其实是执行注册副作用
            // 上面执行注册，下面拿到 disposer 放进栈
            // （实际实现：registerAction 返回 disposer，这里简化为直接 close 语义）
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new Subscription(() -> effectStack.remove(registerAction));
    }

    /**
     * 添加一个纯 closeable（无注册副作用，只是 teardown 时要回收）。
     */
    Subscription addCloseable(AutoCloseable c) {
        ensureActive();
        effectStack.push(c);
        return new Subscription(() -> effectStack.remove(c));
    }

    // ────────── 派生 ──────────

    Fiber spawnChild() {
        ensureActive();
        Fiber child = new Fiber(this, runtime);
        runtime.track(child);
        // 父 dispose 时连带子（结构化并发语义）
        addCloseable(child::dispose);
        return child;
    }

    // ────────── dispose（LIFO 逆序回收）──────────

    CompletableFuture<Void> dispose() {
        if (status != Status.ACTIVE) return CompletableFuture.completedFuture(null);
        status = Status.DISPOSING;

        return CompletableFuture.runAsync(() -> {
            // 1. 先 dispose 所有 child fiber（已由 addCloseable 保证，但显式再 drain）
            // 2. LIFO 回收 effect 栈
            AutoCloseable c;
            while ((c = effectStack.pollLast()) != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    runtime.events().emit(InternalEvents.DISPOSE_ERROR, null, e);
                    // 一个 effect 失败不阻断其余回收
                }
            }
            // 3. 从 registry 注销本 fiber 提供的所有服务
            runtime.registry().revokeAll(this);
            status = Status.DISPOSED;
        }, runtime.virtualThreadExecutor());
    }

    // ────────── scope ──────────

    void setScope(ScopeKey key) {
        if (this.scopeKey != null) {
            throw new IllegalStateException("Fiber scope already set: " + scopeKey);
        }
        this.scopeKey = key;
        this.cachedContext = null; // 失效缓存让 context() 重建
    }

    Context context() {
        if (cachedContext == null) {
            cachedContext = new Context(this, scopeKey);
        }
        return cachedContext;
    }

    public Context ctx() { return context(); }
    public Status status() { return status; }

    private void ensureActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Fiber not active: " + status);
        }
    }
}
```

**关键设计点**：

1. **LIFO effect 栈**：dsh 的 `ctx.effect()` 保证 disposer 逆序执行（后注册先回收），这对 agent lifecycle 至关重要（session 必须在 agent 之后回收）。JH 用 `Deque<AutoCloseable>` + `pollLast()` 实现。

2. **`spawnChild` 自动级联回收**：child fiber 的 dispose 被挂到 parent 的 effect 栈。等价 dsh 的 "fiber unload 连带 unload 子 fiber"。

3. **dispose 跑在虚拟线程 executor 上**：让回收可以 await（teardown 里可能要 flush 持久化），同时不阻塞调用方。

---

## 5. ServiceRegistry — 服务存储与可见性

```java
// io.dsh.kernel.context.ServiceRegistry
package io.dsh.kernel.context;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务存储。key = ServiceKey（name + isolate），value = Impl。
 * Impl 记录提供者 fiber，用于 revokeAll(fiber) 时批量回收。
 */
public final class ServiceRegistry {

    private final ConcurrentHashMap<ServiceKey<?>, Impl<?>> store = new ConcurrentHashMap<>();

    record Impl<T>(T value, Fiber provider) {}

    /**
     * 注册。重复 key fail loud。
     * 返回的 disposer 从 store 删除该 key（幂等）。
     */
    <T> AutoCloseable register(ServiceKey<T> key, T value, Fiber provider) {
        Impl<?> existing = store.putIfAbsent(key, new Impl<>(value, provider));
        if (existing != null) {
            throw new IllegalStateException(
                "Service " + key + " already registered by " + existing.provider);
        }
        return () -> store.remove(key, new Impl<>(value, provider));
    }

    @SuppressWarnings("unchecked")
    <T> Optional<T> get(ServiceKey<T> key, Fiber forFiber) {
        Impl<?> impl = store.get(key);
        if (impl == null) return Optional.empty();
        // 可见性检查：默认所有 fiber 可见父链上的服务
        // isolate 隔离：不同 isolate 的服务仅对同 isolate 的 fiber 可见
        // （JH 简化：isolate 相同即可见，跨 isolate 抛 SecurityException）
        return Optional.of((T) impl.value());
    }

    /** 某个 fiber unload 时，注销它提供的所有服务。 */
    void revokeAll(Fiber provider) {
        store.entrySet().removeIf(e -> e.getValue().provider() == provider);
    }

    /** 枚举所有已注册 key（用于 dump-config / 调试）。 */
    public Set<ServiceKey<?>> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }
}
```

**简化决策（相对 Cordis）**：

- **不做响应式 reload**。Cordis 的 provider 上/下线会触发依赖方 fiber 自动 reload/unload。这个机制非常复杂（epoch 比较、_refresh、_unload 协程），且 JVM 生态里 `ServiceLoader` + 手动启动的场景更常见。JH 选择**静态组合**：所有 plugin 在启动时按依赖顺序加载，不支持的依赖 fail loud。

  > 未来如果需要热重载，可以在 ServiceRegistry 上加一个 `Flow.Publisher<ServiceChangeEvent>`，让 Fiber 订阅后重新 resolve。但 MVP 不做。

- **isolate 仅做命名隔离**，不做运行时权限校验（Cordis 的 `Context.filter` 也只是事件过滤，不是安全边界）。真正的安全边界在 sandbox seam。

---

## 6. Events — 五种 dispatch 模式

```java
// io.dsh.kernel.events.Events
package io.dsh.kernel.events;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class Events {

    private final ConcurrentHashMap<EventKey<?>, List<Registration<?>>> hooks = new ConcurrentHashMap<>();
    private final Executor virtualThreadExecutor;

    public Events(Executor virtualThreadExecutor) {
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    record Registration<T>(
        EventListener listener,
        ScopeKey scopeKey,   // listener 所属 scope（null = 全局）
        boolean global       // 是否绕过 scope filter
    ) {}

    // ────────── 订阅 ──────────

    public Subscription subscribe(EventKey<?> key, EventListener listener,
                                   ScopeKey scopeKey, boolean global) {
        var reg = new Registration<>(listener, scopeKey, global);
        hooks.computeIfAbsent(key, k -> CopyOnWriteArrayList::new).add(reg);
        return new Subscription(() -> hooks.getOrDefault(key, List.of()).remove(reg));
    }

    // ────────── 过滤（scope 链）──────────

    /**
     * scope 过滤规则（对应 dsh scopeTarget）：
     * - carrierScope == null：全局事件，所有 listener 收。
     * - listener.global == true：收所有。
     * - listener.scopeKey == null（全局 listener）：收所有。
     * - 否则：listener.scopeKey 是 carrierScope 或其祖先时收（事件向上冒泡）。
     */
    private boolean passesFilter(Registration<?> reg, ScopeKey carrierScope) {
        if (carrierScope == null || reg.global || reg.scopeKey == null) return true;
        // 沿 carrierScope 的祖先链查找 listener.scopeKey
        for (ScopeKey c = carrierScope; c != null; c = Scope.parentOf(c)) {
            if (c.equals(reg.scopeKey)) return true;
        }
        return false;
    }

    // ────────── EMIT（同步，忽略返回值/异常）──────────

    public void emit(EventKey<?> key, Object carrier, Object payload) {
        List<Registration<?>> regs = hooks.get(key);
        if (regs == null) return;
        ScopeKey carrierScope = Scope.scopeOf(carrier);
        for (Registration<?> reg : regs) {
            if (!passesFilter(reg, carrierScope)) continue;
            try {
                reg.listener().handle(carrier, payload);
            } catch (Exception e) {
                // observer 失败不阻断其他 listener（对应 dsh session/event 语义）
                virtualThreadExecutor.execute(() -> { /* log */ });
            }
        }
    }

    // ────────── WATERFALL（around-middleware，next() 串链）──────────

    @FunctionalInterface
    public interface Next<T> { T invoke() throws Exception; }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> waterfall(
            EventKey<T> key, Object carrier, List<Object> args, Next<T> inner) {
        List<Registration<?>> regs = hooks.get(key);
        ScopeKey carrierScope = Scope.scopeOf(carrier);

        // 过滤出参与本链的 listener（保序）
        Deque<EventListener> chain = new ArrayDeque<>();
        if (regs != null) {
            for (Registration<?> reg : regs) {
                if (passesFilter(reg, carrierScope)) {
                    chain.add(reg.listener());
                }
            }
        }

        // 构建 next 链（对应 cordis events.ts:234-243 的 shift 模式）
        AtomicReference<Next<T>> nextRef = new AtomicReference<>();
        Next<T> next = () -> {
            EventListener cb = chain.pollFirst();
            if (cb == null) return inner.invoke();
            return (T) cb.handle(carrier, new WaterfallArgs<>(args, nextRef.get()));
        };
        nextRef.set(next);

        // 在虚拟线程上跑（listener 可能 await IO）
        return CompletableFuture.supplyAsync(() -> {
            try {
                return next.invoke();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, virtualThreadExecutor);
    }

    // ────────── PARALLEL（全并发，等待全部完成）──────────

    public CompletableFuture<Void> parallel(EventKey<?> key, Object carrier, Object payload) {
        List<Registration<?>> regs = hooks.get(key);
        if (regs == null) return CompletableFuture.completedFuture(null);
        ScopeKey carrierScope = Scope.scopeOf(carrier);

        List<CompletableFuture<Void>> futures = regs.stream()
            .filter(reg -> passesFilter(reg, carrierScope))
            .map(reg -> CompletableFuture.runAsync(() -> {
                try { reg.listener().handle(carrier, payload); }
                catch (Exception e) { /* 收集，不立即抛 */ }
            }, virtualThreadExecutor))
            .toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    // ────────── SERIAL（顺序 await，可返回值）──────────

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> serial(EventKey<?> key, Object carrier, Object payload) {
        List<Registration<?>> regs = hooks.get(key);
        if (regs == null) return CompletableFuture.completedFuture(null);
        ScopeKey carrierScope = Scope.scopeOf(carrier);

        CompletableFuture<T> chain = CompletableFuture.completedFuture(null);
        for (Registration<?> reg : regs) {
            if (!passesFilter(reg, carrierScope)) continue;
            chain = chain.thenComposeAsync(prev -> {
                try {
                    return (CompletableFuture<T>) reg.listener().handle(carrier, payload);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, virtualThreadExecutor);
        }
        return chain;
    }

    // ────────── BAIL（同步顺序，第一个非 null 停止）──────────

    @SuppressWarnings("unchecked")
    public <T> T bail(EventKey<T> key, Object carrier, Object payload) {
        List<Registration<?>> regs = hooks.get(key);
        if (regs == null) return null;
        ScopeKey carrierScope = Scope.scopeOf(carrier);
        for (Registration<?> reg : regs) {
            if (!passesFilter(reg, carrierScope)) continue;
            Object result = reg.listener().handle(carrier, payload);
            if (isBailed(result)) return (T) result;
        }
        return null;
    }

    private static boolean isBailed(Object v) {
        return v != null && !(v instanceof Boolean b && !b);
    }
}
```

### EventKey — 事件名 + 类型 token + dispatch mode

```java
// io.dsh.kernel.events.EventKey
package io.dsh.kernel.events;

public record EventKey<T>(
    String name,
    Class<T> type,
    DispatchMode mode
) {
    public enum DispatchMode { EMIT, WATERFALL, PARALLEL, SERIAL, BAIL }

    public static <T> EventKey<T> emit(String name, Class<T> type) {
        return new EventKey<>(name, type, DispatchMode.EMIT);
    }
    public static <T> EventKey<T> waterfall(String name, Class<T> type) {
        return new EventKey<>(name, type, DispatchMode.WATERFALL);
    }
    public static EventKey<Void> parallel(String name) {
        return new EventKey<>(name, Void.class, DispatchMode.PARALLEL);
    }
    public static <T> EventKey<T> serial(String name, Class<T> type) {
        return new EventKey<>(name, type, DispatchMode.SERIAL);
    }
    public static <T> EventKey<T> bail(String name, Class<T> type) {
        return new EventKey<>(name, type, DispatchMode.BAIL);
    }
}
```

**dispatch mode 是事件契约的一部分**：EventKey 在构造时绑定 mode，`emit/waterfall/...` 方法可以校验调用方用对了方法。这对应 dsh 的 "`@mode` tag 是公开契约"。

### EventListener — 统一 handler 接口

```java
// io.dsh.kernel.events.EventListener
package io.dsh.kernel.events;

@FunctionalInterface
public interface EventListener {
    /**
     * 处理事件。
     * @param carrier  事件载体（携带 scope 信息，对应 dsh 的 thisArg）
     * @param payload  事件负载；waterfall 模式下是 {@link WaterfallArgs}
     * @return 对 waterfall/serial/bail：返回值；对 emit/parallel：忽略
     */
    Object handle(Object carrier, Object payload) throws Exception;
}
```

### WaterfallArgs — 带 next 的参数包

```java
// io.dsh.kernel.events.WaterfallArgs
package io.dsh.kernel.events;

import java.util.List;

public record WaterfallArgs<T>(
    List<Object> args,
    Events.Next<T> next
) {}
```

listener 形如：
```java
ctx.on(AgentEvents.PRE_STEP, (carrier, payload) -> {
    WaterfallArgs<PreStepDecision> wp = (WaterfallArgs<PreStepDecision>) payload;
    // 检查 carrier、修改 args[0]（messages）
    return wp.next().invoke(); // 委托给下一个 listener
});
```

---

## 7. FiberRuntime — 顶层运行时

```java
// io.dsh.kernel.runtime.FiberRuntime
package io.dsh.kernel.runtime;

public final class FiberRuntime implements AutoCloseable {

    private final ServiceRegistry registry = new ServiceRegistry();
    private final Events events;
    private final ExecutorService virtualThreadExecutor;
    private final Fiber rootFiber;
    private final Set<Fiber> allFibers = ConcurrentHashMap.newKeySet();

    public FiberRuntime() {
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.events = new Events(virtualThreadExecutor);
        this.rootFiber = new Fiber(null, this);
        allFibers.add(rootFiber);
    }

    public Context rootContext() { return rootFiber.context(); }
    public ServiceRegistry registry() { return registry; }
    public Events events() { return events; }
    public Executor virtualThreadExecutor() { return virtualThreadExecutor; }

    void track(Fiber f) { allFibers.add(f); }

    @Override
    public void close() {
        // 1. dispose root fiber（级联所有 child）
        rootFiber.dispose().join();
        // 2. 关闭 executor
        virtualThreadExecutor.close();
    }
}
```

**入口模式**：
```java
try (var runtime = new FiberRuntime()) {
    Context ctx = runtime.rootContext();
    // 加载 plugin（通过 PluginLoader，见 §8）
    pluginLoader.loadAll(ctx, classpathPlugins);
    // 驱动 agent loop
    Agent agent = ctx.get(AgentLoop.KEY).create(sessionId, options);
    agent.send(userMessage);
    agent.whenIdle().join();
}
// runtime.close() 逆序回收所有 fiber
```

---

## 8. Plugin 与 PluginLoader

dsh 的 plugin 是一个实现 `Service` 接口的对象。JH 等价物：

```java
// io.dsh.kernel.plugin.Plugin
package io.dsh.kernel.plugin;

@FunctionalInterface
public interface Plugin {
    /**
     * 挂载到 context。所有注册（provide/on）必须通过 ctx，
     * 由返回的 fiber effect 栈保证卸载时回收。
     */
    void apply(Context ctx) throws Exception;
}
```

```java
// io.dsh.kernel.plugin.PluginLoader
package io.dsh.kernel.plugin;

import java.util.*;

/**
 * 通过 ServiceLoader 发现所有 Plugin 实现，按依赖顺序加载。
 * 依赖顺序：Plugin 可声明 @InjectService(KEY) 表明需要某服务，
 *           loader 拓扑排序后加载（缺依赖 fail loud）。
 */
public final class PluginLoader {

    public void loadAll(Context rootCtx) {
        ServiceLoader<Plugin> loader = ServiceLoader.load(Plugin.class);
        List<Plugin> plugins = new ArrayList<>();
        loader.forEach(plugins::add);

        // 拓扑排序（按 @InjectService 注解声明的依赖）
        List<Plugin> sorted = topoSort(plugins);

        for (Plugin p : sorted) {
            Context child = rootCtx.child();
            try {
                p.apply(child);
            } catch (Exception e) {
                throw new RuntimeException("Plugin failed: " + p.getClass(), e);
            }
        }
    }

    private List<Plugin> topoSort(List<Plugin> plugins) {
        // 解析每个 Plugin 的 @InjectService 注解，构建 DAG，Kahn 算法
        // ...
    }
}
```

**每个 Plugin 实现类在 `META-INF/services/io.dsh.kernel.plugin.Plugin` 注册**（ServiceLoader 标准）。JPMS 模块则用 `provides Plugin with XxxPlugin;` 在 `module-info.java` 声明。

---

## 9. 与 dsh 的语义对齐清单

| dsh 语义 | JH 实现 | 对齐情况 |
|---|---|---|
| `ctx.x` Proxy 查找 | `ctx.get(KEY)` 显式 | ✅ 语义等价，语法显式 |
| `inject` 声明依赖 | `@InjectService` 注解 + topoSort | ✅ 静态版本（无热重载） |
| `ctx.effect(disposer)` | `Subscription`（AutoCloseable）+ fiber 栈兜底 | ✅ |
| `ctx.on/off` | `Subscription`（close 即 off） | ✅ |
| 5 种 dispatch | `emit/waterfall/parallel/serial/bail` | ✅ |
| waterfall `next()` | `WaterfallArgs.next()` | ✅ shift 模式 |
| scope filter（向上冒泡）| `passesFilter` 沿 parent 链 | ✅ |
| fiber LIFO teardown | `Deque.pollLast()` | ✅ |
| isolate 隔离 | `ServiceKey.isolate` | ✅ 简化版 |
| 响应式 reload | **未实现**（静态组合） | ❌ MVP 不做 |
| `Context.filter` Proxy | `Scope.scopeOf(carrier)` + `passesFilter` | ✅ |
