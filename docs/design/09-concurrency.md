# 09 · 并发模型 — Virtual Thread + ScopedValue + 结构化并发

dsh 基于 Cordis 的 fiber（协程）+ async/await。JH 用 **Java 25 Virtual Thread** 实现等价的"同步写法、异步调度"，用 **`ScopedValue`（JEP 506，Java 25 final）** 替代 `ThreadLocal` 做 initiator / request-context 传递，用 `StructuredTaskScope`（JEP 505，**仍为 preview**）实现结构化生命周期。

> **Java 25 特性状态提醒**：
> - **Virtual Thread**：自 21 final，生产可用。25 进一步优化了 synchronized 下的虚拟线程行为（JEP 491），pinning 大幅减少。
> - **`ScopedValue`（JEP 506）**：**Java 25 转 final**，生产可用。这是本项目选 25 而非 21 的首要理由。
> - **`StructuredTaskScope`（JEP 505）**：**Java 25 仍为第五 preview**。本项目仅在少数类（agent-loop 工具并行执行）使用，需 `--enable-preview` 编译运行，并隔离以便 final 后平滑迁移。

## 1. 为什么 Virtual Thread（而不是 CompletableFuture 链或 Reactor）

| 维度 | Virtual Thread | CompletableFuture 链 | Project Reactor |
|---|---|---|---|
| 写法 | 同步（看起来阻塞）| 回调链 | 声明式流 |
| 心智模型 | 与 dsh async/await 一致 | 分散 | 学习曲线高 |
| 取消传播 | `Thread.interrupt()` + `AbortController` | 手动 future 链 | 复杂 |
| 栈追踪 | 完整（可读）| 无（异步栈丢失）| 无 |
| JVM 原生 | ✅ JDK 25 | ✅ | ❌ 外部依赖 |
| 适合 IO 密集 | ✅（agent loop 天然 IO：LLM 流式、子进程）| ⚠️ | ✅ |

**结论**：Virtual Thread 让 agent loop 代码读起来像顺序同步代码（`response.body().forEachRemaining(...)` 阻塞但实际挂起），与 dsh 的 `for await (const chunk of stream)` 心智模型一致。

## 2. Fiber（JH）= Cordis Fiber（生命周期）+ Virtual Thread（执行）

**重要区分**：
- **Cordis Fiber / JH `Fiber`**：生命周期容器（effect 栈、scope key、inject 依赖）。**不是** OS 线程，也不是虚拟线程。
- **Virtual Thread**：执行载体。一个 agent 的 driver 跑在一个虚拟线程上。

一个 JH `Fiber` 可能在任意虚拟线程上执行代码（driver 跑一个、工具执行跑另一个），但 effect 栈的回收是 fiber 级别的（fiber dispose 时逆序回收所有 effect，不论它们在哪个线程注册的）。

```java
// FiberRuntime 提供 virtual thread executor
public final class FiberRuntime {
    private final ExecutorService virtualThreadExecutor =
        Executors.newVirtualThreadPerTaskExecutor();

    public Executor virtualThreadExecutor() { return virtualThreadExecutor; }
}
```

所有 `CompletableFuture.runAsync(task, virtualThreadExecutor)` 在虚拟线程上跑。task 内部可以写阻塞代码（IO、`Object.wait()`），不占用 platform thread。

## 3. Agent Driver —— 单虚拟线程排空

一个 agent 的 driver 是**一个虚拟线程**上的排空循环：

```java
private void ensureDriver() {
    if (driver != null && !driver.isDone()) return;
    status = AgentStatus.RUNNING;
    driver = CompletableFuture.runAsync(this::drainLoop, virtualThreadExecutor);
}

private void drainLoop() {
    // 当前在虚拟线程上
    try {
        while (hasWork()) {
            runTurn();  // 同步调用，内部可阻塞（LLM 流式、工具执行）
        }
    } finally {
        status = AgentStatus.IDLE;
        driver = null;
    }
}
```

