# 迭代 26 — 技能生态（Skills）（状态：进行中）

模块：`skill/skill`（Definition：契约与注册表）· `skill/local`（Provider：本地发现，插件 id `skill-local`）· `skill/tool`（Consumer：`skill` 工具 + 目录装载，插件 id `skill-tool`）· `bundle/base`（装配行）· 文档同步面（五项触发逐一核对：`02` 模块/seam 表 + dsh 映射、`03` 事件 schema、`04`/`05` 如列提示段/契约清单、`07` 配置键、`12` 导出面）——与 A 表订正面重叠：`02-module-layout.md` ×3、`03-session-event-sourcing.md`、`10-testing.md`、`iteration-24.md`、`iteration-14.md`

路线来源：0.3 顺序 1（`docs/design/README.md:159`）——「**Skills**：本地发现、按需加载、来源和优先级、作用域可见性；分离部署供应与 Agent 消费」，验收「可移植到独立项目；技能中的命令仍走既有工具治理，不另开执行通道」；0.3 目标句 `:155`。前置勘察（it26 前置，2026-10-08 放行）：`docs/plan/evidence/iteration-26/dsh-upstream-recon.md`（四档勘察报告）+ `roadmap-diff-proposal.md`（②③提案；**A 表 6 条归本迭代**，B 表按项留作后续迭代四确认输入，C 表负结论维持）。

## 四确认

