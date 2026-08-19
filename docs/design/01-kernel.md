# 01 · Kernel — Scope / Events / Plugin

Kernel 是 Cordis 思想的 JVM 等价物——**思想照搬，形状不照搬**。它提供三个原语：

1. **`Scope`** —— 唯一的生命周期 + 可见性容器：LIFO effect 栈、服务 overlay、事件订阅归属、父子级联。
2. **`Events`** —— 两种 dispatch 模式（notify / waterfall），其余派发形态是这两种之上的工具方法。
3. **`Plugin`** —— 带稳定 id 的挂载单元，`apply(Scope)` 里的一切注册都是可回收的 effect。

目标行数：**整个 kernel < 1200 行 Java**（不含测试）。

## 0. 与 Cordis 形状的三处刻意偏离

Cordis 的 `fiber` / `scope` / `context` 三套概念是 TS 生态的历史产物。JH 不复制这个形状：

| Cordis 形状 | JH 形状 | 理由 |
|---|---|---|
| Fiber（生命周期）+ ScopeKey（可见性标签）+ Context（facade）三套概念 | 一个 `Scope` 同时是生命周期容器和可见性边界 | 三个概念在 Cordis 里本就纠缠；Java 的 try-with-resources + 级联 close 让一个对象足够。概念减半，语义不丢：per-agent 隔离 = 子 scope overlay |
| 五种 dispatch（emit/waterfall/parallel/serial/bail）| 两种模式（NOTIFY / WATERFALL）+ 工具方法 | serial = 顺序 notify；parallel = 并发 notify + join；bail = waterfall 且 listener 可短路。模式进类型、形态进工具，消灭"mode × 方法配对"整类错误 |
| fiber 缓存父链服务 + provider 下线时全树 evict | 每次访问沿 scope 链重新解析 | 一次 map 查找的成本，换来**吊销后不可能拿到僵尸引用**（不变式 R3 结构性成立），整个缓存失效机器删除 |

另有两处收缩：`ServiceKey` 无 `isolate` 字段（隔离由子 scope 承担，两套隔离机制留一套）；不做运行时热重载（静态组合，缺失依赖 fail loud）。

## 1. 模块定位：单一 JPMS 模块

kernel 同属一个 JPMS 模块 `io.javanatic.harness.kernel`（Maven `harness-kernel-core`），三个导出包：

```
kernel/core/
  └── io.javanatic.harness.kernel.scope    (Scope, Runtime, ServiceKey, Disposable)
      io.javanatic.harness.kernel.events   (Events, EventKey, 监听器接口, WaterfallArgs, Next)
      io.javanatic.harness.kernel.plugin   (Plugin, PluginLoader)
```

**为什么不再按概念拆模块**：`Scope` 的方法签名同时引用 Events（订阅）与 Plugin（加载）——按概念拆模块必然成环，JPMS 禁止模块互 `requires`。一个模块 + 多个导出包保留包级组织与 `exports` 边界（内部实现放非导出包）。`kernel.brand`（`Id<T>`）与 `kernel.config`（`ConfigService`）因零依赖独立成模块。

JPMS 根名统一为 `io.javanatic.harness.*`（全仓库约定，见 [02](02-module-layout.md)）。

## 2. ServiceKey — 类型品牌的服务标识

```java
// io.javanatic.harness.kernel.scope.ServiceKey
/**
 * 类型品牌的服务标识。结构上只有 name，类型上是 T 的载体。
 * 泛型 T 仅供编译器在 provide / resolve 处做类型检查，运行时不参与 equals（erasure）。
 *
 * key 是共享常量：由 seam 的 Definition 模块持有唯一 public static final 实例，
 * Provider 与 Consumer 都 import 该常量，不各自 new。name 拼写错误的出错面
 * 因此收敛到 Definition 一处。
 */
public record ServiceKey<T>(String name) {
    @Override
    public String toString() { return name; }
}
```

