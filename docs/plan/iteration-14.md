# 迭代 14 — 交互面：REPL + 流式渲染（状态：已完成——CI 待 push 放行后复验）

模块：`interaction/commands`（stub → 落地）、`examples/headless`（REPL + 流式渲染器）、`core/session`（`TurnEndReason.Error` 增失败分类）、`core/agent-loop`（`assistant/chunk` 落账 + 溢出判定读 Kind）、`llm/llm` + `llm/openai-compat`（`Kind.OVERFLOW` 与厂商分类）、`session/persistence-jsonl`（codec）、`bundle/base`（commands 行）

四确认日期：2026-09-15（用户确认草案；条件已照办：三条补充入「设计增量」——词表家与依赖环 / 渲染出锁 / OVERFLOW 并入 S2；S2 锚点表含 `AgentLoopImpl.isContextOverflow` 行）

## 四确认

- **内容**：
  1. **`interaction/commands` 落地**（05 §9 清单 stub → 实装；dsh `@deepseek-ai/dsh-commands` 形状）：`CommandRegistry`（register 即 effect、重复名 fail loud、list/find）+ `parseCommand`（行首 `/`、名 `[a-z0-9_-]+`、rawInput = 其余原文含空白）+ `command/run`/`command/done` log-only 事件对 + bundle `commands` 行。契约要点：处理前先落 `command/run`、settle 落 `command/done`（异常路径也落）；未知 `/` 行由适配器拒绝、**不送模型**；命令结果只进屏幕与日志、**不进模型历史**（dsh 契约）。
  2. **REPL（examples/headless）**：裸 `jh`（无 task、非 `--verify`）进入交互循环——`/` 行走命令面；非 `/` 行 → `agent.followup(...)`（各成其 turn，输入与模型运行并行、天然排队）；内置 `/help`、`/exit` 由适配器注册进 registry；EOF ≡ `/exit`；`/exit` 走既有 dispose 链（cancel(Disposed) → 静止 → scope 回收），进行中的轮以 aborted 落账、`--resume` 可续；`--resume=<id>`（无 task）在既有会话上进 REPL；`--workspace/--approval/--docker/--image/--policy/--profile/--provider/--model/…` 全 flag 照常。
  3. **流式渲染 + typed 失败渲染**：`assistant/chunk`（03 §1 设计态）落地为 log-only 事实；渲染器 = `SessionEvents.APPENDED` 订阅者，逐块渲染模型输出、工具一行摘要；turn 失败按 `TurnEndReason.Error` 的 kind 渲染（兑现 05 §3 it12.6 承诺：AUTH 引查 key、RATE_LIMIT 稍后重试……不解析消息文本）。

- **目标**：路线 it14「交互面：REPL + 流式渲染」可交付；命令面从占位转实装（05 §9 清单第 8 行）；it12.6 落的 `Kind` 词表获得第一个消费者。

- **为什么**：① headless 今日只有一次性形态，交互面是路线既定（README §路线），且 durable resume 的自然消费方就是它。② dsh commands 契约（注册即 effect、run/done 落账、结果不进模型历史）是命令面既有形状，JH 已有 ExtensionEvent + ServiceLoader codec 承接机制（todo/plan 两先例）。③ 流式渲染必须有 chunk 事实：03 早已设计 `assistant/chunk`（log-only、投影不读），dsh 也持久化该事件（TTFT/遥测）——本迭代兑现，渲染器才有逐块数据。④ 05 §3 写着「消费方（it14 交互面）按 kind 渲染」——欠的债本迭代还，且把 OVERFLOW 一并收编（防第三次挂账）。

