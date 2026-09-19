# 迭代 18 — 取消与执行收敛（状态：已完成——S-a/S-b/S-c 分别提交 add1089/cd76cc5/6649f46；④ 补测与 ②⑦⑧ 核对随本次收口提交生效；验收 ①–⑧ 全部勾选）

模块：`core/tools`（executor join-all 收敛 + 审批 seam 变更）+ `core/agent-loop`（whenIdle 语义/重试语义注释）+ `interaction/approval`（可轮询审批通道）+ `examples/headless`（SIGINT 入口 + exit 4 + REPL Ctrl-C）+ 文档（04 / 09 / 12 迁移 / `--help` / README ×2）

四确认日期：2026-09-18（草案 v1 起草于 2026-09-18；四裁决点同日裁定）——总体路线 it18 行见 [design/README](../design/README.md#020--reliable-single-agent-product)

## 四确认

- **内容**：
  1. **收敛语义修复（executor join-all）**：`ToolExecutorImpl.execute` 从「首个异常 Future 即抛、放弃兄弟 join」改为**等全部 Future settle 再传播**；传播的异常选择规则 = **`AbortedException`（输入序首个）优先，其余异常按输入序**。语义钉死：**`whenIdle` 完成 ⇒ 无工具线程仍在跑**（协作工具取消即返回；不合作工具拖到它自己返回为止——限制见第 4 条）。`Agent.whenIdle` javadoc 与 `STATUS.IDLE` 注释同步该定义。
  2. **审批等待取消（seam 变更）**：`ApprovalService.require` 增 `AbortSignal` 参数；**参数不够**——`ApprovalPrompt.stdin()` 阻塞读须改**可轮询形状**；等待中取消**抛 `AbortedException`**（走既有收敛，**不落 error result**）；REPL 的 `ReplApprovalInput` 同步接。属 0.2.0 内 seam 契约变更，按 [12](../design/12-api-stability.md) 附迁移说明。
  3. **CLI 取消入口（exit 4 端到端可达）**：`sun.misc.Signal` 捕 SIGINT——**一次性任务** → `cancel(User)` → 主线程自然走完 → **exit 4**（不需 `System.exit`）；**REPL Ctrl-C = 取消当前轮不退出**（aborted 落账后 REPL 继续；空闲 Ctrl-C/EOF 才退）；**第二次 SIGINT = halt（130）逃生门**（收敛卡住时用户可强退）。三钉子：`jdk.unsupported` 用**非 static requires**（若改静态则须同时列 dist 模块清单，二选一，实施时以 jlink 镜像实跑验证）；CI 不便测信号 → **子进程 INT 集成测试或手工验收记录**（S3 定形）。
  4. **不合作插件限制（不设等待上界）**：join-all 后，不轮询信号且无 `onCancel` 的纯 Java 工具会**拖住收敛直到它自己返回**（dispose 亦然）——不设上界，**根据**：Java 本无法强杀线程；子进程击杀已由 `onCancel` 接住（shell 双 Provider 在案）；纯 Java 不合作插件在 04/09 明示限制。超时（shell 60s/LLM 看门狗）≠ 取消的关系写进关系表：**超时 = tool error result 不停轮；取消 = aborted 停轮；idle = 驱动静止（含工具线程）**。
  5. **核验测试**：重试不重放（REQUEST_ERROR / 溢出恢复路径 `tool/call` 恰好一次）——新测试顺带裁定「**重试 `continue` 不 `step++` → 同号 `step/start` 重复**」为**预期语义**并文档化（每次尝试 = 新 `step/start` + `llm/request`，step 号复用）；审批取消单测用 **fake prompt 注入信号实现**；端到端 SIGINT 走手工验收记录。
- **目标**：路线四条核心验收各有确定性测试证据（keyless），且 CLI 用户 Ctrl-C 后数秒内收敛退出（exit 4）、会话 `turn/end(aborted("user"))` 落账可 `--resume` 续。验收问题（每轮必答）：**「长任务停不下来、停了不知干不干净」变为「取消后 `whenIdle` 完成即安全——按路线原话：完成时慢写工具必须已停，无遗留执行、无 idle 后写文件；一次性任务退出码 4 可判读」**。
- **为什么**：0.2.0 主题是「可靠单 Agent」——用户能停止执行并安全恢复。it17 让「任务结果可判读」，本迭代让「停止可判读」：取消骨架（AbortController/信号/收敛）自 it5/it6 已存在且单测在案，但**收敛边界从未被端到端钉过**（多工具并行只测过单工具、审批等待无信号、不合作插件无文字、CLI 无入口）；it19（崩溃恢复）与 it22（取消入口的用户面）建立在本迭代的收敛语义之上。
- **不做**：
  - 不做 `Thread.interrupt` 迁移、不做强杀不合作工具的机制——协作式是既定机制（09 §4），本迭代只把限制写明并用测试钉住
  - 不设等待上界（已裁定，见裁决 4）
  - 不做 REPL 的会话列举/状态面板/澄清问答——归 it22；本迭代只给 REPL Ctrl-C 取消当前轮语义
  - 不做 `runMaintenance` 的取消（04 §2 挂账：维护取消语义随第一个真实维护消费者定形，归 it20）
  - 不改 `AbortSignal` / `AbortController` / 事件 schema（`turn/end(aborted(cause))` 形态不变；`Agent.cancel` 签名不动）
  - 不做超时值调整（shell 60s / LLM 看门狗维持现状），只做「关系明确」
  - 不引入第三方依赖（`jdk.unsupported` 属 JDK 内部模块，非第三方）

### 裁决记录（2026-09-18）

1. **并行工具收敛 = join 全部再传播**；异常选择规则：`AbortedException`（输入序首个）优先，其余按输入序。验收按路线表原话：`whenIdle` 完成时慢写工具必须已停。
2. **`require` 增信号**（seam 变更，0.2.0 迁移说明）；点破实现现实：参数不够——stdin 须改可轮询形状；取消抛 `AbortedException` 不落 error result；REPL 的 `ReplApprovalInput` 同步接。
3. **`sun.misc.Signal` 捕 SIGINT**；三钉子：① `jdk.unsupported` 用非 static requires（静态则须同时列 dist 模块清单，二选一，实施时镜像实跑验证）；② REPL Ctrl-C = 取消当前轮不退出（空闲 Ctrl-C/EOF 才退）；③ 第二次 SIGINT = halt（130）逃生门。CI 测不到信号 → 子进程 INT 集成测试或手工记录。
4. **不设等待上界**；根据：Java 本无法强杀线程；子进程击杀已接 `onCancel`；纯 Java 不合作插件文档明示。
5. **（补充）重试 `continue` 不 `step++` → 同号 `step/start` 重复**：新测试断言 `tool/call` 恰一时顺带裁定（倾向文档化为预期语义）。
6. **（补充）审批取消单测**用 fake prompt 注入信号实现；端到端 SIGINT 手工验收记录。
7. **（2026-09-19 放行附注）** S-a 放行；冻结语义「整批工具无视取消并正常返回 → turn 以 `completed` 关轮」背书（裁决 4 的逻辑必然，日志诚实）。CLI 入口停点（S-c；放行附注写作「S-d」）落地时 12 §6 须补一行「取消仅对协作面生效，不合作批以 Completed 收口（exit 0）」。
8. **（2026-09-19 放行附注）** S-b 放行；偏离 1–4 全部接受（`onCancel` 保留不计时断言；REPL 用例断言结局非路径；`ReplApprovalInput` 仅补观测面；不可中断原读残留已文档化）。附带项：并发 ask 行窃取观察行见「修正」表（pre-existing，挂账不拦）；S-a 与 S-b 分两个 commit；证据措辞自 S-c 起——mutation 日志带变体标注、sat 轮日志内回显退出码。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：04 §8/§9 取消收敛语义补全（join-all 次序与异常选择、不合作限制「不设上界」、超时/取消/idle 关系表）；09 §4/§5 同步；CLI 行为契约（SIGINT→cancel(User)→exit 4；REPL Ctrl-C 取消当前轮；二次 SIGINT→130）进 `--help` 与 README ×2；12 迁移说明（`ApprovalService.require` 签名）
- **MODIFIED**：`ToolExecutorImpl.execute` 异常路径（早抛 → 全 settle 再传播）；`ApprovalService.require` 签名 + 三 Provider（Ask/Deny/Auto）+ `ApprovalPrompt`（可轮询）+ `ReplApprovalInput`；`HeadlessMain`（SIGINT 处理、退出码路径、REPL Ctrl-C）；`Agent.whenIdle` javadoc / `AgentLoopImpl` 静止语义注释；重试同号 step 语义注释（04 §7/§15）
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `core/tools/.../ToolExecutorImpl.java:execute`（:57-79 join 循环） | 全 Future settle 再传播；异常选择（Aborted 输入序优先，余按输入序） | ☑ S-a 已放行（commit add1089） |
| `core/tools/.../ApprovalService.java:require`（:27） | 增 `AbortSignal` 参数（javadoc：等待中取消抛 `AbortedException`） | ☑ S-b 已放行（commit cd76cc5） |
| `core/tools/.../ApprovalAutoPlugin.java` + `ApprovalDeniedException` 调用链 | 实现新签名 | ☑ S-b 已放行（commit cd76cc5） |
| `interaction/approval/.../ApprovalAskPlugin.java`（:46-50）/ `ApprovalDenyPlugin.java` | 实现新签名；Ask 走可轮询 prompt | ☑ S-b 已放行（commit cd76cc5） |
| `interaction/approval/.../ApprovalPrompt.java:stdin`（:25-38） | 阻塞读改可轮询形状（signal 感知，取消即抛） | ☑ S-b 已放行（commit cd76cc5） |
| `examples/headless/.../ReplApprovalInput.java` | 同步新形状（取消/退出语义） | ☑ S-b 已放行（commit cd76cc5） |
| `examples/headless/.../HeadlessMain.java`（run :324-；finishOneShot :415；runRepl/replLoop :507-601；bindSigint :626；SigintPolicy :646-688） | SIGINT 处理三件套；REPL Ctrl-C 取消当前轮；二次 SIGINT halt | ☑ S-c 已放行（2026-09-19，本提交） |
| `examples/headless/src/main/java/module-info.java` | `requires jdk.unsupported`（非 static；jlink 镜像实跑验证 include） | ☑ S-c 已放行（2026-09-19，本提交）；镜像实跑已验（钉①✓ `--list-modules` 含 `jdk.unsupported@25.0.4.1`） |
| `core/agent-loop/.../AgentLoopImpl.java`（runStepLoop 重试 `continue` :392-395；whenIdle 语义） | 语义注释（重试同号 step；静止=含工具线程） | ☑ S-a 已放行（commit add1089） |
| `core/agent/src/main/java/io/javanatic/harness/agent/Agent.java:whenIdle`（:67-68) | javadoc 钉「静止」定义 | ☑ S-a 已放行（commit add1089） |
| 测试：`core/tools/.../ToolExecutorTest.java`、`core/agent-loop/.../AgentLoopTest.java`、`interaction/approval/.../ApprovalModesTest.java`（或新增 fake prompt 用例）、`examples/headless/...`（信号/审批 e2e） | 新用例（见验收 ①–⑥） | S-a/S-b 用例已随停点放行；S-c=新增 `HeadlessSigintTest`（7 例，已放行）；收尾段=④ `requestErrorRetryDoesNotReplayToolCalls` + ② `cancelDuringToolExecutionAbortsTurn` 补断言 + `CompactionTest` 溢出断言（待终局放行） |
| 文档：`docs/design/04-agent-loop.md` §8/§9、`09-concurrency.md` §4/§5、`12-api-stability.md`、`--help`、README ×2 | 同步（含迁移说明） | ☑ S-c 面全部落笔（04 §8 表/§9 句、09 §4 段/§5 join-all、12 §3 迁移（S-b）/§6 SIGINT 契约、`--help`、README ×2） |

