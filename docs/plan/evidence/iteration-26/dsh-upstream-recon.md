# it26 前置勘察报告：dsh 上游增量（新基线 `5badb15009` · dsh-v0.2.1-alpha.1）

> 任务：it26 前置勘察（研究任务，不设停点）。本报告 + `docs/dsh-reference.md` 基线补记 +
> `docs/plan/evidence/iteration-26/roadmap-diff-proposal.md`（②③ 提案）三件产出。
> 设计文档订正**未实施**，逐条汇总于提案，等评审后再进四确认。
> 勘察对象：本地 dsh 仓库 `/Users/abel_yang/study/deepseek/deepseek-harness`（只读）。

## 1. 对照基线（事实源）

| 时点 | commit | 日期 | 依据 |
|---|---|---|---|
| 初次分析（JH 设计文档写作期） | `3a46bd67` 当日 tip | 2026-08-13 21:57 | `docs/design/README.md:117` 自述「参考本地 dsh 截至 2026-08-13 的源码与演进记录」 |
| JH 首次提交前后 dsh 主线 | `b6d0195d` → `5bb600f9`（tip） | 2026-08-14 22:35 → 08-15 02:49 | JH 首提交 `69e15df` @ 2026-08-15 15:47；本报告 diff 基准 = `b6d0195d`（与 tip 包集合实测一致，§8） |
| it14 补勘点 | `9ccd9b06` | 2026-09-13 | it14 期间的引用核对 |
| **本次新基线** | **`5badb15009ae1756c3afe0ae0cef1faafc290ccc`** | **2026-10-03 11:48** | tag `dsh-v0.2.1-alpha.1`，merge PR #5648 |

- 尺度：真实包（`packages/<组>/<包>/package.json` 计法）**219 → 319**（新增 119 / 移除 19）。
- 窗口内（≥ 2026-08-15）Agent Notes **346 篇**：implemented 306（architecture 196 / feature 44 /
  process 26 / testing 24 / simplification 8 / bug-fix 8）、proposed 40（simplification 18 /
  architecture 12 / feature 6 / process 2 / bug-fix 2）、rejected 0。
- 版本线 tag（均窗口内）：`dsh-v0.1.7-{alpha.1,alpha.2,rc.1,rc.2}`（2026-09-22→24）→
  `dsh-v0.2.0-{rc.1,rc.2}`（09-28→29）→ `dsh-v0.2.1-alpha.1`（10-03，新基线）。无 CHANGELOG；
  发布说明 = GitHub Releases + `docs/persistence-changes/releases/` + `docs/upgrade-guide/<版本>/`
  （窗口内条目：v0.1.7-rc.2 两条、v0.2.0-rc.2 四条）。
- 文档演进：新增根级文档 architecture 面（agent-lifecycle、api-gateway、tool-catalog、
  tool-execution-pipeline、session-format-status、defensive-patterns、event-producer-consumer、
  graph-atlas、development、testing、persistence-catalog/schema/json、dependency-catalog.json、
  postmortem/、rescope 等）；新增 subsystems 文档约 20 篇（agent-team、boot、browser-use、
  claude-code-mods、mcp、web-client、webhook 等）；删除 `subsystems/code-runtime.md`、
  `subsystems/invariants.md`；`docs/capability-seams.md` 转为**机械生成**（`scripts/gen-doc-graphs.ts`，
  mermaid seam graph，标注 "do not edit by hand"）。

**19 项移除**（全名单）：

```
client/runtime  client/schema-form  client/web-react
code-runtime/code-runtime  code-runtime/code-runtime-worker-thread
e2b/e2b  e2b/fs-e2b  e2b/subprocess-e2b
examples/acp-demo  examples/agent-spine-demo  examples/jsonrpc-demo
host/apiproxy  preset/agent-presets
runtime-diagnostics/invariants  session/session-persistence-sqlite
settings/settings-file  subagent/tool-subagent-report
test-support/acp-snapshot  workflow/workflow-worker-thread
```

