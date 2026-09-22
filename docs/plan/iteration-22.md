# 迭代 22 — 会话操作与人工协作（状态：进行中——S-a 到点，packet 已出待放行；S-b/S-c 待开工）

模块：`session/persistence` + `session/persistence-jsonl`（会话目录只读列举面）· `interaction/ask`（新：`ask_user` 工具）· `core/tools`（`concludesTurn` 通道实装 + 免审批声明）· `interaction/approval`（空闲上限）· `examples/headless`（`--sessions` / `--approval-timeout` / `/cancel` / 提问出口）· 文档（02/03/04/05/07/12 + USAGE + README 双语 + AGENTS）

路线来源：README 0.2.0 表 it22「**会话操作与人工协作**：会话列举、状态、恢复指引、取消入口；与审批分离的澄清问答」——验收「不需手工翻 JSONL 找会话；无交互终端不无限等待输入；拒绝审批与回答问题语义清楚」。

## 四确认

- **内容**（四件）：
  1. **会话列举 / 状态 / 恢复指引**（新增只读面，keyless）：
     - **seam 增量**：`SessionPersistence` 增 `list(int max)` → `List<SessionSummary>`（record：`id` / `createdAt` / `lastActivity` / `eventCount` / `cwd`（`Optional`，seed-only 会话无 `request/header`）/ `busy`）。**有界读**：header.json 全读（小文件）+ `log.jsonl` **首尾各一次有界读**（头部取 `request/header.cwd`，尾部取最后一行 seq → 事件数），不解析全量日志——列举成本与会话体积解耦（it20/it21 有界口径）。
     - **busy 探针**：对 `.writer.lock` 做 `tryLock` 试占（**只探不改**：文件不存在→直接判空闲，不创建；占用→null→busy；空闲→立即释放）。现状单写者锁 `FileLock`（`JsonlPersistence.java:245-274`）随进程消亡由 OS 释放，探针语义准确且零副作用。
     - **CLI**：`jh --sessions[=<N>]`（缺省 20；keyless、不建 agent、不建会话；stdout 每会话一行，序 = `lastActivity` 降序、`id` 升序兜底 tie-break——同机同盘输出稳定）；`--sessions` 与任务文本 / `--resume` 同用 → 用法错误 exit 2。目录不存在 = 空列表 + 一行提示（不是错误——首次使用是常态）。
     - **恢复指引（含失败面修正）**：`--resume=<不存在 id>` 现为 `NoSuchElementException` **裸栈逃逸**（`JsonlPersistence.java:114-115`；headless 只捕 `IOException|CompletionException` 与 `IllegalArgumentException`）——修为 exit 3 + stderr 点名 `jh --sessions` 查可用 id；写者锁冲突文案（既有，点 `--resume`）保留；REPL 横幅补 session id 与续跑提示（现仅启动 stderr 打一次 `session=<id>`，`HeadlessMain.java:380-381`，REPL 横幅 `:603` 不含）。
  2. **澄清问答（`ask_user`，与审批分离）**：
     - **工具**：`ask_user(question)`——模型问一句、人答一句。**实现形状 = 停轮数据（设计预留位实装）**：结果置 `concludesTurn=true`，轮在该 step 后自然收口（`AgentLoopImpl.java:475` 已按此位断轮、codec 已读写该位 `CoreCodecs.java:136/146`、04 §:512/539 已文档化「数据驱动停 turn」——**唯一缺环是 executor 硬编码 `false`**，`ToolExecutorImpl.java:171`）。
     - **答复 = 下一轮 user message**（REPL 下一行即答复；one-shot 用既有 `--resume=<id> "答复文本"`）。问答两半都是日志事实（`tool/call`+`tool/result` / `user/message`），**R1 天然成立、回放不重放提问**；且**非交互场景在结构上不可能挂等**（不需要任何输入通道）。
     - **与审批分离**：工具面声明「免审批」（见设计增量），`approval.require` 对该工具不触发——`--approval=ask` 下提问不先弹 y/N、`--approval=deny` 下仍可提问（问答不是副作用，拒批不该堵住澄清）。语义可辨：审批拒绝 = error result `denied: …`（工具未执行）；提问 = 正常结果 + 停轮 + 下一轮 user message。
     - **出口契约**：one-shot 停轮于提问时 stdout = 提问文本、stderr = 轮末统计 + 续跑指引（`jh --resume=<id> "答复文本"`）、**退出码 5 = 等待人工答复**（新增码；既有 0–4 语义不动）；REPL 渲染器对 `concludesTurn` 结果加可辨前缀（提问行与普通工具输出不混同）。
  3. **非交互等待界**：`ApprovalPrompt.stdin()` 增**空闲上限**（`stdin(Duration idleTimeout)`，0/负 = 不设限）——**非交互终端（stdin 非 terminal）缺省 300 秒**，超时按**拒绝**（fail-closed）并给可行动文案（点名 `--approval=auto|deny`、`--approval-timeout=`）；交互终端（真人可能思考很久）缺省不设限；`--approval-timeout=<秒>`（0 = 不设限）显式覆盖。EOF 即时拒绝语义**不变**（`ApprovalPrompt.java:68-69`）。
  4. **取消入口补全**：REPL 命令面加 `/cancel`（复用 SIGINT 同一收敛路径 `HeadlessMain.java:415` 的 `cancelTurn`）——在途轮取消并 `turn/end(aborted)` 落账、REPL 继续；空闲时明确回一行「无进行中的轮」。命令面现仅 `/help`/`/exit`（`:588-591`），取消只有 SIGINT 且不在 `/help` 可见面。
