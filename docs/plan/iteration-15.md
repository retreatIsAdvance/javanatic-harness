# 迭代 15 — 生产模拟场景进 CI（replay 驱动：keyless、确定性）（状态：进行中）

模块：`examples/headless`（main：`--budget=`；test：生产场景）、`examples/agent-spine`（test：R1ReplayHashTest 升级）、docs；`bundle/base` 零改动（AppBoot/Policy 复用）

四确认日期：2026-09-17（用户确认草案；四处必补照办：provider="replay" 接线 / 审批行数全程计 / R1 终局 schema 指纹重取口径 / LlmPlugin 注入可见性首步核对；待定项裁决：R1ReplayHashTest 升级**转正**）

## 四确认

- **内容**：
  1. **生产模拟场景测试**（examples/headless 新增 `ProductionScenarioTest`）：经 `AppBoot` 以生产组合 boot（bundle base + `Policy.PRODUCTION` verify 通过）→ boot 后注入 `ReplayPlugin`（replay 驱动：无网络、无 key）→ 一条任务内多步工具调用 + 中途 compaction → budget 超限优雅停 → 重启（新 Runtime 载盘）resume 续跑 → 逐锚点折叠 R1 全比对。
  2. **`--budget=` CLI 收口**（it13 挂账原话「完整可达随生产模拟归 it15」）：`HeadlessMain` 增 `--budget=N`（parse/正值校验/USAGE/overlay `loop-guard.maxBudgetTokens`），使 `--verify --policy=production` 可达。
  3. **R1 全比对 + R1ReplayHashTest 升级（转正）**：场景末对盘上日志的**每条** `LlmRequestEvent` 按 seq 前缀折叠重建请求（提示词 + schema 双哈希 + 窗口字段自洽）；`R1ReplayHashTest` 同口径升级——从「静态提示词对多锚点（只证恒常）」升级为「每个锚点都能从日志重建请求」。

- **目标**（验收判据 = roadmap 原话逐项）：「生产模拟场景（PRODUCTION policy + 多步工具任务 + 中途 compaction + 重启 resume + budget 优雅停 + R1 全比对）进 CI 常绿」：
  - **PRODUCTION policy**：场景 boot(verify=true, PRODUCTION) 成功；反向用例（缺 budget）仍拒（S2 判据）。
  - **多步工具任务**：一条任务内 ≥2 次工具调用（fs_read → fs_write → 收尾文本）。
  - **中途 compaction**：任务中途触发（Usage inputTokens 超 `maxContextTokens` → 压缩调用 → 继续）。
  - **重启 resume**：新 Runtime 从盘 load → resume，轮号从日志连续。
  - **budget 优雅停**：超限以 `turn/end(Error: token budget exceeded…)` 关轮，无悬挂 turn/start；累计跨重启（从日志事件求和）。
  - **R1 全比对**：每条锚点双哈希全等 + 窗口字段自洽。
  - **进 CI 常绿**：常规单测随 `mvn package` 双 job 自动跑；CI 实际绿依赖 push 放行（与积压提交同批挂账）。

- **为什么**：
  ① 这是 0.1.0 门槛的最后一环——it16 只剩发布工程（Central/门面冻结/双语 README），功能性「生产模拟」判据归本迭代。
  ② 零件全在但无端到端组合：R1ReplayHashTest（单轮两锚点、程序化组合）、CompactionTest（手装 loop）、HeadlessResumeTest（假 HTTP、无 replay）、Policy.PRODUCTION（只有反向用例）——没有一个场景把「生产组合 + 确定性回放 + 压缩 + 重启 + 预算 + R1」串起来。
  ③ 挂账收口：`--budget=`（it13）与「R1 全比对」口径需本迭代归位。

- **不做**：
  - 真实模型调用 / 真实 key（keyless 硬约束；人工真跑非本迭代判据）
  - 新增第三方依赖；扩 `ReplayAdapter` 错误能力（overflow 已有 CompactionTest 的 custom adapter 覆盖；本场景走 Usage/ratio 路径）
  - module-info 依赖方向改动；one-shot `jh "任务"` 输出形态
  - 新建 example 模块；跨模块共享 test 构件（见落盘补充 6）
  - 提前 push