**优势**：`runTurn()` 内部的 `llm.stream(...).forEach(chunk -> ...)` 看起来阻塞，实际挂起虚拟线程等 SSE。代码读起来与 dsh 的顺序 async/await 一致，无需 `.thenCompose` 链。

**取消**：`drainLoop` 在关键点检查 `abortSignal.checkAbort()`，取消时抛 `AbortedException`，虚拟线程正常结束。

## 4. 取消传播

### AbortController（协作式取消）

JH 用协作式取消（而非 `Thread.interrupt()`），因为：
- `interrupt()` 会在任意安全点抛 `InterruptedException`，难以精确控制。
- agent loop 需要在**自己选择的检查点**响应取消（模型请求间隙、工具执行边界）。

```java
public final class AbortController {
    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void cancel(AgentCancelCause c) {
        if (cancelled.compareAndSet(false, true)) {
            cause.set(c);
            listeners.forEach(Runnable::run);
        }
    }

    public boolean isAborted() { return cancelled.get(); }

    void addListener(Runnable r) {
        if (isAborted()) r.run();
        else listeners.add(r);
    }
}

public record AbortSignal(AbortController controller) {
    public boolean isAborted() { return controller.isAborted(); }

    /** 在检查点调用，已取消则抛 AbortedException。 */
    public void checkAbort() {
        if (isAborted()) throw new AbortedException(controller.cause());
    }
}

public class AbortedException extends RuntimeException {
    private final AgentCancelCause cause;
    public AbortedException(AgentCancelCause c) { super(c.toString()); this.cause = c; }
    public AgentCancelCause cancelCause() { return cause; }
}
```

### 工具执行的取消

工具执行（如 bash 子进程）注册取消监听，取消时 `destroyForcibly`：

```java
CompletableFuture.supplyAsync(() -> {
    Process p = pb.start();
    signal.controller().addListener(() -> p.destroyForcibly());  // 取消 → kill
    // ... 等待进程 ...
}, virtualThreadExecutor);
```

### 模型流式的取消

SSE 读取循环每行检查 `signal.checkAbort()`：

```java
response.body().forEachRemaining(line -> {
    signal.checkAbort();  // 取消时抛 AbortedException，虚拟线程结束
    parseAndEmit(line);
});
```

HTTP client 的 `response.body()` 是阻塞读，虚拟线程挂起不占 platform thread。取消时 `AbortedException` 抛出，虚拟线程正常退出，HTTP 连接由 try-with-resources 关闭。

## 5. 结构化并发（StructuredTaskScope）

> ⚠️ **JEP 505 在 Java 25 仍是第五 preview**。下列代码需要 `--enable-preview` 编译和运行。本项目把 `StructuredTaskScope` 的使用**隔离在 agent-loop 的工具并行执行这一个类**里（`ToolExecutor`），一旦 JEP 505 转 final，迁移面收敛在单文件。MVP 若不想引入 preview，可退化为 `CompletableFuture.allOf` 手写等价逻辑（见本节末尾的 fallback）。

对于"一个 step 内并行执行多个工具调用"或"并行 fan-out 的 session/flush"，用 `StructuredTaskScope`：

```java
// 并行执行多个工具调用
private List<ToolResultEvent> executeTools(List<ToolCallEvent> calls, AbortSignal signal) {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        List<StructuredTaskScope.Subtask<ToolResultEvent>> subtasks = calls.stream()
            .map(call -> scope.fork(() -> {
                signal.checkAbort();
                return executeOneTool(call, signal);
            }))
            .toList();

        scope.join();          // 等所有完成
        scope.throwIfFailed(); // 任一失败抛

        return subtasks.stream()
            .map(StructuredTaskScope.Subtask::get)
            .toList();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
    }
}
```