- **目标**：真实运维动作链在「找不到会话 / 需要澄清 / 无人值守」三处从**难操作或挂死**变成**可验证地完成**：
  - 找会话：`jh --sessions` 一次看全（id/状态/cwd/最后活动/事件数）→ 直接拿 `--resume=<id>` 续跑；不再手工 `ls ~/.harness/sessions` + 翻 JSONL 猜 id
  - 澄清：模型能问、人能答，**不经审批通道**；无人值守时提问以「停轮 + 退出码 5 + 指引」落地，而不是没人答就等到天荒地老
  - 无人值守：`--approval=ask` 在非 TTY（无 EOF、无输入）下**有界等待后按拒绝**，绝不无限挂起
  - 取消：REPL 有可发现的 `/cancel`（`/help` 可见），语义与 Ctrl-C 同收敛
- **为什么**（现状证据）：
  - ① **会话零列举能力**：全仓 Java 主源码 + bundle + CLI 无任何枚举会话目录的代码（`grep --list/--sessions/--status` 无命中；只有测试里 `Files.list(sessions)`）；会话 id 只在启动 stderr 打一次（`HeadlessMain.java:380-381`），REPL 横幅不含——续跑必须先找到它。
  - ② **未知 id 裸栈**：`--resume=<不存在>` 抛 `NoSuchElementException("session not on disk: …")`（`JsonlPersistence.java:114-115`）无人接住——违反 it17 立的失败面契约（失败 = stdout 空 + stderr 诊断 + 退出码 3）。
  - ③ **问答通道空置**：`ToolResultEvent` javadoc 明写「ask_user 类交互工具为 true；**本迭代工具恒 false**」（`ToolResultEvent.java:11-12`），循环（`AgentLoopImpl.java:475`）与 codec（`CoreCodecs.java:136/146`）两侧都已就位——**设计预留、无生产者**；今天模型要澄清只能把问题塞进答案文本等人重开会话。
  - ④ **审批无空闲上限**：`ApprovalPrompt.stdin()` 只轮询取消信号（`ApprovalPrompt.java:52-67`），无超时；非 TTY 且对端不 EOF（管道持有打开）时**无限等待**——USAGE 只承诺「非交互环境(EOF)按拒绝」（`:109-110`），未覆盖这一格。
  - ⑤ **取消入口不可发现**：命令面 `/help` 只列 `/help`、`/exit`；SIGINT 语义只写在 `--help` 里（`:128-134`）。