### 落盘补充（确认时要求的四处必补 + 两处落盘钉）

1. **provider="replay" 接线显式**（必补①）：场景 agent 的 `AgentOptions` 显式 `("replay", "m")`。keyless PRODUCTION 组合里 `llm-openai-compat` 行被 env 表达式禁用——用默认 provider 会在首次调用路由失败。剧本骨架第一条钉。
2. **审批放行行数按全程计**（必补②）：`System.setIn` 一次给足顺序行，行数 = 全程工具触审批数（含 leg2 resume 后）；该行数是场景脚本的一部分，首步核对后定稿。
3. **R1 终局 schema 指纹重取口径**（必补③）：终局新 Runtime 无 agent——`toolSchemaFingerprint` 从终局组合 `rt.root().require(ToolRegistry.KEY).schemas(rt.root())` 重取（与锚点写入时同形状 scope），与折叠提示词哈希逐锚点比对；锚点表已列。
4. **首步核对清单**（必补④，开工第一件事）：
   - ① 哪些工具触审批（fs_read / fs_write / shell…）→ 定 setIn 行数（见必补②）；
   - ② `LlmPlugin` 路由对 boot 后注入 adapter 的可见性（无缓存重解析 = R3 结构性保证——花一行验证，与审批核对同列）。
5. **场景家（落盘钉）**：`examples/headless`。依据：AppBoot 双向显式校验要求宿主持有**完整插件集**——headless 已有（HeadlessVerifyTest 实证），仅需 test-scope 加 `harness-llm-replay` 一行；agent-spine 需补多个 test-scope 插件依赖，且其既有 `harness-llm-deepseek`（test）存在被 discover 发现 → 双向校验失败的风险，得不偿失。
6. **fold helper 形态（落盘钉）**：两处**局部私有实现**（场景 + R1ReplayHashTest 升级），口径逐字对齐（场景侧先落，升级侧对齐）；不为 ~20 行跨模块引入 test-jar / 新模块。

## 剧本骨架（S1 实现直接照此；数值已在首步核对后钉死，见下）

| 腿 | 动作 | 断言要点 |
|---|---|---|
| 装配 | profile.yml（`bundles: [base]`, `policy: production`）+ overlays（fs-local/shell-tool/sandbox-policy/persistence-jsonl 钉 @TempDir；approval-ask 启用、approval-auto/deny 滤除；loop-guard `maxBudgetTokens=N`；compaction 启用 `maxContextTokens=M`）→ `AppBoot.boot(verify=true, PRODUCTION)` → `loadAll` 注入 `ReplayPlugin(scripts)` | verify 通过；manifest 为组合真相（不含注入项） |
| leg1 | followup(多步任务) → 脚本：fs_read → fs_write →（Usage inputTokens>M）→ compaction（summary 脚本）→ 终答 STOP | turn1 闭合；compaction 事件对在案；工具真跑 |
| leg2 | dispose → **新 Runtime**（同 profile+overlays，同 boot+注入+注册）→ `persistence.load` → `SessionStore.create(seed)` → `agents.resume` → followup → 脚本：fs_read → 终答 STOP（终答 Usage 使累计 outputTokens 跨 N；step 检查在调用前，不误触） | 轮号从日志连续；resume 后工具面照常 |
| leg3 | 同 leg2 runtime followup → turn3 起始 `checkBudget` 拒 | `turn/end(Error)` 消息含 token budget exceeded；start/end 配对无悬挂；agent 静止 |
| 终局 | **第三个 Runtime** 载盘 → 逐锚点前缀折叠（`events[0..messagesToSeq]` → `Session.create` → `prompts.assemble` → sha256；schema 从终局 registry 重取） | 每条锚点双哈希全等；`messagesFromSeq==0`、`messagesToSeq==锚点 seq-1` |

各腿各自构造 `ReplayPlugin`（脚本随腿重置——callIndex 按实例）。