## 审查停点（开工前填写：按锚点分组的必停点）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a 收敛语义**：executor join-all + 异常选择 + `whenIdle` 定义（承载类全文 = `ToolExecutorImpl`/`AgentLoopImpl` 相关段） | 锚点 1 + 9 | ☑ 已放行（2026-09-19） |
| **S-b 审批 seam 变更**：`ApprovalService.require` 签名 + 可轮询 stdin + `ReplApprovalInput`（跨模块效应） | 锚点 2–6 | ☑ 已放行（2026-09-19） |
| **S-c CLI 信号入口**：SIGINT 三件套 + `requires jdk.unsupported` 取舍 + jlink 镜像实跑 + REPL 语义；落地时 12 §6 补一行「取消仅对协作面生效，不合作批以 Completed 收口（exit 0）」（2026-09-19 放行附注） | 锚点 7–8 | ☑ 已放行（2026-09-19；证据见下 §S-c 证据） |

## 验收（证据 = 实际执行的命令与结果）

- [x] ① 取消后无遗留执行（join-all）：`ToolExecutorTest.abortWaitsForAllToolsBeforePropagating`（等全部 settle 再传播）+ 异常选择三例（`abortOutranksEarlierPlainFailureInInputOrder` / `firstAbortInInputOrderIsThrown` / `plainFailureSelectionStaysInputOrder`）+ `AgentLoopTest.cancelJoinsParallelToolBatchBeforeIdle`（先断言「写手未停 且 whenIdle 未完成」`:285-286`，放闸后 join 完成时写手已停 `:290`）——S-a 放行（add1089）
- [x] ② idle 纪律：`AgentLoopTest.cancelDuringToolExecutionAbortsTurn:258` 补断言（whenIdle 完成 ⇒ `status()==IDLE`；mutB 红在 :258）；「idle 后无写」由 ① 的 `:290` 覆盖；「turn/end 先于状态迁移」由结构保证（`AgentLoopImpl` runTurn 内 append `:323` 先于 driver finally 的 `settleDriver` `:210-223`）——STATUS 为异步 NOTIFY 派发，无以事件顺序直证的测试，此处如实注明
- [x] ③ 不合作插件限制：`AgentLoopTest.uncooperativeToolDelaysIdleUntilItReturns`（a17：取消后 `idle.isDone()==false`、返回后批 Completed 关轮）+ 04 §8/§9、09 §4 文字（S-a/S-c）
- [x] ④ 模型重试不重放：`AgentLoopTest.requestErrorRetryDoesNotReplayToolCalls`（新）+ `CompactionTest` 溢出路径 `tool/call` 恰 1 断言；同号 `step/start` 语义注释随 S-a（04 §7）——见「收尾段证据」
- [x] ⑤ 审批等待取消：`ApprovalModesTest.cancelDuringApprovalWaitAbortsWithoutErrorResult`（fake prompt 注入信号）+ `stdinPromptAbortsOnCancelWhileWaiting`——S-b 放行（cd76cc5）
- [x] ⑥ CLI：`HeadlessSigintTest` 7 例（含真 `Signal.raise` e2e：exit 4 + `aborted("user")` 落账 + `--resume` 续跑）+ 镜像冒烟 4/130/0/0（S-c 证据 + 收尾段终态树复跑）
- [x] ⑦ 文档同步：04 / 09 / 12（迁移）/ `--help` / README ×2——commit 6649f46
- [x] ⑧ 全量 `mvn -B package` 绿（67 套 / 386 测试）+ jlink 镜像实跑（`--help` / `--verify` / 四场景 SIGINT 冒烟）——见「收尾段证据」