- **不做**：
  - 不做跨进程取消/信标协议（单写者锁是 R1/R3 地基；外部进程只能看到「占用」状态并据此提示，不引入第二写者路径）
  - 不做会话删除 / 归档 / 清理 / 改名 / 标签（列举只读；清理是独立风险面）
  - 不做问答样式扩展：单问题自由文本，无 options/多选/附件；不做多轮引导式问答
  - 不做子 agent / 委派的问答路由（0.3 委派条目）、不做用户级问答 UI（未来 UI/ACP 经 `ApprovalPrompt` 同形注入，接口已预留）
  - 不做会话日志全文检索（列举只读 header + 首尾有界；「搜日志内容」是另一件事）
  - 不动审批三模式的语义（免审批是**工具属性声明**，不是第四种模式）；不动既有退出码 0–4、stdout 契约（仅新增 5）
  - 不新增第三方依赖；不做真 TTY 自动化测试（CI 无 TTY：等待界用注入超时单测 + 本机真跑取证）

## 裁决记录（2026-09-22）

评审结论：**四确认确认，开工**。四项待裁全部按推荐落地：

| 裁决 | 内容 |
|---|---|
| A | `ask_user` **免审批**（`ToolDefinition` 声明面 + executor 跳过审批 stage；治理摘要点名免审批工具） |
| B | 非交互空闲上限缺省 **300 秒**（交互终端不设限；`--approval-timeout=` 显式覆盖） |
| C | **新增退出码 5** = 等待人工答复（既有 0–4 语义不动；stdout = 提问文本） |
| D | `--sessions` 用 **flag** 形态（`--sessions[=<N>]`，缺省 20） |

**备选与代价（留档，不重开）**：

| # | 事项 | 建议 | 备选与代价 |
|---|---|---|---|
| A | `ask_user` 是否免审批 | **免审批**（ToolDefinition 声明面 + executor 跳过 stage 3）：与审批分离是验收原词；`--approval=deny` 下仍能澄清（问答无副作用） | 不免审批：`--approval=ask` 下先弹 `allow ask_user …? [y/N]`（双重交互，问题本身要人「批准」才能问），`deny` 档模型永远不能澄清 |
| B | 非交互空闲上限缺省值 | **300 秒**（够慢脚本，够早暴露挂起）；交互终端不设限 | 60 秒（脚本慢管道易误杀）/ 不设缺省上限只给 flag（验收「不无限等待」在缺省路径不成立） |
| C | 新增退出码 5 = 等待人工答复 | **新增**：无人值守场景可编程判别（`case $? in 5) 读 stdout 问题 → 人答 → --resume`），stdout = 提问文本 | 不加：脚本只能靠解析 stdout/stderr 判别，语义靠约定 |
| D | `--sessions` 形态 | **flag**（`--sessions[=<N>]`）：与既有 flag 解析同构，零子命令语义 | 子命令 `jh sessions`：需引入「首参非任务文本」的判别，与「裸词 = 任务文本」现状冲突 |

## 验收问题（路线 README：哪一种用户任务从做不到/做不稳/难操作，变成可验证地完成？）

