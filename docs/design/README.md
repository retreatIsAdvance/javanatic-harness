# Javanatic Harness（JH）— 设计文档

把 [DeepSeek Harness (dsh)](../dsh-reference.md) 的工程思想移植到 JVM 的架构设计。

> **溯源**：本设计的原始参照系是 dsh 仓库。文档中出现的 `packages/...`、`vendor/cordis`、`docs/architecture.md` 等路径均指 dsh 仓库内的路径，详见 [dsh-reference.md](../dsh-reference.md)。

代号 **Javanatic**（下文简称 JH）。目标范围：**可扩展框架级别** —— 主干 + 完整 capability seam（fs/shell/llm）+ JSONL 持久化 + headless profile。

> **移植立场**：思想照搬，形状不照搬。Cordis 的 Fiber/ScopeKey/Context 三件套收敛为统一 `Scope`；五种 dispatch 收敛为两模式；`Flow.Publisher` 流换成阻塞 `Stream` + 有界队列背压（[01 §0](01-kernel.md)、[00 §0](00-overview.md)）。

## 快速导航

| # | 文档 | 核心内容 |
|---|---|---|
| 00 | [总体设计](00-overview.md) | 设计目标、R1–R4 治理不变式总表、技术栈选型理由、模块总览、dsh↔JH 映射速查表 |
| 01 | [Kernel](01-kernel.md) | 统一 Scope（生命周期+可见性+服务 overlay）、两模式 Events（NOTIFY/WATERFALL）、Plugin 装载与原子回滚（R3）|
| 02 | [模块布局](02-module-layout.md) | JPMS 模块清单（kernel 三模块）、依赖图、module-info 示例、Maven 多模块结构 |
| 03 | [Session 事件溯源](03-session-event-sourcing.md) | `LoggedEvent` 信封、核心事件、Surface 投影、codec 归持久化 seam、R1 可重建性 |
| 04 | [Agent Loop](04-agent-loop.md) | Turn/Step 状态机、Inbox、initiator（ScopedValue）、AgentHandle、取消收敛 |
| 05 | [Capability Seam](05-capability-seam.md) | 三角色范式、阻塞 Stream LLM seam、fs/shell seam、真实 ApprovalService、ToolRegistry/ToolExecutor（R2）|
| 06 | [Scope](06-scope.md) | agent 作用域（扁平兄弟）、ScopedLayers、事件向上冒泡、Preset 组合 |
| 07 | [Profile/Bundle](07-profile-bundle.md) | 三层叠加（rows 引用插件 id）、ConfigService、`--verify` 与 policy 档位（R4）|
| 08 | [类型纪律](08-type-discipline.md) | sealed union、Branded ID、Map→Union 扩展、fail loud、零注解 domain |
| 09 | [并发模型](09-concurrency.md) | Virtual Thread、错误即数据 + join 收敛（无 preview）、有界队列背压、teardown 顺序 |
| 10 | [测试策略](10-testing.md) | R1–R4 测试映射、jqwik 属性测试、keyless snapshot 回放、架构测试 |
| 11 | [Java 25 升级专题](11-java25-upgrade.md) | 为何选 25 不选 21、依赖的 JEP 状态、无 preview 立场、降级路径 |

## 核心设计决策（TL;DR）

### 技术栈
- **Java 25 LTS**：`sealed`/`record`/pattern matching（自 21 final）+ `ScopedValue`（JEP 506 final）+ `import module`（JEP 511 final）+ Flexible Constructor Bodies（JEP 513 final）；**无 preview 依赖**（505 不用，[11 §5](11-java25-upgrade.md)）
- **Virtual Thread**：IO 密集 agent loop 的默认并发模型，"同步写法、异步调度"；工具并行 = submit + join，错误即数据（[09 §5](09-concurrency.md)）
- **JPMS（`module-info.java`）**：`requires`/`exports`/`provides` 是 capability seam 边界的编译期表达
- **Maven 多模块 + BOM**：kernel 三模块（core/brand/config），全仓 27 个叶子模块（38 个 reactor 项目）
- **System.Logger**：JDK 内建日志，零依赖
- **JUnit 5 + AssertJ + jqwik**：单测 + 属性测试
- **不引入 Spring**：自研统一 Scope 内核（< 1200 行），保留对生命周期的控制权

### TS → Java 设施映射
| dsh (TypeScript/Cordis) | JH (Java 25/JPMS) |
|---|---|
| `declare module` 声明合并 | `sealed` 核心 + `non-sealed Extension` + codec 注册 |
| Discriminated union | `sealed interface` + `record` + `switch` pattern |
| Fiber/ScopeKey/Context 三件套 | 统一 `Scope`（overlay 隔离，每访问沿链重解析）|
| `ctx.<key>` Proxy | `scope.require(ServiceKey<T>)` 显式 |
| 5 种 dispatch（emit/serial/parallel/waterfall/bail）| 2 模式 + `notify`/`notifyOrdered`/`notifyAndWait`/`firstOf` 工具方法 |
| Promise/async + Flow.Publisher | 虚拟线程阻塞式 + `Stream<StreamChunk>` 有界队列背压 |
| `ctx.effect(disposer)` | `scope.effect(Effect)` + LIFO 栈 |
| `!!js` config 表达式 | `${env:...}` 受限插值（无任意代码）|
| tsx source launch | `java -jar` / `jlink` |