**组级复核**（测量面：`git ls-tree --name-only <commit> packages/` 顶层条目；与上方包级口径
不同面，数字不互比）：顶层条目 54 → 60 = 组目录 49 → 54（净增 9：browser-use、computer-use、
deliverables、document、experimental、ptc-runtime、ssh、telemetry、webhook；净失 4：
code-runtime、e2b、examples、runtime-diagnostics）+ 顶层散件文件 5 → 6（新增
`packages/tsdown.worker.ts`）。四组路径均 BASE=Y／NEW=N；其中 `code-runtime` 系**更名重组**
为 `ptc-runtime`（`code-runtime-node` → `ptc-runtime-node` 整树 rename；`7c9bb5914c`
@ 2026-09-12），`e2b`／`examples`／`runtime-diagnostics` 系移除（`c49db8bc8c` @ 09-11 ／
`244de7c18a` @ 08-26 ／ `f028f25667` @ 09-30），三笔 merge 提交的 first-parent diff 显示
组级 README 与整树包目录一并删除。命令与输出见 §8。

**119 项新增**（按组计数，前 10）：client 27、experimental 24、util 10、session 8、api 7、
ssh 4、llm 4、credentials 3、bundle 3、boot 3；其余（各组 1–2）：webhook、test-support、skill、
ptc-runtime、preset、host、deliverables、context、workflow、telemetry、subprocess、shell、
schedule、mcp、document 等。关键新增：`api/*-controller`（6）+`api/workspace-files`、
`boot/{config-editor,hmr,plugin-manager}`、`experimental/*`（24）、`mcp/mcp-resources`、
`preset/{agent-preset,agent-preset-registry}`、`session/session-format`+`session-format-v0-to-v1`
…`v3-to-v4`+`session-format-catalog`、`skill/{skill-office,tool-workspace-dependencies}`、
`bundle/{acp-app,sdk-app,sdk-minimal}`、`workflow/workflow-ptc`、`shell/tool-pwsh-persistent` 等。

## 2. ① 0.3 面重叠（skills / MCP / web / LSP）—— 四域全部在新基线成立

结论：JH 0.3 路线（`docs/design/README.md:155-164`）四个交付面的 dsh 对照实现均在，且多数在
**基线前已成熟**；窗口内仅三个小增量。JH 计划与 dsh 现状无冲突。

### 2.1 skills（`packages/skill/`）

- 基线已有：`skill`（`ctx.skills` 注册表，合并多来源目录、按名解析）、`skill-filesystem`
  （project/custom/user 目录发现 + 变更 watch）、`skill-badge`、`tool-skill`（会话目录，
  **durable 有序目录** + `skill` 加载工具 + `/name` 直呼）。
- 窗口内新增：`skill-office`（Word/PPT/Excel 工作流，结构检查）、`tool-workspace-dependencies`
  （报告 Office 解释器路径/版本，Desktop/SDK 载体）。
- 对照 JH 计划（README:159「本地发现、按需加载、来源和优先级、作用域可见性；分离部署供应与
  Agent 消费」）：dsh 的 registry+filesystem+tool-skill 形状一一对应；「技能中的命令仍走既有
  工具治理」与 dsh 一致（skill 只加载指令，模型工具面仍是 `ctx.tools`）。

### 2.2 MCP（`packages/mcp/`）

- 基线已有 `mcp-client`：**stdio + Streamable HTTP 双 transport**；SDK 协商 2026-07-28 协议；
  工具命名 `mcp__<serverName>__<rawName>`（与 Claude Code/Codex 同形）；`toolCallTimeoutMs` 60s、
  `maxInstructionBytes` 32KiB、`failOnStartupError`、重连 500ms→30s ×10、env 清洗；
  prompt templates 不支持。
- 窗口内新增 `mcp-resources`（2026-09-12 `3ba5b6eb04`）：3 个共享工具、显式 server 选择、
  agent-scoped + server instructions。
