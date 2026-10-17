# 迭代 27 — MCP Tools（MCP 工具生态）（状态：进行中）

模块：`mcp/mcp`（Definition：`McpClientService` 契约 + server/工具/错误类型，零第三方）· `mcp/stdio`（Provider：JSON-RPC 2.0 stdio 客户端与子进程生命周期，插件 id `mcp-stdio`）· `mcp/tool`（Consumer：工具注册进既有 `ScopedRegistry` + `mcp/catalog` 事件，插件 id `mcp-tool`）· `core/tools`（`ValueSchema` 扩展：`Opt` 可选包装——跨模块共享词表）· `bundle/base`（装配行）· 文档同步面（五项触发逐一核对：`02` 模块/seam 表 + dsh 映射、`03` 事件 schema、`05` 三角与 schema 边界、`07` 配置键、`12` 导出面；另 `AGENTS.md` 现状段收口时）

路线来源：0.3 顺序 2（`docs/design/README.md:160`）——「**MCP Tools**：先 stdio，再 Streamable HTTP；稳定命名、schema 边界、超时取消、有限重连」，验收「服务重启、重名、畸形结果、大输出有测试；进入审批、审计和资源限制；首版不做 Resources/Prompts」；`:164`「新增协议依赖、注册表归属和作用域规则在对应迭代单独评审」= 本四确认即该评审点；B3 裁定（`docs/plan/evidence/iteration-26/roadmap-diff-proposal.md` §B3）——Resources 二版边界与 dsh 契约要点入本迭代设计增量。dsh 对照（`docs/plan/evidence/iteration-26/dsh-upstream-recon.md` §2.2）：命名 `mcp__<serverName>__<rawName>`、`toolCallTimeoutMs` 60s、重连 500ms→30s ×10、env 清洗。

## 四确认

**四确认已裁定（2026-10-15）：T1/T2/T3/T4 按拟案通过 + 五条补强并入**（T2 歧义防护入内容 2；env 清洗规则入内容 1；启动时机与成本、沙箱关系入内容 1/3；事件名 `mcp/catalog` 定名）。