### 五大设计基石（思想 1:1，形状 Java 化）
1. **一切皆插件** — 稳定插件 id + 静态组合，换 provider 即换产品形态
2. **Session 是事件日志** — `LoggedEvent(seq, event)` 信封；模型历史是 derived 投影
3. **Capability Seam 三角色** — Definition/Provider/Consumer，JPMS 编译期隔离
4. **配置即组合** — Profile/Bundle/Patch 三层叠加，rows 引用插件 id
5. **显式与纪律** — sealed 穷尽、Branded ID、fail loud、teardown 顺序

### 四条治理不变式（R1–R4）
| # | 不变式 | 一句话 |
|---|---|---|
| **R1** 可重建性 | 模型某一轮看到的完整请求可从持久化事实逐字节重建（哈希锚点 + replay 测试）|
| **R2** 执行一致性 | 模型发起的副作用有且仅有一条受控路径（ToolExecutor pipeline + 架构测试）|
| **R3** 副作用消除 | 插件失败/作用域关闭即清理一切注册副作用（子 scope 原子回滚 + 无缓存解析）|
| **R4** 治理完备 | 生产配置能证明权限、审计、停止条件已挂载（构造器强制 + `--verify` + policy 档位）|

总表与机制链接见 [00 §0.5](00-overview.md)。

## MVP 交付清单（可扩展框架级别）

### ✅ 实现
- Kernel（core：Scope/Events/Plugin；brand；config）
- Core（session/system-prompt/tools/agent/agent-loop）
- LLM seam（Definition 阻塞 Stream + DeepSeek Provider + Replay Provider）
- FS seam（Definition + Local Provider + Tool Consumer）
- Shell seam（Definition + Bash-Local Provider + Tool Consumer）
- Sandbox seam（Definition + Local）
- Session Persistence（SessionStore + SessionEventCodec SPI + JSONL backend）
- Interaction（**Approval 三模式真实实现** + Commands stub）
- 治理（`--verify` + `policy: standard/production` 档位）
- Bundle（base + headless）
- Examples（agent-spine demo + headless runner）

### 🔌 留接口（Definition 完整，无 Provider）
- Subagent / Web / LSP / Terminal / Compaction / Jobs

### ❌ 不做
- Web UI / ACP / Codex/Claude Code subagent 桥 / E2B / SQLite / OTel / Workflow(Ralph) / 运行时热重载

## 与 dsh 的关键差异

| 维度 | dsh | JH | 理由 |
|---|---|---|---|
| 生命周期/可见性容器 | Fiber + ScopeKey + Context 三套 | 统一 `Scope` 一套 | 概念减半，语义不丢 |
| 事件分发 | 5 种 dispatch | 2 模式 + 工具方法 | 消灭"mode×方法"配对错误类 |
| 服务解析 | fiber 缓存 + 下线全树 evict | 每访问沿链重解析 | 无缓存即无僵尸引用（R3 结构性）|
| LLM 流 | `Flow.Publisher` | 阻塞 `Stream` + 有界队列 | 背压自然，删 Reactive Streams 合规bug 类 |
| 扩展事件 | 编译期 declaration merging | 运行时 codec 注册 + sealed 核心 | Java 无声明合并 |
| 服务隔离 | 约定 + ESLint | JPMS `requires` 编译期 | **更强** |
| switch 穷尽 | 手动 `assertNever` | sealed switch 编译期强制 | **更强** |
| 审批 | approval 插件（可选挂）| executor 固定 stage + 构造器强制 | R2/R4 收紧 |
| `!!js` 表达式 | 任意 JS 代码（loader context）| 受限插值 + 相等比较 | 安全收紧 |
| 响应式 reload / 热重载 | 内建 | 不做（静态组合）| JVM 生态习惯重启 |
| 持久化 JSON 注解 | 类型层注解散布 | domain 零注解，codec 归 seam | 边界纪律 |

## 实现路线（垂直切片）

1. **Iteration 1（已完成）**：kernel（`brand` 的 `Id<T>` + `core` 的 Scope/Events/Plugin，30+ 测试含 jqwik LIFO 性质测试）。实现相对本设计稿的修正已回写 [01](01-kernel.md)：`parent()` root 返回 null、`Next` varargs + `WaterfallArgs.rest`、订阅类型化双入口、waterfall 守卫包 rest、PluginScope 挂载视图（provide 落共享 root，effect 落私有 child）
2. **Iteration 2（最小完整竖切）**：core.session → llm.replay → core.tools + fs 最简 seam → core.agent-loop → examples.headless 跑通第一轮 turn。R1–R4 测试随切片走，不是收尾补 ([10](10-testing.md))
3. **Iteration 3**：system-prompt 组装、shell seam、真实 llm-deepseek、JSONL 持久化 + R1 replay 哈希测试
4. **Iteration 4**：scope/preset 组合（06）、approval-ask 交互档、`--verify` + policy 档位（R4）
5. 每实现一个模块先写 invariant/属性测试：先定不变式，再写实现

## 许可与引用

本设计文档参考 DeepSeek Harness 的架构与工程约定，原创移植到 JVM 体系。dsh 的设计思想版权归其原作者；JH 的 Java 落地设计为本文档原创。