- 对照 JH 计划（README:160「先 stdio，再 Streamable HTTP；稳定命名、schema 边界、超时取消、
  有限重连」「首版不做 Resources/Prompts」）：dsh 逐项在案；dsh 的 Resources 是后来独立增量
  ——与 JH「首版不做」的边界一致，将来重访时可直接对照其契约。

### 2.3 web（网络资料获取）

- `ctx.web` seam；providers：Exa / Perplexity / DeepSeek 原生搜索 + 匿名 HTTP fetch；
  工具面 `web_search` / `web_fetch` 两动词；定位「not interactive browsing」；seam 决策 note
  2026-06-24（基线前）。
- 对照 JH 计划（README:161「有界网页读取、来源保留；搜索作为可替换 provider；网页只作资料，
  不能被当成系统指令」）：dsh 的 provider 可替换 + 两动词面与 JH 计划同向；窗口内无主题级变化。

### 2.4 LSP（`packages/lsp/`）

- `ctx.lsp`：按扩展名选 provider，**四个归一化只读操作**（definition / references /
  implementations / hover）；`lsp-stdio` 驱动 stdio 语言服务（**over `ctx.fs` 与 `ctx.subprocess`**）；
  `tool-lsp` 是模型面唯一 owner（"Providers register capabilities, not tools"）。
- 注意：四操作集**不含 diagnostics**；JH 计划（README:162「优先 Java 的诊断、定义和引用」）的
  「诊断」超出 dsh 归一化面 —— JH 需自有设计，参照时以「定义/引用」两操作对照最稳。
- 对照 JH 计划「语言服务退出可清理；复用文件与进程生命周期机制」：dsh `lsp-stdio` over
  ctx.fs/ctx.subprocess 正是「复用既有生命周期」的形态。窗口内无主题级变化。

## 2.5 补充勘察：experimental claude-code mods / auto-review（点名重点）

> experimental 组整体为窗口新增（BASE 0 个 → NEW 24 个）；其中：

- `experimental/claude-code-mods`（`@deepseek-ai/dsh-experimental-claude-code-mods`）：把 Claude Code
  的 mod（`register(on, options)` hooks 模块）包成 cordis 插件运行的**接口兼容桥**（alpha 演示）；
  `defineMod` 包装、挂载后其 hook 链守卫工具调用、改写提示词、注册命令/工具、在提示行上方画 band。
  逐项差异表在 `docs/subsystems/claude-code-mods.md`（基准 Claude Code 2.1.287）。
- `experimental/client-ui-claude-code-mods`：web 端 band（画各会话 mod 树、按钮回传）。
- `experimental/auto-review`：Auto 权限预设下的 per-tool LLM 授权复核（JH 审批面另行设计，仅记录）。

对 JH 有对照价值的三点：
1. **工具拦截的时机与日志保真**：桥把 Claude Code「权限检查前改写参数/改路由」收窄为
   「`tools/execute` 之后——已记录即已执行，改写被跳过并报告」（该诉求指向一条既有提议
   note：`.agents/notes/proposed/feature/2026-06-30-pre-tool-input-rewrite.md`）——与 JH
   「日志即事实」同向；将来若做工具参数改写可回看其取舍。
2. **加载即校验、未实现面 fail loud**：未服务的 event 在加载时点名警告、未实现的 `$` 调用
   具体报错（`no implementation for <namespace>.<method>`）——与 JH 插件装载惯例同向。
3. **信任边界**：mod 以插件身份在进程内全权运行（文档明言「不设沙箱——只挂你愿意当插件跑的
   东西」）；JH 插件同为本进程信任面。JH 无 Claude Code 兼容目标，不立项，记录备查。

## 3. ② 新思想（候选；逐条入提案）

1. **会话格式版本化 + 迁移纪律**（窗口内成体系）：`session-format` + 四个显式迁移包
   （v0-to-v1…v3-to-v4）+ `session-format-catalog`；已发布格式不可变、迁移链成包、
   历史格式留档于 `docs/persistence-changes/historical-formats/`；`session-format-status` 文档。
   来源 note：`2026-09-01-v2-embedded-assistant-streams.md` 等。