**为什么不用 `Class<T>` 做 key**：一个 Service 接口常有多个实现方共存注册（tool registry 的多个注册器），`Class<T>` 把 key 和类型绑死。`ServiceKey<T>` 解耦 name 与类型，由 `provide` 调用方保证 `T` 与 impl 匹配。

**为什么没有 isolate**：Cordis 用 isolate label 做 per-realm 命名隔离。JH 的子 scope overlay 已经表达"一个 agent 挂载的服务对其他 agent 不可见"（子 scope 注册的服务只在子树内可解析）。两套隔离机制留一套，留 scope 这套——它同时管生命周期。

## 3. Scope — 生命周期 + 可见性容器

```java
// io.javanatic.harness.kernel.scope.Scope
package io.javanatic.harness.kernel.scope;

import io.javanatic.harness.kernel.events.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 唯一的生命周期 + 可见性容器。
 *
 * 生命周期：effect 栈按 LIFO 回收（后注册先回收），close 级联子 scope。
 * 可见性：服务沿父链向上解析；本 scope 注册的同名服务覆盖（shadow）父级。
 * 事件：scope.events() 返回绑定本 scope 的订阅视图，订阅随 scope 回收。
 *
 * 插件在自己的 scope（通常是 Runtime 为它建的子 scope）上注册一切；
 * 不持有全局静态状态是插件的义务（见 §8 R3）。
 */
public interface Scope extends AutoCloseable {

    // ────────── 服务 ──────────

    /**
     * 注册服务到本 scope。本 scope 内重复注册同 key 抛 IllegalStateException；
     * 子 scope 注册同 key 覆盖父级（overlay/shadow，preset 组合用它）。
     * 注册即 effect：返回的 Disposable close 即注销；
     * 调用方不 close 时，scope close 按 LIFO 兜底回收。
     */
    <T> Disposable provide(ServiceKey<T> key, T impl);

    /** 沿父链向上查找服务；本 scope 起查。 */
    <T> Optional<T> resolve(ServiceKey<T> key);

    /** resolve 的 fail-loud 版：沿链无提供者时抛 ServiceNotAvailableException。 */
    <T> T require(ServiceKey<T> key);

    // ────────── 生命周期 ──────────

    /**
     * 注册一个 effect：register() 执行注册副作用并返回回收器，回收器入栈。
     * register 抛异常则注册失败、无回收器入栈（原子性）。
     */
    Disposable effect(Effect effect);

    /** 纯 teardown 挂载（无注册副作用）。等价于 effect(() -> c)。 */
    Disposable onClose(AutoCloseable c);

    /** 派生子 scope：新的生命周期域 + 服务 overlay 层。父 close 级联子。 */
    Scope child();

    /** 本 scope 在父链中的位置（root 返回 null，链到此终止）。事件冒泡与 overlay 以此为据。 */
    Scope parent();

    /** 绑定本 scope 的事件订阅视图（订阅随 scope 回收，见 §5）。 */
    ScopedEvents events();

    /** LIFO 回收本 scope 全部 effect，并级联所有子 scope。幂等。 */
    @Override
    void close();

    boolean isClosed();
}

/** 注册副作用与回收器的分离：register 的返回值才是 effect 栈持有并回收的东西。 */
@FunctionalInterface
public interface Effect {
    AutoCloseable register() throws Exception;
}
```

