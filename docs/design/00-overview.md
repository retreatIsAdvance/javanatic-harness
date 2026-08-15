# Javanatic Harness — 总体设计

> 代号 **Javanatic**（下文简称 JH）。目标：把 DeepSeek Harness 的工程思想移植到 JVM，产出**可扩展框架级别**的 Agent Harness。

English-friendly headings are kept; 正文为中文。

## 0. 设计目标（DO / DON'T）

### DO

- **忠实复刻五大设计基石**：Plugin/Context、Event Sourcing Session、Capability Seam、Scope、Waterfall dispatch。
- **用 JVM 原生手段替换 TS 专属设施**，而不是"翻译语法"：
  | TS / Cordis | JH 替代 |
  |---|---|
  | `declare module` 声明合并 | ServiceLoader 注册 + `sealed interface` + pattern matching |
  | Discriminated union | Java 25 `sealed interface` + `record` + `switch` pattern |
  | `ctx.<key>` Proxy trap | `ctx.get(ServiceKey<T>)` 泛型显式查找 |
  | symbol service key | 类型品牌 `ServiceKey<T>`（Phantom type） |
  | WeakMap scope | `WeakHashMap<ScopeKey, …>` |
  | Promise / async | `CompletableFuture` + Virtual Thread（`Thread.ofVirtual()`）|
  | `ctx.effect(disposer)` | `Scope.addCloseable(AutoCloseable)` + try-with-resources |
  | `!js`/`!!js` config | YAML（SnakeYAML）+ 显式 `Config` record |
  | tsx source launch | JAR 打包 / `jlink` runtime image |

### DON'T

- **不引入 Spring**。Spring 的 `@Component` 扫描 + BeanPostProcessor 与 Cordis "显式 provide/inject" 模型冲突，且会吞掉框架对生命周期的控制权。JH 用极简自研 Context（< 300 行）。
- **不用反射框架做服务发现**。`ServiceLoader` 足够，且让 classpath 成为组合的显式输入。
- **不做 Lombok**。`record` 已足够。
- **不追求与 dsh 包结构 1:1**。JVM 模块系统（`module-info.java`）与 npm workspace 不同，按 JVM 工程习惯组织。

## 1. 技术栈选型（及理由）

| 维度 | 选型 | 理由 |
|---|---|---|
| 语言 | **Java 25 LTS** | `sealed`/`record`/pattern matching 是移植 dsh 类型纪律的基础；Java 25 在此之上 finalize 了 **Scoped Values（JEP 506）、Flexible Constructor Bodies（JEP 513）、Module Import Declarations（JEP 511）**，直接服务本项目（见下方"Java 25 增量收益"）|
| 并发 | **Virtual Thread** + `StructuredTaskScope` + `ScopedValue` | Agent loop 天然 IO 密集（LLM 流式、工具子进程）；虚拟线程让"同步写法、异步调度"成为默认；`ScopedValue` 替代 `ThreadLocal` 做 initiator 传递，虚拟线程友好且不可变 |
| 模块 | **JPMS（`module-info.java`）** | `requires`/`exports` 是 capability seam 边界的天然表达；`opens` 仅给反射门面；Java 25 的 **`import module`（JEP 511）** 让模块消费者不用再罗列几十个包级 import |
| 构建 | **Maven（多模块 reactor）+ BOM** | 多模块、JPMS 友好、生态成熟、IDE 支持广 |
| YAML | **SnakeYAML（构造器白名单）** | cordis.yml 等价物；拒绝任意类反序列化 |
| JSON | **Jackson** | 持久化、模型请求 JSON 边界 |
| HTTP/LLM | **java.net.http.HttpClient** + SSE | 标准、虚拟线程友好 |
| 日志 | **SLF4J + `System.Logger`** | 不绑死实现 |
| 测试 | **JUnit 5 + AssertJ** | JVM 生态标准 |

### Java 25 相对 Java 21 的增量收益（对本项目）