「无人值守/长会话场景下把 agent 用起来」：今天想知道「我有哪些会话、哪个卡住了、怎么续」必须手工 `ls` + 翻 JSONL；模型需要澄清时只能等人重开会话；`--approval=ask` 在管道环境下可能无限挂起；取消只藏在 Ctrl-C 里不在命令面。it22 后：**一次列举拿到续跑命令**（含状态与失败指引）、**一问一答有独立通道且不阻塞任何非交互路径**、**每个等待点都有界且失败可行动**、**取消在命令面可见**。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - `SessionPersistence.list(int max)` + `record SessionSummary(Id<Session> id, long createdAt, long lastActivityMillis, long eventCount, Optional<String> cwd, boolean busy)`（seam；jsonl 实现走首尾有界读 + 锁探针）
  - 新模块 `interaction/ask`（`artifactId harness-interaction-ask`，插件 id `ask-user`）：`ask_user(question)` 工具 + `module-info` + 测试
  - `ToolDefinition.ofExempt(...)`（免审批工厂；名可在停点定）：免审批声明与 `of(...)` 缺省「要求审批」并存
  - `ToolExecutionResult.concluding(content)`（停轮结果工厂；`success`/`error` 缺省 `concludesTurn=false`）
  - CLI：`--sessions[=<N>]`、`--approval-timeout=<秒>`（0=不设限）、REPL `/cancel`、退出码 5（等待人工答复）
  - 测试：`SessionSummary` 列举（有界/缺目录/坏 header/seed-only/锁占用探针）、`--sessions` CLI（排序/上限/互斥用法错误）、`ask_user` 全链（停轮落账 / `approval=ask|deny` 下不产审批 / resume 答复闭环 / 回放不重放）、`ApprovalPrompt` 空闲上限（超时拒绝 + 文案 + EOF 仍即时）、`/cancel` 收敛