```java
// io.javanatic.harness.kernel.scope.ScopeImpl —— 实现要点
final class ScopeImpl implements Scope {

    private final ScopeImpl parent;                 // null = root
    private final Runtime runtime;                  // 共享的 events bus + executor
    private final ConcurrentHashMap<ServiceKey<?>, Object> services = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<AutoCloseable> effectStack = new ConcurrentLinkedDeque<>();
    private final Set<ScopeImpl> children = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private volatile ScopedEvents eventsView;       // 惰性创建

    enum Status { ACTIVE, CLOSED }

    // 解析：每次访问沿链重查——不缓存。
    // 这不是性能妥协，是 R3 的结构性保证：provider scope close 即注销，
    // 任何后续 resolve 都查不到，僵尸引用在结构上不存在。
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolve(ServiceKey<T> key) {
        ScopeImpl cursor = this;
        while (cursor != null) {
            Object impl = cursor.services.get(key);
            if (impl != null) return Optional.of((T) impl);
            cursor = cursor.parent;
        }
        return Optional.empty();
    }

    public <T> Disposable provide(ServiceKey<T> key, T impl) {
        ensureActive();
        Object prev = services.putIfAbsent(key, impl);
        if (prev != null) {
            throw new IllegalStateException("Service " + key + " already registered in this scope");
        }
        return effect(() -> () -> services.remove(key, impl));
    }

    public Scope child() {
        ensureActive();
        ScopeImpl child = new ScopeImpl(this, runtime);
        effectStack.addLast((AutoCloseable) child::close);   // 父 close 级联子（栈上一个 entry）
        return child;
    }

    // close：先本 scope effect 逆序回收，再级联子 scope（子先于父的 effect 回收
    // 之外的部分；级联本身也是栈上一个 effect，天然排在子 scope 创建之后注册，
    // LIFO 保证子 scope 先于父级更早的 effect 回收——session 先于 agent 的
    // teardown 顺序由此成立，详见 09 §teardown）。
    @Override
    public void close() {
        if (!status.compareAndSet(Status.ACTIVE, Status.CLOSED)) return;
        AutoCloseable c;
        while ((c = effectStack.pollLast()) != null) {
            try {
                c.close();
            } catch (Exception e) {
                // 一个 effect 失败不阻断其余回收；kernel 日志记录
                runtime.logDisposeError(this, e);
            }
        }
    }
}
```

**关键设计点**：

1. **LIFO effect 栈**：后注册先回收。子 scope 的级联 close 是父栈上的一个 entry，位于其后注册的所有 effect 之前回收——"插件 A 依赖插件 B，则 B 先于 A 卸载"由加载顺序 + LIFO 自动给出。
2. **close 幂等且防并发双 drain**：CAS 状态位保证至多一次完整回收；`Disposable.close()` 与 `Scope.close()` 竞争同一条目时，栈的原子摘除保证回收器至多执行一次。
3. **子 scope 可单独 close**：这既是 preset/agent 的隔离原语，也是插件加载失败时的**回滚原语**（见 §7 R3）。

## 4. Disposable — 可撤销的注册

```java
// io.javanatic.harness.kernel.scope.Disposable
/**
 * 一次注册的回收句柄。幂等：多次 close 只有第一次生效。
 * close 先从 scope effect 栈摘除，再执行回收器——顺序保证即使回收器抛异常，
 * 该条目也不会被 scope close 二次回收。
 */
public final class Disposable implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AutoCloseable disposer;
    private final Runnable removeFromStack;

    Disposable(AutoCloseable disposer, Runnable removeFromStack) {
        this.disposer = disposer;
        this.removeFromStack = removeFromStack;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            removeFromStack.run();
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

## 5. Events — 两种模式 + 工具方法

### 监听器接口

```java
// io.javanatic.harness.kernel.events —— 监听器接口族
package io.javanatic.harness.kernel.events;

/** NOTIFY 监听器。handle 允许阻塞（派发在虚拟线程上），返回即完成。 */
@FunctionalInterface
public interface EventListener<T> {
    void handle(Object carrier, T payload) throws Exception;
}

/** WATERFALL 监听器：包一层 WaterfallArgs（args + next），返回值沿链上传。 */
@FunctionalInterface
public interface WaterfallListener<T> {
    T handle(Object carrier, WaterfallArgs<T> args) throws Exception;
}

/**
 * waterfall 的 next：委托给链上的下一个 listener。
 * 零参调用直传当前 args；带参调用以新 args 下传（args 本身不可变，改写即换值）。
 * 注意：调用点写单参 lambda（`none -> …`）——javac 25 拒绝零参隐式 lambda
 * 目标 varargs 抽象方法。
 */
