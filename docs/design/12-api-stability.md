# 12 · API 稳定面（0.1.0 门面冻结）

0.1.0 的「门面」契约：哪些面冻结、冻结到什么粒度、0.x 语义与变更程序。核对基准 = 实现实况（`module-info.java` 导出面全量扫描 / `SessionEvent` sealed permits / 插件 config 读取点 / `jh --help`），2026-09-17 逐项对齐——**导出面 = 本文列面**。模块与依赖图归 [02](02-module-layout.md)；本文管「对外承诺什么」。

## 1. 0.x 语义

| 事件 | 承诺 |
|---|---|
| 0.1.x 修补 | **不破本文任一稳定面**：不删/不改导出包；不改契约接口方法签名；不改 sealed permits 与事件 record 组件（名 / 类型 / 序）；不改配置键名；不删 CLI flag（新增只可加可选项）|
| 破坏性变更 | 只随次版本（0.2.0）发布；release notes 声明变更与迁移路径 |
| 非稳定面 | 实现类、kernel 内部机制、`examples/*` / `dist/*` 的内部行为不承诺——pre-release 立场：无兼容垫片，重命名/重排随破坏性版本走 |

坐标口径：Maven `io.github.retreatIsAdvance:harness-*`（中央仓命名空间 = GitHub 身份）；包名 / JPMS 名 `io.javanatic.harness.*` 是编译期身份——**不同源属有意为之**（[00](00-overview.md)）。

## 2. 包级 API（JPMS 导出面）

`exports` 是唯一编译期可见面：实现类不导出（导出包内的 public 实现类亦属内部，除非本文点名）。全部导出包如下（32 个 reactor 代码模块 / 36 个包；前缀 `io.javanatic.harness.` 省略）：

| 模块 | 导出包 |
|---|---|
| kernel/core | `kernel.scope`、`kernel.events`、`kernel.plugin` |
| kernel/brand | `kernel.brand` |
| kernel/config | `kernel.config` |
| core/session | `session`、`session.event`、`session.message` |
| core/system-prompt | `systemprompt` |
| core/tools | `tools` |
| core/agent | `agent` |
| core/agent-loop | `agentloop` |
| core/todo | `todo` |
| core/plan | `plan` |
| core/preset | `preset` |
| llm/llm | `llm` |
| llm/openai-compat | `llm.openai.compat` |
| llm/deepseek | `llm.deepseek` |
| llm/replay | `llm.replay` |
| fs/fs | `fs` |
| fs/local | `fs.local` |
| fs/tool | `fs.tool` |
| shell/shell | `shell.shell` |
| shell/bash-local | `shell.bash.local` |
| shell/docker | `shell.docker` |
| shell/tool | `shell.tool` |
| session/persistence | `session.persistence` |
| session/persistence-jsonl | `session.persistence.jsonl` |
| sandbox/sandbox | `sandbox.sandbox` |
| sandbox/local | `sandbox.local` |
| sandbox/policy | `sandbox.policy` |
| interaction/approval | `interaction.approval` |
| interaction/commands | `interaction.commands` |
| bundle/base | `boot` |
| examples/agent-spine | `examples.agent.spine` |
| examples/headless | `examples.headless` |

examples 两模块不在发布面（Central 上传面由根 POM release profile 的 `excludeArtifacts` 排除），其导出面仅供仓库内使用与演示。

## 3. Seam 契约（主干接口；不逐类穷尽）

| 契约 | 所在包 | 含义 |
|---|---|---|
| `Plugin` / `Scope` / `ScopedEvents` / `Effect` | `kernel.*` | 插件单元、统一 Scope（生命周期 + 可见性 + 服务 overlay）、订阅面、效应回收（[01](01-kernel.md)）|
| `EventKey` / `Next` / `WaterfallListener` / `EventListener` | `kernel.events` | 两模式 Events（NOTIFY / WATERFALL） |
| `Tool` / `ToolRegistry` / `ToolExecutor` / `ApprovalService` | `tools` | R2 唯一执行路径与审批门（`ApprovalService.Mode` = AUTO / HUMAN_GATE / DENY_ALL）|
| `SessionStore` / `Session` / `LoggedEvent` / `Message` / `ContentBlock` / `SurfaceOp` | `session` / `session.event` / `session.message` | 事件日志与模型消息投影（[03](03-session-event-sourcing.md)）|
| `LlmService` / `LlmAdapter` / `StreamChunk` / `AbortSignal` | `llm` | seam 三角色、阻塞流、取消（[05](05-capability-seam.md)）|
| `FsService` / `ShellExecutor` / `SandboxProvider` / `SandboxPolicyService` / `BackendStatus` | `fs` / `shell.shell` / `sandbox.sandbox` | capability 入口（换 Provider 即换环境）|
| `SessionPersistence` / `SessionEventCodec` / `JsonValue` | `session.persistence` | 持久化 seam 与 codec SPI（ServiceLoader）|
| `ApprovalPrompt` | `interaction.approval` | 人闸回调（ask 模式） |
| `CommandRegistry` / `Command` / `Handler` / `CommandResult` | `interaction.commands` | 命令面（registry / slash 解析 / 事件对，it14） |
| `ConfigService` / `ConfigRowSpec` / `CompositionManifest` | `kernel.config` | 配置服务、行模型、组合清单（进 SessionHeader）|
| `AppBoot` / `BootOptions` / `Policy` | `boot` | 装配入口与治理档（`--verify` 断言面）|
| `LoopGuard` / `CompactionService` / `PreStepDecision` | `agentloop` | 停止条件与长跑压缩 |
| `AgentPresets` / `AgentPreset` | `preset` | per-session 能力集 |
| `SystemPromptService` / `PromptSection` | `systemprompt` | 提示组装注册表 |
| `PlanModeService` | `plan` | 计划模式（纯 fold） |
| `Agent` / `AgentFactory` | `agent` | agent 公开契约（[04](04-agent-loop.md)）|