- **内容**（六件）：

  1. **新模块组 `mcp/`（capability 三角，命名沿 fs/skill 先例）**：
     - `mcp/mcp`（Definition，`harness-mcp-mcp`，JPMS `io.javanatic.harness.mcp`）：`McpClientService` 契约——按 server 取连接面（工具清单快照 / 调用 / 状态）+ `McpToolDescriptor` / `McpCallResult` / 连接错误分档类型；**零第三方依赖**（`requires java.base` 起，对齐 kernel 纪律的模块自持）；
     - `mcp/stdio`（Provider，`harness-mcp-stdio`，插件 id `mcp-stdio`）：**手写最小 JSON-RPC 2.0 over stdio 客户端（T1；Jackson 仅此边界模块）**——换行分帧、`initialize` 协商（协议版本钉 dsh 同口径 2026-07-28，实施期核实修订兼容）、`tools/list`、`tools/call`、通知与错误分档；子进程生命周期（**启动时机 = 装配期（插件 apply/挂载期）**；进程树回收随 scope close，复用 shell/local 先例——**per-session 长命：每个会话拉起全部已配 server（哪怕本轮不用），配置成本在 07 明示**）；超时（dsh 口径 60s，server 级 `timeoutSeconds` 可调）；取消传播（AbortedException 语义衔接既有工具取消链）；有限重连（500ms→30s 退避 ×10）；server stderr → `System.Logger`；**env 清洗 = 缺省最小环境（`PATH`/`HOME`/`TMPDIR`）+ 行配置 `env` 白名单叠加**——不继承全量、不做黑名单（server 以用户全权运行，泄漏面在 env；R4 面）；
     - `mcp/tool`（Consumer，`harness-mcp-tool`，插件 id `mcp-tool`）：已连接 server 工具 → 既有 `ScopedRegistry` 注册（`ToolDefinition`，**`approvalExempt=false` 恒真、无豁免出口**）+ 启动清单落 `mcp/catalog` 扩展事件 + schema 映射（内容 2）；
     - **注册表归属与作用域（评审点定论）**：不新建 MCP 注册表；工具走既有 scoped 注册面（root 层注册、随 scope 回收 R3）；可见性沿用既有 scope 遮蔽语义，零新机制（it26 同款口径）。

  2. **命名与 schema 边界**：
     - 工具名 `mcp__<server>__<raw>`（T2，dsh / Claude Code / Codex 同形）；**歧义防护：server 名与 raw 名拒含 `__`（fail loud）——配置校验（server id，07 面）+ `tools/list` 结果校验（raw 名）两处**——一行规则消掉 `mcp__a__b__c` 拼接二义，且不破坏生态互认；
     - **JSON Schema → `ValueSchema` 白名单映射（schema 边界）**：支持构造 = `type`（object/string/number/integer/boolean/array，递归）+ `description` + `required`（未列者包**新增 `ValueSchema.Opt`**）；`enum` 降级拼接进 description（模型可读、校验不误伤）；不能诚实表达者（`oneOf`/`anyOf`/`allOf`/`$ref` 等）→ **该工具排除 + WARN**（工具级容错、模块级 fail loud 分层——skill/local「损坏跳过」同款方向）；顶层非 object 参数 → 排除 + WARN（`ToolArgs.parse` 恒要求 JSON 对象）；
     - **`ValueSchema.Opt`：wire 零变化**——JSON Schema 序列化输出与现状逐字节一致（现状即无 `required` 数组），`Opt` 仅放宽校验面（`ToolArgs.validate` 对缺失 + `Opt` 放行）；
     - 畸形响应（非法 JSON / 错 id / 缺字段）→ 工具错误结果 fail loud（不静默、不假装成功）。

  3. **治理与进程边界（验收原词「进入审批、审计和资源限制」）**：全部 MCP 工具 `approvalExempt=false`（ask 档全弹；无豁免配置面）；调用经 `ToolExecutor` pipeline（R2 唯一路径）；`tool/call` + `tool/result` 既有事件承载审计（含 server/工具标识可解释）；大输出走既有输出限额；**server 进程与沙箱的关系显式表态：MCP server 是 jh 直接子进程、以用户全权运行、不入沙箱（与 dsh 同口径）——治理边界 = 工具调用面，非进程面**（防「进入资源限制」被误读为进程级约束；shell 子进程在沙箱内与 MCP server 不在沙箱内是两套边界，文档明示）；server 启动失败 fail loud（配置错误；可选性经行 `disabled` 表达式表达，llm 行先例）。

  4. **R1 面（T4 会话内冻结）**：启动清单落 `mcp/catalog` 事件（skill/catalog 同形：变更才落、`ignorable=false`；条目携带可重建注册面的完整信息——name / description / 参数 schema 原文，fold 侧做确定性映射）；**会话内清单冻结**——重连仅续命 + 清单对账；不一致 fail loud，**落点定死：该 server 标记 failed + 当前轮以 `TurnEndReason.Error` 收口 + 诊断提示「server 清单与会话启动不一致，请重开会话」——不静默降级为不可用**；**回放/重建零联网零进程**（工具面从账 fold）。论据：ScopedRegistry 无热变窗口、schema 进请求指纹——热变更清单一律破 R1（与「不做 server 配置热重载」互为表里）。

  5. **装配与配置**：`bundle/base` 两行（`mcp-stdio` + `mcp-tool`，缺省在场——`mcp-stdio` 缺省 `servers: []`，无 server 即零噪声：不拉进程、不落事件、不注册工具）；行配置（07）：`servers[].id`（kebab-case、拒 `__`）/ `.command` / `.args` / `.env`（白名单）/ `.timeoutSeconds`；`--verify` 治理断言扩展（启用组合下：行与插件在场、MCP 工具声明面全数非豁免）；**bundle 行集变化四处消费面同步**（`dist/jh` jlink 清单 / `examples/headless` 叠加依赖 / `integration/consumer-sample` 闭包 / `docs/embedding.md` 计数——AGENTS.md 已固化纪律）+ push 前 `verify-consumer.sh candidate`。

  6. **验收取证**：夹具 MCP server（单文件 Java、无 JH 源码依赖、JDK 直跑；故障注入档：崩溃重启 / 畸形响应 / 重名 / 大输出）；jlink jh 真跑全链（keyless，含配置启停）；模型在环 runbook（待回填，it26 同款）；测试矩阵见验收清单。

- **目标**：0.3 顺序 2 落地——JH 首次可接入用户配置的外部 MCP 工具服务器（stdio）：工具进入审批、审计与资源限制，失败可解释；协议实现自持（零 SDK 依赖），R1–R4 零新范式；路线验收句（服务重启 / 重名 / 畸形结果 / 大输出有测试；进入审批、审计和资源限制）以真跑与测试矩阵取证。

