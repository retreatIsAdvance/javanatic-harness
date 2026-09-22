# 迭代 21 — 工作区理解与可靠编辑（状态：进行中——四确认与裁决 2026-09-22 裁定；锚点/停点已补齐）

模块：`bundle/base`（workspace 一致性断言 + 行声明）· `examples/headless`（扇出四 pin）· `fs/fs` / `fs/local` / `fs/tool`（edit 唯一匹配 + search + 读后修改保护）· `core/session`（说明事实事件 + codec 归 jsonl）· `core/agent-loop`（轮首装载 + `RequestHeader.cwd` 源）· `core/system-prompt`（说明段原生渲染）· 文档（03/04/05/07/12）

路线来源：README 0.2.0 表 it21「**工作区理解与可靠编辑**：统一 workspace 语义；项目说明加载与来源记录；有界搜索；唯一匹配和读后外部修改保护」——验收「重复匹配拒绝或要求明确选择；不静默覆盖读后外部修改；项目说明进入可回放上下文，但不能提升权限」。

## 四确认

- **内容**：
  1. **统一 workspace 语义**（两件并做）：
     - **提示词与围栏同源**：`RequestHeader.cwd` 现取 `System.getProperty("user.dir")`（`AgentLoopImpl.java:285-287`），与 `--workspace=` 围栏（`fs-local.root` / `shell-tool.workspace` / `sandbox-policy.workspace` 三行，`HeadlessMain.buildOverlays` 769-778 扇出）**不是同一个值**——`--workspace=/x` 运行，模型被告知的工作目录是进程启动目录，而工具被围在 /x。改法：`agent-loop` 行增 `cwd` 配置（缺省回落 `user.dir`，向后等价；base bundle 显式声明 `cwd: ${cwd}`），`AgentLoopImpl` 经构造参数接收；`buildOverlays` 用同一次 workspace 值扇出**四处**。
     - **组合期一致性断言**：`AppBoot` 在 resolve 后校验 workspace 承载键（`fs-local.root` / `shell-tool.workspace` / `sandbox-policy.workspace` / `agent-loop.cwd`）**已出现值必须相同**，不一致 fail loud 逐项列出（消灭「漂移=交集生效」静默语义——`FsToolPlugin` javadoc 现明说该语义）。
  2. **项目说明加载与来源记录**（装载归属 = **agent-loop**，P3 改判）：
     - 轮首与 `RequestHeader` 同节奏落账（loop 先例）：经 **java.base** 读 `cwd` 下 `AGENTS.md`（文件名行配置，缺省 `AGENTS.md`；不引 `fs.fs`——说明文件是组合面输入，不是模型围栏内的工具操作；cwd 与围栏同源由 1 保证）。文件不存在 → 无 section，不报错；存在但读失败 → WARN + 无 section（不杀轮）。
     - **来源记录 = 会话事实**：新核心事件 `project/instructions`（path + sha256 + truncated + content，`ignorable` log-only，形同 `RequestHeader` 遥测位），内容 + codec 归 jsonl 后端核心族；**sha256 与上一条相同则不追加**（防百轮日志被重复内容撑爆），段读**最新一条**渲染。
     - **不能提升权限**：装载路径不触碰 ToolRegistry / SandboxPolicyService / Approval；验证用例以「敌意说明文件」断言工具面、沙箱模式、审批模式逐位不变。
     - `core/system-prompt` **原生渲染**说明段（同 `contextSection` 读 `RequestHeader` 先例），零新增依赖。
  3. **有界搜索**：fs seam 新增 `search`（字面串：`pattern` + 可选 `path`，缺省工作区根）；local provider 实现（不跟随符号链接目录；候选文件 realpath 围栏校验后再读；二进制与超限文件跳过）；工具面注册 `fs_search`；输出 `相对路径:行号:行文本`；有界：匹配上限可配（`fs-local.searchMaxMatches`，默认 200）+ 行文本截断 + 尾部标记 + `truncated` 结构位（同 `Listing` 先例）；到上限即停——收集 ≤ 上限+1 条再排序，内存有界（不重复 it20「先物化全部再排序」的账）。
  4. **唯一匹配 + 读后外部修改保护**：
     - **唯一匹配**：`FsService.edit` 语义定为「唯一匹配或拒绝」——0 次 = 现行为（not found）；≥2 次 = fail loud，消息含出现次数与位置（行号），提示补上下文消歧。修复现状契约漂移：工具 schema 已写「须唯一匹配」（`FsToolPlugin.java:31`），实现与工具描述却是「替换第一处」（`LocalFs.java:151-156` / `FsToolPlugin.java:92`）——**两处文本同改，不留半个矛盾**。
     - **读后外部修改保护（log-fold，四确认已裁）**：从会话日志**折叠**目标路径的最新内容事实——`fs_read` 未截断结果 / `fs_edit` 返回的编辑后全文 / `fs_write` 的 `content` 参数（按 seq 取最新，复用 `ToolArgs.parse` 解析历史实参）；`fs_edit` / `fs_write` 执行前比对当前磁盘内容，不一致 → 拒绝「file changed since read，先重读」。**resume 存活**（事实在日志，跨进程仍在——与 it19 故事一致）、**零进程内状态**。若实现证明代价重（每次编辑全日志扫描），退内存读账本 + 边界写明（记录在案）。**边界明示**：从未读过 / 只读过截断内容 / 路径写法不同源 → 无保护（漏报可接受；不产生误报）。