**提示词非平凡化（S1 实现补充）**：各 runtime 装配注册 `PromptSection.Static` + `PromptSection.Dynamic`（"tool results so far: N" 自日志派生）——提示词逐锚点变化（0/1/2/2/3），折叠必须重建锚点当时的日志状态；否则「静态提示词哈希恒等」只证恒常（正是 R1ReplayHashTest 旧形态被点的弱点）。断言含 `systemPromptSha256` 非全等（distinct > 1）以钉住这一点。

**首步核对结果（2026-09-17，开工首步）**：

- ① 审批面：`ToolExecutorImpl` 第 3 步为固定 stage——**每个工具执行恰好一次** `approval.require`（core/tools/ToolExecutorImpl.java:103-106）→ HUMAN_GATE 下 setIn 行数 = 全程工具调用数 = **3**（fs_read / fs_write / leg2 fs_read）；读完 EOF 即拒（fail-closed，场景无碍）。
- ② adapter 注入可见性：`RoutingLlmService.stream()` **调用时**查注册表（llm/llm/RoutingLlmService.java:24，无缓存）→ boot 后注入即见；且 `llm/replay` module-info 无 `provides`、模块无 META-INF/services → ServiceLoader 与双向显式校验不会发现它（注入不污染组合真相）；`PluginLoader.loadAll` 支持 boot 后增量装载（preset 先例，批外 requires 视为外层组合已满足）。
- ③ 数值钉（读 CompactionPlugin 源码后修正）：`shouldCompact` 取 `lastInputTokens` = **全日志 inputTokens 的 max()**（高位水位，CompactionPlugin.java `lastInputTokens`），非「末次」——一旦跨阈，之后**每个 step top 都会复发压缩**。故剧本改为：脚本 leg1 = [A(fs_read, input=60), C(fs_write, input=150 → 跨 M=100), S1(摘要), D(终答, input=60)]；leg2 = [S2(摘要), E(fs_read, input=60), S3(摘要), F(终答, input=60)]。压缩共 **3 次**：turn1 step2 top（A、C 后跨阈）、leg2 turn2 step0 top、step1 top；每次摘要各耗一条脚本（S1/S2/S3）。`retainTokens=1`（日志极小，必须显式小于尾部估价才压得动；否则 keepFrom=0 → "nothing to compact" 抛错）。单条 outputTokens=10 → budget `N=40`——turn1 累计 10；turn2 step0 前 20、step1 前 30 均过（拒条件为 >N）；终答后 40 仍不过……**终局核对**：leg2 F 之后累计 = 5 条 assistant × 10 = 50 > 40 → turn3 起始 `checkBudget` 拒。值在 S1 以断言固化。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - `ProductionScenarioTest`（examples/headless test；含局部 fold 断言与装配 helper）
  - `--budget=` CLI 项（`RunnerOptions.budget` + parse 正值校验 + USAGE + buildOverlays overlay）
- **MODIFIED**：
  - `examples/headless/pom.xml`：test-scope + `harness-llm-replay`（一方，一行）
  - `HeadlessOptionsTest`：--budget 解析/校验/USAGE
  - `HeadlessVerifyTest`：PRODUCTION 可达（`--approval=ask --budget=N` 过；无 budget 仍以 token budget 违规拒——it13 挂账精确证据）
  - `R1ReplayHashTest`：逐锚点前缀折叠升级（只证恒常 → 重建请求）
  - `docs/design/10-testing.md:§3.4`：R1 测试口径同步；`docs/design/README.md` 路线表；`AGENTS.md` 现状
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `examples/headless/…/ProductionScenarioTest.java`（新） | 场景全量：boot+verify、注入、剧本、逐锚点 fold | ☑（S1 已放行） |
| `examples/headless/pom.xml` | test-scope `harness-llm-replay` | ☑（S1 已放行） |
| `examples/headless/…/HeadlessMain.java:RunnerOptions/parse/USAGE/buildOverlays` | `--budget=N`（正值校验；Replace loop-guard.maxBudgetTokens） | ☑（S2 已放行） |
| `examples/headless/…/HeadlessOptionsTest.java` | --budget 用例 + USAGE 断言 | ☑（S2 已放行） |
| `examples/headless/…/HeadlessVerifyTest.java` | PRODUCTION 可达正反用例 | ☑（S2 已放行） |
| `examples/agent-spine/…/R1ReplayHashTest.java` | 逐锚点前缀折叠升级（schema 重取口径同上） | ☑（S3 已放行） |
| `docs/design/10-testing.md:§3.4` / `docs/design/README.md` / `AGENTS.md` | 口径与状态同步 | ☑（S3 已放行） |