- **验收问题**（每轮一问，README §Validation 句式）：*给 agent 接上一套现成 MCP 工具服务器，从「只能手写 Java 插件」变成「配置一行就能用、且治理不破」？*——今天接外部工具必须写 Java 插件进 reactor 再进 jlink 清单；it27 后：用户在自己的 profile patch 里声明受信 server（command/args/env 白名单），其工具以 `mcp__` 命名注入注册面，审批 / 审计 / 限额全部继承既有治理，清单事实在日志里（R1）。

- **为什么**（现状证据）：
  - **零 MCP 支持**：全仓 `mcp` 检索（源码目录 kernel/core/llm/fs/shell/skill/sandbox/session/interaction/bundle/examples/dist/config，大小写不敏感）**零命中**；设计文档 00/05/06 亦无 MCP 内容——本迭代把 MCP 首次写入设计面；
  - **路线已点名**：0.3 顺序 2 与验收句原文（`README:160`）；「不因路线落盘预先批准具体选型」（`:164`）——本四确认即选型评审点（T1 定论：手写最小客户端，不引 SDK）；
  - **机制先例齐备、无需新范式**：capability 三角（fs/shell/sandbox/skill 五次复用后的第六次）、`ScopedRegistry` + setup window（06）、`ToolExecutor` pipeline（R2）、`ExtensionEvent` + ServiceLoader codec（03）、shell/local 子进程回收、`skill/catalog` 事件形态（it26）——R1–R4 全部走既有点位；
  - **dsh 对照判据在手**（新基线 `5badb15009`，勘察 §2.2）：双 transport、`mcp__<server>__<raw>` 命名、60s 超时、退避重连、env 清洗——协议面口径已核，评审成本已付；
  - **治理面现成**：`ToolDefinition` 声明面 + 审批 stage + 输出限额（it11/it12/it20/it22）——「进入审批、审计和资源限制」不需要任何新执行机制；
  - **行集同步纪律已固化**：it26 follow-up 实撞回填的「bundle 行集变化四处消费面」纪律直接适用（内容 5）。

- **不做**：
  - **Streamable HTTP transport**（T3 切片：stdio 首版；挂 it27.1——HTTP 面 session 头/断线语义是独立风险域）；
  - **Resources / Prompts**（首版不做，路线原词；B3 契约要点——显式 server 选择 / agent-scoped / server instructions 上限 32KiB——入设计增量注记，防二版重设计）；
  - **MCP server 端**（JH 不对外暴露自身工具）；
  - **OAuth / 远程认证 / 市场目录**（供应面 = 用户本机配置的受信命令）；
  - **server 配置热重载**（改配置 = 重开会话/进程；与 T4 会话内冻结互为表里）；
  - **官方 MCP Java SDK / 任何第三方协议框架**（T1；Reactor 形态与「同步写法」纪律冲突）；
  - **server 进程沙箱化**（进程边界表态见内容 3——用户配置的受信子进程，同 docker engine 先例；挂账评估）；
  - **工具级豁免配置面**（无 `approvalExempt` 出口——治理不因外部工具放宽）；
  - **server instructions 进提示面**（首版忽略，二版随 Resources 重访）；
  - **不动 kernel 三模块；不动既有工具/审批/事件面语义**（ScopedRegistry / ToolExecutor / 事件 codec 全复用；`ValueSchema` 只加 `Opt` 不删改既有变体、wire 零变化）；
  - **不做 MCP 专属注册表/可见性机制**（评审点定论：沿用既有）。

## 待裁（已裁定 2026-10-15）