@FunctionalInterface
public interface Next<T> {
    T invoke(Object... overrideArgs) throws Exception;
}

/**
 * waterfall 参数包：args 不可变，listener 经 next(新值...) 改写下传。
 * 组件名是 rest（不是 next）：record 组件 next 的访问器 next() 会与委托方法
 * 同名冲突，next(Object...) 显式定义为委托方法。
 */
public record WaterfallArgs<T>(List<Object> args, Next<T> rest) {
    /** 委托尾链：零参直传当前 args，带参改写。每个 rest 只允许调用一次。 */
    public T next(Object... overrideArgs) throws Exception {
        return rest.invoke(overrideArgs);
    }
}
```

### EventKey — 事件名 + 类型 token + 模式

```java
// io.javanatic.harness.kernel.events.EventKey
public record EventKey<T>(String name, Class<T> type, Mode mode) {

    /** 只有两种模式；serial/parallel/bail 形态由 Events 的工具方法表达。 */
    public enum Mode { NOTIFY, WATERFALL }

    public static <T> EventKey<T> notify(String name, Class<T> type) {
        return new EventKey<>(name, type, Mode.NOTIFY);
    }
    public static <T> EventKey<T> waterfall(String name, Class<T> type) {
        return new EventKey<>(name, type, Mode.WATERFALL);
    }

    /** 模式契约校验：订阅/派发方法与 key 绑定的 mode 不符时 fail loud。 */
    void requireMode(Mode expected) {
        if (mode != expected) {
            throw new IllegalStateException(
                "Event " + name + " is " + mode + ", expected " + expected);
        }
    }
}
```

### Events — 订阅 + 派发

```java
// io.javanatic.harness.kernel.events.Events
package io.javanatic.harness.kernel.events;

public final class Events {

    private static final System.Logger LOG = System.getLogger("io.javanatic.harness.kernel.events");

    private final ConcurrentHashMap<EventKey<?>, List<Registration<?>>> hooks = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreads;

    record Registration<T>(Object listener, Scope scope /* null = 全局 */) {}

    // ────────── 订阅（经 ScopedEvents 视图调用，订阅随 scope 回收）──────────

    // 两个类型化入口而非 instanceof 猜测：listener 形参类型即模式依据
    <T> AutoCloseable subscribeNotify(EventKey<T> key, EventListener<? super T> listener, Scope bound) {
        key.requireMode(Mode.NOTIFY);
        return addRegistration(key, listener, bound);
    }

    <T> AutoCloseable subscribeWaterfall(EventKey<T> key, WaterfallListener<? super T> listener, Scope bound) {
        key.requireMode(Mode.WATERFALL);
        return addRegistration(key, listener, bound);
    }

    private AutoCloseable addRegistration(EventKey<?> key, Object listener, Scope bound) {
        Registration<?> reg = new Registration<>(listener, bound);
        hooks.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(reg);
        return () -> hooks.getOrDefault(key, List.of()).remove(reg);
    }

    // ────────── scope 过滤（事件向上冒泡）──────────

    /**
     * listener 在 scope S 订阅，收到从 S 或 S 的任何后代 scope 派发的事件；
     * scope == null 的全局订阅收到一切。对应 dsh 的 scopeTarget 向上冒泡。
     */
    private boolean passesFilter(Registration<?> reg, Scope origin) {
        if (reg.scope() == null) return true;
        for (Scope c = origin; c != null; c = c.parent()) {
            if (c.equals(reg.scope())) return true;
        }
        return false;
    }

    // ────────── NOTIFY：notify（尽力而为）/ notifyOrdered（顺序传播）/ notifyAndWait（并发 join）──────────

