# 迭代 17 — CLI 任务结果闭环与真实任务基线（状态：已完成——S1 提交 cf6a7e3、S2 备料提交 791642c（校正 cef0348/dce66a7）；首轮基线 11 次运行完成并回收：达成 7/10 任务（8/11 运行）；随本次收口提交生效）

模块：`examples/headless`（runner 消费面；内核零改动）+ 文档（`12-api-stability` 同步、README ×2、AGENTS、新增 `docs/baseline.md`）

四确认日期：2026-09-18（草案 v1 起草于 2026-09-18，三裁决点同日裁定；总体路线 it17 行见 [design/README](../design/README.md#phased-evolution-plan)）

## 四确认

- **内容**：
  1. **one-shot 最终答案输出到 stdout（仅成功路径）**：stdout 契约 = **成功有结果 / 失败为空**——任务完成（`turn/end` Completed）时，把**本次运行新开轮**内最后一条 `assistant/message` 的文本块打印到 stdout（纯文本）；**失败（Error/Aborted/无终局）时 stdout 一律为空**，脚本判读无歧义。空答案边界：成功但最终文本为空 → stdout 空 + exit 0（合法成功）。“本次运行新开轮”以 `seq >= Session.firstLiveSeq()` 过滤（现成 public API，resume 天然正确），不用 turn 号推理。诊断（会话 id、事件清单、失败文案、模型遗言）保持 stderr——脚本可 `out=$(jh "任务")` 直接取答案。
  2. **退出码词表**（0.1.x 内的新增语义，同迭代同步 [12 §6](../design/12-api-stability.md)；不删既有 flag 与 0/1/2 语义）：
     - `0` = 任务完成（本轮 `turn/end` Completed）
     - `1` = `--verify` 违规（不变）
     - `2` = 用法错误 / 缺 API key（不变）
     - `3` = 任务失败（本轮 `turn/end` Error：provider 失败、守卫/预算超限）；**kind 级分辨归 stderr 文案，退出码保持三态粗粒度**（不设预算专属 `5`；真需要时后加是纯增量）
     - `4` = 任务被取消（本轮 `turn/end` Aborted；一次性路径当前无 CLI 取消入口——映射先钉住，端到端可达面归 it18）
     - REPL 路径退出码不变（`/exit`、EOF → 0）。
  3. **失败文案**：one-shot 失败时 stderr 打印与 REPL 同源的失败文案（复用 `StreamRenderer.failureText`），不再只有事件类型清单；**模型遗言（失败前最后文本）进 stderr 诊断块**，终端不丢信息。
  4. **异常收敛**：本次运行没有可判定的 `turn/end`（如驱动外异常）→ `3`（fail loud，不当成功）。
  5. **真实任务基线（用户执行）**：新增 `docs/baseline.md`——固定 10 个小型任务（定位 ×3、修改+验证 ×3、测试运行 ×1、重构 ×1、文档 ×1（双语合并）+ **resume 续跑 ×1（两段 = 两次运行）**；种子 = `git archive` 钉提交 `cf6a7e3` 的快照工作区），含记录协议（退出码/stdout 判读、耗时、output tokens 汇总命令、主观判定）。**10 次真跑由用户执行（计费决定权 / 凭证不进会话 / 任务达成的人工判断均归用户），会话只备料（任务清单、种子命令、判读与记录模板）**；S2 停点即交接点。27/30 成功率目标是 0.2.0 发布验收（由后续轮次积累），非本迭代判据。
  6. **文档同步**：`--help` 增退出码段；`12-api-stability §6` 增退出码词表 + stdout 契约 + 空答案边界；README ×2（英/中）用法段各 1-2 行；AGENTS「跑一个 task」补一行退出码。

- **目标**：`jh "任务"` 的结果可由 shell 脚本用退出码 + stdout 直接判读；失败原因在 stderr 可行动；建立可反复执行的真实任务基线（替代社区反馈的校准输入之一）。证据 = 聚焦测试（成功 / 鉴权失败 / 预算超限 / 映射单测）+ 全量绿 + 首轮基线记录（用户回收）。

- **为什么**：CLI 是 0.1.0 发布后的产品主语，但当前 one-shot 失败也 `exit 0`、stdout 无答案（`HeadlessMain.java:330-341` 只向 stderr 打事件类型清单）——脚本无法判断任务结果，这是“生产可用”的最低门槛（[design/README 0.2.0](../design/README.md#020--reliable-single-agent-product) 的首要切片）。退出码 + stdout 属 CLI 稳定面（12 §6），必须先于取消/恢复/资源边界等内核收敛——后续迭代的验收（it18 取消、it19 恢复）都建立在“结果可判读”之上。退出码是**新增**语义，符合 0.1.x「可加不可删」；同时藉首轮真跑建立真实任务基线，实践“维护者场景驱动、真实任务验收”。

- **不做**：
  - 不改 REPL 行为与 stderr 事件清单形态（保持现状，仅新增失败文案一行）
  - 不做 JSON/结构化输出、不做 one-shot 进度渲染
  - 不做取消的新入口（信号处理 / CLI 取消命令）——归 it18
  - 内核模块零改动（core / llm / session 不动；runner 只读 session 事件做映射）
  - 不做基线自动化 runner 脚本（首轮人工记录，自动化按需后置）；不引入新依赖
  - 基线任务不叠加 docker / 沙箱特殊组合（普通本地运行，用默认模型）

### 裁决记录（2026-09-18）

1. **退出码 3/4 采纳，不设预算专属 5**——kind 级分辨归 stderr 文案，退出码保持三态粗粒度；真需要时后加 5 是纯增量。
2. **失败时 stdout 不打印最后文本**（与草案提案相反）——stdout 契约 = 成功有结果 / 失败为空，脚本判读无歧义；模型遗言归 stderr 诊断块；终端不丢信息。
3. **基线 10 次真跑由用户执行、会话备料**（计费决定权 / 凭证不进会话 / 任务达成的人工判断均归用户）；**S2 停点为交接点**。
4. **两处实现形状**（已进锚点表）：新事件边界用 `seq >= firstLiveSeq()` 过滤（现成 public API，resume 天然正确），替代 turn 号推理；契约定义空答案边界：成功但最终文本为空 → stdout 空 + exit 0，写进 12 §6 表。
5. **S1 测试面点名**：exit 3 复用假服务端 401 让失败任务首次穿过 `HeadlessMain.run`；预算终止复用 ProductionScenario overlay 形状；验收含成功 stdout 断言 / 401→3 / 预算→3 / resume 不误判旧轮。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：CLI 退出码词表（3 / 4）；one-shot stdout = 最终答案契约（12 §6）；`docs/baseline.md`（任务集 + 协议 + 记录）
- **MODIFIED**：`--help` 文本（退出码段 + stdout/stderr 说明）；`12-api-stability §6`；README ×2 用法段；AGENTS「跑一个 task」
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `HeadlessMain.java:run`（330-341 一次性分支） | 成功时 stdout 答案；失败时 stdout 空 + stderr 失败文案与遗言诊断块；退出码按终局映射 | ✅ |
| `HeadlessMain.java`（新增 static：`finishOneShot` / `liveEvents` / `finalAnswerText` / `oneShotExitCode` / `oneShotFailureText`，package-private 供测试；`run` 增 `err` 注入重载） | `liveEvents`：本次运行新开轮（`seq >= session.firstLiveSeq()`，现成 public API）；`finalAnswerText`：新开轮最后一条 `assistant/message` 文本；`oneShotExitCode`：Completed→0 / Error→3 / Aborted→4 / 无 turn/end（含未知变体）→3 | ✅ |
| `HeadlessMain.java:USAGE` | 退出码 + stdout/stderr 契约段 | ✅ |
| `docs/design/12-api-stability.md` §6 | 退出码词表 + stdout 契约 + 空答案边界行（成功但最终文本为空 → stdout 空 + exit 0） | ✅ |
| `README.md` / `README.zh-CN.md` 用法段 | 任务结果契约 + 退出码注释 | ✅ |
| `AGENTS.md`「跑一个 task」 | 任务结果契约 + 退出码注释 | ✅ |
| `docs/baseline.md`（新） | 任务集（10 条 / 11 次运行，含多步与 resume）+ 记录协议（模型/参数/日期/费用，二值判定）+ 种子命令 + 首轮记录位 | ✅（备料交付；真跑由用户执行，S2 交接） |
| 测试：新增 `HeadlessOneShotResultTest`（假服务端三例 + 映射/答案单测）；复核既有断言无回归 | 7 例：成功 stdout / 空答案 exit 0 / 401→3 / 预算→3（假服务端 tool_calls+usage 经 `--budget` overlay）/ resume 失败不回放旧轮 / 映射单测 / seed 边界单测；模块 48 例全绿 | ✅ |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S1 CLI 结果契约（stdout 语义 + 退出码 + 失败文案；--help/12/README/AGENTS 同步；聚焦测试） | `HeadlessMain` + 12 §6 + README ×2 | 已放行；已提交 `cf6a7e3`（含证据计数勘正 67 类/379 测试与 packet 注记） |
| S2 **交接点**：基线任务集定稿 + 备料交付（任务清单 / 种子命令 / 判读与记录模板）；**10 次真跑由用户执行**，会话不执行真跑、不进计费与凭证 | `docs/baseline.md` + 用户真跑 | 已放行；备料已提交（791642c）；10 条任务 / 11 次运行已执行并回收（2026-09-18，见下「首轮回收注记」） |

## 验收（证据 = 实际执行的命令与结果）

- [x] 全量 `mvn -B package` 绿：67 类 / 379 测试 / 0 失败 / 0 错误 / 11 跳过（环境门控自跳）——`/tmp/it17-s1-full.log`（BUILD SUCCESS，34.7s 增量；口径 = 全部 `-- in ` 逐类行，含 3 条与 approval 回显粘连的行）
- [x] 聚焦（`HeadlessOneShotResultTest`，`-pl examples/headless -Dtest=HeadlessOneShotResultTest`）：成功（假服务端 200）→ exit 0 且 stdout = `回答完成\n`；401 穿过 `HeadlessMain.run` → exit 3、stdout 空、stderr 含「认证失败…401」；预算（假服务端 tool_calls+usage 60 经 `--budget=50` 走 loop-guard overlay）→ exit 3、stdout 空、stderr 含 `token budget exceeded`；resume 401 失败 → stdout 空且 stderr 无旧轮文本（`第一轮答案` 不出现）；空答案 → stdout 空 + exit 0
- [x] 映射单测 Completed→0 / Error→3 / Aborted→4、无 turn/end→3、未知变体→3（fail loud）
- [x] 突变检查：`liveEvents` 换成全量事件 + Error 分支改判成功 → 4 例必红（auth/budget/resume/映射单测），还原后全绿
- [x] `--help` 实核（jlink 镜像 `bin/jh --help` → exit 0，退出码段与契约段在案）；`12-api-stability §6` 同提交更新
- [x] jlink 镜像重建（`mvn -B package` 产物）：`bin/jh --help` exit 0 / `bin/jh --verify` exit 0
- [x] 首轮基线：10 条任务 / 11 次运行全部执行并回收（01–03 维护者执行、04–10 会话代跑；判据逐条复核 + 04/05/08 独立复跑）；达成 7/10 任务（运行 8/11）；记录见 `docs/baseline.md` §5（含 06/07/09 未达成归因）
- [x] REPL 无回归：模块全套 48 例绿（HeadlessReplTest 4 / HeadlessResumeTest 2 / ProductionScenarioTest 1 等）
- [x] 文档同步四件：`--help` / 12 §6 / README ×2 / AGENTS

### S1 packet 注记（放行复核补充）

- 事件清单 / 会话 id 经 `System.Logger` 落 stderr：**生产为真、测试不可证**（日志不经注入的 `err` 流，聚焦测试只断言失败文案与遗言诊断块）——it13 继承形状，本次不改代码。

### S2 备料注记（任务集定稿口径）

- 任务分布（实况）：定位 ×3、修改+验证 ×3（含跨模块多步 1 条）、测试运行 ×1、重构 ×1、文档 ×1（双语合并为一次运行）、resume 续跑 ×1（两段 = 两次运行）——共 10 条 / 11 次运行。
- 备料期核验过的事实：tokens 汇总 jq 命令在真实会话日志上已验证；`Policy.java` PRODUCTION 违规类别 ≥6 类（任务 03 判据「≥3」）；`BudgetGuardTest` 现无「used == max」边界用例（任务 05 素材成立，实现为 `>` 拒 / `==` 放行）；README ×2 均含旧计数 362（本快照冻结值 379，任务 09 素材成立）。
- 已知执行边界：shell 工具超时 60s 为组合期上限（模型不可调）；任务文本只含模块级增量命令，实测均远低于上限（见下演练）；`docs/baseline.md` 给预热步骤与「超时记未达成」的记录口径。
- 备料演练（2026-09-18，非真跑——无模型调用无计费）：种子 tar 解压 → 预热 `-pl examples/headless -am -DskipTests package` 冷启动 **8.995s** → 模块 `test`（48 例）**3.573s** → 全量 `package`（半热）**36.862s** → `jh --verify --workspace=<快照>` **exit 0**。据此修正了 `docs/baseline.md` 的预热耗时估计与超时边界描述（见修正表）。

### 首轮回收注记（2026-09-18）

- 执行实况：11 次运行全部完成并回收（逐行记录见 `docs/baseline.md` §5）。01–03 由维护者执行；04–10 由会话代跑（用户配置 `DEEPSEEK_API_KEY` 后继续收口；key 值不进对话，计费与判定权仍归维护者）。启动 cwd：01–03 从仓库目录、04 从 `study/deepseek`、05–07 从 `~/jh-baseline`、08–10 按 §4 修订模板 `cd` 进 case 目录。
- 结果：达成 7/10 任务（运行 8/11）；总 tokens 2,907,197 in / 30,371 out。未达成 3 条——**任务 06**：停于两问（A/B 歧义 + 验证不可达）未落编辑，且任务文本 `-am` 在 agent 沙箱内拖入 `sandbox/local` 嵌套 e2e（已知边界）；**任务 07**：agent 信提示词 `working directory`（=`~/jh-baseline`）在其下跑 mvn → reactor 报错 → 停问；**任务 09**：loop-guard 40 步/轮上限截断（无 Completed 终局 → exit 3、stdout 空符合契约），两个 README 已改至正确值 379 但未及收尾。
- 判据面复核（会话独立取证）：任务 04/05/08 在 `unset DEEPSEEK_API_KEY` 后模块测试全绿（49 / 40 / 48 例）；任务 07 快照 surefire 39 例全绿；任务 09 两 README 实核为 379；任务 10 运行 2 stdout 恰为标记本身（`--resume` 同会话增一轮）。
- 下一迭代问题清单候选：① 任务 06 文本去 `-am`（依赖链拖入 `sandbox/local`，沙箱内嵌套 e2e 预期红）；② 提示词 `working directory` 改钉 `--workspace`（消除两处 cwd 取值不一致，见修正表）；③ 步骤预算（40 步/轮上限对文档核对类任务的余量；超限终局 `failureKind=unknown`）；④ 模型停问倾向（06/07 与 10-run1 均出现「先问后做」，是否提示词引导）。

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| `cef0348` | `docs/baseline.md` 备料时预热耗时估计失实（写「约 1–2 分钟/目录」）且超时风险描述过重（「首次构建超过 60s」） | 准备步骤演练实测（见上），修正为「冷启动 ~10s/目录」；「已知边界」改为实测三档数字（预热 9s / 模块 test 4s / 全量 37s），并保留「超时记未达成」口径 |
| `dce66a7` | `docs/baseline.md` 种子命令顺序 bug：`git archive -o ~/jh-baseline/seed.tar` 在 `mkdir -p` 之前，目录不存在时第一行 fatal（exit 128，用户执行时暴露） | `mkdir -p $BASE` 前置并统一用 `$BASE/seed.tar`；补 `ls $BASE/case01` 验证行 |
| （随本次提交） | 命令块内 `#` 注释行在 zsh 交互模式粘贴报 `command not found: #`（无害但干扰判读；用户真跑任务 01 时暴露） | `docs/baseline.md` 全部命令块去注释、说明移出块外，并加「块内不写 `#`」口径；`§4` 耗时口径补「`.err` 时间戳 → `turn/end.time` 推算」法 |
| （随本次提交） | 基线任务 06 文本以 `--help` 开头，被 headless CLI 当未知选项解析 → exit 2（首轮真跑暴露；零计费、工作区无副作用） | 任务文本改为 `jh --help …` 前缀（`docs/baseline.md` §3 同步）；记录行备注保留事故说明 |
| （随本次提交） | §4 模板未规定启动 cwd：headless 提示词 `working directory` = jh 进程 `user.dir`（`AgentLoopImpl` request-context），而 shell 实际在 `--workspace` 执行——从 case 目录外启动时两者不一致、可误导模型（首轮任务 07 实测：agent 信提示词、在 `~/jh-baseline`（无 pom）跑 `mvn -pl core/agent-loop` → reactor 报错 → 停问未达成；任务 06 亦先绕行多步） | `docs/baseline.md` §4 单次与 resume 命令块均加 `cd` 进 case 目录（使两者一致）；「已知边界」补登记该条 + 「提示词改钉 `--workspace`」挂下一迭代；首轮 08 起按新模板执行，01–07 按启动 cwd 实况记录（01–03 从仓库目录、04 从 `study/deepseek`、05–07 从 `~/jh-baseline` 启动） |
| （随本次提交） | 任务 07 判据的例数估计失实（写「约 10 例」，快照实况 39 例；回收时以 case07 surefire 报告实数复核） | `docs/baseline.md` §3 任务 07 判据改为「本快照冻结值 39 例」 |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 四确认第 5 条草案分布「文档 ×2」（初稿） | 文档 ×1（双语两个 README 合并为一次运行）+ resume 续跑 ×1（两段 = 两次运行） | 放行复核要求任务集含 resume 续跑；10 条任务约束下把双语 README 合并（同一判据面），总运行数为 11 次 | 迭代内已同步（`docs/baseline.md` 定稿，四确认行已更新） |
| 四确认第 5 条「10 次真跑」 | 10 条任务 / 11 次运行（任务 10 两段） | resume 任务天然需要两次运行（先建上下文、再续跑）；单条任务语义不变 | 迭代内已同步（任务集与记录表均按 11 行设计） |
| 四确认第 5 条 / 裁决记录 #3「基线真跑由用户执行、会话不执行真跑」 | 01–03 由维护者执行；04–10 由会话代跑 | 用户在本机配置 `DEEPSEEK_API_KEY` 后要求继续收口未完成项；key 值不进对话，计费与判定权仍归维护者（记录由其复核，随提交放行生效） | 迭代内已同步（`docs/baseline.md` §5 执行实况注记 + 「首轮回收注记」） |