- **目标**：真实工作区「定位 → 读 → 安全编辑」任务链每一步可验证：
  - 定位：`fs_search` 一次拿到候选位置（不再 `fs_list` 逐层爬）
  - 阅读：有界（it20 已就位）+ 进入折叠事实
  - 编辑：重复匹配被拒（不再静默改错位置）；读后文件被外部修改被拒（不再静默覆盖）；唯一匹配且未变时成功
  - 上下文：工作区 `AGENTS.md` 进入系统提示词，来源（路径+哈希）随会话日志可回放（R1 逐字节重建不破），且不改变任何权限位
  - 一致性：workspace 一处定义、四处一致；不一致 = 启动即失败而非静默取交集
- **为什么**（现状证据）：
  - ① **编辑静默改错位置**：`LocalFs.edit`（142-159）`indexOf` 取第一处直接替换，无唯一性检查；而工具 schema 的 `old_string` 描述已承诺「须唯一匹配」（`FsToolPlugin.java:31`）——schema 与实现两套契约。重复文本（`}`、import、空行）在真实内容前无法消歧，静默改第一处 = REPL 无人复核时的错误源。
  - ② **读后外部修改无防护**：模型读文件 → 用户/shell 工具/其他进程改了它 → 模型继续 edit/write，本地 provider 整文件读改写直接覆盖，无感。
  - ③ **项目说明不可见，且不能靠「提示词直接读文件」补**：`core/system-prompt` 只有注册表（`SystemPromptImpl`：Static/Dynamic），无文件装载概念；若 `assemble` 直接读工作区文件，提示词就依赖环境（同日志不再必同提示词，R1 破）——必须以会话事实承载。仓库自己的 `AGENTS.md` 是 agent 规范，目前模型看不到。
  - ④ **无搜索**：fs 四操作 + list（05 §4）；「在仓库里找到 X」只能 `fs_list` 逐层 + 逐个 `fs_read`，成本随仓库规模爆炸。
  - ⑤ **workspace 漂移静默**：三处围栏（bundle 默认 `${cwd}`）+ 提示词 cwd（`user.dir`）+ CLI `--workspace=` 扇出；`--workspace=/x` 场景提示词里的 working directory 已经是错的，且无任何告警。
- **不做**：
  - 不要求「先读后写」（edit 对从未读过的文件维持现行为）；折叠事实只读日志、不落新账；不做文件锁 / OS watcher
  - 搜索只做文本层（字面串；正则与 glob 留待后续），不做 LSP / 语义索引（0.3 的 LSP 条目）
  - 不做 `replace_all` / 多编辑批（edit 保持单点替换）
  - 不做多工作区 / 多根支持（统一 = 单根一致性；多根是未来能力）
  - 不合并 it19/it20 挂账（`fs_list` 排序内存边界；治理插件命名折叠）；不做用户级（`~/.harness/AGENTS.md`）与嵌套目录级说明文件
  - 不引入新第三方依赖；不动审批面 / sandbox 授予面语义 / stdout 契约 / 退出码 / 既有事件信封布局（新增事件除外）

### 裁决记录（2026-09-22）

评审结论：**草案成立，建议确认**；五项现状问题全部实证（cwd=user.dir / edit 首处替换 + schema 两行矛盾 / 无装载 / 无搜索 / `--workspace` 提示词错位），与路线表 it21 验收逐词对应。