    /** 每个 listener 各跑一个虚拟线程，fire-and-forget；异常记录日志不传播。 */
    public <T> void notify(EventKey<T> key, Scope origin, Object carrier, T payload) {
        key.requireMode(Mode.NOTIFY);
        for (Registration<?> reg : hooks.getOrDefault(key, List.of())) {
            if (!passesFilter(reg, origin)) continue;
            virtualThreads.submit(() -> invokeNotify(reg, carrier, payload));
        }
    }

    /** 顺序派发：listener 依次在调用方线程执行，任一异常停止派发并传播。 */
    public <T> void notifyOrdered(EventKey<T> key, Scope origin, Object carrier, T payload) {
        key.requireMode(Mode.NOTIFY);
        for (Registration<?> reg : hooks.getOrDefault(key, List.of())) {
            if (!passesFilter(reg, origin)) continue;
            invokeNotify(reg, carrier, payload);   // 异常直接传播
        }
    }

    /** 并发派发并 join 全部完成（持久化 flush barrier 用）；单 listener 失败记日志。 */
    public <T> CompletableFuture<Void> notifyAndWait(EventKey<T> key, Scope origin, Object carrier, T payload) {
        key.requireMode(Mode.NOTIFY);
        List<CompletableFuture<Void>> futures = /* filter 后逐个 submit，同 notify */;
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    // ────────── WATERFALL：waterfall（中间件链）/ firstOf（查询）──────────

    /**
     * 中间件链：不可变 cons-list，buildChain 递归构建，每个 next 绑定剩余尾链。
     * - 不调 next() = 短路（后续 listener 不执行）
     * - next() 调用两次 = IllegalStateException（invokeOnce 守卫，包在传给
     *   listener 的 rest 上——守卫在最外层调用处是错的，listener 拿到的 rest
     *   才是可二次调用的入口）
     * - 同步跑在调用方（虚拟）线程上，listener 阻塞即阻塞，返回值即结果
     */
    public <T> T waterfall(EventKey<T> key, Scope origin, Object carrier,
                           List<Object> args, Next<T> inner) {
        key.requireMode(Mode.WATERFALL);
        List<WaterfallListener<T>> chain = /* filter + cast，kernel 内少数同构 unchecked cast */;
        try {
            return buildChain(chain, 0, carrier, args, inner).invoke();
        } catch (CompletionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompletionException("waterfall failed for " + key.name(), e);
        }
    }

    /**
     * 查询形态（替代 cordis bail）：每个 listener 返回非 null 即短路整链，
     * 全部返回 null 时结果为 empty。inner 是 overrideArgs -> null。
     * "审批决策"、"凭据解析" 这类 first-answer-wins 的场景用它。
     */
    public <T> Optional<T> firstOf(EventKey<T> key, Scope origin, Object carrier, List<Object> args) {
        return Optional.ofNullable(waterfall(key, origin, carrier, args, overrideArgs -> null));
    }

    private <T> Next<T> buildChain(List<WaterfallListener<T>> chain, int index,
                                   Object carrier, List<Object> args, Next<T> inner) {
        if (index == chain.size()) return inner;
        WaterfallListener<T> listener = chain.get(index);
        // 守卫包住传给 listener 的 rest：每个 listener 的 next 只允许调用一次
        Next<T> rest = invokeOnce(buildChain(chain, index + 1, carrier, args, inner));
        return overrideArgs -> listener.handle(carrier,
            new WaterfallArgs<>(overrideArgs.length == 0 ? args : List.of(overrideArgs), rest));
    }

    private static <T> Next<T> invokeOnce(Next<T> next) {
        AtomicBoolean invokedOnce = new AtomicBoolean(false);
        return overrideArgs -> {
            if (!invokedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("waterfall next() invoked twice");
            }
            return next.invoke(overrideArgs);
        };
    }
}
```

### ScopedEvents — 绑定 scope 的订阅视图

```java
// io.javanatic.harness.kernel.events.ScopedEvents
/**
 * Scope.events() 返回的视图。订阅挂在所属 scope 的 effect 栈上：
 * scope close 时订阅自动注销，插件不需要（也不应该）手工管理订阅回收。
 */
public interface ScopedEvents {
    <T> Disposable on(EventKey<T> key, EventListener<? super T> listener);
    /** 全局订阅（忽略 scope 过滤），仍随 scope 回收。 */
    <T> Disposable onGlobal(EventKey<T> key, EventListener<? super T> listener);
    <T> Disposable onWaterfall(EventKey<T> key, WaterfallListener<? super T> listener);
}
```

**绑定与归属分离**（实现层要点）：`ScopedEventsImpl` 持有 bind（过滤判定：收到 bind 及其后代派发的事件）与 owner（注销登记的 effect 栈）。普通 scope 两者同一（`Events.forScope`）；挂载视图两者分离（`Events.forMount(shared, owner)`）——bind=共享 root，owner=插件私有栈。若挂载视图的订阅绑到插件私有房，插件将收不到自身子树之外的派发（session 层派发的用户消息、审批门全部失效）；分离后插件收到整个挂载子树的事件，插件 close 即退订。

**Cordis 五模式 → JH 两模式的映射**：

| Cordis | JH | 说明 |
|---|---|---|
| emit | `notify` | fire-and-forget，每 listener 一个虚拟线程 |
| parallel | `notifyAndWait` | 并发 + join，返回 barrier |
| serial | `notifyOrdered` | 顺序，异常传播 |
| waterfall | `waterfall` | around-middleware，next() 串链 |
| bail | `firstOf` | waterfall 的查询形态，inner = null 默认 |

## 6. Runtime — 顶层运行时

```java
// io.javanatic.harness.kernel.scope.Runtime
package io.javanatic.harness.kernel.scope;

/**
 * 顶层运行时：root scope + 事件总线 + 虚拟线程 executor。
 * try-with-resources 入口；close 顺序 = root scope LIFO 级联回收 → executor 关闭。
 */
public final class Runtime implements AutoCloseable {