### 核验起点（2026-09-18 调研，引用前复核）

现状骨架（全部已实现）：取消发起 = `Agent.cancel(cause, options)`（`AgentCancelCause` 四变体 User/Parent/Hook/Disposed，first-cause-wins）与 `AgentHandle.dispose()` 链（cancel(Disposed) → 等静止 → 回收 scope）；传播 = `signal.checkAbort()` 轮询 + `onCancel(Runnable)` 监听双通道；收敛 = `runTurn` 捕 `AbortedException` → `turn/end(TurnEndReason.Aborted(describe(cause)))`（cause 词表 `user`/`parent`/`hook: …`/`disposed` 在 `AbortController.describe`）。

已有测试（本迭代的既有证据面）：`AbortControllerTest`（5 例：触发/late 注册/动作异常隔离/never/first-cause-wins）、`AgentLoopTest.cancelDuringToolExecutionAbortsTurn`（单协作阻塞工具；取消后无 tool/result、turn 落 aborted("user")）、`AgentLoopTest.disposeCancelsDeregistersAndEndsTurnAsDisposed`、`ToolExecutorTest.abortPropagatesInsteadOfBecomingErrorResult`、`LocalBashExecutorTest`（取消击杀进程树含孙进程）、`DockerShellTest`（容器内进程组击杀）、`OpenAiCompatAdapterTest`（流中取消）、`AgentRegistryTest`（dispose）。