- **MODIFIED**：
  - `ToolExecutorImpl.appendResult`：`concludesTurn` 取 `result.concludesTurn()`（不再硬编码 `false`，`:171`）；stage 3 审批对免审批工具不触发（`:140-143`）
  - `ApprovalPrompt.stdin()` → `stdin(Duration idleTimeout)`（空闲上限；调用方 headless 计算有效值：交互终端 0 / 非交互 300s / flag 覆盖）
  - `HeadlessMain`：USAGE（flags/退出码/REPL 命令 + ask_user 出口契约）；`Options` 增 `sessions`/`approvalTimeout`；`finishOneShot` 增「等待答复」分支（stdout=提问 + stderr 指引 + exit 5）；`runRepl` 注册 `/cancel`；横幅带 session id；`--resume` 未知 id 归一到 exit 3 + 指引；治理摘要 `approval:` 行点名免审批工具
  - `bundle/base bundle.yml`：`ask-user` 行（工具面）；plan 档工具清单文本补 `ask_user`（`bundle.yml:30`——提问无副作用，只读期允许）
  - 文档：02（模块表 + 新模块）、03（`ToolResultEvent` javadoc「ask_user 类工具为 true」→ 实况）、04（§「数据驱动停 turn」补 ask_user 实装）、05（工具契约：停轮位与免审批声明）、07（治理摘要口径）、12（§5 配置键 `approval` 等待界 + §6 CLI/退出码/命令面）、README 双语（快速开始补 `--sessions` 与问答闭环）、AGENTS「现状」
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置；行号基线 2b625e1，开工时复核）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `session/persistence/.../SessionPersistence.java:41-45` | 增 `list` + `SessionSummary`（seam 契约；05 同步） | ✓ S-a（代码；05 文档待收尾） |
| `session/persistence-jsonl/.../JsonlPersistence.java:112-115`（dir 解析 / not-on-disk）、`:245-274`（WriterLock）、`:321` / `:390-395`（header 写） | 列举实现：目录扫描 + header 读 + log 首尾有界读 + 锁探针（不创建文件） | ✓ S-a |
| `core/tools/.../ToolDefinition.java:11-12` + `:34-36` | 免审批声明 + `ofExempt`（`of` 缺省要求审批，36 调用点不动） | |
| `core/tools/.../ToolExecutionResult.java:7-25` | `concludesTurn` 位 + `concluding(...)` 工厂（`new ToolExecutionResult(` 仅 2 处：记录自身工厂） | |
| `core/tools/.../ToolExecutorImpl.java:140-143`（审批 stage）+ `:167-172`（appendResult） | 免审批跳过 + `concludesTurn` 贯通 | |
| `interaction/ask/**`（新） | `ask_user` 工具 + 插件（`concludesTurn=true` 结果） | |
| `interaction/approval/.../ApprovalPrompt.java:39-71` | `stdin(Duration)` 空闲上限（超时=拒绝 + 文案） | |
| `examples/headless/.../HeadlessMain.java:88-154`（USAGE）、`:158-180`（Options）、`:223-278`（parse）、`:380-407`（session id / resume）、`:443-512`（finishOneShot / 退出码 / finalAnswerText）、`:555-564`（治理摘要）、`:586-605`（runRepl 命令注册 / 横幅） | 全链出口：`--sessions` / `--approval-timeout` / `/cancel` / 横幅 / exit 5 / 未知 id 指引 | S-a 部分完成（`--sessions` / 横幅 / 未知 id 指引）；`--approval-timeout` / `/cancel` / exit 5 属 S-c |
| `bundle/base/.../bundle.yml:24-32`（工具与 plan 行）+ `:62`（命令面行） | `ask-user` 行；plan 工具清单文本补 `ask_user` | |
| 测试：`JsonlPersistenceTest` / 新 `JsonlPersistenceListTest`（计划名 `SessionListTest`，落在实现模块故改从同模块命名）、`ToolExecutorTest`、新 `AskUserPluginTest`、`ApprovalPromptTest`（新）、`HeadlessMain` 侧 `HeadlessSessionsTest`（新）/ `HeadlessReplTest` / `HeadlessOneShotResultTest` | ①–⑥ 各腿 | S-a 部分完成（`JsonlPersistenceListTest` 7 + `HeadlessSessionsTest` 6 已就位） |
| 文档 `docs/design/{02,03,04,05,07,12}` + `README.md` / `README.zh-CN.md` + `AGENTS.md` | 增量同步面（设计同步触发规则 1/2/3/4） | |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a 会话查看面**：seam 契约（`SessionPersistence.list` / `SessionSummary`）+ jsonl 有界列举与锁探针 + `--sessions` CLI + **失败面反转**（未知 id 裸栈 → exit 3 + 指引） | 锚点 1–2、8 前半 | **到点（packet 已出，待放行；证据 S-a-focus / S-a-regreen / S-a-mutation-A…F）** |
| **S-b 工具契约**：`concludesTurn` 通道实装（`ToolExecutionResult` → executor → 事件）+ 免审批声明（`ToolDefinition`/executor）+ `ask_user` 工具与插件 + **R4 面**（免审批的治理可见性） | 锚点 3–6 | 待开工 |
| **S-c 等待界与出口契约**：`ApprovalPrompt` 空闲上限 + 非交互缺省 + `--approval-timeout` + exit 5 + `/cancel` + 12 §6 出口面 | 锚点 7–8、9 | 待开工 |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-22/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `S-a-focus.txt` | S-a 聚焦回归：jsonl 模块 30（新 `JsonlPersistenceListTest` 7 + 既有 23）/ headless 模块 69（新 `HeadlessSessionsTest` 6）/ 全 reactor 962 tests、0 failures、0 errors、BUILD SUCCESS、EXIT=0；命令经复跑核验（同命令重跑计数逐项一致） |
| `S-a-regreen.txt` | 六突变逐一撤销后同命令复跑：同计数全绿，证明还原干净无残留 |
| `S-a-mutation-A.txt` | 突变：列举排序口径失效（`lastActivity` 降序 → 升序）→ `JsonlPersistenceListTest` 必红（element at index 0/2 错位）；EXIT=1 |
| `S-a-mutation-B.txt` | 突变：列举忽略 `max`（有界切片退化全量）→ `HeadlessSessionsTest` 截断面必红（`sessions: 3/3` 而非 `2/3`）；EXIT=1 |
| `S-a-mutation-C.txt` | 突变：锁探针失去只读前置（`CREATE` 打开 → 凭空建锁文件）→ 「探针不建文件」必红；EXIT=1 |
| `S-a-mutation-D.txt` | 突变：尾窗放大失效（上限压回初始 8 KiB）→ 末条事件行超 8 KiB 时折叠失败必红（`tail unreadable`，Errors=1）；EXIT=1 |
| `S-a-mutation-E.txt` | 突变：互斥校验缺失（`--sessions` 与任务文本可同用）→ 互斥用例必红；EXIT=1 |
| `S-a-mutation-F.txt` | 突变：未知 `--resume` id 出口码失效（3 → 0）→ 恢复指引用例必红；EXIT=1 |
| （待 S-b/S-c 补：工具契约/免审批/空闲上限的聚焦、突变、package、真跑） | |