2. **v2 结算模型（per-attempt settlement + 内嵌 compact streams）**：顶层 `assistant/chunk`
   持久化事件在 v2 被移除——live 形态 = 进程内 `agent/assistant-stream` start/chunk/end 帧；
   durable 形态 = `assistant/message` **内嵌** `AssistantStreamRecord[]`，或 log-only 的
   `assistant/attempt`（失败 attempt 的日志证据**不伪造模型历史**）。对照 JH：JH 的
   `assistant/chunk` 本就是 log-only、投影不读——同向；JH 无 attempt 级证据概念（记录备查）。
3. **跨进程写租约（kernel 仲裁）**：POSIX `flock(2)` 预编译 binding + Windows named semaphore；
   明确**拒绝**的备选：TTL+续租、proper-lockfile、**Windows 字节区间锁**（强制锁，读者触碰即
   hard-fail——ripgrep 死于 os error 33）、CreateFileW 独占打开（EBUSY 清理）；wedged holder
   保持锁、不夺权；锁文件故意保留；NFSv3 注意事项。来源 note：
   `2026-08-31-cross-process-session-write-lease.md`、`2026-09-07-prebuilt-system-primitives.md`。
   对照 JH：`JsonlPersistence.java:236` 在**专用** `.writer.lock` 上 `tryLock`，读者不触碰
   （`JsonlPersistenceListTest.java:148` 断言只读路径不产生锁文件）——dsh 拒用 Byte-range 的
   场景（读者碰被锁文件）在 JH 结构下不触发；值得一次低成本 Windows 真核（入提案 B2）。
4. **文档图机械生成**：`scripts/gen-doc-graphs.ts` 生成 capability-seams / module-graph /
   graph-atlas（"do not edit by hand"）。对照 JH 05 §11「capability graph 可选」——可机械化候选。
5. **运行时不变式注册表整体退役**（教训完整）：2026-07-19 起 `dsh-invariants`（`ctx.invariants`）
   + 各包 `./invariant` 伴随插件；2026-08-28 按「独立观察」准则删 209 个空伴生、只留 39 个；
   2026-09-30（`f028f25667`）**全删**：包、伴随、gates、resolver 生成器、`INVARIANT` rethrow 路径；
   emitters 改为记录而非重抛；存储不变式回归 always-on Session 边界（dev-invariants note 改写，
   删去 "Package-owned invariant companions" 一节）。对照 JH：`SessionInvariants`（03 §7）是
   复核函数（load/append 复核 + R1 复核），非可选插件——与 dsh 结论「存储边界 always-on +
   关系检查须有独立观察」同向，**无机制改动**；仅引用订正（见 ③-5）。
6. **experimental 作为可选 bundle 的发布政策**（2026-09-21）：默认发布、private 例外清单。
   JH 无 experimental 组；参考其治理措辞（低优先）。
7. **0.4 面参考增量**：jobs 成熟化（observation 协议）、`schedule`/`tool-schedule`、
   `workflow/tool-workflow` + `workflow-ptc`、`goal`、subagent 组扩张、`agent-team`
   （experimental）、PTC fd3 python runtime。JH 0.4 = 后台任务 + 受控委派——生命周期/所有权/
   结算细节可直接对照（JH it20 已收自身资源边界）。
8. **boot 三件套**（`plugin-manager` / `hmr` / `config-editor`）：运行时插件管理、热重载、
   配置编辑。JH 0.2 阶段明确「运行时热重载」不做（README:151）——保持不做，记录观察。
9. **Windows 安全收敛对照**：dsh `2026-09-19-windows-acl-mandatory-integrity-confinement`
   与 JH it25 低完整性机制——双方同向（完整性级别约束），低优先记录。

## 4. ③ 推翻 JH 既有引用（新基线核验为不成立/已变）—— 共 6 条

> 均为**引用/出处事实**，不触及 JH 自身设计决策；改法入提案 A 表，评审后实施。
> 已复核**仍成立**的引用见 §5；「曾疑为失效、复核后判为成立」的一条（TurnEndReasonMap）
> 单列在 ③ 末尾。

