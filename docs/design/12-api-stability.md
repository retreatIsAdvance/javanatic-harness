# 12 · API 稳定面（0.1.0 门面冻结）

0.1.0 的「门面」契约：哪些面冻结、冻结到什么粒度、0.x 语义与变更程序。核对基准 = 实现实况（`module-info.java` 导出面全量扫描 / `SessionEvent` sealed permits / 插件 config 读取点 / `jh --help`），2026-09-17 逐项对齐——**导出面 = 本文列面**。模块与依赖图归 [02](02-module-layout.md)；本文管「对外承诺什么」。

## 1. 0.x 语义

| 事件 | 承诺 |
|---|---|
| 0.1.x 修补 | **不破本文任一稳定面**：不删/不改导出包；不改契约接口方法签名；不改 sealed permits 与事件 record 组件（名 / 类型 / 序）；不改配置键名；不删 CLI flag（新增只可加可选项）|
| 破坏性变更 | 只随次版本（0.2.0）发布；release notes 声明变更与迁移路径 |
| 非稳定面 | 实现类、kernel 内部机制、`examples/*` / `dist/*` 的内部行为不承诺——pre-release 立场：无兼容垫片，重命名/重排随破坏性版本走 |

坐标口径：Maven `io.github.retreatisadvance:harness-*`（中央仓命名空间 = GitHub 身份）；包名 / JPMS 名 `io.javanatic.harness.*` 是编译期身份——**不同源属有意为之**（[00](00-overview.md)）。

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
| interaction/ask | `interaction.ask` |
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

**0.2.0 迁移（it18 取消收敛）**：审批 seam 两处签名加取消信号——`ApprovalService.require(request)` → `require(request, signal)`；`ApprovalPrompt.ask(request)` → `ask(request, signal)`。等待中取消抛 `AbortedException`（走取消收敛，不落 error result）；`ApprovalPrompt.stdin()` 默认可轮询（取消即抛并撤回阻塞读）。AUTO / deny 实现与忽略信号的自定义 prompt 维持原语义。

**0.2.0 迁移（it22 等待界）**：`ApprovalPrompt.stdin()` → `stdin(Duration idleTimeout)`——0/负 = 不设限，正 = 空闲上限（超时**按拒绝**：fail-closed 返回 false，不抛异常）；EOF 即时拒绝语义不变。缺省有效值单源在 `ApprovalPrompt.effectiveIdleTimeout(configuredSeconds, interactive)`（配置 ≥ 0 优先；否则交互终端不设限 / 非交互 `NON_INTERACTIVE_IDLE_TIMEOUT` = 300s），行 config `idleTimeoutSeconds` 覆盖之。

**0.2.0 增量（it22 澄清问答）**：`ToolDefinition.ofExempt(...)`（免审批声明：审批 stage 对声明工具不触发）与 `ToolExecutionResult.concluding(content)`（正常成功但终结本 turn）。`ask_user` 工具（`interaction.ask`，插件 id `ask-user`）——答复 = 下一轮 user message；非交互场景结构上不挂等（出口契约见 §6）。

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
| fs-local | `root` / `maxReadBytes` / `maxListEntries` / `searchMaxMatches` | fs 围栏根（安全边界值）；读取/编辑上限、列举上限与搜索命中上限（默认 256 KiB / 1000 / 200，超限截断标记 / fail loud）|
| shell-tool | `workspace` / `timeoutSeconds` | shell 围栏与超时 |
| shell-bash-local | `maxOutputBytes` | 输出截断 |
| shell-docker | `image` / `maxOutputBytes` | 镜像须本机在场（不自动拉取）|
| todo | `allowParallelInProgress` | 并行 in_progress 开关 |
| presets | `root` / `presets` | preset 组合 |
| agent-loop | `cwd` / `instructionsFile` | 提示词上下文工作目录（`request/header` 落账值；缺省 `user.dir`）。与 `fs-local.root` / `shell-tool.workspace` / `sandbox-policy.workspace` 同源——四处漂移装配期拒绝（[07 §5](07-profile-bundle.md)）；项目说明文件名或路径（it21，缺省 `AGENTS.md`）|
| approval-ask | `idleTimeoutSeconds` | 人闸等待界（秒；0 = 不设限；it22）。缺省按终端形态：**非交互 300s 后按拒绝**（fail-closed）/ 交互不设限；CLI `--approval-timeout=` 覆盖（§6）。读取点 `ApprovalAskPlugin`，有效值判定与 CLI 同源（`ApprovalPrompt.effectiveIdleTimeout`）|

无 config 的插件 id：`session-store`、`agents`、`system-prompt`、`llm`、`approval-auto` / `approval-deny`、`tools`、`sandbox-local`、`fs-tool`、`ask-user`、`commands`（25 个 base 行 id = 14 可配 + 11 无配）。

- **插值白名单**（[07](07-profile-bundle.md)）：`${env:NAME}` / `${props:NAME}` / `${cwd}` / `${home}` / `:-默认值`；比较式 `==` / `!=`（右操作数 `null` 或 `'literal'`）——无任意代码；源不在白名单 fail loud。
- **三层叠加**：bundle 行 → profile 行 → CLI flag overlay（AppBoot 装配序）；行动作为互斥 sealed 联合（include / replace / remove / insert）。