- **内容**（六件；**四确认已裁定（2026-10-09）：T1/T2/T3/T5 按拟案，T4 采选 B——见裁决记录与子问题裁定**）：

  1. **dsh 引用订正 6 条（A 表——勘察 ③ 类收口；随本迭代首笔文档改动落地）**：
     - A-1 `02-module-layout.md:41,:767`：dsh 包列 `preset/agent-presets` → `preset/agent-preset`（组合层）+ `preset/agent-preset-registry`（注册表）——2026-09-21 `d1e22a7e24` 拆分（`composeFrom` 在 registry）；
     - A-2 `:771`：`llm/llm-replay` → `test-support/llm-replay`（`git log --follow -M` 链已查清：根级扁平 → `support` 组 → `test-support` 组；`llm/` 组形全历史从未成立）；
     - A-3 `:778`：`interaction/approval` → `interaction/user-approval`（JH 侧 `interaction.approval` 模块名**不动**）；
     - A-4 `iteration-24.md:14,:38`：`native/landlock-run` → `native/system`（Landlock = 其 `landlock-run` 子路径；保留历史语境注记）；
     - A-5 `10-testing.md:37` + `03-session-event-sourcing.md` §7 首行：dsh runtime invariants 2026-09-30 `f028f25667` 已整体移除 → 改写为历史出处（JH `SessionInvariants` 是自有形态、非移植；退役教训留对照）；
     - A-6 `iteration-14.md:16`：`assistant/chunk` 持久化句加版本限定（分析期 v0/v1 顶层持久化；v2 起 `assistant/message` 内嵌 compact stream / log-only `assistant/attempt`）。
     - 「不改两处」复核结论在案：`03:173` `TurnEndReasonMap` 引用仍有效（live 于新基线 `packages/core/session`）；`12-api-stability.md:48` 表列 JH 自身模块。逐条「现文/事实/建议改法/若错靠什么发现」= 提案 A 表（同目录证据档）。
  2. **skill 三角角色模块（新三模块；02 模块表 + seam 三角表 + dsh 映射行三层同步）**：
     - `skill/skill`（Definition，`harness-skill-skill`）：`SkillService` 契约（catalog 快照 / 按名装载）+ `SkillSummary`/`SkillDefinition` 类型 + 注册表（provider 注册、合并、近者胜遮蔽——首版单 provider，形态对齐工具注册面）；
     - `skill/local`（Provider，`harness-skill-local`，插件 id `skill-local`）：本地根发现——四根：项目/用户 × `.harness`/`.agents`，优先级单条规则 = 项目覆盖用户、同层自有优先（`${cwd}/.harness/skills` > `${cwd}/.agents/skills` > `${home}/.harness/skills` > `${home}/.agents/skills`；T4 采选 B，dsh 同构 rank 100/200/400/500）；格式 = `<root>/<name>/SKILL.md` 目录束；frontmatter 必填 `name`+`description`（目录名即技能名，kebab-case 校验）；条目非法/损坏**跳过并告警**（不杀轮——dsh 同款容错）；一层深、不做嵌套。命名依 trio 规则取部署形态词（`local`——`05:10/:24` 先例 `local / sandbox / replay`；对齐 `fs-local` / `shell-local` / `sandbox-local` 家族），对应 dsh `skill/skill-filesystem`，02 dsh 映射行注记；
     - `skill/tool`（Consumer，`harness-skill-tool`）：`skill(name)` 装载工具（返回正文 + 来源头）——纯读文本，无新执行通道；装载工具的审批声明 = T2。
  3. **目录装载（catalog 通道——R1 零新机制）**：`skill/tool` 挂 `agent/pre-step` 监听（`AgentEvents` 公共挂点，轮首一次、先于首个 `assemble`；**agent-loop 零改动**，T3）→ 目录快照 → 与日志最新 `skill/catalog` 事件比对（条目 = name / 描述截断 / 来源根）→ 变更落账新事件 → 提示段 = `PromptSection.Dynamic` 读最新事件（it21 `ProjectInstructions` + `plan:policy` 同款：目录事实在日志里，回放零磁盘依赖）。要点：① 目录**不入工具描述/schema**（请求指纹含 schema 指纹、须日志可复现；目录走事件 + 提示段）；② 事件 = ExtensionEvent + ServiceLoader codec、`ignorable=false`（静默丢会毁 R1 重建——`plan/mode` 同款理据，`03:448`）；③ 提示语含「先装载再行动、不得据摘要推断指令」（防双重装载/防摘要幻觉）；④ 无技能 = 不落事件不出现；曾有过后清空 = 落空事件退役（dsh 空目录语义）；⑤ 描述截断上限 = 配置键（缺省 500，dsh 同名口径）。**新依赖边**：`skill/tool` → `core-agent-loop`（首个非 examples 依赖 agent-loop 的模块——实施时在 02 依赖列登记）。
  4. **来源、优先级与作用域可见性**：同名近者胜——四根单条规则：项目覆盖用户、同层自有优先（选 B）——且遮蔽即告警；catalog 条目带来源根（模型可见、可解释）；roots 经 `skill-local` 行配置可换；可见性沿用既有 scope 层级/遮蔽规则（工具注册面同款），本迭代不新增可见性机制。
  5. **治理边界（验收原词：不另开执行通道）**：技能正文 = 纯指示文本，装载不改变任何权限位（it21「进入提示词但不提升权限」同款）；正文指示的命令仍只经既有 shell 工具 → 审批 + 沙箱；`--verify` 治理断言覆盖新行与新工具声明面。
  6. **装配、配置与验收取证**：`bundle/base` 三行（注册表 / provider / consumer；workspace 单源词汇承接 it21——项目根取 `${cwd}`）；配置键 = roots、描述截断上限（07 同步）；验收取证 = 独立夹具工程（**非 JH 仓**，无 JH 源码）放技能 → jh 真跑装载闭环（「可移植到独立项目」）+ ask 档技能内命令仍弹审批、sandbox 仍拦的端到端 + 用例矩阵（同名遮蔽 / 损坏跳过 / 空目录退役 / 无技能零噪声 / 截断）+ R1 回放（`skill/catalog` 事件在日志，组装确定不触盘）。

- **目标**：0.3 首个能力面落地——技能从「代码零支持」到「项目内可移植技能：本地发现、按需加载、来源与优先级、作用域可见性」；验收句两点（可移植到独立项目 / 不另开执行通道）以真跑与端到端取证；A 表 6 条同行收口（前置勘察 ③）。