**`ShutdownOnFailure`**：任一子任务失败 → 取消其余子任务。这正是工具并行执行的期望语义（一个工具失败不应让其他继续浪费）。

### 结构化并发的生命周期绑定

`StructuredTaskScope` 是 try-with-resources，离开作用域自动 `close()`（等待所有子任务）。这与 JH Fiber 的 "child fiber dispose 被 parent 兜底" 语义一致：

```
Fiber（effect 栈）
  └─ scope = StructuredTaskScope（子任务）
       └─ Virtual Thread（一个工具执行）
```

parent fiber dispose → scope close → 等子任务 → 逆序回收 effect。**结构化并发保证不泄漏**。

## 6. 并发安全：哪些是线程安全的

| 组件 | 线程安全？ | 手段 |
|---|---|---|
| `ServiceRegistry` | ✅ | `ConcurrentHashMap` |
| `Events`（hooks map）| ✅ | `ConcurrentHashMap` + `CopyOnWriteArrayList` |
| `Session`（log append）| ✅ | `synchronized append()` |
| `SurfaceManager` | ✅ | 单线程访问（driver 虚拟线程）+ 缓存 |
| `Inbox` | ✅ | `synchronized` 方法 |
| `AgentLoopImpl` driver | ⚠️ 单 driver 虚拟线程 | `ensureDriver` synchronized 启动 |
| `ToolRegistry`（注册）| ✅ | `ScopedLayers` 用 `ConcurrentHashMap` |
| `Fiber` effect 栈 | ✅ | `ConcurrentLinkedDeque` |

**规则**：注册/查询类（Registry/Store/Events）线程安全（多 agent 并发访问）；执行类（driver、单 session 的 log append）单线程为主，用 `synchronized` 保护关键区。

## 7. Teardown 顺序（关键不变式）

dsh 强调 "disposer identity is load-bearing: composite effect that owns teardown ORDER must yield THIS function so Cordis nests unregistration at that yield position"。

JH 的 LIFO effect 栈保证：

```java
// agent 创建时的 effect 注册顺序（addEffect/addCloseable）
1. addEffect(register session publication hooks)   // 先注册（最后回收）
2. addEffect(register agent in registry)
3. addCloseable(stop driver + await quiescence)    // 后注册（先回收）

// dispose 时 LIFO 回收：
1. stop driver + await quiescence    // 先停 loop
2. unregister agent                  // 再注销
3. remove session publication hooks  // 最后移 session
```

**关键**：driver 必须先停（等它把最后的 `turn/end` 写完），再注销 agent（否则 `agent/disposed` 在 driver 还在跑时发出），最后移 session（否则 driver 的最终事件无法 publish）。

`Fiber.dispose()` 用 `Deque.pollLast()`（LIFO）保证这个顺序：

```java
public CompletableFuture<Void> dispose() {
    return CompletableFuture.runAsync(() -> {
        AutoCloseable c;
        while ((c = effectStack.pollLast()) != null) {  // LIFO
            try { c.close(); }
            catch (Exception e) { /* log, 不阻断 */ }
        }
        runtime.registry().revokeAll(this);
    }, virtualThreadExecutor);
}
```

## 8. 虚拟线程 + 持久化的交互

Session append 是同步的（`synchronized`），但持久化是异步的（buffer + flush）：

```
driver 虚拟线程                持久化虚拟线程
     │                              │
     │ session.append(event)        │
     │ ──synchronized────────────►  │
     │   log.add(event)             │ buffer.put(event)
     │   notifyObservers(event)     │
     │ ◄─────────────────────────── │
     │                              │ (异步 batch 写盘)
     │                              │
     │ ... 继续 turn ...            │
```

**session/flush barrier**：agent 要确保持久化落盘时（如 resume 前、dispose 时），调 `sessionStore.flush(session)`，它 dispatch `session/flush`（parallel），await 所有持久化 listener：