- **不做**：
  - Ctrl-C（SIGINT）优雅取消——**挂账**：进程退出 + durable resume 兜底（`--resume` 续跑即恢复路径）；中断处理另片
  - one-shot `jh "任务"` 的输出形态（事件清单）与语义——**不动**（CI 冒烟稳定 + it13 契约）；命令面只在 REPL 生效，one-shot 任务文本原样送模型
  - 富终端体验（多行编辑、历史、补全、颜色主题）与 ACP/UI——纯行式 REPL
  - 命令集扩展（/model、/preset、/compact、/resume…）——只收 `/help`、`/exit` 两枚，先把命令面机制立起来
  - 运行中输入 → steering（NEXT_STEP 认领）——非 `/` 行统一 followup 语义；steering 留后续
  - Windows 平台面、镜像压缩收窄、发布工程（it16）
  - 除 `TurnEndReason.Error` 增组件（本迭代已列）外的公开 API 破坏

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：
  - `interaction/commands` 落地：`CommandRegistry`（Definition；`register` 即 effect 返回 `Disposable`、重复名 fail loud）+ `Command` / `CommandContext`（name、rawInput）/ `CommandResult`（sealed：渲染文本 / 退出）+ `parseCommand`；插件 id `commands`
  - `command/run` / `command/done` 事件对（ExtensionEvent，log-only，`ignorable=true`；run 先于 handler、done 在 settle——异常也落；done 携成功/失败与结果摘要）
  - `assistant/chunk` 事件（03 §1 设计态 → 实现）：`type="assistant/chunk"`、log-only、`ignorable=true`
  - `TurnEndReason.Error` 增失败分类组件 + core/session 新词表 enum（`AUTH/RATE_LIMIT/SERVER/NETWORK/TIMEOUT/PROTOCOL/OVERFLOW/UNKNOWN`）
  - `LlmCallException.Kind.OVERFLOW`（llm 词表新变体；`retryable()` = false——溢出由压缩恢复，非传输重试可愈）
  - 渲染器（examples/headless 内）：APPENDED 订阅只入队 + 单渲染虚拟线程按序出队写终端
  - REPL 循环（examples/headless 内）：行读入 → 命令面 / followup 分流；审批问句与 REPL 行通道合流（见补充 6）
- MODIFIED：（旧 → 新）
  - `AgentLoopImpl.runStepLoop`：`chunks.toList()` 一次性折叠 → 边消费边落 `assistant/chunk`（逐 chunk 入账后仍折叠装配）
  - `AgentLoopImpl.isContextOverflow`：消息文本匹配（it10 权宜）→ 读 `LlmCallException.Kind.OVERFLOW`
  - `AgentLoopImpl` turn 失败收敛：`Error(message)` → `Error(message, kind)`（llm Kind → session 词表穷尽映射；非 LLM 意外 → UNKNOWN）
  - `OpenAiCompatAdapter`：非 200 路径读错误体——400 + 溢出厂商信号 → `Kind.OVERFLOW`（分类发生在知 wire 事实的层，it12.6 原则）
  - `CoreCodecs`：turn/end 的 Error 增 kind 编解码；**旧日志缺 kind → 回放缺省 UNKNOWN**（message 保留、渲染同今日，durable resume 不断）
  - `bundle/base/…/bundle.yml`：增 `- plugin: commands` 行
  - `interaction/commands/module-info.java`：requires core.session + session.persistence；provides Plugin + SessionEventCodec
  - `examples/headless`：裸 `jh` 从 exit 2 → REPL；module-info requires interaction.commands；USAGE 增交互用法
  - 设计文档同步：03 §1（permits/事件清单与实况同步 + chunk 机制结论）、05 §3/§9（typed 渲染承诺落地、Commands 行 stub → 实装）、02 Interaction 节、README 运行段 + AGENTS「跑一个 task」
- REMOVED：
  - `isContextOverflow` 的字符串匹配分支（被 Kind 分类替代）
  - `InteractionCommandsModule` 标记类（骨架完成使命，删）

### 落盘补充（确认时要求的三条 + 两条要点钉）

