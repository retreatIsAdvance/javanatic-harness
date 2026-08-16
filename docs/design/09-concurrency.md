# 09 · 并发模型 — Virtual Thread + ScopedValue + 排空循环

dsh 基于 Cordis 的 fiber（协程）+ async/await。JH 用 **Java 25 Virtual Thread** 实现"同步写法、异步调度"，用 **`ScopedValue`（JEP 506，25 final）** 做 initiator 传递，生命周期收敛在 **`Scope` LIFO 栈**（01），工具并行用**普通虚拟线程 + join**（不依赖 preview 的 StructuredTaskScope，见 §5）。

> **Java 25 特性状态**：
> - **Virtual Thread**：21 起 final。25 优化了 synchronized 下的 pinning（JEP 491）。
> - **`ScopedValue`（JEP 506）**：**25 转 final**——本项目选 25 的首要理由。
> - **`StructuredTaskScope`（JEP 505）**：25 仍为第五 preview。**MVP 不使用**（§5 说明为何不需要它）。

## 1. 为什么 Virtual Thread（而不是 CompletableFuture 链或 Reactor）

| 维度 | Virtual Thread | CompletableFuture 链 | Project Reactor |
|---|---|---|---|
| 写法 | 同步（看起来阻塞）| 回调链 | 声明式流 |
| 心智模型 | 与 dsh async/await 一致 | 分散 | 学习曲线高 |
| 取消传播 | `checkAbort()` 协作点 + 异常 | 手动 future 链 | 复杂 |
| 栈追踪 | 完整（可读）| 无（异步栈丢失）| 无 |
| JVM 原生 | ✅ JDK 25 | ✅ | ❌ 外部依赖 |
| 适合 IO 密集 | ✅（LLM 流式、子进程）| ⚠️ | ✅ |

**结论**：agent loop 读起来像顺序同步代码（`stream.forEach(...)` 阻塞但实际挂起），与 dsh 的 `for await` 心智模型一致。kernel 事件派发也不返回 future——waterfall 同步返回值，notify fire-and-forget（01 §5），整个内核无回调链。

## 2. Scope（生命周期）与 Virtual Thread（执行）的分工

**重要区分**：

- **`Scope`**：生命周期 + 可见性容器（effect 栈 LIFO、服务 overlay、订阅归属）。不是线程。
- **Virtual Thread**：执行载体。driver 一个、每个工具执行一个、每个 notify listener 一个。

一个 Scope 上的 effect 可能在任意虚拟线程上注册；回收是 scope 级的（`close()` 逆序回收，不论注册线程）。执行结构由 Runtime 的 executor 提供：

```java
public final class Runtime implements AutoCloseable {
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    public ExecutorService virtualThreads() { return virtualThreads; }
    // close(): root.close() LIFO 级联 → virtualThreads.close()
}
```

**规则**：需要并发就 `virtualThreads.submit(...)`（或 fork 虚拟线程）；**禁止创建独立的平台线程池**——所有执行都挂在 Runtime 的 executor 下，teardown 时统一收敛（R3：scope 外启动的线程清不掉）。

## 3. Agent Driver —— 单虚拟线程排空

一个 agent 的 driver 是**一个虚拟线程**上的排空循环（04 §5）：

```java
driver = CompletableFuture.runAsync(
    () -> registry.withInitiator(this, this::drainLoop),   // 绑定点（见 §11）
    virtualThreads);

private void drainLoop() {
    try {
        while (hasWork()) runTurn();   // 同步调用，内部可阻塞
    } finally {
        // 状态复位 + 唤醒竞态防护：退出窗口内有新 work → 重启 driver
        status = IDLE; driver = null;
        if (hasWork()) ensureDriver(); else notifyIdle();
    }
}
```

**唤醒竞态**（修正）：send() 与 driver 退出并发时，"投递成功但 driver 已决定退出"的窗口由 finally 重查 `hasWork()` 封闭——不丢唤醒。