Java 25（2025-09 GA，5 年 LTS）finalize 了几个**本项目直接受益**的特性。下表只列与本设计相关的（完整 18 个 JEP 见 [OpenJDK](https://openjdk.org/projects/jdk/25/)）：

| JEP | 特性 | 状态 | 对本项目的意义 |
|---|---|---|---|
| **506** | Scoped Values | **Final** | 替代 [04](04-agent-loop.md) 里 `ThreadLocal<Agent> initiator` 的方案。`ScopedValue` 不可变、虚拟线程继承友好、无需清理，是 agent initiator / request-context 传递的正解。21 里它是 preview，不可生产用 |
| **511** | Module Import Declarations | **Final** | `import module io.deepseek.harness.kernel;` 一行替代罗列 kernel 的所有包级 import。本项目 30+ 模块、消费者跨包频繁，显著降噪 |
| **513** | Flexible Constructor Bodies | **Final** | record 构造器里可在 `super(...)` **之前**放语句。本项目大量 record 做参数校验（[08 §6](08-type-discipline.md)），此前要绕开"super 必须首行"限制，现在可直接前置校验 |
| 505 | Structured Concurrency | 第五 Preview | 仍是 preview，**生产代码不依赖**（[09](09-concurrency.md) 的 `StructuredTaskScope` 用法保持不变，但标注为"preview API，需 `--enable-preview`"）|
| 507 | Primitive Patterns | Preview | 不依赖（本设计的事件/ID 类型都是引用类型）|
| — | Virtual Threads | Final（自 21） | 已是 21 的 final 特性，25 无变化 |
| — | Stream Gatherers | Final（自 22, JEP 461→[JEP 485 final in 23]） | 可选用于 surface fold 的自定义中间操作，非必需 |

> **取舍**：生产代码只依赖 **final** 特性（506/511/513 + 21 已有的 sealed/record/pattern matching/virtual thread）。505（Structured Concurrency）虽仍是 preview，但它的 `ShutdownOnFailure` 语义太好，本项目**仅在测试和 agent-loop 内部**用它，用 `--enable-preview` 编译运行，并隔离在少数类里以便 506 final 后迁移。详见 [09-concurrency.md](09-concurrency.md)。

### 为什么不是 Java 21

21 LTS 完全能跑通这套设计（sealed/record/pattern matching/virtual thread 在 21 已 final）。选 25 的理由：

1. **`ScopedValue` final（506）**：这是最大的硬收益。21 里 `ScopedValue` 是 preview，生产代码被迫用 `ThreadLocal`，而 `ThreadLocal` 在虚拟线程下有继承陷阱（子虚拟线程默认**不**继承父的 `ThreadLocal`，需 `Thread.Builder.inherit()` 手工开启，且可变、易泄漏）。initiator 传递是 agent loop 的核心横切关注点，值得为 506 上 25。
2. **`import module`（511）+ Flexible Constructor Bodies（513）**：二者都是"降噪"特性，单独不值升级，但叠加 506 后收益足够。
3. **5 年 LTS**：25 的支持周期到 2030+，与一个新框架的预期寿命匹配；21 的免费支持止于 2028-09（社区）。
4. **虚拟线程成熟**：25 对虚拟线程的 pinning、`synchronized` 优化比 21 更成熟（JEP 491 在 24 重写了 synchronized 的虚拟线程行为）。

### 为什么不是 Kotlin

Kotlin 的 `sealed class` + `when` 确实更优雅，coroutines 的结构化并发也更贴合 agent loop。但用户选择"由我推荐"时，**Java 25 的 `sealed`/`record`/`ScopedValue`/`StructuredTaskScope` 已足够复刻 dsh 的类型纪律**，且：

1. JPMS 在 Java 的一等公民地位比 Kotlin scripting 更稳定。
2. 避免 Kotlin metadata 反射带来的 JPMS `opens` 困境。
3. 面向更广的 JVM 生态（Kotlin 可作为消费者语言，但框架本体保持 Java）。

> 若未来需要，Kotlin/Scale/Clj 的消费者可通过 JPMS `requires` 直接调用 JH 模块。

## 2. 模块总览（JPMS 视图）

```
                    ┌──────────────────────────────────────────┐
                    │           io.deepseek.harness            │  ← root aggregator
                    └──────────────────────────────────────────┘
                                       │
        ┌──────────────────┬───────────┼────────────┬─────────────────┐
        ▼                  ▼           ▼            ▼                 ▼
  io.dsh.core.*      io.dsh.llm   io.dsh.fs    io.dsh.shell     io.dsh.bundle.*
  (session/loop/     (adapter     (provider    (executor        (profile/base/
   tools/scope/...)   seam)        seam)         seam)            headless)
        │                  │           │            │
        ▼                  ▼           ▼            ▼
  io.dsh.kernel      ───────► 所有模块 requires ◄──────
  (Context/Fiber/Events/Scope/Config —— Cordis 等价物)
```

模块命名约定：`io.deepseek.harness.<group>.<pkg>`（缩写 `io.dsh.<g>.<p>`），与 dsh 的 `@deepseek-ai/dsh-<g>-<p>` 对应。

详细模块清单见 [02-module-layout.md](02-module-layout.md)。

## 3. 文档导航

| 文档 | 内容 |
|---|---|
| [01-kernel.md](01-kernel.md) | Kernel：Context / ServiceRegistry / Fiber / Events / Waterfall —— Cordis 等价物的 Java 设计 |
| [02-module-layout.md](02-module-layout.md) | JPMS 模块布局、依赖图、模块命名约定 |
| [03-session-event-sourcing.md](03-session-event-sourcing.md) | Session：Event Sourcing、SessionEvent 类型体系、Surface 投影、JSONL 持久化 |
| [04-agent-loop.md](04-agent-loop.md) | Agent Loop：Turn/Step 状态机、Inbox、waterfall 扩展点、取消与取消收敛 |
| [05-capability-seam.md](05-capability-seam.md) | Capability Seam 范式：Definition/Provider/Consumer 三角色 Java 落地，含 fs/shell/llm 三个完整 seam |
| [06-scope.md](06-scope.md) | Scope：ScopedLayers、scopeTarget filter、事件向上冒泡、preset 组合 |
| [07-profile-bundle.md](07-profile-bundle.md) | Profile/Bundle/Patch 三层叠加、cordis.yml 等价物、headless profile |
| [08-type-discipline.md](08-type-discipline.md) | 类型纪律：sealed union、Branded ID、Map→Union 扩展、assertNever |
| [09-concurrency.md](09-concurrency.md) | 并发模型：Virtual Thread、结构化并发、取消传播、teardown 顺序 |
| [10-testing.md](10-testing.md) | 测试策略：invariant companion、snapshot 回放、provider fake |

## 4. 一图流：一次 Turn 的数据流

```
                       ┌─────────────────────────────────────────────────┐
                       │                  Agent Loop                      │
                       │                                                  │
   user followup  ──►  │  Inbox ──► claim ──► agent/pre-step (waterfall)  │
                       │                           │                      │
                       │                  reject ◄─┴─► enter(messages)   │
                       │                           │                      │
                       │        Session.append(turn/start, user/message) │  ──►  SessionEventLog
                       │                           │                      │         (append-only)
                       │           SystemPrompt.assemble() + Tools.schema │
                       │                           │                      │
                       │              agent/request (waterfall)          │
                       │                           │                      │
                       │              ctx.llm.stream(request)             │  ──►  LLM Provider
                       │                           │                      │       (DeepSeek/pi-ai/replay)
                       │              assistant/chunk* ──► assistant/msg  │
                       │                           │                      │
                       │              tool/call* (if any)                 │
                       │                  │                               │
                       │      tools/pre-execute → tools/execute           │
                       │       (waterfall)          │                     │
       ┌───────────────┼──────────────────────┐    │                     │
       │  ctx.fs       │  ctx.shell           │ ◄──┘ capability seam     │
       │  (Provider)   │  (Provider)          │     dispatch to provider  │
       │      ▲        │      ▲               │                           │
       │      │ impl/  │      │ impl/         │      tools/post-execute  │
       │      │ local/ │      │ local/        │           │               │
       │      │ e2b    │      │ sandbox/      │      tool/result*        │
       └──────┴────────┴──────┴───────────────┘           │               │
                       │                                   ▼               │
                       │           agent/turn-stopping (serial)           │
                       │                   │                              │
                       │      Session.append(turn/end) ──────────────►   │
                       └──────────────────────────────────────────────────┘
```

## 5. 与 dsh 的映射速查表

| dsh 概念 | JH 对应 | 备注 |
|---|---|---|
| Cordis `Context` | `io.dsh.kernel.context.Context` | 显式 `get(KEY)` 而非 `ctx.x` |
| `ctx.provide(name, value)` | `Context.provide(KEY, impl)` | KEY 为 `ServiceKey<T>` |
| `inject: { foo: Service }` | `@InjectService(KEY)` 或 `Fiber.requires(KEY)` | |
| `ctx.on('event', cb)` | `Context.on(EventType.KEY, listener)` | 返回 `Subscription`（AutoCloseable）|
| `ctx.waterfall('event', args, inner)` | `Events.waterfall(KEY, args, inner)` | `Next<T>` 函数式接口 |
| `ctx.effect(() => disposer)` | `Scope.addCloseable(c)` 或 `Scope.effect(() -> …)` | try-with-resources 友好 |
| Cordis fiber | `Fiber`（生命周期容器）| Virtual Thread 驱动 |
| `SessionEventMap` declaration merging | `SessionEventRegistry` + `sealed interface SessionEvent` + ServiceLoader 注册 | 运行时合并 |
| `SessionEvent` 条件字段 | `sealed interface SessionEvent permits SurfaceEvent, LogOnlyEvent` | 编译期保证 |
| `surfaceOp`/`sourceEventSeqs` | `sealed interface SurfaceOp`（`Append`/`Replace`）| record |
| `ScopeKey` opaque object | `record ScopeKey(Object token) {}` | |
| `ScopedLayers<L>` | `ScopedLayers<L>`（泛型容器）| 1:1 移植 |
| `scopeTarget` filter | `EventFilter`（`Predicate<ScopeKey>`）| |
| Bundle / Profile / Patch | `Bundle`（YAML）+ `Profile`（YAML）+ `Overlay`（YAML）| 三层叠加 |
| `dsh --profile web --dump-config` | `jh --profile web --dump-config` | |
| Brand `<B>` | `record Id<T>(String value)`（phantom type）| |
| `assertNever(x)` | `throw new IllegalStateException("Unhandled: " + x)` | sealed switch 的 default |

## 6. 非 target（明确不做）

- **不做 Web UI**。headless runner + JSON-RPC（ACP 等价物）足够。
- **不做 ACP/Codex/Claude Code subagent 桥接**。留 `SubagentProvider` 接口，不实现具体桥。
- **不做 E2B 集成**。`SandboxProvider` 接口留一个 local 实现即可。
- **不做 telemetry/OpenTelemetry**。`SessionTelemetry` 接口留空实现。
- **不做 snapshot 测试基础设施**。文档里给出策略建议，不强制实现。

这些"不做"项都在 `05-capability-seam.md` 留有接口形状，未来可插拔。