1. **词表家与依赖环**：失败分类词表以 `core/session` 为家——`llm/llm` requires session，`TurnEndReason.Error` 引用 `LlmCallException.Kind` 会成依赖环（JPMS 编译期即拒）。agent-loop 做 llm Kind → session 词表的**穷尽 switch 映射**（llm 词表加变体 → 映射点编译期强制更新，防漂移）。Error 增组件是 schema 变更：新日志写 kind；**旧日志缺 kind → 回放缺省（UNKNOWN），渲染回退 message-only**。
2. **渲染出锁**：APPENDED 观察者在 session monitor 内同步执行（`SessionStore` notifyOrdered 在 append 路径）——渲染器回调**只入队**（不写终端、不做 IO），单渲染虚拟线程按序出队写 stdout。若在回调里直接写终端，每 chunk 的 IO 会卡住 append/flush 屏障（chunk 风暴下整条落账链路被拖慢）；dsh 同样把渲染移出 session 线程。
3. **OVERFLOW 并入 S2**：`Kind.OVERFLOW` 本迭代落（适配器厂商信号分类 + `isContextOverflow` 改读 Kind，it10 字符串匹配退役）。不并入即为该判定的第三次挂账——故跨模块效应一并进 S2 停点。
4. **`assistant/chunk` 机制 pin**：结论 = **ExtensionEvent + ServiceLoader codec（家：core/agent-loop）**，非 sealed permits。依据：`StreamChunk` 属 llm（02 §归属修正），`llm requires session`——core/session 的 sealed 记录承载不了 `StreamChunk` 载荷（成环）；在 session 另造 chunk 词表则同一 wire 事实两个家（违「一个事实只有一个家」）。agent-loop 同时可见 llm + session 且是 chunk 生产者；codec 走既有 `SessionEventCodec` SPI（todo/plan 两先例）。03 现行 permits 清单本就落后于实况（TodoWriteEvent 实装在 todo 模块、Compaction\*/RequestHeader 已落地）——同步窗口在本迭代。
5. **chunk 风暴量测试**（各一行断言，不造大 fixture）：① 每 chunk 往返 jsonl（落账后 replay 同序同量）；② APPENDED 观察者按序收全；③ compaction `estimateTokens` 对 chunk 事件 = 0（估价器只认 surface 三类）。
6. **REPL 审批合流**：REPL 模式下 stdin 只有一个读者——`--approval=ask` 的裁决行由 REPL 行通道转交 pending 审批请求（问句由既有 ApprovalPrompt 打 stderr，不改 prompt 形状），不引入第二个 stdin 读者（双读 = 行窃取竞态，与 fail loud 相悖）。

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `interaction/commands/…/module-info.java` + 骨架类 | 契约落地：requires session/persistence、provides Plugin + codec、删标记类 | ☑ |
| `interaction/commands/…/*`（新） | `CommandRegistry` / `parseCommand` / 事件对 / codec | ☑ |
| `bundle/base/src/main/resources/META-INF/harness/bundle.yml` | 增 `- plugin: commands` 行 | ☑ |
| `core/session/…/TurnEndReason.java` | `Error` 增 kind 组件 + 词表 enum | ☑ |
| `session/persistence-jsonl/…/CoreCodecs.java` | Error kind 编解码 + 旧日志缺省 UNKNOWN | ☑ |
| `llm/llm/…/LlmCallException.java` | `Kind.OVERFLOW` + `retryable()` 表 | ☑ |
| `llm/openai-compat/…/OpenAiCompatAdapter.java` | 非 200 读错误体；400 + 溢出信号 → OVERFLOW | ☑ |
| `core/agent-loop/…/AgentLoopImpl.java:runStepLoop` | 逐 chunk 落 `assistant/chunk` | ☑ |
| `core/agent-loop/…/AgentLoopImpl.java:isContextOverflow` | 文本匹配 → 读 Kind；turn 收敛映射 session 词表 | ☑ |
| `core/agent-loop/…/module-info.java` | requires session.persistence + provides codec | ☑ |
| `core/agent-loop/…/CompactionPlugin.java:estimateTokens` | chunk 事件 = 0 测试钉住 | ☑ |
| `examples/headless/…/HeadlessMain.java:run/parse/USAGE` | 裸 `jh` → REPL；渲染器接线 | ☑ |
| `examples/headless/…/module-info.java` | requires interaction.commands | ☑ |
| `docs/design/03-session-event-sourcing.md:§1` | permits/事件清单与实况同步 + chunk 机制结论 | ☑ |
| `docs/design/05-capability-seam.md:§3/§9` | typed 渲染承诺落地、Commands 行实装 | ☑ |
| `docs/design/02-module-layout.md:Interaction 节` + `README.md` + `AGENTS.md` | 状态与运行段同步 | ☑ |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S1 commands 落地 | `interaction/commands` 全量契约（registry/parse/事件对/codec）+ bundle 行 + 聚焦测试 | 已放行（提交 7d2a027）|
| S2 事件 schema 跨模块（chunk + Error kind + OVERFLOW） | `TurnEndReason` / `CoreCodecs` / `LlmCallException` / `OpenAiCompatAdapter` / `AgentLoopImpl`（含 `isContextOverflow`）/ compaction 估价 + 聚焦测试 | 已放行（提交 ac9445b）|
| S3 REPL + 渲染器 | `HeadlessMain`（REPL 分支/渲染接线/用法）+ 聚焦测试（stdin 注入） | 已放行（用户「继续下一步」）；提交待显式放行 |