| 裁决 | 内容 |
|---|---|
| **结构性改判** | **P3 装载归属 = agent-loop（非 system-prompt）**：R1 决定读文件必须在 `assemble` 外——轮首落账是 loop 的 `RequestHeader` 先例（同节奏同形状）；system-prompt 原生渲染说明段（同 `contextSection` 读 `RequestHeader` 先例）零新增依赖；避免 spine 模块 `requires fs.fs` |
| **锚点增强 1** | 四键断言爆炸半径：`AppBootTest` 独立 overlay 无 `sandbox-policy` pin 必红须补；`buildOverlays` 全调用方补第四 pin；base `bundle.yml` agent-loop 行加 `cwd: ${cwd}` |
| **锚点增强 2** | 说明事件变化检测：sha256 与上一条相同则不追加；段读最新一条，R1 不受影响 |
| **锚点增强 3** | edit 双文本对齐：`FsToolPlugin` `:31` 字段与 `:92` 描述两处同改（勿留半个矛盾） |
| **锚点增强 4** | read-ledger 的 **log-fold 替代**（设计点，四确认裁）：从 tool/call + tool/result 折叠未截断 `fs_read` 内容——resume 存活（与 it19 故事一致）、零 per-Session 内存态（JH 无先例，`PlanModeService` 刻意纯 fold）；**倾向 log-fold，证重则退内存态 + 边界写明** |
| P1 | 按推荐：字面期承载 `FsService`（ripgrep/LSP 期抽独立 seam） |
| P2 | 按推荐：新核心事件 `ignorable` + codec，估价 0 与系统提示词待遇一致 |
| P4 | 按推荐：不存在 = 静默无 section；存在但读失败 = WARN + 无 section（不杀轮） |
| P5 | 按推荐：读后修改保护覆盖 `fs_edit` + `fs_write`（均整文件覆盖面） |

### 验收问题（路线 README：哪一种用户任务从做不到/做不稳/难操作，变成可验证地完成？）

「在真实仓库里让 agent 定位并修改一处代码」：今天定位靠爬目录、编辑可能静默改错同名文本、读后文件被外部修改会被静默覆盖、仓库 `AGENTS.md` 规范对模型不可见、提示词工作目录与实际围栏还可能不一致。it21 后这条任务链每一步都有拒绝语义与明确边界，且「不安全路径」全部有失败用例证明。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - `FsService.SearchResult`（record：`matches`（path/line/text）+ `truncated`）与 `FsService.search(pattern, path)`（字面串，05 §4 摘录同步）
  - 配置键：`fs-local.searchMaxMatches`（默认 200）/ `agent-loop.cwd`（缺省回落 `user.dir`；base 显式 `${cwd}`）/ `agent-loop.instructionsFile`（默认 `AGENTS.md`）
  - 核心事件 `project/instructions`（path/sha256/truncated/content，ignorable log-only；CoreCodecs 注册）
  - fs-tool 内部 log-fold 折叠器（路径最新内容事实；不新增导出面）
  - 测试：workspace 一致性断言（正/拒路径）、edit 唯一匹配拒绝（含行号）、log-fold 读后修改拒绝 + resume 存活、search 有界（截断/二进制/围栏/排序）、说明装载（渲染/变化不追加/不存在静默/敌意文件权限不变）、headless 四 pin 集成