## 验收（证据 = 实际执行的命令与结果）

- [ ] ① 列举：`jh --sessions`（keyless、不建会话）列出 id / 最后活动 / cwd / 事件数 / 状态，序稳定、缺省上限 20 且 `--sessions=<N>` 生效；会话目录不存在 → 空列表 + 提示不报错
- [ ] ② 恢复指引：`--resume=<不存在>` → exit 3 + stderr 点名 `--sessions`（不再裸栈）；REPL 横幅含 session id
- [ ] ③ 澄清问答与审批分离：`ask_user` 停轮落账；`--approval=ask` 下提问不产审批提示、`--approval=deny` 下仍可提问；审批拒绝（error `denied`）与回答（下一轮 user message）语义可辨
- [ ] ④ 停轮与回放：`concludesTurn=true` 落账且 turn 在该 step 后收口；resume/回放不重放提问、R1 链不变
- [ ] ⑤ 无交互等待界：非 TTY 无 EOF 无输入 → 有界等待后按拒绝 + 可行动文案（不挂死）；EOF 仍即时拒绝；`printf 'y\n' | …` 仍放行
- [ ] ⑥ 取消入口：REPL `/cancel` 取消在途轮（aborted 落账、REPL 继续）、空闲明确提示；与 SIGINT 同收敛
- [ ] ⑦ 突变检查：列举排序/上限、`concludesTurn` 贯通、免审批声明、空闲上限各自破坏必红、还原复绿
- [ ] ⑧ 全量 `mvn -B package` 绿
- [ ] ⑨ 真跑（jlink 镜像）：`--sessions` 列举 + 一问一答闭环（提问 → exit 5 → `--resume` 答复 → 完成）+ 非交互等待界观测
- [ ] ⑩ 文档同步在案（02/03/04/05/07/12 + USAGE + README 双语 + AGENTS）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| | | |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 四确认 1「`log.jsonl` **首尾各一次有界读**（尾部取最后一行 seq → 事件数）」（本 plan 四确认节） | 尾部窗口**有界成长**：单次 8 KiB 起，逐次 ×8 至上限 32 MiB；窗口已覆盖全文件仍无一行可解析才 fail loud（`MAX_SCAN_BYTES`） | 单事件行可超 8 KiB（长 `fs_read` 结果就是一条 `tool/result` 行）——固定 8 KiB 会把**正常会话**判成「尾不可读」而报错；成长只在必要时发生，成本仍与「尾事件体积」成比例而非与日志体积成比例 | 迭代内已同步（本行；停点 packet 点名） |
| 锚点 8 只列 `HeadlessMain` 的 USAGE/Options/parse/run/finishOneShot/摘要/runRepl 面 | 既有诊断文案泛化：`mountedRow` 的「组合清单无 persistence-* 行」→「组合清单无 <prefix>* 行」、`configText` 的「行 <plugin> 缺 config …」→「组合自述失败: 行 <plugin> 缺 config <key>」 | `sessionListing` 复用 `mountedRow` 取 `persistence-*` 行以显示 root；原文案把模块名前缀硬编码在消息里，复用时会印出与调用点不符的措辞 | 迭代内已同步（本行；`HeadlessVerifyTest` 断言同步） |