## 6. CLI 面（`jh`，dist/jh jlink 镜像）

`jh --help` 为唯一权威清单（本文列面与它对齐）。冻结项：`--workspace=` / `--verify` / `--policy=STANDARD|PRODUCTION` / `--approval=auto|ask|deny` / `--approval-timeout=<秒>` / `--budget=` / `--docker [--image=]` / `--resume=` / `--sessions[=<N>]` / `--provider=` / `--model=` / `--base-url=` / `--api-key-env=` / `--api-key=` / `--profile=` / `--help`。REPL：非 `/` 行成轮；`/help` / `/exit`（EOF 同）/ `/cancel`（it22：取消在途轮，同 Ctrl-C 收敛；空闲回「无进行中的轮」）；每轮末渲染一行轮末统计（`stats:` 见下）。`--verify` 无 key 可跑，exit 0/1；通过时 stdout 打印治理摘要（07 §6）。`--sessions` 只读旁路（keyless、不建会话；与任务文本 / `--resume` / `--verify` 互斥——一次只做一件事）。

一次性任务的结果契约（it17 起，输出形状的稳定承诺）：

| 退出码 | 语义 |
|---|---|
| 0 | 任务完成（本轮 `turn/end` Completed；`--verify` 通过 / `--help` 同） |
| 1 | `--verify` 违规（不变） |
| 2 | 用法错误 / 缺少 API key（不变） |
| 3 | 任务失败（`turn/end` Error：厂商错误 / 守卫或预算超限 / `--resume` 写者锁冲突;无 turn/end 同归 3） |
| 4 | 任务被取消（`turn/end` Aborted;REPL 路径不适用,恒 0） |
| 5 | 等待人工答复（it22：本轮 Completed 且含 `concludesTurn` 的 `tool/result`——模型经 `ask_user` 提问停轮） |

- **stdout 契约 = 成功有结果 / 等待答复有提问 / 失败为空**：成功时 stdout 为本次运行新开轮最后一条 `assistant/message` 的文本（纯文本）;**成功但最终文本为空 → stdout 空 + exit 0**（合法成功）;**等待答复（exit 5）→ stdout 为该轮 `concludesTurn` 结果的内容（提问文本）**;**失败（Error / Aborted / 无终局）→ stdout 恒为空**——脚本判读无歧义。
- **一问一答闭环**（it22，exit 5 的用法）：答复 = **下一轮 user message**（`jh --resume=<id> "答复文本"`；REPL 下提问行后的下一行即答复）——提问与答复都是日志事实，无常驻等待、无第二输入通道；非交互场景因此结构上不可能挂等（对照 §6 等待界：审批**有**输入通道，故需 `--approval-timeout=`）。
- “本次运行新开轮” = `seq >= Session.firstLiveSeq()`（seed 长度）;`--resume` 续跑不误判旧轮文本与旧终局。
- 诊断（会话 id、事件清单、`FailureKind` 文案、模型遗言）全部走 stderr;kind 级分辨读 stderr 文案,退出码保持三态粗粒度（不设预算专属码）。**成功路径** stderr 另有一行轮末统计（it20）：`stats: turn=… steps=… tokens_in=… tokens_out=… elapsed=…s`——与 REPL 轮末行同形，数据源全在事件流（`step/start` 计数、`assistant/message` 的 usage 求和、`turn/start→turn/end` 事件时间差）；stdout 契约不受影响。失败路径不另打统计行；**等待答复（exit 5）**同成功路径打统计行，并追加一行答复指引（`jh --resume=<id> "答复文本"`）。
- **`--resume=` 占用拒绝**（it19，单写者保护）：目标会话正被另一写者（另一进程 / 同 JVM 另一 Runtime）占用时,启动期以「写者锁冲突」文案拒绝（exit 3、stdout 空）——不等待、不并发写;写者锁随进程死亡释放（崩溃/SIGKILL 后可直接 resume,不需要人工清理）。

Ctrl-C（SIGINT）契约（it18 起，随 §1 语义声明）：

- **一次性任务**：Ctrl-C → `cancel(User)` → 收敛后 `turn/end(aborted("user"))`、exit 4（stdout 空）；不 `System.exit` 硬退——主线程自然走完收敛与落账。**REPL**：Ctrl-C 取消当前轮并清空排队输入、不退出（空闲 Ctrl-C ≡ `/exit`/EOF，exit 0）。
- **再按一次逃生门**：上次取消尚未收敛（同一活动）时再按 Ctrl-C → halt(130)（不跑钩子，收敛卡住时可强退）。
- **取消仅对协作面生效**：整批工具无视取消并正常返回时，turn 以 Completed 收口（exit 0）——不合作工具会拖住收敛，逃生门即为此备（04 §8 不合作边界）。

## 7. 变更程序

- 与本文冲突的实现改动：**同提交**先改本文，禁止静默漂移（[AGENTS](../../AGENTS.md) 文档即事实源）。
- 稳定面增删：随 0.2.0+ 发布按 §1 语义声明；0.1.x 内的新增（新包 / 新可选 flag / 新 config 键）允许，但须同提交补进本文。
- 核对方法被固化：导出面 = `module-info.java` 扫描；配置键 = 插件 config 读取点；CLI = `jh --help`——每次发布验收重跑核对。