**取消**：`drainLoop` 及其调用的每个长操作在关键点 `signal.checkAbort()`，取消时抛 `AbortedException`，虚拟线程正常结束。

## 4. 取消传播（协作式）

不用 `Thread.interrupt()`（任意安全点抛 `InterruptedException`，不可控）；在**自己选择的检查点**响应取消（04 §9 的 AbortController / AbortSignal）。

```java
// 工具执行（子进程）：注册取消监听，取消 → kill
Process p = pb.start();
signal.controller().addListener(() -> p.destroyForcibly());

// 模型流式：消费循环每 chunk 自查
stream.forEach(chunk -> { signal.checkAbort(); session.append(...); });
```

HTTP body 是阻塞读——虚拟线程挂起不占 platform thread；取消时 `AbortedException` 抛出，连接由 try-with-resources 关闭。

## 5. 工具并行 —— 错误即数据，join 即收敛（无需 preview）

工具并行的语义在 R2 下发生了关键简化：**单个工具失败不是异常，是 error result**（04 §executeTools；executor 把每个工具的异常转成 `ToolResultEvent(error)`，只有 `AbortedException` 传播）。因此根本不需要 `ShutdownOnFailure` 的"任一失败取消其余"——失败已经是数据，全部跑完、逐个落账即可：

```java
// io.javanatic.harness.tools.ToolExecutor —— 并行执行（无 preview 依赖）
List<LoggedEvent<ToolResultEvent>> execute(List<ToolCallEvent> calls, AbortSignal signal) {
    List<Future<LoggedEvent<ToolResultEvent>>> futures = calls.stream()
        .map(call -> virtualThreads.submit(() -> executeOne(call, signal)))
        .toList();
    return futures.stream().map(this::await).toList();   // 逐个 join；错误已封装为 result
}

private LoggedEvent<ToolResultEvent> await(Future<LoggedEvent<ToolResultEvent>> f) {
    try {
        return f.get();
    } catch (ExecutionException e) {
        // executeOne 内已捕获一切工具异常并转 error result；
        // 能到这里的只有 AbortedException（取消要传播）或执行框架自身的 bug
        throw (RuntimeException) e.getCause();
    }
}
```

- **不引入 `--enable-preview`**：MVP 零 preview 依赖，分发与工具链最简。
- **为何曾经考虑 StructuredTaskScope**：为了"任一失败取消其余 + 作用域退出自动 join"。前者被错误即数据消解（失败不中断同伴，这正是期望语义——一个 bash 失败不该浪费掉并行的 fs_read 结果）；后者由"submit 全部 → get 全部"的顺序结构等价给出。
- **将来 JEP 505 final 后**：可换 `StructuredTaskScope.Open` 获得 join 的取消传播与线程转储归组——纯实现替换，语义不变，单文件迁移（11 §8）。

## 6. 并发安全：哪些是线程安全的

| 组件 | 线程安全？ | 手段 |
|---|---|---|
| Scope 服务 overlay | ✅ | 每 scope 一个 `ConcurrentHashMap` |
| `Events`（hooks map）| ✅ | `ConcurrentHashMap` + `CopyOnWriteArrayList` |
| `Session`（append / 读）| ✅ | `synchronized`（append 与快照读同锁，无撕裂）|
| `SurfaceManager` | ✅ | 仅在 Session 锁内访问 |
| `Inbox` | ✅ | `synchronized` 方法 |
| `AgentLoopImpl` driver | ⚠️ 单 driver | `ensureDriver` synchronized + finally 重查 |
| `ToolRegistry`（注册）| ✅ | 06 的 scoped layers 用并发容器 |
| Scope effect 栈 | ✅ | `ConcurrentLinkedDeque` + CAS |

**规则**：注册/查询类（Registry/Store/Events）线程安全（多 agent 并发访问）；执行类（driver、单 session 的 log append）以单写者为主，`synchronized` 保护关键区。25 下虚拟线程进入 `synchronized` 块不再 pinning（JEP 491），无需为虚拟线程改写 `ReentrantLock`。