| # | 事项 | 裁定 |
|---|---|---|
| T1 | 协议实现：手写最小 JSON-RPC 2.0 stdio（Jackson 仅 `mcp/stdio` 边界）vs 官方 Java SDK（Reactor） | **通过（按拟案）**：SDK 绑 Reactor + 不定版本面，与「同步写法、虚拟线程」纪律冲突；dsh 自有 mcp-client 立场同构。测试配夹具 server 真跑，不写「平台无关单测假绿」 |
| T2 | 工具命名 `mcp__<server>__<raw>` vs JH snake 变体 | **通过 + 歧义防护**：server/raw 名拒含 `__`（fail loud，配置校验 + `tools/list` 校验两处）——消掉 `mcp__a__b__c` 拼接二义，不破坏生态互认 |
| T3 | 切片：it27 = stdio 首版；Streamable HTTP 挂 it27.1 vs 双 transport 同迭代 | **通过（按拟案）**：HTTP 面独立风险域，与路线「先 stdio，再 Streamable HTTP」字面一致 |
| T4 | 会话内冻结：重连仅续命、清单不符 fail loud vs 热变更事件 | **通过 + 落点定死**：server failed + 当前轮 `TurnEndReason.Error` 收口 + 重开会话提示——不静默降级。论据比拟案更硬：schema 进请求指纹，热变更清单一律破 R1；与「不做热重载」互为表里 |

补强并入（评审意见，2026-10-15）：① T2 歧义防护（内容 2）；② env 清洗显式规则——缺省最小环境 + 白名单叠加（内容 1）；③ 启动时机写明装配期 + per-session 全部拉起成本（内容 1 与 07）；④ server 进程与沙箱关系表态（内容 3）；⑤ 事件名统一 `mcp/catalog`（内容 4）。

## 设计增量（ADDED / MODIFIED / REMOVED）（开工前补齐）

**ADDED**：
- 模块 `mcp/mcp`（`harness-mcp-mcp`，JPMS `io.javanatic.harness.mcp`）：`McpClientService` 契约（按 server 取连接面：工具清单快照 / 调用 / 状态）+ `McpToolDescriptor`（name/description/参数 schema 原文/来源 server）/ `McpCallResult` + 连接错误分档类型；零第三方依赖；
- 模块 `mcp/stdio`（`harness-mcp-stdio`，插件 id `mcp-stdio`）：JSON-RPC 2.0 over stdio 最小客户端（换行分帧 / initialize 协商 / tools/list / tools/call / 错误分档）+ 子进程生命周期（装配期启动、进程树回收、stderr 转日志）+ 超时/取消/有限重连（500ms→30s ×10）+ env 清洗（缺省最小环境 + 白名单叠加）；
- 模块 `mcp/tool`（`harness-mcp-tool`，插件 id `mcp-tool`）：工具注册（`ScopedRegistry`，`approvalExempt=false`）+ `mcp/catalog` ExtensionEvent（`ignorable=false` + ServiceLoader codec；条目 = server id / 工具 name / description / 参数 schema 原文）+ schema 白名单映射（`Opt`/`enum` 降级/不支持排除 WARN）；
- 事件词表：`mcp/catalog`（同 `skill/catalog` 形态；变更才落；空清单不落/退役语义随 skill 先例）；
- `ValueSchema.Opt`（core/tools 共享词表扩展：sealed permits 加 `Opt(ValueSchema)`；wire 零变化，`ToolArgs.validate` 缺失 + `Opt` 放行）；
- 工具命名规则（`mcp__<server>__<raw>` + `__` 拒绝两处校验）与 env 清洗规则（缺省最小环境 + 白名单）的文档存在处（02/05/07）；
- 配置键（07）：`mcp-stdio` 行 `servers[]`（id/command/args/env/timeoutSeconds）+ `mcp-tool` 行说明；
- `bundle/base` 装配两行（`mcp-stdio` + `mcp-tool`）；
- 新依赖边：`mcp/stdio` → `mcp/mcp`（+ Jackson 边界）；`mcp/tool` → `mcp/mcp` + `core-tools`（02 依赖列登记）；
- 装配单线程假设（评审 F2 注记，2026-10-16）：`mcp-stdio` 的 `apply` 全周期同步完成于装载线程（装载契约：`PluginLoader.loadAllUnder` 同步 apply，回滚/收尾在其返回后；并发 close 属框架误用面）；`apply` 内加 cheap 护栏（start 后发现 `scope.isClosed()` 即自收口 + fail loud）——effect 注册为 check-then-act，完整不变量以装载契约收口。

