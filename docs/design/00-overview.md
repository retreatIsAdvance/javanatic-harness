# Javanatic Harness — 总体设计

> 代号 **Javanatic**（下文简称 JH）。目标：把 DeepSeek Harness 的工程思想移植到 JVM，产出**可扩展框架级别**的 Agent Harness。
>
> 移植立场：**思想照搬，形状不照搬**。Cordis 的 Fiber / ScopeKey / Context 三件套、五种 dispatch、Flow.Publisher 流在 JH 各有一个更简单的 Java 等价物（见 [01 §0](01-kernel.md)）。

English-friendly headings are kept; 正文为中文。

## 0. 设计目标（DO / DON'T）

### DO

- **忠实复刻五大设计基石**：一切皆插件、Session 是事件日志、Capability Seam 三角色、配置即组合、显式与纪律（四条治理不变式 R1–R4，见 §0.5）。
- **用 JVM 原生手段替换 TS 专属设施**，而不是"翻译语法"：
  | TS / Cordis | JH 替代 |
  |---|---|
  | `declare module` 声明合并 | ServiceLoader 注册 + `sealed interface` + pattern matching |
  | Discriminated union | Java 25 `sealed interface` + `record` + `switch` pattern |
  | `ctx.<key>` Proxy trap | `scope.require(ServiceKey<T>)` 泛型显式查找 |
  | symbol service key | `ServiceKey<T>` 泛型品牌（无 isolate 字段）|
  | Fiber + ScopeKey + Context 三件套 | 统一 `Scope`：生命周期 + 可见性 + 服务 overlay 一体 |
  | WeakMap scope 表（缓存 + evict）| **删除**：每次访问沿 scope 链重解析，无缓存即无僵尸引用 |
  | Promise / async | 虚拟线程阻塞式写法（"同步写法、异步调度"）|
  | `Flow.Publisher` 流式 LLM | 阻塞 `Stream<StreamChunk>` + 有界队列背压（09 §9）|
  | `ctx.effect(disposer)` | `scope.effect(Effect)`，LIFO 栈回收 |
  | `!js`/`!!js` config | YAML（SnakeYAML）+ 受限插值 `${env:}`/`${props:}`/`:-`/`==`/`!=`（无任意代码）|
  | tsx source launch | JAR 打包 / `jlink` runtime image |

### DON'T

- **不引入 Spring**。`@Component` 扫描 + BeanPostProcessor 与"显式 provide/require"模型冲突，且会吞掉框架对生命周期的控制权。JH 自研统一 Scope 内核（< 1200 行，[01](01-kernel.md)）。
- **不用反射框架做服务发现**。`ServiceLoader` 足够，且让 module-path 成为组合的显式输入。
- **不做 Lombok**。`record` 已足够。
- **不 1:1 复刻 Cordis 形状**。Fiber、isolate、五种 dispatch 模式、响应式 reload 不搬——统一 Scope、两模式事件、静态组合是刻意偏离（[01 §0](01-kernel.md)）。
- **不依赖任何 preview 特性**。全仓库无 `--enable-preview`（[11 §5](11-java25-upgrade.md)）。

### 0.5 四条治理不变式（R1–R4）

JH 把 dsh 的工程直觉收敛成四条可验证的不变式，机制与测试贯穿全部设计文档：

| # | 不变式 | 一句话陈述 | 机制 | 强制点 |
|---|---|---|---|---|
| **R1** | 可重建性 | 模型某一轮看到的完整请求内容，可从持久化事实中逐字节重建 | `LlmRequestEvent` 携带 sha256(systemPrompt) / sha256(toolsSchema) / 消息窗口 `[fromSeq, toSeq]` / params；CompositionManifest 随 SessionHeader 持久化 | [03 §8](03-session-event-sourcing.md)：replay 测试重导出请求并比对哈希 |
| **R2** | 执行一致性 | 模型发起的副作用有且仅有一条受控路径 | 单一 schema 源（ToolRegistry）、单一分发点（ToolExecutor.execute）、executor 无条件落账 tool/call + tool/result、审批与超时是 executor 固定 stage | [05 §8](05-capability-seam.md)：架构测试断言全库唯一调用点 |
| **R3** | 副作用消除 | 插件加载失败或作用域关闭时，其注册的一切副作用都被清理 | 每插件子 scope + 原子回滚（apply 抛 → child.close()）；服务无缓存、每访问重解析 | [01 §8](01-kernel.md)：回滚测试 + 结构性无僵尸引用 |
| **R4** | 治理完备 | 生产配置能**证明**权限、审计、停止条件已挂载 | `AgentLoopImpl` 构造器强制 LoopGuard/Session/ToolExecutor；executor 构造器强制 ApprovalService；`policy: production` 禁 AUTO 审批 | [07 §6](07-profile-bundle.md)：`jh --profile X --verify` 无 key 可跑、CI 断言 |