- **MODIFIED**：
  - `FsService.edit` 契约：唯一匹配或拒绝（javadoc + 拒绝消息含计数与行号）
  - `LocalFs.edit` 唯一匹配实现；`LocalFs` 增 search 实现；`FsLocalPlugin` 键解析（searchMaxMatches）
  - `FsToolPlugin`：`old_string` schema 与工具描述双文本对齐（`须唯一匹配`）；edit/write 前置 log-fold 保护；注册 `fs_search`
  - `bundle/base bundle.yml`：agent-loop 行 `cwd: ${cwd}`；`AppBoot`：四键一致性断言（装配期恒执行）
  - `HeadlessMain.buildOverlays`：第四 pin（agent-loop.cwd）
  - `AgentLoopPlugin` 读行配置（cwd/instructionsFile）→ `AgentLoopImpl` 构造参数；`AgentLoopImpl.runTurn`：`RequestHeader` 用配置 cwd + 轮首说明装载落账
  - `SystemPromptImpl`：原生说明段（读最新 `project/instructions`）
  - 文档：03（事件表 + ignorable 语义）、04（轮首节奏 + cwd 源）、05（§4 摘录：edit 语义 + search）、07（配置键/工作区一致性）、12（§5 配置键表）
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `bundle/base/.../META-INF/harness/bundle.yml:67`（agent-loop 行） | `cwd: ${cwd}` 显式声明 | ✅ S-a |
| `bundle/base/.../boot/AppBoot.java:187-211`（bootReported） | resolve 后四键 workspace 一致性断言（fail loud 列各值） | ✅ S-a |
| `bundle/base/src/test/.../AppBootTest.java:56-59` | 独立 overlay 补 `sandbox-policy` + `agent-loop` pin；新增不一致拒绝用例 | ✅ S-a（16 用例）|
| `examples/headless/.../HeadlessMain.java:769-778`（buildOverlays） | 第四 pin `agent-loop{cwd: workspace}`；javadoc/USAGE 措辞同步 | ✅ S-a |
| `core/agent-loop/.../AgentLoopPlugin.java:59-67` | 读行配置 cwd/instructionsFile → Factory → 构造器 | ✅ S-a（cwd；instructionsFile 待 S-c）|
| `core/agent-loop/.../AgentLoopImpl.java:98-117` + `:283-287` | cwd 构造参数；`RequestHeader` 用配置值；轮首说明装载落账 | ✅ S-a（cwd；装载待 S-c）|
| `core/session/.../event/ProjectInstructions.java`（新） | 事件记录（ignorable，type `project/instructions`） | |
| `session/persistence-jsonl/.../CoreCodecs.java:53-175` | 注册 `project/instructions` codec（`:166` request/header 邻位） | |
| `core/system-prompt/.../SystemPromptImpl.java:48-69` | 原生说明段（读最新 `project/instructions`，与 contextSection 同形） | |
| `fs/fs/.../FsService.java:31-36` + `:49-55` | edit 唯一匹配契约 javadoc；`search` + `SearchResult`（05 §4 摘录同步） | |
| `fs/local/.../LocalFs.java:141-159` + `FsLocalPlugin.java:36-41` | edit 唯一匹配（计数+行号）；search 实现；`searchMaxMatches` 键解析 | |
| `fs/tool/.../FsToolPlugin.java:31` + `:92-96` + `:99-106` | schema/描述双文本对齐；log-fold guard 接入 write/edit；`fs_search` 注册 | |
| `fs/tool/.../` 新类（log-fold 折叠器） | 从 `ToolCallEvent`/`ToolResultEvent` 折叠目标路径最新内容事实（复用 `ToolArgs.parse`） | |
| `fs/local/src/test/.../LocalFsTest.java`、`FsLocalConfigTest.java`、`fs/tool/src/test/.../FsToolEndToEndTest.java` | 唯一匹配拒绝/行号、search 有界、log-fold 拒绝 + resume 存活 | |
| `core/agent-loop/src/test/.../AgentLoopTest.java`、`core/system-prompt/src/test/.../SystemPromptServiceTest.java`、`core/session/src/test/.../SessionEventTypesTest.java` | 轮首装载落账/变化不追加/渲染/敌意文件权限不变/事件 codec 往返 | ✅ S-a 部分（`configuredCwdReachesRequestHeaderAndPrompt`）|
| 文档 `docs/design/{03,04,05,07,12}` | 事件表、轮首节奏、§4 摘录、配置键与一致性口径 | ✅ S-a（04 §6 / 07 §5 / 12 §5；03/05 待 S-c）|

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a 组合面统一（workspace）**：`bundle.yml` / `AppBoot` 断言 + `AppBootTest` 补 pin / `buildOverlays` 四 pin / `AgentLoopPlugin`+`AgentLoopImpl` cwd 源 / 04+07+12 文档 | 锚点 1–6 | **已放行（2026-09-22，有条件——取证重跑落盘 + 勾 ①⑥ 组合面腿 + 41 模块口径；三项随本提交办结）**。取证：`docs/plan/evidence/it21/`（聚焦 `S-a-focus.txt` EXIT=0：AppBootTest 16 / AgentLoopTest 22 / HeadlessOptionsTest 16 / headless 合计 62；突变 A `S-a-mutation-A.txt` EXIT=1 恰红 `workspaceDriftFailsLoudNamingEveryDeclaredValue`；突变 B `S-a-mutation-B.txt` EXIT=1 恰红 `configuredCwdReachesRequestHeaderAndPrompt`；突变 C `S-a-mutation-C.txt` EXIT=1 headless 1 失败 + 9 错误点名漂移键值；三处还原逐字节一致，`S-a-regreen.txt` EXIT=0 复绿）|
| **S-b fs 编辑面（唯一匹配 + log-fold 读后保护）**：`FsService` 契约（承载）/ `LocalFs.edit` / `FsToolPlugin` 双文本对齐 + guard + 用例 | 锚点 10–12 | 待处理 |
| **S-c 项目说明 + 有界搜索**：`project/instructions` 事件 + codec（新词表）/ agent-loop 装载 / system-prompt 渲染 / search seam+provider+tool / 03+05 文档 | 锚点 7–9、13 | 待处理 |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-N/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `docs/plan/evidence/it21/S-a-focus.txt` | S-a 聚焦（① 组合面腿；EXIT=0）|
| `docs/plan/evidence/it21/S-a-mutation-A.txt` | 突变 A：`AppBoot` 漂移断言失效 → `workspaceDriftFailsLoudNamingEveryDeclaredValue` 必红（⑥ 组合面腿）|
| `docs/plan/evidence/it21/S-a-mutation-B.txt` | 突变 B：轮首 cwd 回退 `user.dir` → `configuredCwdReachesRequestHeaderAndPrompt` 必红（①⑥ 组合面腿）|
| `docs/plan/evidence/it21/S-a-mutation-C.txt` | 突变 C：CLI 扇出漏 `agent-loop` pin → headless 用例红 + 消息点名漂移键值（①⑥ 组合面腿）|
| `docs/plan/evidence/it21/S-a-regreen.txt` | 三处突变还原后复绿（含 `git status --short`）|