- **③-1 `preset/agent-presets` 已拆分**：`02-module-layout.md:41`（`harness-core-preset` 行末列）
  与 `:767`（§6 映射表）引用 `preset/agent-presets`；新基线为 `preset/agent-preset` +
  `preset/agent-preset-registry`（2026-09-21 `d1e22a7e24` PR #4569；`composeFrom` 现居
  `agent-preset-registry/src/index.ts`）。JH 侧映射（`core.preset`）不变。
- **③-2 `llm/llm-replay` 从未存在**：`02-module-layout.md:771` 引用；dsh 回放包在两个基线下均在
  `test-support/llm-replay`（`packages/llm/llm-replay` 全历史为空）。JH 模块 `llm.replay` 不受影响。
- **③-3 `interaction/approval` 从未存在**：`02-module-layout.md:778` 引用；dsh 审批包在两基线下
  均为 `interaction/user-approval`（npm `@deepseek-ai/dsh-user-approval`；`@deepseek-ai/dsh-approval`
  全历史不存在）。JH 自身模块 `interaction.approval` 与其文档引用不受影响
  （`12-api-stability.md:48` 等表格列的是 JH 自身模块，无需改）。
- **③-4 `native/landlock-run` 已重组**：`iteration-24.md:14, :38` 引用；2026-09-07 `336ebb235e`
  迁入 `native/system`（根级 workspace，非 `packages/` 下），2026-09-08 `97e7223d5f` 子路径化：
  npm `@deepseek-ai/node-addon-system`，`./landlock-run`（launcherPath/probe/grantArgs）+
  `./flock`（tryLockExclusive）；工作流更名 `node-addon-system(-release).yml`。
- **③-5 运行时不变式机制已整体移除**：`10-testing.md:37`「dsh 的 `dsh-session/invariant` 是核心」
  与 `03-session-event-sourcing.md` §7 首行「对应 dsh 的 session invariant」；该机制 2026-09-30
  `f028f25667` 全删（`@deepseek-ai/dsh-invariants`、各包 `./invariant`、gates、resolver、
  `INVARIANT` rethrow；upgrade-guide `v0.2.0-rc.2/remove-runtime-invariants/`）。
  JH `SessionInvariants` 保留（自有形态），仅出处措辞需改。
- **③-6 `assistant/chunk` 持久化叙述已过时**：`iteration-14.md:16`「dsh 也持久化该事件（TTFT/遥测）」；
  该句在初次分析时点（8/13 基线）**成立**（v0/v1 顶层持久化），窗口内 v2（2026-09-01 note）将其
  移除，改为 `assistant/message` 内嵌 compact streams / log-only `assistant/attempt`；搬迁由
  `session-format-v1-to-v2` 迁移包承担，历史 v0/v1 形态留档
  `docs/persistence-changes/historical-formats/` 与 `persistence-changes/releases/`。措辞需加版本
  限定或改写。

**曾疑为失效、复核后判为成立**：`03-session-event-sourcing.md:173`「对应 dsh 的
merge-extensible TurnEndReasonMap」——`TurnEndReasonMap` 在 `packages/core/session/src/types.ts`
两个基线下均在（新基线 grep 命中 live 代码，非仅 archived 文档）。**不改**。

## 5. 有效引用复核（全局核验结论）

以 `git cat-file -e <commit>:<path>` 双基线全量核验，以下 JH 引用的 dsh 路径在新基线**仍成立**
（同 BASE=Y/NEW=Y）：