## 7. Teardown 顺序（不变式）

dsh 强调 "disposer identity is load-bearing: the composite effect that owns teardown ORDER must nest unregistration at that yield position"。JH 的 Scope LIFO effect 栈保证（01 §3）：

```java
// agent 创建时的 effect 注册顺序（scope.effect / scope.onClose）
1. effect(register session publication hooks)   // 先注册 → 最后回收
2. effect(register agent in registry)
3. onClose(stop driver + await quiescence)      // 后注册 → 先回收

// scope.close() 时 LIFO 回收：
1. stop driver + await quiescence    // 先停 loop（等它写完最后的 turn/end）
2. unregister agent                  // 再注销（否则 disposed 事件早于 driver 退出）
3. remove session publication hooks  // 最后移 session（driver 的最终事件仍可 publish）
```

两个 R3 场景复用同一原语：

- **插件加载失败回滚**：`child.close()`（01 §7）——apply 抛异常即回收该子树全部 effect，父级干净。
- **整体 shutdown**：`Runtime.close()` → root LIFO 级联 → executor close 等残余任务。

**回收失败不阻断**：单个 effect 的 close 抛异常只记日志，其余继续回收——teardown 永不因一个坏 disposer 卡死。

## 8. 虚拟线程 + 持久化的交互

Session append 同步（锁内），持久化异步（订阅 `session/appended`，buffer + batch 落盘，03 §6）：

```
driver 虚拟线程                持久化虚拟线程
     │ session.append(event)        │
     │ ──synchronized────────────►  │ (notify session/appended)
     │   log.add + surface commit   │ buffer.put → 异步 batch 写盘
     │ ◄─────────────────────────── │
     │ ... 继续 turn ...
```

**flush barrier**：resume 前 / dispose 前确保持久化落盘，`SessionStore.flush` dispatch `session/flush` 并 await 全部持久化 listener（01 §5 `notifyAndWait`——并发派发 + join，异常不传播只记日志）：

```java
// dispose 前：先静止，再 flush
handle.dispose = awaitQuiescence()
    .thenCompose(v -> sessions.flush(scope, session))
    .thenRun(() -> agentScope.close());
```

## 9. 背压与限流

### LLM 流式背压 —— 有界阻塞队列（不是 Flow.Publisher）

LLM seam 的流式接口是**阻塞 `Stream<StreamChunk>`**（05 §LLM）：provider 跑在自己的虚拟线程上，把 chunk 写进有界 `BlockingQueue`；`Stream` 的 next 阻塞读（消费慢 → 队列满 → producer 挂起）。背压由队列容量自然给出，无 Reactive Streams 协议税（onSubscribe/request 合规是前版一整类缺陷的来源）：

```java
// Provider 侧（DeepSeek SSE 解析循环，跑在 producer 虚拟线程）
for (SseEvent e : sse) {
    signal.checkAbort();
    queue.put(parseChunk(e));       // 队列满 → 挂起（背压）
}
queue.complete();

// Consumer 侧（driver 虚拟线程，04 §7）
try (Stream<StreamChunk> chunks = llm.stream(callConfig, request, signal)) {
    chunks.forEach(chunk -> { signal.checkAbort(); session.append(...); });
}
```

为何不用 `Flow.Publisher`：JH 里流的唯一消费者是 agent loop 自己，无 reactive 互操作需求；虚拟线程下"阻塞队列 + 阻塞流"语义相同、代码量减半。将来接 WebFlux 之类，在 seam 上写一个小适配器即可。

### 工具输出限流（spill）

大输出（`ls -la /` 几千行）不全量塞回模型。spill（移植 dsh `ctx.spillStore`）：`tools/post-execute` 监听器检查输出长度，超阈值 spill 到磁盘，返回定位符 + 前 N 行 + 检索提示（`fs_read` with offset 取回）。