```java
public CompletableFuture<Boolean> flush(Session session) {
    return events.parallel(SessionEvents.FLUSH, session, session)
        .thenApply(v -> true);
}

// 用法（dispose 前）
awaitQuiescence().thenCompose(v -> sessions.flush(session)).join();
```

## 9. 背压与限流

### LLM 流式背压

`Flow.Publisher<StreamChunk>`（Reactive Streams）天然支持背压。agent loop 的订阅者用 `request(N)` 控制拉取速度：

```java
llm.stream(config, request, signal).subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;
    @Override public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(1);  // 一次要一个
    }
    @Override public void onNext(StreamChunk chunk) {
        session.append(toChunkEvent(chunk));  // 写日志
        subscription.request(1);              // 要下一个
    }
    @Override public void onError(Throwable t) { ... }
    @Override public void onComplete() { ... }
});
```

### 工具输出限流（spill）

大输出（如 `ls -la /` 几千行）不应全量塞回模型。spill 机制（移植 dsh `ctx.spillStore`）：

- `tools/post-execute` 监听器检查输出长度，超阈值则 spill 到磁盘，返回定位符 + 检索提示。
- 模型看到的是 `"Output spilled (5000 lines). First 50 lines: ... Use fs_read with offset to retrieve more."`

## 10. 与 dsh 并发模型对齐

| dsh | JH | 备注 |
|---|---|---|
| Cordis fiber（协程生命周期） | `Fiber`（effect 栈生命周期）| 不绑定执行线程 |
| async/await | Virtual Thread（同步写法）| 心智模型一致 |
| `ctx.effect(disposer)` | `Fiber.addCloseable`（LIFO）| teardown 顺序保证 |
| AbortSignal.reason | `AbortSignal.controller().cause()` | first-cause-wins |
| `structuredClone`/Promise.all | `StructuredTaskScope.ShutdownOnFailure` | 结构化并发 |
| 持久化异步 buffer + flush barrier | 同（session/flush parallel）| 1:1 |
| LLM 流式 AsyncIterable | `Flow.Publisher<StreamChunk>` | Reactive Streams 标准 |
| 背压 | `Flow.Subscription.request(N)` | 标准背压 |
| 取消检查点 | `signal.checkAbort()` | 协作式 |
| teardown ORDER（composite effect）| LIFO `Deque.pollLast()` | 1:1 |
| 同进程因果归因（initiator）| `ScopedValue<Agent>`（JEP 506 final）| 不可变，虚拟线程继承 |

## 11. ScopedValue 详解（JEP 506，Java 25 final）

`ScopedValue` 是 Java 25 final 的"作用域值"——一个**不可变、有限生命周期、虚拟线程继承**的值容器，专为替代 `ThreadLocal` 在虚拟线程时代的痛点设计。本项目用它传递 agent initiator（[04 §8](04-agent-loop.md)）和 request-context。

### 为什么不用 ThreadLocal

| 维度 | ThreadLocal | ScopedValue |
|---|---|---|
| 可变性 | 可变（任意代码 `set()`）| **不可变**（绑定后只读）|
| 清理 | 必须手动 `remove()`，否则线程池泄漏 | `where(v).run(op)` 退出自动解绑 |
| 虚拟线程继承 | 默认**不**继承子虚拟线程的值（需 `Thread.Builder.inherit()`）| **自动继承**所有子虚拟线程（含 StructuredTaskScope fork 的）|
| 开销 | 每 Thread 一个 map，虚拟线程量大时有 GC 压力 | 绑定是栈帧级，几乎零开销 |
| 生命周期 | 与 Thread 绑定（线程复用 → 值串台）| 与作用域绑定（词法确定）|

对 agent loop 的关键场景：driver 虚拟线程 fork 出工具执行子虚拟线程，这些子线程要读到 `currentInitiator()` 做日志归因。`ScopedValue` 自动继承，`ThreadLocal` 默认不继承——这是选 ScopedValue 的硬理由。