`vendor/cordis`、`docs/architecture.md`、`docs/glossary.md`、`util/brand`（`@deepseek-ai/dsh-brand`）、
`core/{session,system-prompt,tools,agent,agent-loop,scope}`、`core/scope`（ScopedLayers）、
`todo/tool-todo`、`plan/plan-mode`、`llm/llm`、`llm/llm-deepseek`、`test-support/llm-replay`、
`fs/{fs,fs-local,tool-fs,tool-fs-search}`、`shell/{shell,bash-local,tool-bash}`、
`sandbox/{sandbox,sandbox-local}`（含 `sandbox-local/src/profiles.ts`）、
`session/{session-persistence,session-persistence-jsonl}`、`interaction/{commands,user-approval}`、
`bundle/base`、`core/agent-default-model`、`preset/persona`、
`sourceEventSeqs`/`surfaceOp`/provenance 事件字段、`request/context` 事件、`agent/inbox/{claimed,discarded,inserted,spliced}`、
`composeFrom`（agent-preset-registry）、`!!js`、`spill/spill`、`hooks/hooks-claude-code`、
`subagent/subagent-claude-code`、`.agents/notes/...`、`.agents/skills`（dsh 自身工作流技能；
`.claude/skills` 为 symlink → `../.agents/skills`）、`docs/subsystems/core.md`（TurnEndReasonMap
等映射表）。

## 6. ④ 无关增量（明确不跟）

