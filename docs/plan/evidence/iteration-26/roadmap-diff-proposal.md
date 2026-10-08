# it26 前置：Roadmap 差异提案（②③，待评审后进四确认）

> 状态：**提案，未实施**。评审通过后：A 表（③ 引用订正）随 it26 首笔文档改动落地；
> B 表（② 候选）按项转为对应迭代的四确认输入——本提案不预先批准任何实施。
> 依据：`docs/plan/evidence/iteration-26/dsh-upstream-recon.md`（同目录，含核验命令与原始输出）。
> 基线：dsh `5badb15009`（dsh-v0.2.1-alpha.1，2026-10-03）。

## A. ③ 设计文档引用订正（6 条）

> 均为**出处事实**订正，不触及 JH 自身设计决策（JH 侧模块名、机制、路线均不变）。
> 「若错靠什么发现」= 该订正若与事实不符时，重跑哪个命令即红。

| 编号 | 位置 | 现文（摘） | 新基线事实 | 建议改法 | 若错靠什么发现 |
|---|---|---|---|---|---|
| A-1 | `02-module-layout.md:41`、`:767` | dsh 包列 `preset/agent-presets` | 2026-09-21 `d1e22a7e24` 拆为 `preset/agent-preset` + `preset/agent-preset-registry`（`composeFrom` 在 registry） | 改为 `preset/agent-preset`（组合层）/ `preset/agent-preset-registry`（注册表），或合写 `preset/agent-preset{,-registry}` | `git cat-file -e 5badb15009:packages/preset/agent-presets/package.json`（红即订正错） |
| A-2 | `02-module-layout.md:771` | dsh 包列 `llm/llm-replay` | 两基线下均在 `test-support/llm-replay`；`packages/llm/llm-replay` 全历史为空 | 路径直接替换为 `test-support/llm-replay` | `git log --all -- 'packages/llm/llm-replay'`（非空即订正错） |
| A-3 | `02-module-layout.md:778` | dsh 包列 `interaction/approval` | 两基线下均为 `interaction/user-approval`（npm `@deepseek-ai/dsh-user-approval`）；`@deepseek-ai/dsh-approval` 全历史不存在 | 路径替换为 `interaction/user-approval`；JH 侧 `interaction.approval` 名不动 | `git cat-file -e 5badb15009:packages/interaction/user-approval/package.json` |
| A-4 | `iteration-24.md:14`、`:38` | 「`native/landlock-run` 为 C + 独立 CI」「其兜底 `native/landlock-run` 随产物自带」 | 2026-09-07 `336ebb235e` 迁入 `native/system`、09-08 `97e7223d5f` 子路径化：`@deepseek-ai/node-addon-system`（`./landlock-run`、`./flock`） | 改为 `native/system`（Landlock = 其 `landlock-run` 子路径）；保留历史语境可加「（分析期 path 为 native/landlock-run，2026-09-07 重组）」 | `git ls-tree --name-only 5badb15009 native/`（非 native/system 即订正错） |
| A-5 | `10-testing.md:37`、`03-session-event-sourcing.md` §7 首行 | 「dsh 的 `dsh-session/invariant` 是核心」「对应 dsh 的 session invariant」 | 运行时不变式机制 2026-09-30 `f028f25667` **整体移除**（`@deepseek-ai/dsh-invariants`、各包 `./invariant`、gates、resolver、`INVARIANT` rethrow） | 改写为历史出处（「dsh 分析期曾以 ./invariant 伴随插件 + 运行时注册表实现，2026-09 已退役；JH `SessionInvariants` 是自有形态，非移植」），或删 dsh 出处句仅留 JH 自述 | `git cat-file -e 5badb15009:packages/runtime-diagnostics/invariants/package.json`（判存在即订正错） |
| A-6 | `iteration-14.md:16` | 「dsh 也持久化该事件（TTFT/遥测）」 | 该句在分析期（8/13 基线）成立（v0/v1 顶层持久化）；v2（2026-09-01 note）取消顶层 `assistant/chunk`，改 `assistant/message` 内嵌 compact stream / log-only `assistant/attempt`（搬迁由 `session-format-v1-to-v2` 迁移包承担） | 加版本限定：「（分析期 v0/v1 持久化；v2 起改为内嵌 stream / log-only attempt）」 | `git grep -c 'assistant/chunk' 5badb15009 -- packages/core/agent-loop/src`（现役 src 有命中即「已移除」判错；新基线为 0 命中） |

**不改两处（复核结论）**：`03-session-event-sourcing.md:173` 的 `TurnEndReasonMap` 引用仍成立
（live 于 `packages/core/session/src/types.ts`）；`12-api-stability.md:48` 等表格列的是 **JH 自身
模块**（非 dsh 路径），无需改动。

## B. ② 候选与建议处置

### B1 会话格式版本化（建议：挂账评估，不立即立项）

- 事实：dsh v0→v4 迁移链（`session-format` + 四个迁移包 + catalog）；已发布格式不可变、
  迁移器成包、upgrade-guide 逐版本条目；v2 结算模型（内嵌 streams）随版本纪律落地。