## 审查停点（到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S1 生产场景测试（replay 注入 + 剧本 + 局部 fold） | `ProductionScenarioTest` + `pom.xml` + 首步核对结果 | ☑ 已放行（9a6da31） |
| S2 `--budget=` CLI | `HeadlessMain` + `HeadlessOptionsTest` / `HeadlessVerifyTest` | ☑ 已放行（a8cf8c8） |
| S3 R1ReplayHashTest 升级 + 文档同步 | `R1ReplayHashTest` + 10/README/AGENTS | ☑ 已放行（e465b87） |

## 验收（证据 = 实际执行的命令与结果）

- [x] 首步核对清单结果在案（工具审批面 / LlmPlugin 注入可见性 / 数值钉——见「剧本骨架」后核对结果块）
- [x] S1 聚焦测试绿：`mvn -B -pl examples/headless test` → 37/37 绿（ProductionScenarioTest 1/1）；突变验证：折叠前缀改全量日志 → R1 断言拒（"anchor seq 4 提示词重建" 哈希不符），还原后复绿
- [x] S2 聚焦测试绿：`mvn -B -pl examples/headless test` → 41/41 绿（HeadlessOptionsTest/HeadlessVerifyTest 各新增 2 例）；突变验证：注释掉 loop-guard overlay → `verifyProductionReachableWithHumanGateAndBudget` 拒（expected: 0 but was: 1），还原后复绿
- [x] S3 聚焦测试绿：`mvn -B -pl examples/agent-spine -am test` → BUILD SUCCESS（上游全模块绿；agent-spine 4 类 6 用例 0 败 1 跳——keyless e2e 自跳过）；突变验证：折叠前缀改全量日志 → R1ReplayHashTest 拒（"anchor seq 4 提示词重建" 哈希不符），还原后复绿
- [x] 全反应堆 `mvn -B -q package` 绿（组合面改动）——2026-09-17：BUILD SUCCESS，63 类 / 365 用例 / 0 败 0 错 4 跳（keyless e2e 自跳过），jlink 镜像建成（`harness-dist-jh` SUCCESS）；前置修复 #25 flake（58c5195，见「修正」）——首两跑红皆因它（2/2），修复后一次通过。注：构建树另含非本迭代的 module-info opens 未提交扫批（并行工作面，非本迭代改动面）
- [x] 文档同步：10 §3.4 R1 口径（逐锚点前缀折叠 + 非平凡化前件）、README 路线表（it15 入已完成、表起点 it16）、AGENTS 现状（headless 增 `--budget=` + 场景测试；治理档示例补 budget）
- [ ] CI 双 job 绿（push 放行后复验；与积压提交同批挂账）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| 58c5195 | `TodoPluginTest.parallelBatchInterleavesAndPairsByCallIdNotAdjacency`（#25）门禁单向因果仍有缺口：`tool/call` 在各批内 worker 落账，慢 worker 晚于快工具全序列起步时审计对成相邻（全量两连红 2/2；434c017 的因果设计未闭合） | `gate_echo` 双向因果门控——门控等 slow 的 `tool/call`、slow 等门控 result，`slow.call < gate.result < slow.result` 恒成立；任一串行执行序必超时炸红。证据：聚焦 20/20、14-spinner 饱和 8/8、串行执行突变红（`TodoPluginTest:174` 超时）/还原 6/6 |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