## 验收（证据 = 实际执行的命令与结果）

- [x] **S1 聚焦测试绿**：`mvn -B -q -pl interaction/commands -am test` 绿（提交 7d2a027）；全量复验 `interaction/commands` surefire 11 tests / 0 败 0 错（注册重复 fail loud + 回收、`/` 行解析与非 `/` 行拒绝、rawInput 原文、run 先于 done、异常也落 done）
- [x] **S2 聚焦测试绿**：提交 ac9445b；全量复验 `core/session` 34 / `session/persistence-jsonl` 13 / `llm/llm` 13 / `llm/openai-compat` 22 / `core/agent-loop` 39 tests 全 0 败 0 错——`turn/end` Error kind 往返 + **旧日志缺 kind → UNKNOWN** 回放；chunk 风暴三则（jsonl 往返同序同量 / APPENDED 观察者按序收全 / `estimateTokens` = 0）；OVERFLOW 分类（假服务端 400 溢出体）；溢出恢复路径改读 Kind 后 it10 既有用例仍绿
- [x] **S3 聚焦测试绿**：`mvn -B -q -pl examples/headless -am test` 12 tests 全绿（`HeadlessReplTest` 4 / `StreamRendererTest` 6 / `ReplApprovalWiringTest` 2，2026-09-15 11:00）——REPL 分流（stdin 注入空流，避 surefire 挂起流坑）/ `/help` `/exit` / 未知 `/` 命令不送模型 / 渲染器入队-出队顺序（含面板行断流式行）/ typed 失败文案 / 审批行通道转交（转发批准 + close 拒）
- [x] **全反应堆 `mvn -B -q package` 绿**：exit 0（2026-09-15，约 13min）；64 个 suite / **357 tests / 0 败 0 错 / 4 跳过**；jlink 镜像重建（`dist/jh/target/jlink-image/bin/jh`）
- [x] **真跑（镜像 launcher）：裸 `bin/jh` REPL**（session `headless-1789443746458-75bb`，exit 0）：多轮 followup 中文流式渲染逐块可见；`/help` 列命令面；`/exit` 与 EOF 均干净退出；`--resume=<id>` 无任务进 REPL 续既有会话（stderr「resume session=…」、续跑模型响应在案）
- [x] **真跑：`--approval=ask` 在 REPL 下走行通道审批**（session `headless-1789443830726-fdc7`）：问句打 stderr（既有 ApprovalPrompt 未改，见设计偏离）；「y」经 REPL 行通道转交 → `fs_read` 真执行（「← hello from it14 e2e」）；「n」→ `← error: denied: denied by human gate: fs_read`；裁决行不入模型历史（日志 user/message 计数不含裁决行）
- [x] **真跑：typed 失败渲染**：坏 key → `turn 失败: 认证失败：检查 API key（--api-key= 或 --api-key-env=）（LlmCallException: deepseek http 401）`——按 `FailureKind` 出可行动文案、厂商细节仅附注；坏 key 真跑 exit 码仍 0，判据看渲染文本与日志（符合预期）
- [x] **回归：one-shot `jh "任务"` 输出形态不变、`--verify` 无 key exit 0**：one-shot stdout 为空、stderr 事件清单 `0: turn/start … 10: turn/end` seq 连续形态不变且**无 `assistant/chunk` 行**（渲染订阅只在 REPL 挂）；`--verify` 无 key exit 0
- [ ] **CI 双 job 绿（push 需用户放行）**：待 push 放行后推送积压提交（434c017 / e8ee1e4 / d0eab5e / dc82b30 / 7a19fd0 / 7d2a027 / ac9445b + 本轮 S3 与文档）并复验

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| it14 补充 6「渲染器呈现问句」 | 审批问句仍由既有 `ApprovalPrompt.stdin()` 直接打 stderr（prompt 未改）；渲染器只承接 REPL 面板与流式输出（stdout） | 不动既有 prompt 形状（改动面最小）；stderr 与 stdout 各自单写者，无合流必要 | 迭代内已同步（补充 6 措辞已订正为「问句由既有 ApprovalPrompt 打 stderr」）|