- JH 现状：JSONL + ServiceLoader codec；R1 回放哈希 + codec 往返矩阵（it19.1）已固 wire 保真；
  旧日志缺字段回退（如 `kind` → `UNKNOWN`）是逐点约定，**无版本位/迁移链**。
- 建议：当出现第二次「必须改 wire 形状且旧日志必须可载」的需求时立项，做最小形态
  （header 版本位 + 显式迁移器 SPI + 未知版本 fail loud），不做通用迁移框架。0.3 四确认时
  仅作为输入，不预设实施。
- 影响面：`core/session` 信封 + `session/persistence-jsonl` codec + R1 回放夹具。
- 若错靠什么发现：立项评估时先写旧会话装载失败用例 + R1 回放红（先有失败样本再谈机制）。

### B2 写者锁 Windows 面核对（建议：随 0.3 首个触 Windows 的迭代低成本补一条证据）

- 事实：dsh 用内核仲裁（flock + Windows named semaphore），**明确拒用** Windows 字节区间锁
  （强制锁，读者触碰被锁文件即 hard-fail）。JH 锁面 = Java `FileLock`（Windows 映射即
  `LockFileEx` 强制字节区间锁），持于**专用** `.writer.lock`，读者不触碰（列表测试在案）。
- 建议：复用 it25 VM（或任意真 Windows）跑双进程用例各一条：(a) 写者持锁时读者正常读日志；
  (b) 第二写者被拒（fail loud）；(c) 杀进程后锁释放、残留 `.writer.lock` 无害。预期零代码改动，
  只落证据。
- 若错靠什么发现：该真跑即裁决——红了说明 JH「专用锁文件」结构在 Windows 下仍有洞，
  需在设计层讨论（正是 dsh 拒用理由的正面检验）。

### B3 MCP Resources 边界确认（建议：0.3 MCP 迭代四确认时重看）

- 事实：dsh 窗口内新增 `mcp-resources`（3 共享工具、显式 server 选择、agent-scoped +
  server instructions）——是其 stdio 成熟后的独立增量。
- JH 计划：0.3 首版不做 Resources/Prompts（`docs/design/README.md:160`）——**维持**。建议把
  「Resources 留待二版 + dsh 契约要点（显式选择/作用域）」写进该迭代 Design 增量，防止将来重设计。
- 若错靠什么发现：与 dsh `packages/mcp/mcp-resources/README.md` 对照复核。

### B4 capability graph 机械化（建议：挂账）

- 事实：dsh `scripts/gen-doc-graphs.ts` 机械生成 capability-seams / module-graph / graph-atlas
  （"do not edit by hand"）。
- JH：05 §11「capability graph 可选」。建议挂账：若 0.3 的 skills/MCP 扩展面使 seam 图手工维护
  成本上升，再加生成脚本（形态自定）；当前不动。

### B5 记录类（不改动，入对照池）

- **v2 attempt settlement**：失败 attempt 的日志证据不伪造模型历史——与 JH `assistant/chunk`
  log-only 设计同向；无动作。
- **不变式退役教训**：dsh「独立观察」准则（删 209 空伴生）→ 全删注册表——JH `SessionInvariants`
  是复核函数非可选插件，形态合规；仅引用订正（A-5）。
- **0.4 面**：jobs / schedule / workflow-ptc / goal / subagent 扩张——it26+ 的 0.4 四确认对照输入。
- **boot 热重载三件套**：JH 维持「本阶段不做运行时热重载」（`docs/design/README.md:151`）。
- **Windows ACL 收敛**：dsh 2026-09-19 强制性完整性约束与 JH it25 低完整性同向，无动作。
- **deliverables（窗口新增组）**：`tool-present`（显式工作区交付声明）+ `workspace-changes`
  （per-turn 工作区变更记录：git 工作树快照 + 全文件捕获 + 逐文件对比）——与 JH「结果可观察」
  同向；无动作（记录备查；`client/ui-deliverables` 旧基线已有）。
- **extensions 组（旧基线已有、窗口内高热 ~1182 提交）**：`tool-cordis`（插件开发用只读运行时
  检视）+ `cordis-host-runner`（模型挂载的 dual-half 包：动态包定义注册表 + 宿主半部沙箱生命
  周期 + invoke 处理器表）——把插件框架能力开到模型面；与 boot 三件套同域，记录备查。
- **experimental claude-code mods / auto-review（窗口新增）**：Claude Code mod 兼容桥 + web band
  + Auto 预设 per-tool LLM 复核——JH 无 Claude Code 兼容目标；三点对照价值见勘察报告 §2.5。

## C. 负结论（明确不因本轮勘察调整）

- 不因 dsh 的 web 客户端 / api-gateway / desktop / ssh / telemetry 增量触碰 JH 非目标。
- 不引入 experimental 组与其发布政策（JH 无此面）。
- 不改造 JH 锁机制本身（B2 仅核对）；不改 0.3 交付顺序与验收边界（除 A 表出处订正）。