本迭代定性的四个面：① 并行工具取消的收敛次序（`execute` 早抛放弃兄弟 join，09 §5 文档意图为「submit 全部 + join 全部」——单工具协作场景不暴露，现有测试恰是单工具）；② 不合作工具（拖住收敛或被遗留，二者必居其一）；③ 审批等待取消（`require` 无信号；`AskApproval` 阻塞在 `prompt.ask` stdin 读）；④ 重试不重放（结构上不重放，无测试钉住）。

CLI 层事实：headless 一次性路径无任何信号处理，Ctrl-C 直杀 JVM（会话停在中途、无收敛落账）；REPL 的 `EOF ≡ /exit` 经 dispose 链以 aborted 收敛，交互式 Ctrl-C 同上直杀。`oneShotExitCode` 的 Aborted→4 映射在案（it17），端到端不可达。

### S-c 证据（2026-09-19，已放行——commit 6649f46）

- **实现面**：`HeadlessMain` 增 `SigintPolicy`（epoch = `whenIdle` future 身份——同轮首按 → 提示 + `cancelTurn`；未收敛再按 → `halt(130)`；静止期 REPL → 退出请求、一次性 → 忽略；收敛后新轮首按不误触 halt）；REPL 行循环改可轮询（companion 读线程 + `BlockingQueue` + `poll(50ms)` + `exitRequested`——阻塞 stdin 不可中断）；`module-info` `requires jdk.unsupported`（非 static）；it14 挂账「Ctrl-C 优雅取消（中断处理另片）」由本步收口。
- **聚焦**：`HeadlessSigintTest` **7 例全绿**（`-pl examples/headless -am -Dtest=HeadlessSigintTest`，`/tmp/jh-sc-t3-restore.log`，内回显 `EXIT=0`）。7 例 = 策略层 3（首按取消/再按 130；一次性静止忽略 vs REPL 静止退出；收敛后新轮首按=取消）+ 真 SIGINT e2e 4（一次性：exit 4 + stdout 0 字节 + stderr「任务被取消: user」+ 落账 `aborted("user")`；REPL 取消当前轮循环不退；REPL 静止退出无取消提示；aborted 后 `--resume` 续跑落「答复」）。真信号用例带 POSIX+可装 handler 前置（`Assumptions`）。
- **突变检查**（变体在源码带 `MUT-x` 标注、日志名带变体、日志内回显退出码）：**mutA**（剥离首按动作：注释 `notice.accept`+`cancelTurn.run()`）→ **5 红**（:97/:130 断言、:185→awaitLogContains 20s、两个 e2e 20s ERROR）；**mutB**（注释 `epoch == cancelledEpoch → halt(130)` 分支）→ **1 红**（:101）；**mutC**（注释行循环 `exitRequested` 轮询）→ **1 红**（:203 20s ERROR）。逐一还原。日志 `/tmp/jh-sc-t3-mut{A,B,C}.log`（EXIT=1）；还原复绿见上。`grep -rn "MUT-"` 无残留。
- **饱和压测**：8× `yes` 负载 6 轮 + 16× 2 轮全绿（逐轮日志内回显 `EXIT=0`，`/tmp/jh-sc-sat1-8.log`）。
- **全量**：`mvn -B package` BUILD SUCCESS 38.6s（`/tmp/jh-sc-package.log`，EXIT=0）；surefire XML 汇总 **67 套 / 385 测试 / 0 败 / 0 错 / 11 跳过**（docker 门控自跳）；headless 模块 56 例。
- **jlink 镜像实跑**：`bin/java --list-modules` 含 `jdk.unsupported@25.0.4.1`（钉①生效）；`bin/jh --help` exit 0（Ctrl-C 契约段在案）/ `--verify` exit 0；**四场景 SIGINT 冒烟**（直起镜像 JVM；非交互 bash 后台默认 SIG_IGN 吞 INT——脚本 `set -m` 作业控制修正，机制已实证）：
  - 一次性 → **exit 4**，stdout 0 字节，stderr「已请求取消」+「任务被取消: user」，journal 终态 `turn/end {"cause":"user","kind":"aborted"}`（seq 5，无后续）
  - 双 INT → **exit 130**，journal 停于 `llm/request` seq 4 无 `turn/end`（halt 跳过收敛 hook——逃生门设计如此）
  - REPL 在飞 → **exit 0**，stdout 含「已请求取消」，journal `aborted("user")` → `command/run exit` → `command/done`
  - REPL 静止 → **exit 0**，stdout 仅横幅无取消提示，会话目录仅 `header.json`（无轮事件）
  日志 `/tmp/jh-smoke-{oneshot,double,repl-inflight,repl-idle}-{out,err}.log`；**退出码持久化** = `/tmp/jh-smoke-{同名}-result.log`（放行附注后复跑一轮，4/130/0/0 复现实核）。