Electron desktop、web client UI（窗口内 client 组新增 27 包，多为 ui-*）、api-gateway/controllers、ssh 组、
telemetry/otel、语音、computer-use/browser-use、office-to-pdf、ACP/Codex 桥、
credentials/*（JH 无授权面）、e2b 移除（JH 从未规划）、analytics 等——JH 0.2/0.3 范围与非目标
（README:151、design/README:86）已覆盖；记录为「对齐确认」，不产生动作。

窗口提交热点复核（`b6d0195d..NEW` 共 8407 提交）为上述结论提供量化佐证：`apps/` 3089
（`apps/web` 2114 / `apps/cli` 631 / `apps/desktop` 526）+ `packages/client` 2766 —— 约七成
提交集中在产品客户端面。

## 7. 勘察方法学与踩坑（供后续勘察复用）

1. **不要用快照 diff 法**：上一轮用 /tmp 快照对比产出过错误结论（把 BASE 已存在的
   `shell/tool-bash` 等报成新增、把组 README 文件计成包）。权威法 = `git ls-tree` 直查：
   - 包清单：`git ls-tree -r --name-only <commit> | grep -E '^packages/[^/]+/[^/]+/package\.json$'`
   - 单路径存在性：`git cat-file -e <commit>:<path>`（exit code 判 Y/N）
   - 组清单：`git ls-tree --name-only <commit> packages/<组>/`
2. **组 README 是文件不是包**：`ls-tree` 组目录会把 `README.i18n.yaml / README.md / README.zh.md`
   一并列出，计数时须剔除或改用 package.json 口径。
3. **`native/` 在仓库根级**（与 `packages/` 平级），不在 `packages/` 下；`docs/rescope.md`
   是单文件不是目录（`docs/rescope/` 不存在）。
4. **upgrade-guide 文件名不统一**：`docs/upgrade-guide/<版本>/<条目>/` 下是 `guide.md` /
   `guide.zh.md` / `guide.i18n.yaml`，不是 README.md。
5. **统计数必须随附「命令 + 测量面定义」**：同一量在不同测量面下数字不可互比。本轮实例：
   包级 219→319（面：`packages/<组>/<包>/package.json` 清单）、组级 54→60（面：`ls-tree`
   顶层条目，含组目录与顶层文件）、提交数 8407（面：`git rev-list --count`，含 merge）。
   报告与提案中的统计句均注所属面；跨面引用时先回命令重测（§1、§8）。

## 8. 核验命令与关键输出（原始取证）

> 全部在 dsh 仓库内执行（只读）；`BASE` = `b6d0195d5f04be9c3867cc776ab922c5dbd2f448`，
> `NEW` = `5badb15009ae1756c3afe0ae0cef1faafc290ccc`。

```console
$ git ls-tree -r --name-only $BASE | grep -E '^packages/[^/]+/[^/]+/package\.json$' | sed 's|/package.json||' | sort > /tmp/jh-base-real.txt
$ git ls-tree -r --name-only $NEW  | grep -E '^packages/[^/]+/[^/]+/package\.json$' | sed 's|/package.json||' | sort > /tmp/jh-new-real.txt
$ echo "BASE=$(wc -l < /tmp/jh-base-real.txt) NEW=$(wc -l < /tmp/jh-new-real.txt)"
BASE=219 NEW=319
$ comm -23 /tmp/jh-base-real.txt /tmp/jh-new-real.txt   # 19 条移除（见 §1 全名单）
$ comm -13 /tmp/jh-base-real.txt /tmp/jh-new-real.txt | wc -l
119

$ for p in interaction/user-approval mcp/mcp-resources skill/tool-skill ...; do
    b=$(git cat-file -e $BASE:packages/$p/package.json 2>/dev/null && echo Y || echo N)
    n=$(git cat-file -e $NEW:packages/$p/package.json 2>/dev/null && echo Y || echo N)
    echo "$p BASE=$b NEW=$n"; done
interaction/user-approval BASE=Y NEW=Y
interaction/permission-presets BASE=Y NEW=Y
interaction/tool-ask-user BASE=Y NEW=Y
interaction/user-questions BASE=Y NEW=Y
mcp/mcp-client BASE=Y NEW=Y
mcp/mcp-resources BASE=N NEW=Y
skill/tool-skill BASE=Y NEW=Y

$ git ls-tree --name-only $NEW native/     -> native/system（BASE 为 native/landlock-run）
$ git ls-tree --name-only $NEW packages/session/
  session-format-catalog  session-format-v0-to-v1  session-format-v1-to-v2
  session-format-v2-to-v3  session-format-v3-to-v4  session-format
  session-log-deepseek  session-turn-outline ...（session-persistence-sqlite 已不在）

$ git log --format='%h %ad %s' --date=short --diff-filter=A -1 -- packages/preset/agent-preset-registry/package.json
d1e22a7e24 2026-09-21 feat(preset): declare Agent compositions in profile YAML (#4569)
$ git log --format='%h %ad %s' --date=short --diff-filter=A -1 -- packages/mcp/mcp-resources/package.json
3ba5b6eb04 2026-09-12 feat(mcp): add scoped resources and server instructions
$ git log --format='%h %ad %s' --date=short --all -1 -- native/system/README.md
97e7223d5f 2026-09-08 refactor(native): expose Landlock through its capability subpath
$ git log --format='%h %ad %s' --date=short --all -1 -- native/landlock-run/README.md
336ebb235e 2026-09-07 refactor(native): move Landlock workspace to native/system
$ git log --format='%h %ad %s' --date=short --diff-filter=D -1 -- 'packages/preset/agent-presets/package.json'
d1e22a7e24 2026-09-21（与新增同笔：拆分）

$ git log -1 --format='%B' f028f25667
refactor: remove runtime invariant plugins（2026-09-30）
  Delete @deepseek-ai/dsh-invariants、各包 ./invariant 伴生及其测试、invariant gates 与 Vitest
  setup、scoped-event resolver 生成器、INVARIANT-coded rethrow 路径；更新文档并新增 upgrade guide。
$ git show $NEW:docs/upgrade-guide/v0.2.0-rc.2/remove-runtime-invariants/guide.md   # 迁移四条（见 ③-5 引用）

$ git log --all --oneline -- 'packages/llm/llm-replay'        # 空（从未存在）
$ git grep -l 'TurnEndReasonMap' $NEW -- packages            # packages/core/session/src/types.ts 等（live，非 archived）

$ git ls-tree -r --name-only $NEW .agents/notes/ | grep -E '^\.agents/notes/(implemented|proposed|rejected)/[a-z-]+/2026-(08-(1[5-9]|2[0-9]|3[01])|09-[0-9]{2}|10-[0-9]{2})-[^/]+\.md$' | wc -l
346
```

追加核验（2026-10-08，交叉核对）：

```console
$ git rev-list -1 --before='2026-08-15 15:48+0800' --first-parent $NEW    # JH 首提前主线 tip
5bb600f9fb 2026-08-15 02:49 Merge pull request #2577 … revert-2571-worktree-reducepkg
$ git rev-list --count $BASE..$NEW ; git rev-list --count 5bb600f9..$NEW
8407 ; 8403
$ diff <($BASE 包清单) <(5bb600f9 包清单)      # 空输出 = 包集合一致，diff 结论不受影响
$ git rev-list --count $BASE..$NEW -- apps   → 3089（web 2114 / cli 631 / desktop 526）
$ 其余热点（同区间 -- <路径>）：packages/client 2766、subagent 552、session 428、boot 298、
  util 233、interaction 146、skill 112、web 93、mcp 85、lsp 64、extensions 1182
```

组级与更名链复核（同日追加；测量面 = `ls-tree --name-only packages/` 顶层条目）：

```console
$ git ls-tree --name-only $BASE packages/ | sed 's|/$||' | sort > /tmp/g-base.txt   # NEW 同法
$ echo "BASE=$(wc -l < /tmp/g-base.txt) NEW=$(wc -l < /tmp/g-new.txt)"
BASE=54 NEW=60
$ comm -23 /tmp/g-base.txt /tmp/g-new.txt
packages/code-runtime  packages/e2b  packages/examples  packages/runtime-diagnostics
$ comm -13 /tmp/g-base.txt /tmp/g-new.txt
packages/browser-use  packages/computer-use  packages/deliverables  packages/document
packages/experimental  packages/ptc-runtime  packages/ssh  packages/telemetry
packages/tsdown.worker.ts（散件文件，非组）  packages/webhook
$ git ls-tree -d --name-only $BASE packages/ | wc -l → 49 ；NEW → 54    # 组目录面（顶层文件 5→6）
$ for g in code-runtime e2b examples runtime-diagnostics; do git cat-file -e $BASE:packages/$g; git cat-file -e $NEW:packages/$g; done
# 四组均 BASE=Y → NEW=N
$ git diff --name-status -M 7c9bb5914c^1 7c9bb5914c -- packages/code-runtime/ packages/ptc-runtime/
D packages/code-runtime/README.md ; R056 …/README.i18n.yaml → packages/ptc-runtime/… ;
R087 packages/code-runtime/code-runtime-node/package.json → packages/ptc-runtime/ptc-runtime-node/package.json ; …（更名重组）
$ git diff --name-status -M c49db8bc8c^1 c49db8bc8c -- packages/e2b/
D packages/e2b/README.{i18n.yaml,md,zh.md} + packages/e2b/{e2b,fs-e2b,subprocess-e2b}/ 整树全 D（整组移除）
$ git diff --name-status -M 244de7c18a^1 244de7c18a -- packages/examples/
D packages/examples/README.{i18n.yaml,md,zh.md} + agent-spine-demo 全 D（该 merge 前组内仅剩此包，移除即空）
$ git diff --name-status -M f028f25667^1 f028f25667 -- packages/runtime-diagnostics/
D packages/runtime-diagnostics/README.{i18n.yaml,md,zh.md} + invariants 全 D
$ git log --follow -M --diff-filter=R --format='%h %ad %s' --date=short -- 'packages/test-support/llm-replay/package.json'
d02e9f1bd6 2026-06-20 Reorganize packages into a modular hierarchy   # packages/llm-replay/… → packages/support/llm-replay/…
a2d0f7f411 2026-08-13 refactor: apply repository naming contract      # packages/support/llm-replay/… → packages/test-support/llm-replay/…
$ git log --all --oneline -- 'packages/llm/llm-replay' | wc -l → 0   # llm/ 组形从未成立
$ git cat-file -e $BASE:packages/support/llm-replay/package.json → N # 08-13 命名契约早于分析 tip，无残留
```

坑位记录（§7 的方法学）：/tmp 快照 diff 法曾把 BASE 已存在的包误报为新增——已弃用，
全部结论以上述 `ls-tree`/`cat-file` 直查为准。
