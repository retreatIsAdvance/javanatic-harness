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
| 12 | [API 稳定面](12-api-stability.md) | 0.1.0 门面冻结：导出包 / seam 契约 / 事件 schema / 配置键 / CLI + 0.x 语义与变更程序 |

## 核心设计决策（TL;DR）

### 技术栈
- **Java 25 LTS**：`sealed`/`record`/pattern matching（自 21 final）+ `ScopedValue`（JEP 506 final）+ `import module`（JEP 511 final）+ Flexible Constructor Bodies（JEP 513 final）；**无 preview 依赖**（505 不用，[11 §5](11-java25-upgrade.md)）
- **Virtual Thread**：IO 密集 agent loop 的默认并发模型，"同步写法、异步调度"；工具并行 = submit + join，错误即数据（[09 §5](09-concurrency.md)）
- **JPMS（`module-info.java`）**：`requires`/`exports`/`provides` 是 capability seam 边界的编译期表达
- **Maven 多模块 + BOM**：kernel 三模块（core/brand/config），全仓 33 个叶模块（45 个 reactor 模块）
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
- Interaction（**Approval 三模式真实实现** + 命令面 registry/slash 解析，it14）
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

## 实现路线（垂直切片；2026-09 开源目标修订）

已完成的垂直切片（it1 kernel / it2 session / it3 llm seam + replay / it4 tools + fs / it5 agent-loop + 竖切 / it6 shell + deepseek + 持久化 + R1 闭环 / it7 openai-compat + 治理上线 / it7.1 厂商灵活化 / it8 组合数据化 AppBoot + ConfigService + manifest / it9 scope + preset / it10 长跑能力 compaction + budget + resume / it11 todo_write + 计划模式 / it12 sandbox 同机进程约束 + restriction / it12.5 shell-docker 环境级隔离 / it12.6 硬化回填（JSONL 耐久、typed LLM 失败、LocalFs realpath、`--verify` 平台预警）/ it12.7 平台链落地（linux=bwrap + CI 双 job）/ it13 dist jlink + CLI 完备 / it14 交互面 REPL + 流式渲染 / it15 生产模拟场景进 CI（replay 驱动：keyless、确定性）+ `--budget=` 收口 + R1 逐锚点前缀折叠口径 / it16 发布工程 → **0.1.0 已发布**（Maven Central + GitHub Release；门面冻结 + 双语 README + jlink 归档 + [release.md](../release.md)））。R1–R4 测试随切片走，不做收尾补（[10](10-testing.md)）。

**开源决策（2026-09-08 确认）**：License Apache-2.0；JDK 25 LTS 单版本（不降 21——ScopedValue 终版叙事与差异化优先，采用税在文档中明示）；首发同时面向国际与中文社区。

### Phased Evolution Plan

**规划基准：2026-09-18，0.1.0 已发布；以下阶段均为计划、尚未开工。** 本节是总体迭代路线的事实源；具体迭代仍须按 [plan/README.md](../plan/README.md) 完成四确认、设计增量、锚点与审查停点，路线落盘不等于实现授权，也不代表验收通过。

规划原则：**维护者场景驱动、真实任务验收、社区反馈校准**。发布初期不等待社区反馈才推进；优先级以生产可用、易用、易扩展为目标，而非模块数量。参考本地 dsh 截至 2026-08-13 的源码与演进记录（不代表远端最新状态；路径口径见 [dsh-reference.md](../dsh-reference.md)），吸收取消收敛、语义检查点、外部插件接入和任务所有权的经验，不照搬其包规模、HMR 或 Web 平台。

| 目标版本 | 阶段 | 用户得到什么 | 推进条件 |
|---|---|---|---|
| 0.2.0 | 可靠且好用的单 Agent（it17–25） | CLI 任务结果可信、故障可收敛、工作区操作可靠、Java 外部接入与平台交付可验证 | 现有重试、取消、恢复、压缩等能力按真实入口补齐验证，不重复从零建设 |
| 0.3 系列 | 可复用、可接入的工具生态 | skills + MCP Tools；随后网络资料获取与 LSP | 依赖单 Agent 治理、外部扩展和进程生命周期闭环；0.3.0 聚焦 skills 与 MCP Tools |
| 0.4 | 受控后台任务与多 Agent | 长任务可管理，委派有权限、预算、取消与结果终局 | 先完成后台任务，再证明委派相对单 Agent 对固定任务集有实际收益 |

#### Production Scope and Compatibility