- **收尾待办（放行附注点名）→ 已落地**：④「重试不重放」测试面当时缺（`AgentLoopTest` 无工具 × 重试用例）；收尾段独立补测（见「收尾段证据」），**不并入 S-c 提交**（6649f46）——停点↔提交对应保持。
- **前瞻（it19 输入，放行附注）**：双 INT 冒烟的 journal 残形（`llm/request` 无 `turn/end`）= 崩溃恢复的设计输入；本冒烟脚手架（`/tmp/jh-smoke.sh` + hold server）可复用为恢复测试 fixture 生成器。

### 收尾段证据（2026-09-19，已放行——随本次收口提交）

- **④ 重试不重放（新测试）**：
  - `AgentLoopTest.requestErrorRetryDoesNotReplayToolCalls`（agent a18）：工具批执行后**下一请求**失败 → REQUEST_ERROR 裁决 1 次重试 → 断言执行面 `toolRuns==1`、事件面 `tool/call` 恰 1、`step/start` 序列 `[0,1,1]`（重试 `continue` 不 `step++` → 同号重复 = 裁定语义，04 §7）、终局 `Completed`。
  - `CompactionTest.overflowKindTriggersForcedCompactionAndRetry` 补 `tool/call` 恰 1 断言（溢出恢复同径：只重发请求、不重放工具）。
  - 聚焦绿：`-pl core/agent-loop -am -Dtest='AgentLoopTest,CompactionTest' -Dsurefire.failIfNoSpecifiedTests=false`（19+6 例，EXIT=0，`/tmp/jh-it18-04-t1.log`）。
  - 突变 **mutA**（重试分支插 `step++`，即把「continue 不 `step++`」改坏）→ **1 红**在 `AgentLoopTest.java:470`（新用例的 step 序列断言 `containsExactly(0,1,1)`），`/tmp/jh-it18-04-mutA.log`（EXIT=1，19+6 例中 1 败）；还原复绿 `/tmp/jh-it18-04-restore2.log`（EXIT=0；surefire XML 19+6 例 0 败）。mutA/还原均在**终态树**上复跑（行号与现文件一致）。