- **验收问题**（每轮一问，README §Validation 句式）：*给 agent 装上「项目/个人专有知识」的任务，从难/做不到变成可验证地完成？*——今天要让 agent 按专有流程与约定工作，只能把内容塞进任务文本或写进 `AGENTS.md`（全量常驻提示词、无按需、无来源与遮蔽语义）；it26 后：技能 = 项目内可移植文件，模型先见目录、按名装载正文、来源与遮蔽可解释，装载与技能内命令都不绕既有治理。

- **为什么**（现状证据）：
  - **零技能支持**：全仓 `skill` 检索（大小写不敏感、Java 源码）唯一命中是 `DockerShellExecutor.java:281` 局部量假阳性（`processKilled`）——无任何发现/装载/提示面代码；
  - **路线已点名**：0.3 顺序 1 与验收句原文（`docs/design/README.md:159`）；「不因路线落盘预先批准具体选型」（`:164`）——本四确认即选型评审点；
  - **机制先例齐备、无需新范式**：it21 `ProjectInstructions`（轮首装载 → 落账 → Dynamic 段读数，`AgentLoopImpl.java:350`）+ `PlanModePlugin`（Dynamic 段 + 工具注册 + 事件 fold）——目录装载走同款通道，R1 不扩机制面；
  - **dsh 对照判据在手**（新基线 `5badb15009`）：三角色 `skill/skill` + `skill/skill-filesystem` + `skill/tool-skill`、根优先级面、malformed 跳过、catalog 截断 500、空目录退役——勘察报告 §2 已核，评审成本已付；
  - **治理面现成**：`ToolDefinition` 声明面 + 审批 stage + 沙箱（it11/it12/it22）——「技能内命令走既有治理」不需要任何新执行机制；
  - **勘察产出待回收**：A 表 6 条（③）自前置勘察即挂本迭代首笔。

- **不做**：
  - **MCP Tools / 网络资料 / LSP**（0.3 后续顺序项，各自迭代）；
  - **远程 / 市场供应**（dsh skill-marketplace / office 面）——本迭代供应面 = 文件；
  - **文件监听 / 热更新**（dsh「invalidation provider-driven、无 TTL」教训在案；目录变更在轮首顺带发现即够）；
  - **用户显式调用 `/name` 与 invocation 双轴（modelInvocable / userInvocable）**——挂账；与 `interaction/commands` 面交互另议（路线词「作用域可见性」不要求用户调用面）；
  - **自定义第三根 / 扁平 `<name>.md` / 嵌套多级 / 技能继承组合**（T4 维度备选面，留挂账；跨工具根族已随 T4 裁选 B 入正选）；
  - **技能签名 / 信任 / 审计面**（无市场即无供应链信任问题）；
  - **不动既有提示面语义**（`AGENTS.md` 装载行为、`plan:policy` 段不变；`03:173` / `12:48` 两处引用不动——A 表复核结论）；
  - **不新增第三方依赖**（frontmatter 解析见 T5；任何 YAML 依赖引入须点名裁决）；
  - **A 表之外不顺手改其他设计文档处**（勘察 C 表负结论维持——「不因本轮勘察调整」）。

## 待裁（四确认一并裁定）