    private final Events events;
    private final ExecutorService virtualThreads;
    private final Scope root;

    public Runtime() {
        this.virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        this.events = new Events(virtualThreads);
        this.root = new ScopeImpl(null, this);
    }

    public Scope root() { return root; }
    public Events events() { return events; }

    void logDisposeError(Scope scope, Exception e) {
        System.getLogger("io.javanatic.harness.kernel.scope").log(
            System.Logger.Level.WARNING, "effect dispose failed in {0}", scope, e);
    }

    @Override
    public void close() {
        root.close();          // LIFO 级联：所有插件 effect、子 scope
        virtualThreads.close(); // 最后等残余 listener 任务结束
    }
}
```

**入口模式**：

```java
try (var runtime = new Runtime()) {
    Scope root = runtime.root();
    PluginLoader loader = new PluginLoader();
    Map<String, Plugin> discovered = loader.discover();
    List<Plugin> ordered = /* 07 的 boot 流程按 rows 顺序选取 */;
    loader.loadAll(root, ordered);
    Agent agent = root.require(AgentRegistry.KEY).create(root, options);
    agent.followup(userMessage);
    agent.whenIdle().join();
}
// runtime.close() 逆序回收一切
```

## 7. Plugin 与 PluginLoader — 挂载与原子回滚

```java
// io.javanatic.harness.kernel.plugin.Plugin
package io.javanatic.harness.kernel.plugin;

import io.javanatic.harness.kernel.scope.Scope;
import java.util.Set;

public interface Plugin {

    /**
     * 全局唯一的稳定 id（kebab-case，如 "llm-deepseek"、"fs-local"）。
     * 它是配置 rows / patch / 依赖声明的引用锚点——rows 按 id 引用插件，
     * 不写类名（类名在 JPMS 下不可跨模块反射访问）。
     */
    String id();

    /** 依赖的其他插件 id（决定加载顺序，拓扑排序用）。 */
    default Set<String> requires() { return Set.of(); }