## 1. 技术栈选型（及理由）

| 维度 | 选型 | 理由 |
|---|---|---|
| 语言 | **Java 25 LTS** | `sealed`/`record`/pattern matching 是移植 dsh 类型纪律的基础；Java 25 在此之上 finalize 了 **Scoped Values（JEP 506）、Flexible Constructor Bodies（JEP 513）、Module Import Declarations（JEP 511）**（见下方"Java 25 增量收益"）|
| 并发 | **Virtual Thread + ScopedValue** | Agent loop 天然 IO 密集（LLM 流式、工具子进程）；虚拟线程让"同步写法、异步调度"成为默认；`ScopedValue` 替代 `ThreadLocal` 做 initiator 传递，不可变且虚拟线程自动继承。**无 preview 依赖**：工具并行 = submit + join（错误即数据，[09 §5](09-concurrency.md)）|
| 模块 | **JPMS（`module-info.java`）** | `requires`/`exports` 是 capability seam 边界的天然表达；`opens` 仅给 codec/adapter 的反射门面；**`import module`（JEP 511）** 让模块消费者不用罗列几十个包级 import |
| 构建 | **Maven（多模块 reactor）+ BOM** | 多模块、JPMS 友好、生态成熟、IDE 支持广 |
| YAML | **SnakeYAML（构造器白名单）** | profile/preset 等价物；拒绝任意类反序列化 |
| JSON | **Jackson（只在边界）** | 持久化 codec、LLM 请求/响应、DeepSeek HTTP；domain record **零 Jackson 注解**（[03 §6](03-session-event-sourcing.md)）|
| HTTP/LLM | **java.net.http.HttpClient** + SSE | 标准、虚拟线程友好 |
| 日志 | **System.Logger（JDK 内建）** | 零依赖；需要时换 backend，调用点零改动（[01 §9](01-kernel.md)）|
| 测试 | **JUnit 5 + AssertJ + jqwik** | 标准单测 + 属性测试（envelope seq、provenance、structuralFreeze 等，[10 §3](10-testing.md)）|

### Java 25 相对 Java 21 的增量收益（对本项目）