- **双受众**：直接下载运行 CLI 的用户；通过已发布 Java 依赖嵌入 Agent、开发和组合插件的开发者。内部 reactor 示例通过不等于外部消费者路径可用。
- **近期生产边界**：单用户、单工作区的受控自动化，以及可信 Java 插件的应用嵌入；不扩张成多租户服务平台。
- **不变量边界**：R1 的日志可重建不等于外部副作用可安全重放；R3 清理注册不等于回滚业务文件；JPMS 隔离不等于恶意插件安全沙箱。
- **版本稳定性**：遵守 [12-api-stability.md](12-api-stability.md)。兼容的明确缺陷修复可进入 0.1.x；破坏稳定面的调整随 0.2.0，附 release notes 与迁移路径。总体路线不修改既有 API 契约。
- **平台承诺**：Windows 本机隔离 + pwsh、Linux Landlock fallback 保留在 0.2.0，不静默顺延；无法满足限制语义时 fail-closed，不用裸跑冒充支持。

#### 0.2.0 — Reliable Single-Agent Product

目标：用户能完成任务、判断成败、停止执行并安全恢复；Java 开发者能通过独立工程接入。以下是工作切片与验收目标，不是固定日历工期或已存在的保证。

| 迭代 | 内容与用户价值 | 核心验收 |
|---|---|---|
| it17 | **CLI 任务结果闭环与真实任务基线**：one-shot 最终答案；stdout 与诊断分离；成功、失败、取消的退出语义；建立固定真实任务集 | shell 脚本可判断任务结果；鉴权错误、预算终止、正常完成不都表现为成功；交互模式无回归 |
| it18 | **取消与执行收敛**：核验模型流、并行工具、审批等待、shell 子进程的取消链；明确超时、取消与 idle 的关系 | 可控场景取消后无遗留执行；不先报 idle 后继续修改文件；不合作插件限制明确；模型重试不重放工具副作用 |
| it19 | **崩溃恢复与写入安全**：核验请求/工具派发前耐久屏障；单会话写者保护；识别未完成工具调用的“结果未知” | SIGKILL、写入失败、双进程竞争均有测试；屏障失败不进入副作用；恢复不自动重放结果未知的工具；撕裂尾修复无回归 |
| it20 | **长任务资源边界**：明确 token 预算口径；压缩触发与失败策略；工具输出进入上下文及落盘的限额 | 压缩后仍可完成长任务；大输出不撑爆内存/上下文；资源统计与估算边界可解释，不宣称无法保证的费用硬上限 |
| it21 | **工作区理解与可靠编辑**：统一 workspace 语义；项目说明加载与来源记录；有界搜索；唯一匹配和读后外部修改保护 | 重复匹配拒绝或要求明确选择；不静默覆盖读后外部修改；项目说明进入可回放上下文，但不能提升权限 |
| it22 | **会话操作与人工协作**：会话列举、状态、恢复指引、取消入口；与审批分离的澄清问答 | 不需手工翻 JSONL 找会话；无交互终端不无限等待输入；拒绝审批与回答问题语义清楚 |
| it23 | **外部 Java 接入闭环**：仓库外嵌入与插件示例；显式组合方式和排错指引；区分标准发行包与二开发行方式 | 独立 Maven 工程完成嵌入 Agent、注册工具和治理验证；不依赖主仓源码或 reactor 偶然提供的依赖；候选工件与发布工件分别复验 |
| it24 | **Linux 交付与 Landlock**：可直接使用的 Linux 归档；Landlock fallback 与能力诊断 | 干净 Linux 环境可安装运行；bwrap 不可用时按能力选择；不满足约束时 fail-closed；真实 OS 验证，不以编译通过代替 |
| it25 | **Windows 与 0.2 发布验收**：pwsh、本机隔离、进程树清理、路径/编码适配及发行包 | 真 Windows 环境完成安装、任务、拒批、取消和恢复；已支持平台回归；不是只装 pwsh 即算支持；发布工件与稳定面核对齐全 |

**依赖与取舍**：先让任务结果可观察，再验证取消和崩溃终局；可靠执行与有界资源是工作区工具、外部插件及后续工具生态的基础。Windows 可行性和测试环境提前核实，实现保持单主线，避免到版本末尾才发现平台阻塞。取消泄漏、磁盘故障传播等未经故障验证的问题应记录为待验证边界，不预先定性为已证缺陷。

**本阶段不做**：多 Agent、分布式调度、Web UI、完整 OTel 平台、运行时热重载、通用 exactly-once、业务副作用自动回滚，也不承诺向现有 jlink 镜像随意丢 JAR 即可启用插件。

#### 0.3 — Reusable Capabilities and Tool Ecosystem

目标：用户增加能力不必修改内核，维护者不必亲自实现所有专用工具。0.3.0 聚焦 skills 与 MCP Tools；网络资料获取和 LSP 随后在 0.3 系列滚动细化，不预先固定远期迭代编号。