| # | 事项 | 拟案（推荐） | 备选与代价 |
|---|---|---|---|
| T1 | catalog 通道形态 | 日志事件 `skill/catalog` + Dynamic 提示段（R1 与 it21 / `plan:policy` 同款；回放零磁盘依赖、单一机制） | dsh 式 durable 合成 user 消息：KV 前缀友好（变更只追加尾部），但引入合成 user 消息面（JH 现无此形态）、提示机制两条路径 |
| T2 | `skill` 装载工具审批声明 | `of()` 需审批（与 `fs_read` 同档；读文件入上下文的模型动作走审批是常态口径，不因技能面开例外） | `ofExempt` 免审批（装载非副作用、deny 档技能仍可用；但突破「免审批只给无副作用交互工具」既有口径，且与 `fs_read` 不对等） |
| T3 | 发现节奏 | 轮首（`agent/pre-step` 挂点；步内新建技能下一轮生效） | 步前（dsh pre-step 对齐）：需在 agent-loop 增加「请求前」钩子——核心改动面大；收益仅步内新技能当轮可见 |
| T4 | 根集合与目录形态（两维分开） | 根集合：双根（项目 `${cwd}/.harness/skills` + 用户 `${home}/.harness/skills`），跨工具根族经下方子问题裁为选 B 后并入为四根；目录形态：`<root>/<name>/SKILL.md` 目录束单形态 | 根集合备选：仅项目根（跨项目个人技能不可用）／自定义根（dsh 五根中第 3 根，rank 300）／仅 `.agents`（子问题选 C，已否）；目录形态备选：加扁平 `<name>.md`（dsh 双形态）／嵌套多级（不做）——均可挂账 |
| T5 | frontmatter 解析 | 自持最小解析（`---` 定界 + 顶层 `key: value` 标量；零新增依赖，SnakeYAML 边界不扩大；完整 YAML 语义面挂账） | 引入 YAML 依赖：语义完整，但新增第三方依赖须点名，且与 kernel 面零依赖纪律、SnakeYAML 收口（`bundle/base`）冲突 |

**裁决记录（2026-10-09）**：T1 / T2 / T3 / T5 按拟案通过；T4 采**选 B**（并行根族、自有优先，并入为四根）——见下方子问题裁定。

**T4 子问题（跨工具互认；2026-10-09 附）：`.harness/skills` 是否让位于 `.agents/skills`？**

拟案双根之外，是否再认 `.agents/skills` 跨工具根族（项目 `${cwd}/.agents/skills` + 用户 `${home}/.agents/skills`）——

- **选 A（拟案维持，仅自有双根）**：互认挂账。代价：其它工具写入 `.agents/skills` 的技能在 JH 不可见——JH 指令面已采跨工具约定（`AgentLoopPlugin.DEFAULT_INSTRUCTIONS_FILE = "AGENTS.md"`，`AgentLoopPlugin.java:45`），技能面不采的理由只能是范围纪律、不是立场，须明说。
- **选 B（并行根族、自有优先；已裁定采用）**：四根 = 项目 / 用户 × `.harness` / `.agents`，优先级单条规则——项目覆盖用户、同层自有优先（`${cwd}/.harness` > `${cwd}/.agents` > `${home}/.harness` > `${home}/.agents`；dsh 同构：rank 100 项目 dsh < 200 agents、400 < 500，「Lower ranks win」`packages/skill/skill/src/index.ts:78`；dsh 全貌为五根，第 3 根 = 自定义 custom rank 300，本迭代不采）。依据：dsh 实装形状 + JH 先例（AGENTS.md；本仓 AGENTS.md「决策记录阈值」点名 `.agents/notes/` 目录约定）；机制零新增（roots 行 + 既有近者胜遮蔽）；收益：跨工具互认落地，「可移植到独立项目」延伸成立。**上下文注入面注记**：认跨工具根 = 其他工具写入的技能进入 JH 上下文，是上下文注入面的又一来源面扩张（it21 `AGENTS.md` 装载已是「项目文件 → 上下文」注入先例，且同为跨工具约定；本次新意 = **技能目录 + 跨工具根 + 用户家目录**三个新来源面）——治理不受影响（正文纯指示、命令仍走审批 + 沙箱，见内容 5），由既有缓解兜住（catalog 带来源根可解释 + 提示语「先装载再行动、不得据摘要推断指令」）；技能签名 / 信任 / 审计面维持不做（无市场即无供应链面）。
- **选 C（让位，仅 `.agents/skills`；已否）**：单一面最大互通；代价：与 `${home}/.harness/sessions` 既有配置根惯例分家（会话在 `.harness`、技能在 `.agents`），且与 dsh 对照反向（dsh 自有优先）。

代价对照：A 面最小（互认挂账）／B 用例矩阵加「同层自有优先」一组、夹具加一例／C 配置面分家。**裁定：B（2026-10-09）。**