口径：「41 模块」= `-pl bundle/base,core/agent-loop,examples/headless -am` 的 reactor 闭包构成（3 个目标模块 + 其全部上游依赖模块 = 41 个 reactor 项目，构建日志 `[41/41]`）。

## 验收（证据 = 实际执行的命令与结果）

- [x] ① workspace 一致：`--workspace=` 场景提示词 cwd 与三处围栏同值；四键不一致的组合启动即 fail loud（`AppBootTest` 拒绝用例 + headless 集成）—— **S-a 取证**：`evidence/it21/S-a-focus.txt`（AppBootTest 16 / AgentLoopTest 22 / HeadlessOptionsTest 16 / headless 合计 62）+ `S-a-mutation-A/C.txt`（断言与 CLI 扇出各自必红）
- [ ] ② 唯一匹配：重复匹配 edit 被拒（消息含计数与行号），工具 schema/描述与实现同口径（`LocalFsTest` + `FsToolEndToEndTest`）
- [ ] ③ 读后外部修改：读→外部改写→edit/write 被拒；未读过的文件维持现行为；跨进程 resume 后保护仍在（log-fold；`FsToolEndToEndTest`）
- [ ] ④ 说明：工作区 `AGENTS.md` 进入系统提示词且来源落账（路径+sha256）；内容不变不追加事件；不存在静默；敌意说明不改变工具/沙箱/审批权限位（`AgentLoopTest` + `SystemPromptServiceTest`）
- [ ] ⑤ 搜索：`fs_search` 一次定位命中；输出有界（上限+截断位）；不越围栏、不跟符号链接目录、跳二进制/超限（`LocalFsTest` + `FsToolEndToEndTest`）
- [ ] ⑥ 突变检查：唯一匹配、log-fold 拒绝、说明装载、四键断言各自破坏必红、还原复绿 —— **四键断言腿 ✅ S-a**（`evidence/it21/S-a-mutation-A/B/C.txt`）；其余三腿待 S-b/S-c
- [ ] ⑦ 全量 `mvn -B -q package` 绿
- [ ] ⑧ 真跑：jlink 镜像 `--workspace=` 真任务观测提示词 cwd/`AGENTS.md` 生效/搜索命中；文档同步（03/04/05/07/12）在案

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| dsh：搜索为独立包 `tool-fs-search` | 字面期承载 `FsService.search`（同围栏同 provider 同 root） | 单实现单消费；独立 seam 需要第二个后端（ripgrep/LSP）才有形 | 迭代内已同步（05 §4 措辞）；ripgrep/LSP 期抽独立 seam |
| dsh：说明文件候选列表（多文件优先级） | 单文件名行配置（缺省 `AGENTS.md`），不做候选列表与用户级/嵌套级 | 有意简化；多候选引入优先级语义与来源消歧成本，真实需求出现再补 | 迭代内已同步（04/07 措辞） |
