# Javanatic Harness（JH）— 设计文档

把 [DeepSeek Harness (dsh)](../dsh-reference.md) 的工程思想移植到 JVM 的架构设计。

> **溯源**：本设计的原始参照系是 dsh 仓库。文档中出现的 `packages/...`、`vendor/cordis`、`docs/architecture.md` 等路径均指 dsh 仓库内的路径，详见 [dsh-reference.md](../dsh-reference.md)。

代号 **Javanatic**（下文简称 JH）。目标范围：**可扩展框架级别** —— 主干 + 完整 capability seam（fs/shell/llm）+ JSONL 持久化 + headless profile。

## 快速导航

| # | 文档 | 核心内容 | 行数 |
|---|---|---|---|
| 00 | [总体设计](00-overview.md) | 设计目标、技术栈选型理由、模块总览、dsh↔JH 映射速查表 | ~200 |
| 01 | [Kernel](01-kernel.md) | Context / ServiceKey / Fiber / Events（5 种 dispatch）/ Waterfall —— Cordis 等价物 | ~450 |
| 02 | [模块布局](02-module-layout.md) | JPMS 模块清单、依赖图、module-info 示例、Maven 多模块结构 | ~500 |
| 03 | [Session 事件溯源](03-session-event-sourcing.md) | sealed SessionEvent、Surface 投影、JSONL 持久化、invariant companion | ~400 |
| 04 | [Agent Loop](04-agent-loop.md) | Turn/Step 状态机、Inbox、waterfall 扩展点、取消传播 | ~400 |
| 05 | [Capability Seam](05-capability-seam.md) | 三角色范式 + LLM/FS/Shell 三个完整 seam + ToolRegistry pipeline | ~450 |
| 06 | [Scope](06-scope.md) | ScopedLayers、scopeTarget filter、事件向上冒泡、Preset 组合 | ~400 |
| 07 | [Profile/Bundle](07-profile-bundle.md) | 三层叠加、cordis.yml 等价、headless profile、表达式插值 | ~350 |
| 08 | [类型纪律](08-type-discipline.md) | sealed union、Branded ID、Map→Union 扩展、fail loud | ~300 |
| 09 | [并发模型](09-concurrency.md) | Virtual Thread、结构化并发、AbortController、teardown 顺序 | ~350 |
| 10 | [测试策略](10-testing.md) | invariant property 测试、keyless snapshot 回放、provider fake | ~350 |
| 11 | [Java 25 升级专题](11-java25-upgrade.md) | 为何选 25 不选 21、依赖的 JEP 状态、preview 风险与 fallback、迁移路径 | ~250 |

## 核心设计决策（TL;DR）

### 技术栈
- **Java 25 LTS**：`sealed`/`record`/pattern matching（自 21 final）+ `ScopedValue`（JEP 506 final）+ `import module`（JEP 511 final）+ Flexible Constructor Bodies（JEP 513 final）
- **Virtual Thread**：IO 密集 agent loop 的默认并发模型，心智模型与 dsh async/await 一致
- **JPMS（`module-info.java`）**：`requires`/`exports`/`provides` 是 capability seam 边界的编译期表达
- **Maven 多模块 + BOM**：多模块构建
- **不引入 Spring**：自研极简 Context（< 1500 行），保留对生命周期的控制权

### TS → Java 设施映射
| dsh (TypeScript/Cordis) | JH (Java 25/JPMS) |
|---|---|
| `declare module` 声明合并 | `sealed` 核心 + `non-sealed Extension` + 运行时注册 |
| Discriminated union | `sealed interface` + `record` + `switch` pattern |
| `ctx.<key>` Proxy | `ctx.get(ServiceKey<T>)` 显式 |
| symbol service key | `ServiceKey<T>` 泛型品牌 |
| Promise/async | `CompletableFuture` + Virtual Thread |
| `ctx.effect(disposer)` | `Subscription`（AutoCloseable）+ fiber LIFO 栈 |
| WeakMap scope | `WeakHashMap<ScopeKey, …>` |
| `!!js` config 表达式 | `${env:...}` 受限插值（无任意代码） |
| tsx source launch | `java -jar` / `jlink` |

### 五大设计基石（1:1 复刻 dsh）
1. **一切皆插件** — 无特权内核，换 provider 即换产品形态
2. **Session 是事件日志** — 模型历史是 derived 投影，不是独立存储
3. **Capability Seam 三角色** — Definition/Provider/Consumer，JPMS 编译期隔离
4. **配置即组合** — Profile/Bundle/Patch 三层叠加
5. **显式与纪律** — sealed 穷尽、Branded ID、fail loud、teardown 顺序

## MVP 交付清单（可扩展框架级别）

### ✅ 实现
- Kernel（context/fiber/events/scope/plugin/config）
- Core（session/system-prompt/tools/agent/agent-loop）
- LLM seam（Definition + DeepSeek Provider + Replay Provider）
- FS seam（Definition + Local Provider + Tool Consumer）
- Shell seam（Definition + Bash-Local Provider + Tool Consumer）
- Sandbox seam（Definition + Local stub）
- Session Persistence（JSONL backend）
- Interaction（Approval stub + Commands stub）
- Bundle（base + headless）
- Examples（agent-spine demo + headless runner）

### 🔌 留接口（Definition 完整，无 Provider）
- Subagent / Web / LSP / Terminal / Compaction / Jobs

### ❌ 不做
- Web UI / ACP / Codex/Claude Code subagent 桥 / E2B / SQLite / OTel / Workflow(Ralph)

## 与 dsh 的关键差异

| 维度 | dsh | JH | 理由 |
|---|---|---|---|
| 扩展事件 | 编译期 declaration merging | 运行时 registry + sealed 核心 | Java 无声明合并 |
| 服务隔离 | 约定 + ESLint | JPMS `requires` 编译期 | **更强** |
| switch 穷尽 | 手动 `assertNever` | sealed switch 编译期强制 | **更强** |
| 响应式 reload | provider 上下线自动 reload | **静态组合**（MVP 不做） | 简化 |
| `!!js` 表达式 | 任意 JS 代码（loader context） | 受限插值 + 相等比较 | 安全收紧 |
| 热重载 | 内建 HMR | 不做 | JVM 生态习惯重启 |

## 下一步建议

1. **按文档顺序实现**：01 kernel → 02 验证 JPMS 布局 → 03 session → 04 agent-loop → 05 seam（先 fs，最简单）→ 07 headless runner 跑通第一轮 turn → 补 06 scope → 10 测试
2. **先跑通"最小 happy path"**：headless profile + replay provider + 一个 fs_read 工具，验证整条链路
3. **每实现一个模块写 invariant 测试**：先定不变式，再写实现
4. **参考 dsh 源码**：本文档的每个设计点都标注了 dsh 对应源码路径，实现时可对照

## 许可与引用

本设计文档参考 DeepSeek Harness 的架构与工程约定，原创移植到 JVM 体系。dsh 的设计思想版权归其原作者；JH 的 Java 落地设计为本文档原创。