## 4. 事件 schema

- **信封**：`LoggedEvent(seq, event)`——seq 单调（JSONL 一行一条）；`SessionEvent.time()` = epoch 毫秒。
- **核心 sealed**：`SessionEvent permits` `TurnStart` / `TurnEnd` / `StepStart` / `StepEnd` / `UserMessageEvent` / `AssistantMessageEvent` / `LlmRequestEvent` / `ToolCallEvent` / `ToolResultEvent` / `CompactionStart` / `CompactionSummary` / `CompactionEnd` / `RequestHeader` / `SessionEndSeedEvent` / `ExtensionEvent`（[03](03-session-event-sourcing.md)）。
- **扩展点**：`ExtensionEvent`（开放）+ `SessionEventCodec<T>` SPI——ServiceLoader 注册、jsonl 落盘驱动；现有 codec 实例：assistant/chunk、todo、plan、command 等。
- **R1 锚点**：`LlmRequestEvent` 双哈希——模型请求可逐字节重建，回放闭环比对（[10](10-testing.md)）。
- **失败词表**：`FailureKind`（typed LLM 失败，it12.6）。

## 5. 配置键（profile / bundle 行）

行引用**插件 id**（kebab-case，ServiceLoader 发现）；`config` 键 camelCase。全部可配插件与实际键（读取点实核）：

| 插件 id | config 键 | 说明 |
|---|---|---|
| persistence-jsonl | `root` | 会话根目录（安全边界值，缺失 fail loud）|
| llm-openai-compat | `name` / `baseUrl` / `apiKey` / `apiKeyEnv` | 厂商名与端点；key 字面量与环境变量二选一 |
| loop-guard | `maxTurns` / `maxStepsPerTurn` / `maxBudgetTokens` | 停止条件（PRODUCTION 档要求非零，[07](07-profile-bundle.md)）|
| compaction | `contextWindow` / `maxContextTokens` / `thresholdRatio` / `retainTokens` / `retries` / `summarizationProvider` / `summarizationModel` | 长跑压缩（窗口是模型属性，base 不猜数）|
| plan | `section` | 计划模式提示段 |
| sandbox-policy | `mode` / `workspace` | 沙箱档与围栏根 |
| fs-local | `root` | fs 围栏根（安全边界值）|
| shell-tool | `workspace` / `timeoutSeconds` | shell 围栏与超时 |
| shell-bash-local | `maxOutputBytes` | 输出截断 |
| shell-docker | `image` / `maxOutputBytes` | 镜像须本机在场（不自动拉取）|
| todo | `allowParallelInProgress` | 并行 in_progress 开关 |
| presets | `root` / `presets` | preset 组合 |

无 config 的插件 id：`session-store`、`agents`、`system-prompt`、`llm`、`approval-auto` / `approval-ask` / `approval-deny`、`tools`、`sandbox-local`、`fs-tool`、`commands`、`agent-loop`（24 个 base 行 id = 12 可配 + 12 无配）。

- **插值白名单**（[07](07-profile-bundle.md)）：`${env:NAME}` / `${props:NAME}` / `${cwd}` / `${home}` / `:-默认值`；比较式 `==` / `!=`（右操作数 `null` 或 `'literal'`）——无任意代码；源不在白名单 fail loud。
- **三层叠加**：bundle 行 → profile 行 → CLI flag overlay（AppBoot 装配序）；行动作为互斥 sealed 联合（include / replace / remove / insert）。

## 6. CLI 面（`jh`，dist/jh jlink 镜像）

`jh --help` 为唯一权威清单（本文列面与它对齐）。冻结项：`--workspace=` / `--verify` / `--policy=STANDARD|PRODUCTION` / `--approval=auto|ask|deny` / `--budget=` / `--docker [--image=]` / `--resume=` / `--provider=` / `--model=` / `--base-url=` / `--api-key-env=` / `--api-key=` / `--profile=` / `--help`。REPL：非 `/` 行成轮；`/help` / `/exit`（EOF 同）。`--verify` 无 key 可跑，exit 0/1。

## 7. 变更程序

- 与本文冲突的实现改动：**同提交**先改本文，禁止静默漂移（[AGENTS](../../AGENTS.md) 文档即事实源）。
- 稳定面增删：随 0.2.0+ 发布按 §1 语义声明；0.1.x 内的新增（新包 / 新可选 flag / 新 config 键）允许，但须同提交补进本文。
- 核对方法被固化：导出面 = `module-info.java` 扫描；配置键 = 插件 config 读取点；CLI = `jh --help`——每次发布验收重跑核对。