**MODIFIED**：
- `02-module-layout.md`：模块表 + seam 三角表 + 依赖图 + dsh 映射行（`mcp/mcp-client` → `mcp/stdio` 映射注记）；
- `03-session-event-sourcing.md`：ExtensionEvent 生产实例清单加 `mcp/catalog`（与 `skill/catalog`/`plan/mode` 并列）+ 事件 schema 节；
- `05-capability-seam.md`：三角条目加 MCP + 「schema 边界：JSON Schema → ValueSchema 白名单映射 + `Opt` 扩展」小节 + 进程边界表态（server 不入沙箱）；（`04` 如列工具面/提示面清单则复核，无列不动）；
- `07-profile-bundle.md`：`mcp-stdio` 行配置键节（含 env 白名单语义、per-session 启动成本、server 失败 fail loud）；
- `12-api-stability.md`：导出面加三模块（预发布面）；
- `AGENTS.md`：现状段与 Repository layout 加 `mcp/` 行（收口时）；
- B3 注记（入 02 或 05 一处）：首版不做 Resources/Prompts；二版重访对齐 dsh 契约要点（显式 server 选择 / agent-scoped / server instructions 上限 32KiB）。

**REMOVED**：无。

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| 根 `pom.xml`（modules） | 新增 `mcp` 组注册 | ☑（ace05fb） |
| `core/tools` `ValueSchema` | 新增 `Opt` 变体（sealed permits 扩展）——**跨模块共享词表扩展（停 2 核心评审对象）** | ☑ |
| `core/tools` `ToolArgs.validate` | 缺失 + `Opt` 放行（一次改动，全既有 use-site 回归） | ☑ |
| `core/tools` `ScopedRegistry.register` | **零改动**——MCP 工具复用既有注册面（锚在此防误改）；本批仅序列化重定位（`toJson` 上移 `ValueSchema`，register 语义未动，见停 2 评审记录） | ☑ |
| `core/session` 事件 codec 登记面（ServiceLoader） | `mcp/catalog` codec 挂载（`skill/catalog` 先例） | ☑ |
| `bundle/base` `bundle.yml` | 两行（`mcp-stdio` 缺省 `servers: []` / `mcp-tool`） | ☐ |
| `examples/headless` / `dist/jh` / `integration/consumer-sample` / `docs/embedding.md` | 行集同步面四处（jlink 清单 / 叠加依赖 / 闭包 profile / 计数） | ☐ |
| `examples/headless` `HeadlessMain`（exempt 面） | 核对：MCP 工具无豁免路径（构造即定，无配置出口） | ☐ |
| 设计文档 `02`/`03`/`05`/`07`/`12` + `AGENTS.md` | 上表同步面（同一提交） | ☐ |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1（承载契约 + 新词表） | `mcp/mcp`（`McpClientService` 契约 / 类型 / 错误分档）+ `mcp/stdio`（JSON-RPC 帧 / 协商 / 子进程生命周期 / 重连退避） | ☑ |
| 停 2（跨模块效应 + R1） | `ValueSchema.Opt` 扩展（共享词表——全部既有 use-site 回归）+ `mcp/tool`（schema 映射白名单 / `mcp/catalog` codec / 注册面 / 声明面） | ☑ |
| 停 3（组合面 + 验收取证） | `bundle/base` 行 + `--verify` 断言 + 夹具 server 真跑与故障注入档 + R1 回放 | ☐ |

## 停 1 评审记录（2026-10-16）

**总判：方向放行，附修正**——技术面实质成立、packet 质量高；packet 声明逐项核验属实；F1【中】须在停 2 动工前修，F2/F3【低】处置随停 1。三项处置如下（证据 `stop1-review-round1.txt`）：

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| F1 | 中 | `JsonRpcPeer.request` 等待循环 `checkAbort()` 直接抛出，绕过 `cancelPending`——轮询型 signal（不实现 onCancel）取消时：① pending 槽泄漏 ② 不发 `notifications/cancelled` ③ 不记撤销记忆（晚到响应走 unmatched WARN） | 等待循环 try/catch `AbortedException` → `cancelPending` 后重抛；钉死用例 `pollOnlyCancelNotifiesServerAndDropsLateResponse`（`PollOnlySignal` + 夹具 `--record-cancel-to` + JUL 捕获无 unmatched WARN）；突变 D1（回退修复）/ D2（仅去撤销记忆）各自红→还原→逐字节验真 |
| F2 | 低 | `apply` 启动竞态残余：`onClose` 只覆盖已 start 返回的连接（close 与第 N 个 start 并发时该进程不在回收名单） | 双管：① `apply` 循环 cheap 护栏——start 后发现 `scope.isClosed()` 即自收口 + fail loud；② 装配单线程假设入设计增量（装载契约：`PluginLoader.loadAllUnder` 同步 apply；effect 注册为 check-then-act，插件侧无闭环语义，完整不变量以装载契约收口） |
| F3 | 低 | 饱和跑清理脚本静默失手（`jobs -p` 在无作业控制 shell 下回空，遗留 `yes` 压满 CPU 制造假红） | 固化「同 shell `pkill -x yes` + 收尾 `pgrep -x yes` 验空」入 AGENTS.md 已知坑（按实测形态固化，`jobs -p` 不采用）；本轮饱和轮按新纪律执行 `CLEANED-OK` |