### 用法

```java
// 定义（static final，不可变容器）
private static final ScopedValue<Agent> INITIATOR = ScopedValue.newInstance();

// 绑定 + 在作用域内执行
public <T> T withInitiator(Agent a, Supplier<T> op) {
    return ScopedValue.where(INITIATOR, a).get(op);
}

// 读取（作用域外或未绑定时 isBound()=false）
public Agent currentInitiator() {
    return INITIATOR.isBound() ? INITIATOR.get() : null;
}

// 嵌套绑定（内层遮蔽外层，退出恢复）
ScopedValue.where(INITIATOR, parentAgent).run(() -> {
    // INITIATOR.get() == parentAgent
    ScopedValue.where(INITIATOR, childAgent).run(() -> {
        // INITIATOR.get() == childAgent（内层遮蔽）
    });
    // INITIATOR.get() == parentAgent（恢复）
});
```

### 虚拟线程继承的实测语义

```java
ScopedValue.where(INITIATOR, agent).run(() -> {
    // 当前虚拟线程：INITIATOR.get() == agent ✅

    // fork 子虚拟线程（StructuredTaskScope 或手动）
    Thread.startVirtualThread(() -> {
        // 子虚拟线程：INITIATOR.get() == agent ✅（自动继承）
        log("initiator={}", currentInitiator());
    }).join();

    // StructuredTaskScope fork 的子任务也继承
    try (var scope = new StructuredTaskScope.Open()) {
        scope.fork(() -> {
            // INITIATOR.get() == agent ✅
            return null;
        });
        scope.join();
    }
});
// 作用域外：INITIATOR.isBound() == false ✅（自动解绑）
```

### 注意事项

- **不可变意味着不能"更新"**：若需要在一个深调用栈里"修改" initiator（罕见），用 `ScopedValue.where(INITIATOR, newValue).run(inner)` 开新作用域，而非 set。
- **不跨线程池边界**：提交到 `ExecutorService` 的任务**不**继承 ScopedValue（因为提交时脱离了 `run()` 的词法作用域）。若需在池任务里读 initiator，把它作为**显式参数**传递（这正是 dsh 倡导的"显式 > 隐式"）。本项目 initiator 仅在 agent driver 的虚拟线程树内传递，不跨池。
- **与 `StructuredTaskScope` 协同最佳**：StructuredTaskScope fork 的子任务在父作用域内，自动继承 ScopedValue。

## 12. StructuredTaskScope 的 preview 风险与 fallback

JEP 505 在 Java 25 仍是第五 preview。若 MVP 不愿引入 `--enable-preview`，工具并行执行可退化为纯 `CompletableFuture`：

```java
// 不用 StructuredTaskScope 的 fallback（无 preview）
private CompletableFuture<List<ToolResultEvent>> executeTools(
        List<ToolCallEvent> calls, AbortSignal signal) {
    List<CompletableFuture<ToolResultEvent>> futures = calls.stream()
        .map(call -> CompletableFuture.supplyAsync(() -> {
            signal.checkAbort();
            return executeOneTool(call, signal);
        }, virtualThreadExecutor))
        .toList();

    // 任一失败时取消其余（手动等价 ShutdownOnFailure）
    CompletableFuture<Void> all = CompletableFuture.allOf(
        futures.toArray(CompletableFuture[]::new));

    return all.thenApply(v -> futures.stream()
            .map(CompletableFuture::join)
            .toList())
        .exceptionally(ex -> {
            futures.forEach(f -> f.cancel(true));  // 取消其余
            throw new CompletionException(ex);
        });
}
```

**权衡**：fallback 失去了 StructuredTaskScope 的"作用域退出自动 join + 取消传播"的词法保证，需手动 `cancel`。但本项目只在工具并行执行这一处用，复杂度可控。建议 MVP 先用 fallback，待 JEP 505 final（预计 Java 26/27）再切换。