| 顺序 | 交付范围 | 验收与边界 |
|---|---|---|
| 1. Skills | 本地发现、按需加载、来源和优先级、作用域可见性；分离部署供应与 Agent 消费 | 可移植到独立项目；技能中的命令仍走既有工具治理，不另开执行通道 |
| 2. MCP Tools | 先 stdio，再 Streamable HTTP；稳定命名、schema 边界、超时取消、有限重连 | 服务重启、重名、畸形结果、大输出有测试；进入审批、审计和资源限制；首版不做 Resources/Prompts |
| 3. 网络资料获取 | 有界网页读取、来源保留；搜索作为可替换 provider | 明确网络权限、重定向与内网访问策略；网页只作资料，不能被当成系统指令 |
| 4. LSP | 优先 Java 的诊断、定义和引用，再考虑其他语言 | 固定任务证明优于纯文本搜索；语言服务退出可清理；复用文件与进程生命周期机制 |

MCP 优先于大量自建连接器，以控制个人维护成本；接入外部工具不等于绕过治理。新增协议依赖、注册表归属和作用域规则在对应迭代单独评审，不因路线落盘预先批准具体选型。

#### 0.4 — Managed Background Work and Delegation

目标：长任务与并行工作有可管理的生命周期，而不只是“能创建子 Agent”。

1. **先后台任务**：任务 ID、所属会话、状态与输出游标；输出限额、超时、取消和完成通知；宿主退出清理。重启后如实标记失联或未知，不冒充任务仍可续接。验收覆盖长时间构建/测试、取消、宿主退出和输出限额。
2. **再受控委派**：父子所有权和权限继承（不能扩权）；有界并发、调用深度和总资源预算；人工问答统一路由；管理机制保证结果或失败结算，不依赖提示词提醒汇报；父任务取消时子任务一起收敛。验收覆盖父子取消、问答等待、预算耗尽和结果终局，并与单 Agent 比较实际任务收益。

**进入条件**：单 Agent 取消、恢复、人工通道与后台任务验收通过。**不做**分布式 Agent 集群、跨机器工作流平台及既有非目标中的 ACP/Codex/Claude Code subagent 桥接；其他既有非目标仍见 [00 §6](00-overview.md#6-非-target明确不做)。

#### Validation and Rolling Planning

以下是待建立的验收目标，不是当前成绩；keyless replay 验证机制确定性，不能代替真实模型任务、真实进程崩溃或真实平台验收。

| 证据 | 目标与记录方式 |
|---|---|
| 首次使用 | 网络与 key 已准备好的干净环境，按文档 10 分钟内完成首个任务；逐一验证发行平台 |
| 真实任务集 | 固定 10 个小型任务，覆盖定位、修改、测试、重构、文档；固定模型与配置，每项运行 3 次，记录成功率、人工介入、耗时与 token；暂以 27/30 成功为建议发布目标，首轮基线后校准，不以调低目标掩盖回归 |
| 故障与治理 | 拒批、取消、超时、断流、磁盘写失败、SIGKILL、重复 writer 等确定性场景全部通过；越权执行、已知破坏性恢复和虚假成功不能靠平均成功率抵消 |
| 外部消费者 | 独立工程验证 Java 嵌入与插件接入；候选版本验证候选工件，发布后复验 Central 工件 |

- 每两轮迭代复盘真实失败任务、人工补救步骤和重复问题，决定后续切片如何细化；社区反馈出现后加入证据，不作为开工前提。
- 每轮四确认增加一个验收问题：哪一种用户任务从做不到、做不稳或难操作，变成可验证地完成？契约与故障语义先审，实现后再用真实入口验收，证据留在对应 `iteration-N.md`。
- 近期详细、远期按依赖滚动规划；未通过所依赖的验收，不叠加下一层能力。范围过大可在开工四确认时拆分，不静默改变版本承诺。
- 实现变化按 [设计同步触发规则](../plan/README.md#设计同步触发规则) 更新对应设计；非稳定面自由重排不得覆盖 [API 稳定面](12-api-stability.md)，其指令文档冲突在后续获授权的同步中处理。
- **it23「外部 Java 接入闭环」已完成**（2026-09-24 放行收口——独立 Maven 工程嵌入、候选/发布两条工件腿复验、[接入指南](../embedding.md)从零走查；见 [docs/plan/iteration-23.md](../plan/iteration-23.md)）；下一迭代（it24）见上表，具体契约、锚点和审查停点留待四确认，本节不替代开工手续。

## 许可与引用

本设计文档参考 DeepSeek Harness 的架构与工程约定，原创移植到 JVM 体系。dsh 的设计思想版权归其原作者；JH 的 Java 落地设计为本文档原创。