## 停 2 评审记录（2026-10-17）

**总判：通过放行**——26+31 例独立复绿、validate EXIT=0、六项偏离全部合理且已点名待裁决；F1 处置轮合格。三项【低】备注不阻塞（处置见修正表），六项偏离裁决全部接受（见设计偏离表）。证据 `stop2-review-round1.txt`。

亮点（评审点名）：DRIFTED/FAILED 拆分 + MA 突变钉优先级（类型断言防重构退化）；fold == registry.schemas 逐字段相等是 R1 检验最强形态；S1（Opt）独立成批 + 四杀先行，批准边界执行到位。

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-27/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `stop1-focused-test.txt` | 停 1：`mvn -pl mcp/stdio -am test` EXIT=0 + 本模块 34 例（16+4+10+4）逐 suite 计数与计时 |
| `stop1-validate.txt` | 停 1：`mvn -B -q validate` 全 reactor EXIT=0（仅 spotless 惯例警告 4 行） |
| `stop1-repeat-saturated.txt` | 停 1：CPU 饱和（`yes`）重复跑——三轮（加固前，计时互异）+ 一轮（加固后，清理验证 CLEANED-OK） |
| `stop1-mutation-checks.txt` | 停 1：突变 A 对账失效 / B 歧义防护失效 / C 耗尽终态移除，各自红→还原→（复绿并入终局套件） |
| `stop1-flake-and-hardening.txt` | 停 1：收尾期宿主机负载假红（`yes` 遗留）→ 根因取证 → 用例加固（1s→2s，语义不变）与复验 |
| `stop1-review-round1.txt` | 停 1 评审处置：F1 修复 + 钉死用例（终态 35 例复跑绿）+ 突变 D1/D2 红→逐字节还原 + 饱和轮（新清理纪律 CLEANED-OK）+ validate 复跑 |
| `stop2-s1-opt-mutations.txt` | 停 2-S1：`Opt` 豁免 / 在场解包 / wire 解包 / `rawJson` 原文四杀（M1–M4 红→还原）+ core/tools 31 例复绿 |
| `stop2-drifted-refinement.txt` | 停 2 前置批：DRIFTED 精化聚焦 19 例 EXIT=0（4+5+10） |
| `stop2-focused-test.txt` | 停 2：聚焦 26 例（7+8+11）+ 全链回归 274 例（12 模块逐计数，含 core/tools 31 例） |
| `stop2-mutation-checks.txt` | 停 2：突变 MA 漂移优先级 / MB 排除工具过滤 / MC 未知关键字 / MD Opt 包裹，各自红→shasum 逐字节还原 |
| `stop2-validate.txt` | 停 2：`mvn -B -q validate` 全 reactor EXIT=0（spotless 惯例警告 4 行） |
| `stop2-review-round1.txt` | 停 2 评审处置：三项备注（控制流异常豁免注记落地 / transport 中立挂 27.1 / entries 缓存挂账）+ 六项偏离裁决 + 复验 |

## 验收（证据 = 实际执行的命令与结果）

（勾选与证据随工作推进落此。）