## 设计增量（ADDED / MODIFIED / REMOVED）（开工前补齐）

**ADDED**：
- 模块 `skill/skill`（`harness-skill-skill`，JPMS `io.javanatic.harness.skill.skill`）：`SkillService` 契约（catalog 快照 / 按名装载）+ `SkillSummary`/`SkillDefinition` 类型 + 注册表（provider 注册、合并、近者胜遮蔽；首版单 provider，形态对齐工具注册面）；
- 模块 `skill/local`（`harness-skill-local`，插件 id `skill-local`）：四根本地发现（选 B）+ `<root>/<name>/SKILL.md` 目录束装载 + frontmatter 自持最小解析（`name`+`description` 必填、目录名 = 技能名 kebab-case）+ 条目损坏跳过并告警 + 遮蔽告警；
- 模块 `skill/tool`（`harness-skill-tool`，插件 id `skill-tool`）：`skill(name)` 装载工具（`ToolDefinition.of()`，需审批——T2）+ `agent/pre-step` 轮首监听（T3）+ `skill/catalog` ExtensionEvent 落账（`ignorable=false` + ServiceLoader codec）+ `PromptSection.Dynamic` 目录提示段（「先装载再行动、不得据摘要推断指令」；无技能不落事件不出现，曾有过清空落空事件退役）；
- 事件词表：`skill/catalog`（条目 = name / 描述截断 / 来源根；截断上限 = 配置键，缺省 500）；
- 配置键：`skill` roots（四根缺省，可覆盖）+ 描述截断上限（`07` 同步）；
- `bundle/base` 装配三行（注册表 / provider / consumer）；
- 新依赖边 `skill/tool` → `core-agent-loop`（首个非 examples 依赖 agent-loop 的模块——02 依赖列登记）。

**MODIFIED**：
- `02-module-layout.md`：模块表 + seam 三角表 + 依赖图 + dsh 映射行（三行新增 + `skill/skill-filesystem` → `skill/local` 映射注记）；
- `03-session-event-sourcing.md`：ExtensionEvent 生产实例清单加 `skill/catalog`（与 `todo/write`/`plan/mode`/`assistant/chunk` 并列）+ 事件 schema 节；
- `04`/`05`：如列提示段清单 / 契约清单则加目录段与 `SkillService` 行（逐一核对，无列不动）；
- `07-config`：新增两配置键；
- `12-api-stability.md`：导出面加三模块；
- `AGENTS.md`：现状段与 Repository layout 加 `skill/` 行；
- A 表 6 条（引用订正，首笔文档改动落地）。

**REMOVED**：无。

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `bundle/base`（AppBoot / CompositionManifest 装配行） | 加 `skill` 注册表 / `skill-local` / `skill-tool` 三行 + roots、截断上限配置键接线 | ☐ |
| `core/agent-loop` `AgentEvents`（pre-step 挂点） | **零改动**——只作公共挂点被 `skill/tool` 消费（锚在此防误改） | ☐ |
| `core/system-prompt`（`PromptSection` 注册面） | `skill/tool` 经既有注册面挂 Dynamic 段（消费方，不改面） | ☐ |
| 设计文档 `02`/`03`/`04`/`05`/`07`/`12` + `AGENTS.md` | 上表 MODIFIED 同步面（代码与文档同一提交） | ☐ |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1（承载契约 + 新词表） | `skill/skill`：`SkillService` / `SkillSummary` / `SkillDefinition` / 注册表遮蔽语义 | ☐ |
| 停 2（跨模块效应：新依赖边 + R1 面） | `skill/tool`：装载工具声明面 + `skill/catalog` 事件 codec + Dynamic 段 | ☐ |
| 停 3（组合面 + 验收取证） | `bundle/base` 装配行 + `--verify` 治理断言 + 夹具真跑与 R1 回放 | ☐ |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-26/）

| 文件 | 覆盖（停点/验收项） |
|---|---|

## 验收（证据 = 实际执行的命令与结果）

（四确认裁定后启用；勾选与证据随工作推进落此。）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