- **② idle 纪律（补断言 + 突变）**：`cancelDuringToolExecutionAbortsTurn` 尾部补 `agent.status() == IDLE`（`:258`）。突变 **mutB**（`AgentLoopImpl.settleDriver` 不落 IDLE）→ **2 红**（`:258` 新断言 + `:195` 既有 happy path），`/tmp/jh-it18-02-mutB.log`（EXIT=1，"expected: IDLE but was: RUNNING"）；还原复绿 `/tmp/jh-it18-02-restore.log`（EXIT=0）。两轮 `grep -rn "MUT-"` 无残留。
- **⑧ 全量 + 镜像实跑（终态树）**：`mvn -B package` BUILD SUCCESS 39.9s（`/tmp/jh-it18-close-full.log`，EXIT=0）；surefire XML 汇总 **67 套 / 386 测试 / 0 败 / 0 错 / 11 跳过**（385 + ④ 新例 1）。镜像重建时间 13:35 实跑：`bin/jh --help` exit 0（`/tmp/jh-it18-close-help.log`）/ `--verify` exit 0（「信息: verify 通过」，`/tmp/jh-it18-close-verify.log`）；**四场景 SIGINT 冒烟复跑**（终态树）：**4 / 130 / 0 / 0**，持久化 `/tmp/jh-smoke-{oneshot,double,repl-inflight,repl-idle}-result.log`；journal 复核——一次性 `turn/end aborted("user")` 收尾（seq 5 无后续）、双 INT 残形止于 `llm/request` seq 4 无 `turn/end`（逃生门）、REPL 在飞 `aborted("user")` → `command/run exit` → `command/done`、REPL 静止仅 `header.json`（无轮事件）。

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| S-a（未提交） | 新用例 `cancelJoinsParallelToolBatchBeforeIdle` 初版（`whenIdle().thenRun` 回调采证）在 8× CPU 饱和压测中出现 1 次瞬态红（1/~13 轮；证据 `/private/tmp/jh-sa-loop-3.log`：`:288` AssertionFailedError，0.113s；机制未构造出，此后未复现——倾测试形态时序敏感，非产品缺陷） | 改写为确定性逆否形态：取消后先断言「工具未停 且 `whenIdle` 未完成」，再放闸 `join`（无时序回调面）。饱和压测合计 140 轮 0 失败（逐轮日志 `/private/tmp/jh-sat{2,3,4,5}-N.log`，30/40/40/30 轮；最终形态 = `jh-sat5-*` 30 轮，单跑/成对交替，含 8×/16× 负载；红绿判定：日志含 `^\[ERROR\]` 或 `AssertionFailedError` 即红） |
| S-a（措辞订正，不改代码） | 描述「reason 在 runStepLoop 前初始化」不准 | 实况：`reason` 是 `runTurn` 局部变量（`AgentLoopImpl`:294 声明）；`runStepLoop` **正常返回后** :311 赋 `Completed`；工具批后的 abort 早退（:415-418）是 `runStepLoop` 内 `return`，落回同一正常路径——非「默认初始化」 |
| S-b（观察，pre-existing 锐边） | 同批多工具 + HUMAN_GATE 时各工具并发等待审批：两个 ask 的读线程互相窃取 stdin 行（REPL 代理下 `forward` 只喂到一个等待者；裸 stdin 下 `readLine` 瓜分字节流）——误答可能投给错误请求 | 挂账（S-c 不处理）：executor 级审批串行化（一次只放一个 ask 进读），或人读摘要标注 callId 使误裁决可辨；触发面 = 同批多工具 + 交互审批 |
| S-b（证据措辞，下轮顺手） | mutation/sat 日志的命名与自含性：变体需在文件名标注、轮日志需自回显退出码（本轮靠外层 echo 承载） | 自 S-c 起：mutation 日志名含变体（`*-mutA/B/C`），sat 轮日志内回显 `EXIT=` |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 04 §8/§9 取消收敛语义补全（原计划验收⑦统一落地） | §8 两条（「执行收敛」join-all /「不合作边界」）随 S-a 实现落地 | join-all 即契约本体——与实现同屏交停点审查，避免设计/代码两处临时不一致 | 迭代内已同步；⑦ 余量 = 04 其余 / 09 / 12 / `--help` / README×2 |