## 10. 与 dsh 并发模型对齐

| dsh | JH | 备注 |
|---|---|---|
| Cordis fiber（协程生命周期）| `Scope`（effect 栈 LIFO）| 不绑定执行线程 |
| async/await | Virtual Thread（同步写法）| 心智模型一致 |
| `ctx.effect(disposer)` | `Scope.effect` / `onClose` | teardown 顺序保证 |
| AbortSignal.reason | `AbortSignal.controller().cause()` | first-cause-wins |
| Promise.all（工具并行）| submit 全部 + join 全部 | 错误即数据，无需 fail-fast |
| 持久化异步 buffer + flush barrier | 同（session/flush → notifyAndWait）| 1:1 |
| LLM 流式 AsyncIterable | 阻塞 `Stream<StreamChunk>` + 有界队列 | 背压 = 队列容量 |
| 取消检查点 | `signal.checkAbort()` | 协作式 |
| teardown ORDER（composite effect）| Scope LIFO + 子 scope 级联 entry | 1:1 |
| 同进程因果归因（initiator）| `ScopedValue<Agent>`（JEP 506 final）| 绑定点规则见 §11 |

## 11. ScopedValue 详解（JEP 506，25 final）

`ScopedValue`：**不可变、有限生命周期、虚拟线程创建时继承**的作用域值。JH 用它传 agent initiator（04 §10）与 request-context。

### 为什么不用 ThreadLocal

| 维度 | ThreadLocal | ScopedValue |
|---|---|---|
| 可变性 | 可变（任意代码 `set()`）| **不可变**（绑定后只读）|
| 清理 | 必须手动 `remove()`，池化线程泄漏 | `where(v).run(op)` 退出自动解绑 |
| 虚拟线程继承 | 默认不继承 | **创建时**自动继承 |
| 开销 | 每 Thread 一个 map | 绑定是栈帧级 |
| 生命周期 | 与 Thread 绑定（复用 → 串台）| 与作用域绑定（词法确定）|

### JH 的三条绑定规则（不变式化，04 §10）

1. **绑定点唯一**：`drainLoop` 整段跑在 `withInitiator(this, ...)` 内；agent 生命周期内的一切模型调用、工具执行、事件派发都在绑定内发生。
2. **继承发生在创建时**：ScopedValue 按线程**创建时刻**快照继承——绑定后 caller 再绑新值，已派生的子虚拟线程看不到。因此**禁止向池化 executor 提交**任务再期望读到 initiator（池化线程的绑定属于创建它的别人）；需要并发就 fork 虚拟线程（工具执行、notify 派发都如此，§2 规则）。要显式传值就当参数传——"显式 > 隐式"。
3. **不可变**：绑定期内无人能改写 initiator。嵌套绑定（subagent 内层遮蔽外层、退出恢复）用 `where(...).run(...)` 开新作用域表达。

```java
// 嵌套遮蔽：subagent 场景
ScopedValue.where(INITIATOR, parentAgent).run(() -> {
    // parent 的工具执行：INITIATOR.get() == parentAgent
    ScopedValue.where(INITIATOR, childAgent).run(() ->
        child.followup(task));      // child loop 内读到 childAgent
    // 恢复 parentAgent
});
```

## 12. 不使用 StructuredTaskScope 的决策记录

| 问题 | 答案 |
|---|---|
| 为什么 25 不用 | JEP 505 第五 preview；`--enable-preview` 侵入编译/运行/分发全链路 |
| 为什么语义上不需要 | 工具失败是 error result（数据）不是异常——"任一失败取消其余"反而不是期望语义；join 收敛由顺序结构给出 |
| 失去了什么 | STS 的线程转储归组与 join 期取消传播的词法保证；当前无消费者 |
| 何时重新评估 | JEP 505 final（预计 26/27）：`ToolExecutor` 单文件可换 `STS.Open`，语义不变（11 §8 迁移路径） |