    /**
     * 挂载到自己的子 scope。所有注册（provide / events().on）都过 scope effect 栈，
     * 卸载时 LIFO 回收。禁止持有全局静态状态（见 R3 剩余风险）。
     */
    void apply(Scope scope) throws Exception;
}
```

```java
// io.javanatic.harness.kernel.plugin.PluginLoader
/**
 * 通过 ServiceLoader 发现所有 Plugin 实现。
 *
 * 发现与顺序分离：discover() 建立 id→Plugin 索引；加载顺序由调用方给出
 * （07 的 boot 流程按 rows 顺序，或 topoSort 按 requires 拓扑序）。
 * fail loud 三类：重复 id；引用了不存在的 id（requires 与 row 双侧）；环。
 */
public final class PluginLoader {

    /** 发现 module-path/classpath 上全部 Plugin，按 id 索引。重复 id fail loud。 */
    public Map<String, Plugin> discover() {
        Map<String, Plugin> byId = new LinkedHashMap<>();
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            Plugin prev = byId.putIfAbsent(p.id(), p);
            if (prev != null) {
                throw new IllegalStateException(
                    "Duplicate plugin id '" + p.id() + "': "
                        + prev.getClass().getName() + " vs " + p.getClass().getName());
            }
        }
        return byId;
    }

    /**
     * 按给定顺序加载：每个 plugin 一个 PluginScope 挂载视图。
     * requires 中出现尚未加载的 id → fail loud（顺序错了）。
     * apply 抛异常 → 立即 close 该视图（回滚全部副作用，含已 provide 的服务）→ 异常上抛。
     * 加载是逐插件原子的：不存在半挂载的插件（R3）。
     */
    public void loadAll(Runtime runtime, List<Plugin> ordered) {
        Scope root = runtime.root();
        Set<String> loaded = new HashSet<>();
        for (Plugin p : ordered) {
            for (String dep : p.requires()) {
                if (!loaded.contains(dep)) {
                    throw new IllegalStateException(
                        "Plugin '" + p.id() + "' requires '" + dep
                            + "' which is not loaded before it (check row order)");
                }
            }
            Scope mount = runtime.mountScope();
            try {
                p.apply(mount);
            } catch (Exception e) {
                mount.close();   // R3 回滚原语：私有 child 单独 close
                throw new IllegalStateException(
                    "Plugin failed and rolled back: " + p.id(), e);
            }
            loaded.add(p.id());
        }
    }