Java 25（2025-09 GA，5 年 LTS）finalize 了几个**本项目直接受益**的特性。下表只列与本设计相关的（完整 18 个 JEP 见 [OpenJDK](https://openjdk.org/projects/jdk/25/)）：

| JEP | 特性 | 状态 | 对本项目的意义 |
|---|---|---|---|
| **506** | Scoped Values | **Final** | 替代 [04 §8](04-agent-loop.md) 里 initiator 传递的 ThreadLocal 方案。不可变、虚拟线程自动继承、无需清理。21 里它是 preview，不可生产用 |
| **511** | Module Import Declarations | **Final** | `import module io.javanatic.harness.kernel;` 一行替代罗列 kernel 的所有包级 import。本项目 30+ 模块、消费者跨包频繁，显著降噪 |
| **513** | Flexible Constructor Bodies | **Final** | record 构造器里可在字段赋值之前放校验语句。本项目大量 record 做 fail-loud-at-construction 校验（[08 §10](08-type-discipline.md)）|
| 505 | Structured Concurrency | 第五 Preview | **不使用**。工具并行用 join-all + 错误即数据（[09 §5](09-concurrency.md)、[11 §5](11-java25-upgrade.md)）|
| 507 | Primitive Patterns | Preview | 不依赖（本设计的事件/ID 类型都是引用类型）|
| — | Virtual Threads | Final（自 21） | 已是 21 的 final 特性，25 无变化 |
| — | Stream Gatherers | Final（自 23） | 可选用于 surface fold 的自定义中间操作，非必需 |

> **取舍**：生产与测试代码只依赖 **final** 特性（506/511/513 + 21 已有的 sealed/record/pattern matching/virtual thread）。整仓库无 `--enable-preview`：505 的 `ShutdownOnFailure` fail-fast 与"每个工具调用都落账"的 R2 审计语义冲突，join-all + 错误即数据是更正确的默认（决策记录见 [11 §5](11-java25-upgrade.md)）。

### 为什么不是 Java 21

21 LTS 完全能跑通这套设计（sealed/record/pattern matching/virtual thread 在 21 已 final）。选 25 的理由：

1. **`ScopedValue` final（506）**：这是最大的硬收益。21 里 `ScopedValue` 是 preview，生产代码被迫用 `ThreadLocal`，而 `ThreadLocal` 在虚拟线程下有继承陷阱（子虚拟线程默认**不**继承父的 `ThreadLocal`，需 `Thread.Builder.inherit()` 手工开启，且可变、易泄漏）。initiator 传递是 agent loop 的核心横切关注点，值得为 506 上 25。
2. **`import module`（511）+ Flexible Constructor Bodies（513）**：二者都是"降噪"特性，单独不值升级，但叠加 506 后收益足够。
3. **5 年 LTS**：25 的支持周期到 2030+，与一个新框架的预期寿命匹配；21 的免费支持止于 2028-09（社区）。
4. **虚拟线程成熟**：25 对虚拟线程的 pinning、`synchronized` 优化比 21 更成熟（JEP 491 在 24 重写了 synchronized 的虚拟线程行为）。

### 为什么不是 Kotlin

Kotlin 的 `sealed class` + `when` 确实更优雅，coroutines 的结构化并发也更贴合 agent loop。但用户选择"由我推荐"时，**Java 25 的 `sealed`/`record`/`ScopedValue`/虚拟线程已足够复刻 dsh 的类型纪律**，且：

1. JPMS 在 Java 的一等公民地位比 Kotlin scripting 更稳定。
2. 避免 Kotlin metadata 反射带来的 JPMS `opens` 困境。
3. 面向更广的 JVM 生态（Kotlin 可作为消费者语言，但框架本体保持 Java）。

> 若未来需要，Kotlin/Scala/Clojure 的消费者可通过 JPMS `requires` 直接调用 JH 模块。

## 2. 模块总览（JPMS 视图）

```
                    ┌──────────────────────────────────────────┐
                    │           io.javanatic.harness            │  ← root aggregator
                    └──────────────────────────────────────────┘
                                       │
        ┌──────────────────┬───────────┼────────────┬─────────────────┐
        ▼                  ▼           ▼            ▼                 ▼
  harness.core.*     harness.llm  harness.fs   harness.shell    harness.bundle.*
  (session/tools/    (deepseek/   (local/      (bash-local/     (base/headless)
   agent/loop/...)    replay)      tool)        tool)
        │                  │           │            │
        ▼                  ▼           ▼            ▼
  harness.kernel ────────► 所有模块 requires ◄───────
  (Scope/Events/Plugin —— Cordis 等价物，统一内核)
```

模块命名约定：JPMS 根名统一 `io.javanatic.harness.*`（无缩写）；Maven coordinates `io.javanatic:harness-<group>-<pkg>`，artifactId 全程小写连字符（如 `harness-kernel-core`）。

详细模块清单见 [02-module-layout.md](02-module-layout.md)。

## 3. 文档导航

| 文档 | 内容 |
|---|---|
| [01-kernel.md](01-kernel.md) | Kernel：统一 Scope（生命周期 + 可见性 + 服务 overlay）、两模式 Events、Plugin 装载与原子回滚（R3）|
| [02-module-layout.md](02-module-layout.md) | JPMS 模块布局、依赖图、模块命名约定、Maven 多模块结构 |
| [03-session-event-sourcing.md](03-session-event-sourcing.md) | Session：`LoggedEvent` 信封、核心事件、Surface 投影、seam 编解码（codec）、R1 可重建性 |
| [04-agent-loop.md](04-agent-loop.md) | Agent Loop：Turn/Step 状态机、Inbox、initiator（ScopedValue）、AgentHandle 生命周期 |
| [05-capability-seam.md](05-capability-seam.md) | Capability Seam 三角色落地：阻塞 Stream LLM、fs/shell seam、真实 ApprovalService、ToolRegistry/ToolExecutor（R2）|
| [06-scope.md](06-scope.md) | Scope 应用：agent 作用域、ScopedLayers、事件向上冒泡、preset 组合 |
| [07-profile-bundle.md](07-profile-bundle.md) | Profile/Bundle/Patch 三层叠加、ConfigService、`--verify` 与 policy 档位（R4）|
| [08-type-discipline.md](08-type-discipline.md) | 类型纪律：sealed union、Branded ID、Map→Union 扩展、fail loud |
| [09-concurrency.md](09-concurrency.md) | 并发模型：虚拟线程、错误即数据 + join 收敛、有界队列背压、teardown 顺序 |
| [10-testing.md](10-testing.md) | 测试策略：R1–R4 测试映射、jqwik 属性测试、keyless snapshot 回放、架构测试 |
| [11-java25-upgrade.md](11-java25-upgrade.md) | 为何选 25 不选 21、JEP 状态、无 preview 立场、降级路径 |

## 4. 一图流：一次 Turn 的数据流

```
                       ┌─────────────────────────────────────────────────┐
                       │                  Agent Loop                      │
                       │                                                  │
   user followup  ──►  │  Inbox ──► claim ──► agent/pre-step (waterfall)  │
                       │                           │                      │
                       │                  reject ◄─┴─► enter(messages)   │
                       │                           │                      │
                       │   Session.append(turn/start, user/message 一次) │  ──►  SessionEventLog
                       │                           │                      │         (append-only)
                       │           SystemPrompt.assemble() + Tools.schema │
                       │                           │                      │
                       │      Session.append(llm/request: 哈希锚点, R1)  │
                       │                           │                      │
                       │              agent/request (waterfall)          │
                       │                           │                      │
                       │           llm.stream(request) 阻塞消费          │  ──►  LLM Provider
                       │                           │                      │       (DeepSeek/replay)
                       │              assistant/chunk* ──► assistant/msg │
                       │                           │                      │
                       │              tool/call* (if any) ──► executor   │
                       │                  │        (R2 唯一路径)          │
       ┌───────────────┼──────────────────────┐    │                     │
       │  fs provider  │  shell provider      │ ◄──┘ 校验→审批→超时→执行 │
       │  (Plugin)     │  (Plugin)            │     →审计落账(pipeline)  │
       │      ▲        │      ▲               │                           │
       │      └────────┴──────────────────────┘      tool/result*        │
       │                capability seam                 │                │
       │                                                ▼                │
                       │           agent/turn-stopping (notifyOrdered)   │
                       │                   │                              │
                       │      Session.append(turn/end) ──────────────►   │
                       └──────────────────────────────────────────────────┘
```

## 5. 与 dsh 的映射速查表

| dsh 概念 | JH 对应 | 备注 |
|---|---|---|
| Cordis fiber（生命周期）| 统一 `Scope` + LIFO effect 栈 | 生命周期、可见性、服务 overlay 一体（[01](01-kernel.md)）|
| `ctx.<key>` Proxy | `scope.require(ServiceKey<T>)` | 显式查找；沿父链重解析（无缓存）|
| `ctx.provide(name, value)` | `scope.provide(KEY, impl)` | 子 scope 同名 shadow 父级（overlay）|
| `inject: { foo: Service }` | `Plugin.requires()`（插件 id）+ loadAll 顺序校验 | 静态组合，缺失 fail loud |
| `ctx.on('event', cb)` | `scope.events().on(EventKey, listener)` | 返回 `Disposable`，随 scope 回收 |
| `ctx.effect(() => disposer)` | `scope.effect(Effect)` | register 返回回收器入 LIFO 栈 |
| `ctx.waterfall('event', args, inner)` | `Events.waterfall(EventKey, …, Next)` | cons-list 链 + next() 一次防护 |
| 五种 dispatch（emit/serial/parallel/waterfall/bail）| 两模式 NOTIFY/WATERFALL + `notify`/`notifyOrdered`/`notifyAndWait`/`firstOf` | 模式进类型、形态进工具（[01 §5](01-kernel.md)）|
| isolate 命名隔离 | 子 scope overlay | 两种机制留一种 |
| `ScopeKey` + WeakMap scope 表 | **删除** | 每访问沿 Scope 链重解析；ScopedLayers 键直接用 Scope |
| `scopeTarget` filter | `passesFilter` 沿 origin 父链 | 事件向上冒泡 |
| 响应式 reload（provider 上下线）| **不做**（静态组合）| JVM 生态习惯重启 |
| `SessionEventMap` declaration merging | `sealed SessionEvent` 核心 + `non-sealed Extension` + codec 注册 | 运行时合并 |
| 事件 seq 字段 | `record LoggedEvent<T>(long seq, T event)` 信封 | seq 在 append 的锁内分配（[03](03-session-event-sourcing.md)）|
| 模型请求可重建 | `LlmRequestEvent` 哈希锚点 + CompositionManifest | R1（[03 §8](03-session-event-sourcing.md)）|
| 工具执行 pipeline 分散 | `ToolExecutor` 单一 pipeline（校验→审批→超时→执行→审计）| R2（[05 §8](05-capability-seam.md)）|
| `dsh --profile web --dump-config` | `jh --profile web --verify` | 无 key 可跑、CI 断言（R4）|
| Brand `<B>` | `record Id<T>(String value)`（phantom type）| |
| `assertNever(x)` | `throw new IllegalStateException("Unhandled: " + x)` | sealed switch 的扩展分支 |

## 6. 非 target（明确不做）

- **不做 Web UI**。headless runner + JSON-RPC（ACP 等价物）足够。
- **不做 ACP/Codex/Claude Code subagent 桥接**。留 `SubagentProvider` 接口，不实现具体桥。
- **不做 E2B 集成**。`SandboxProvider` 接口留一个 local 实现即可。
- **不做 telemetry/OpenTelemetry**。`SessionTelemetry` 接口留空实现。
- **不做运行时热重载/卸载**。静态组合；R3 覆盖加载失败与 shutdown 两个场景（[01 §8](01-kernel.md)）。
- **不做 dsh 级 snapshot 平台**。轻量 goldens + replay（[10 §5](10-testing.md)）够用。

这些"不做"项都在 [05-capability-seam.md](05-capability-seam.md) 留有接口形状，未来可插拔。