- [ ] MCP 工具端到端可用：夹具 server → jlink jh 真跑（keyless，含配置启停与零噪声面）
- [ ] 审批：ask 档 MCP 工具调用弹闸；免审批名单不含 mcp 工具（`--verify` 面）
- [ ] 审计：`tool/call` + `tool/result` 落账（含 server/工具标识可解释）
- [ ] 大输出：既有限额适用于 MCP 结果（测试）
- [ ] 服务重启：崩溃 → 有限重连（退避档）→ 清单一致续用；不一致 fail loud（落点：server failed + `TurnEndReason.Error` + 重开会话提示）
- [ ] 重名：server 内重复 / 跨 server 撞名 / 与内建工具撞名 fail loud；`__` 拒绝规则两处生效（测试）
- [ ] 畸形结果：协议级畸形 → 工具错误结果不静默（测试）
- [ ] schema 边界：白名单映射矩阵 + `enum` 降级 + 不支持构造排除告警（测试）
- [ ] R1：回放/重建零联网零进程（`mcp/catalog` fold）；schema 映射确定性（测试）
- [ ] 行集同步面四处 + `verify-consumer.sh candidate` 双腿
- [ ] 模型在环 runbook（待回填）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| 停 1 修正批（同停 1 提交） | 假红（收尾自助发现）：`McpStdioCallTest#wrongIdResponseIsDroppedAndCallTimesOut` server 超时 1s 与夹具 JVM 冷启共额——宿主机极端负载下握手假红（根因 = 饱和跑 `yes` 遗留 + IDE 并发；证据 `stop1-flake-and-hardening.txt`） | 超时加宽 1s→2s（断言随动：`timed out after 2s` / ≥1800ms；语义不变）；饱和跑清理改 `pkill -x yes` + `pgrep` 验空 |
| 停 1 评审修正批（同停 1 提交） | 评审 F1（中）：取消链轮询路径绕过 `cancelPending`——槽泄漏 + 远端悬挂 + 撤销记忆漏记（消费面接上取消链将成真实语义洞；证据 `stop1-review-round1.txt`） | 等待循环收口（try/catch → `cancelPending` → 重抛）+ 钉死用例 + 突变 D1/D2 |
| 同上 | 评审 F2（低）：`apply` 启动竞态残余（close 与第 N 个 start 并发面） | cheap 护栏（start 后发现 closed 即自收口 + fail loud）+ 装配单线程假设入设计增量 |
| 同上 | 评审 F3（低）：饱和跑清理纪律未固化 | 入 AGENTS.md 已知坑（`pkill -x yes` + `pgrep` 验空；按实测形态，`jobs -p` 无作业控制 shell 下回空不采用） |
| 停 2 评审修正批（同停 2 提交） | 评审备注 1（低）：控制流异常未登记豁免（AGENTS「不用异常做流程控制」） | 两处异常 javadoc 补「控制流豁免」注记（解析器递归下降最近捕获点语义，非错误路径；证据 `stop2-review-round1.txt`） |
| 同上 | 评审备注 2（低）：`requires "mcp-stdio"` 使 consumer 绑死具体 transport | 挂 it27.1（HTTP transport 引入时定 transport 中立 provides/requires 或可插拔清单）；现不动 |
| 同上 | 评审备注 3（低）：`entries()` 每请求全量重映射（每工具一次 JSON parse） | 挂账效率注记（connection 层缓存映射结果；schema 冻结下正确性无虞） |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| `02` Jackson 边界白名单（原：stdio 边界模块） | `mcp/tool` 也需 Jackson（`inputSchema` 字符串原文映射） | 信任边界输入解析，同 core/tools 08 §6 先例 | 收口落：02 白名单 +1 |
| `02` 依赖边（计划书：mcp/tool → mcp/mcp + core-tools） | 增 core-session / session-persistence / core-agent / core-agent-loop / llm | REQUEST 检查点（Agent/AgentEvents/Session）与 codec 落点决定 | 收口落：02 依赖列按实登记 |
| T4 落点措辞「server failed」 | 独立 `DRIFTED` 变体（重连后清单不一致 → 落轮终）；FAILED（进程不可用）不拦轮，call 侧降级 | R1 边界是注册面/请求指纹，非进程存活；清单变才破 R1 | 收口落：03/05/07 按实现措辞 |
| `type: integer` 映射 | `ValueSchema.Num`（wire "number"） | 词表无整数变体；加变体超出「只加 Opt」批准面；弱化仅放宽校验 | 已登记（测试注释）；不新增变体 |
| `Opt` wire 零变化 | `ValueSchema.toJson` + 嵌套 `Serialization`（ScopedRegistry 的 toJson/toNode 上移） | Opt 解包与 fold 重建必须共享唯一序列化点；嵌套类保最小导出面 | 评审接受；收口同步 05/12 导出面措辞 |
| `mcp/catalog` 条目映射告警 | map() 纯函数不落日志；warnings 自包含 label；apply() 单点 WARN | 同一 map 注册面/fold 面共用；确定性是 R1 前提；防每 REQUEST 重复告警 | 评审接受，无需同步 |