    /** 按 requires 做 Kahn 拓扑排序（rows 未显式定序时用）。环 fail loud。 */
    public List<Plugin> topoSort(Collection<Plugin> plugins) { /* Kahn；剩余节点非空即环 */ }
}
```

每个 Plugin 实现类经 ServiceLoader 发现：JPMS 模块在 `module-info.java` 声明 `provides io.javanatic.harness.kernel.plugin.Plugin with XxxPlugin;`；classpath jar 用 `META-INF/services/...`。同一语义。

**PluginScope 挂载视图**（`Runtime.mountScope()` 创建，包私有，Plugin 只见 `Scope` 接口）：`provide` 落**共享 mount root**（跨插件可见——纯私有 child 里 provide，兄弟插件沿父链解析不到，"b requires a" 直接失败）；`effect` / 子 scope 落**插件私有 child**（close 即整体回滚）；订阅经 `events()` 的挂载视图——**过滤绑共享 root、注销登记插件私有栈**（见 §5 绑定与归属分离）。解析沿私有 child 向上，两处都可见。

它住在 `.scope` 包而非 `.plugin` 包：provide 走 `ScopeImpl.registerService`（包私有无栈注册通道）——"注销服务"登记到插件私有栈，插件自己的 teardown effect 先跑（此刻服务仍可见），服务摘除恒为插件回收的最后一步。若走公开的 `root.provide`，注销器会落在 root 栈的级联 entry 之上，关停时服务先消失、插件 teardown 后跑，顺序相反（迭代 1 实测暴露）。

**为什么不用注解声明依赖**：Java 注解成员只接受编译期常量，`ServiceKey`/插件对象放不进去；字符串 id 走 `requires()` 方法，拼写错误在 loadAll / topoSort 处 fail loud。

## 8. 不变式 R3：副作用消除

> **R3（副作用消除）**：插件加载失败或作用域关闭时，它注册的一切副作用——服务、事件订阅、后台任务、句柄——都被清理；不存在半挂载状态，不存在吊销后的僵尸引用。

机制与证明点：

| 保证 | 机制 | 证明 |
|---|---|---|
| 半挂载不存在 | 每插件独立挂载视图（PluginScope）；apply 失败立即 `mount.close()` 回滚 | 加载失败回滚测试（10） |
| 僵尸引用不存在 | 服务**每次访问沿链重解析**，不缓存；provider scope close 即从 overlay 摘除 | 结构性：无缓存即无失效遗漏 |
| 回收顺序 | effect 栈 LIFO（addLast/pollLast）；子 scope 级联 entry 位于其后续 effect 之前 | teardown 顺序测试（09 §teardown）+ jqwik 性质：任意注册序的回收序恒为其逆序 |
| 重复回收不可能 | Disposable CAS + 栈原子摘除；Scope close CAS | 并发 close 测试 |

**边界与剩余风险**（诚实声明）：

- **运行时卸载/升级是 MVP 非目标**。静态组合：全部插件启动时加载，teardown 发生在 shutdown。R3 的机制在 shutdown 与加载失败两个场景生效；将来做运行时卸载，复用同一 `child.close()` + 增加 in-flight drain 语义。
- **Scope 之外的副作用清不掉**：JVM 全局静态、scope 外启动的线程、`System.setProperty` 之类。插件义务：禁改全局状态；文件写入走 fs seam。靠 lint + review 执行——这是 R3 无法用测试完全证明的部分。

## 9. 日志 — System.Logger

kernel 全部日志走 JDK 内置 `System.Logger`：

```java
private static final System.Logger LOG = System.getLogger("io.javanatic.harness.kernel.events");
LOG.log(System.Logger.Level.WARNING, "notify listener failed for {0}", key.name(), e);
```

不引入 SLF4J 依赖：`System.Logger` 是 JDK 原生 API，默认路由 `java.util.logging`；需要对接 SLF4J/Logback 时替换 logger backend，**调用点零改动**。

## 10. 与 dsh 的语义对齐清单

| dsh 语义 | JH 实现 | 对齐情况 |
|---|---|---|
| `ctx.x` Proxy 查找 | `scope.require(KEY)` / `resolve(KEY)` 显式 | ✅ 语义等价，语法显式 |
| fiber（生命周期）| `Scope` + LIFO effect 栈 | ✅ 统一进 Scope |
| scope key（可见性）| `Scope` 父链 + 事件冒泡过滤 | ✅ 统一进 Scope |
| `inject` 声明依赖 | `Plugin.requires()`（id）+ loadAll 顺序校验 | ✅ 静态版本（无热重载） |
| `ctx.effect(disposer)` | `Effect.register()` 返回回收器 + Disposable + scope 栈兜底 | ✅ |
| `ctx.on/off` | `Disposable`（close 即 off），scope close 兜底 | ✅ |
| 5 种 dispatch | 2 模式（NOTIFY/WATERFALL）+ notify/notifyOrdered/notifyAndWait/firstOf 工具 | ✅ 形态全部可表达 |
| waterfall `next()` | cons-list 不可变链 + once 防护 | ✅ shift 语义 |
| scope filter（向上冒泡）| `passesFilter` 沿 parent 链 | ✅ |
| isolate 隔离 | 子 scope overlay（同名服务 shadow 父级） | ✅ 一种机制替代两种 |
| provider 下线失效缓存 | 无缓存：每访问重解析 | ✅ 结构性更强 |
| 响应式 reload | **未实现**（静态组合） | ❌ MVP 不做 |
