# 迭代 19 — 崩溃恢复与写入安全（状态：进行中——四确认与五裁决点已于 2026-09-19 裁定；S-a 编辑与取证（聚焦 + 突变 + 还原复绿）完成，停点待放行）

模块：`session/persistence-jsonl`（屏障对账 + 单写者锁）+ `core/agent-loop`（派发前屏障调用点 + resume 恢复收口）+ `core/tools`（工具批派发前屏障）+ `core/session`（恢复尾形分析 + `SessionStore` 修复 + `FailureKind.DISK`）+ `examples/headless`（`--resume` 占用/恢复出口 + SIGKILL e2e）+ 文档（03 / 04 / 09 / 12 / `--help`）

四确认日期：2026-09-19（草案 v1 起草与裁定同日；五裁决点均按推荐并附细化）——总体路线 it19 行见 [design/README](../design/README.md#020--reliable-single-agent-product)

## 四确认

- **内容**：
  1. **派发前耐久屏障（批级）**：`llm/request` 派发与工具副作用执行前，经既有 FLUSH seam（`SessionStore.flush` → `JsonlPersistence.flushBarrier`）落**一次 fsync + 对账**（`writtenLines == seq` 不符即抛）——把「append 观察者异常按契约 contained 吞掉写失败」的洞显形在屏障处。屏障失败 ⇒ 该派发**不进入**、turn 以 Error 收口。**隐含重构披露**：`tool/call` 落账前移至 `execute()` 批前导（全部落账 → flush → 再 fork 工具线程；**重复 callId 检测随之搬家**），S-a 锚点列明；LLM 侧序 = `llm/request` 落账 → flush → `llm.stream`。
  2. **单会话写者保护**：会话目录级**独立** `.writer.lock`（`FileChannel.tryLock`）；第二写者（第二进程 / 同 JVM 第二 Runtime）fail loud；`--resume` 命中占用同样被拒（`load` 先试锁，再做撕裂尾修复）。**顺带修现存 bug（折入本条）**：`SessionStore.create`（:41-55）对已存在 id 静默 `put` 覆盖、且旧 owner 的 `onClose` 会移除**新**会话——改 `putIfAbsent` + fail loud。
  3. **「结果未知」识别与恢复收口（必需，非可选）**：resume 装载后识别未完成尾形（assistant 工具调用无配对 `tool/result`；开着的 turn）；**不自动重放**；以追加「恢复事实」收口——error `tool/result`（文案「结果未知，可自行核验」）+ `turn/end(Aborted("interrupted"))`。**契约理由**：悬空 `tool_use` 会让 resume 后首个真实 API 请求违反 OpenAI 配对契约（400）——不收口则恢复不可用。**事件序锚点**：恢复事实在 **end-seed 之后**追加（`Session.firstLiveSeq` 语义内），其 `sourceEventSeqs` 引用 seed 前 seq（= 悬空调用/消息的 seq）。
  4. **测试/证据**：SIGKILL 子进程 e2e（复用 it18 冒烟脚手架 + hold 夹具；**kill 由观察盘上事实触发**——日志出现目标行才杀，非 sleep；POSIX 门控 + 直起 JVM + `set -m`）；写入失败确定性注入（屏障故障注入需**伪 writer/FLUSH 钩子**——常规 rig 无 barrier 观测面，钩子记录屏障调用序 + 可注入失败）；双进程竞争（双 Runtime + 真第二进程）；撕裂尾全回归。

- **目标**：路线四条核心验收各有确定性测试证据（keyless）——SIGKILL / 写入失败 / 双进程竞争均有测试；屏障失败不进入副作用；恢复不自动重放结果未知的工具；撕裂尾修复无回归。验收问题（每轮必答）：**「进程被杀后盘上的事实与恢复后的行为对得上吗——恢复不假装成功、不悄悄重做」**。

- **为什么**：0.2.0 主题「可靠单 Agent」——it17 让「结果可判读」、it18 让「停止可判读」，本迭代让「崩溃可判读」。现状只覆盖**优雅退出**：耐久屏障唯一挂点在 dispose（`AgentLoopPlugin` :117），在飞请求与工具的「事实先于副作用」无任何保证；it18 双 INT 冒烟的 journal 残形（止于 `llm/request` 无 `turn/end`）就是真实崩溃样本（it18 放行已注明为本迭代设计输入）。三个缺口各有出处：① 屏障——R1（可重建性）与审计的诚实前提是「请求锚/工具审计行在派发前耐久」，否则崩溃场景下重建无据；② 写者锁——持久化 javadoc 明写的挂账（it6 / it12.6 一路挂账「多进程锁」），当前 seq 损坏在多进程下静默；③ 结果未知——resume 只做 load→seed→resume，尾形无人识别，模型消息投影会出现无配对 `tool/result` 的 `tool_use`，用户也不知道副作用是否发生。it20（资源边界）与 it22（会话操作）建立在恢复语义之上。

- **不做**：
  - 不做 exactly-once / 业务副作用回滚（路线 0.2.0 明示非目标）
  - 不做多写者并发（单写者保护 = 拒绝第二写者，不是合并）；不做跨机/网络盘锁语义保证（本机常规文件锁为限）
  - 不做 per-event fsync（it12.6 已裁：force 只在派发点 + dispose 两处）；不做断电级语义宣称（fsync 诚实叙事维持）
  - 不自动续跑/自动补完未完成 turn（恢复不重放；续不续 = 用户/新任务决定）
  - 不改事件信封与 JSONL 布局（除恢复收口所需的最小追加事件）；不改 `--resume` 语法
  - 不做 it22 的会话列举/状态面板；压缩/维护的崩溃安全挂账
  - 不引入第三方依赖

### 裁决记录（2026-09-19）

1. **屏障位置 = 批级**（同意）。隐含重构披露：`tool/call` 落账前移至 `execute()` 批前导（全部落账 → flush → 再 fork），**重复 callId 检测随之搬家**，S-a 锚点列明；LLM 侧 `llm/request` → flush → `stream`；对账入 `flushBarrier`（`writtenLines == seq` 不符即抛）。
2. **`FailureKind.DISK`**（同意）。通道 = **专用类型异常在 `failureKind` 非-llm 分支识别**，不进 `LlmCallException.Kind`；波及面已核：`StreamRenderer.failureText`（:136-144 switch）、`CoreCodecs` wire（:242/:247 未知→UNKNOWN 回退）、`TurnEndReason.Error` record。
3. **独立锁 / `load` 查 / exit 3**（同意）。补：`SessionStore.create` 已存在 id 静默覆盖 + 旧 owner `onClose` 移除新会话（现存 bug）——`putIfAbsent` + fail loud 折入内容 ②（S-b 范围）；12 §6 exit 3 词表扩「**写者锁冲突**」。
4. **恢复即收口**（同意，且**升级为必需**）：悬空 `tool_use` 使 resume 后首个真实 API 请求违反 OpenAI 配对契约（400）。锚点：恢复事实**在 end-seed 后追加**、`sourceEventSeqs` 引 seed 前 seq；文案「**结果未知，可自行核验**」。
5. **JUnit 子进程 SIGKILL**（同意）：kill 由**观察盘上事实**触发（非 sleep）；POSIX 门控；直起 JVM + `set -m`。

两处锚点补充采纳：① 屏障故障注入需伪 writer 钩子（常规 rig 无 barrier 观测面）；② 恢复收口事件序锚点（end-seed 后 + seq 引用）写进设计增量。停点 S-a/S-b/S-c 与停点↔提交对应：认可。

### S-a 实施中披露（2026-09-19，超出裁决字面的改动逐项列明）

- **隐含重构 #3（裁决 1/2 生效的前提）**：`Events.notifyAndWait` 原用 `invokeLoggingFailures`（失败记日志、不传播，09-concurrency.md:174 明写「异常不传播只记日志」），屏障的抛无法穿透 `SessionStore.flush` → 改为 `invokePropagating`（`CompletionException(key + " listener failed", e)`；**全部 listener 照跑完、不 fail-fast**）。同步面：`Events` 类 javadoc、`SessionEvents.FLUSH`、`SessionStore`/`flush` javadoc、01/09 文档。
- **核验起点订正**：本文件「核验起点」原写 `SessionStore.flush`「listener 异常不被 contained，会传播」——**为误**（实为 `invokeLoggingFailures` 吞掉）；订正为：原语义吞日志，S-a 起改为传播。
- **隐含重构 #4（R4 组合责任）**：`ToolExecutorImpl` 构造新增 `SessionStore` 依赖 → `ToolsPlugin.apply` 显式 `scope.require(SessionStore.KEY)`（审批在前保持 fail-loud 归因序）→ 组合必须装载 `SessionStorePlugin`。测试 rig 波及 8 文件 ~17 位点（ApprovalModesTest×4、ShellToolEndToEndTest×3、PresetServiceTest×1、FsToolEndToEndTest×2、SandboxPolicyTest×2、PlanModePluginTest×3、TodoPluginTest×5、ToolExecutorTest×2），全部补前缀；`ToolExecutorTest.pluginAssemblyRequiresApprovalFirst` 更名并补「缺会话存储 → fail loud」断言。
- **小增补（超出裁决字面，需知悉）**：`SessionWriter.writeEnvelope` 加跳号护栏（`entry.seq() > writtenLines` 即拒）——否则「写失败被吞 → 后续写把 `writtenLines` 追平假象」会让屏障给出假的「已耐久」结论、盘上留 seq 洞。护栏把洞留在屏障对账处显形（测试 `gapWriteAfterSwallowedFailureIsRejectedSoBarrierStaysRed`）。
- **对账口径落地**：`flushBarrier(expectedSeq)` 先对账后 fsync（`writtenLines != expectedSeq` 即抛）；`SessionStore.flush` 将 listener 失败包成 `DurabilityException`（`session` 模块新增类）；`AgentLoopPlugin.dispose` 保持 flush + done 完成 + scope 收拢的嵌套形（屏障失败也必须收拢 scope）。
- **测试面**：屏障故障注入走 FLUSH 钩子（`onGlobal(SessionEvents.FLUSH, …)` 计数 + 条件抛）——`AgentLoopTest` 两则（LLM 侧 / 工具批侧：不派发或未执行 + `Error(DISK)`）、`JsonlPersistenceTest` 两则（只读日志 → 对账抛 `DurabilityException`；跳号护栏）、`EventsTest` 一则（失败传播且全部 listener 照跑）、`StreamRendererTest` DISK 行。

### S-a 取证（2026-09-19；日志在 /tmp）

**聚焦测试**（全仓 `mvn -B test` = `/tmp/it19-sa-test-full3.log`，EXIT=0、BUILD SUCCESS；仅 keyless 门控的 `DeepSeekE2ETest` / `RealModelAgentE2ETest` SKIP，为既有常态）：

| 断言面 | 测试 | 结果 |
|---|---|---|
| 屏障不确认 → 请求不派发 + `Error(DISK)` | `AgentLoopTest.dispatchBarrierFailureBlocksLlmCallWithDiskKind` | 绿 |
| 屏障不确认 → 工具未执行（`tool/call` 已落账、无 `tool/result`） | `AgentLoopTest.toolBatchBarrierFailureStopsExecutionAfterCallsLanded` | 绿 |
| listener 失败传播且全部 listener 照跑 | `EventsTest.notifyAndWaitFailsFutureAfterAllListenersRan` | 绿 |
| 只读日志 → 写失败被 contained 吞 → 对账抛 `DurabilityException` | `JsonlPersistenceTest.flushBarrierExposesSwallowedWriteFailure` | 绿 |
| 跳号护栏：洞不被后续写假性追平 | `JsonlPersistenceTest.gapWriteAfterSwallowedFailureIsRejectedSoBarrierStaysRed` | 绿 |
| 无写者会话屏障跳过（不假证） | `JsonlPersistenceTest.flushBarrierIgnoresSessionsNeverPersistedHere` | 绿 |
| DISK 渲染行（写盘失败 / --resume 提示） | `StreamRendererTest.failureTextIsKindDriven` | 绿 |

**突变检查**（破坏必红 → 还原复绿；「还原」行 = 五处全还原后的复绿取证）：

| MUT | 破坏点 | 红例（实际） | 日志 |
|---|---|---|---|
| A | 删 LLM 侧派发前 flush（`AgentLoopImpl` :375） | 2 红（两则屏障测试） | /tmp/it19-sa-mutA.log |
| B | 删工具批 flush（`ToolExecutorImpl` :62） | 恰 1 红（`toolBatchBarrierFailureStopsExecutionAfterCallsLanded`） | /tmp/it19-sa-mutB.log |
| C | 删 `flushBarrier` 对账（保留 fsync） | 2 红（只读日志对账 + 跳号保持红） | /tmp/it19-sa-mutC.log |
| D | `notifyAndWait` 回退 `invokeLoggingFailures`（吞语义） | 源码级：EventsTest 1 红；装突变 jar 无 `-am` 直跑 agent-loop：4 红（含两则屏障测试，另 2 则为混料环境波及、不作强归因） | /tmp/it19-sa-mutD.log、/tmp/it19-sa-mutD-agentloop.log |
| E | 删 `writeEnvelope` 跳号护栏 | 恰 1 红（`gapWriteAfterSwallowedFailureIsRejectedSoBarrierStaysRed`） | /tmp/it19-sa-mutE.log |
| 还原 | 五处全部还原（grep 复核：`AgentLoopImpl` :375 / `ToolExecutorImpl` :62 / `flushBarrier` 对账 :240 / 跳号护栏 :255 / `invokePropagating`） | 全仓 `mvn -B test` = BUILD SUCCESS、EXIT=0 | /tmp/it19-sa-test-full3.log |

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：03 持久化语义（派发前屏障口径与失败语义、写者锁占用/释放/平台注记、恢复收口口径与「不重放」承诺、**恢复事实事件序：end-seed 后追加 + `sourceEventSeqs` 引 seed 前 seq**）；04 派发前屏障调用点与失败收敛；09 并发（单写者保护 + `SessionStore.create` fail loud）；12 §6 exit 3 词表扩「写者锁冲突」；`--help` / README ×2 同步；`FailureKind.DISK` 分类说明；恢复文案「结果未知，可自行核验」＝模型可见文本（钉进测试）；测试 rig 伪 writer/FLUSH 钩子（记录屏障调用序 + 可注入失败）
- **MODIFIED**：`JsonlPersistence.flushBarrier`（fsync → fsync + 对账）+ `writeEnvelope` 跳号护栏 + `SessionWriter` javadoc；`JsonlPersistence` 写者锁（`attach`/`SessionWriter`/`load`）；`AgentLoopImpl`（runStepLoop 请求派发前 flush；构造注入 `SessionStore`，R4 注入构造器；`failureKind` 非-llm 分支识别 DISK 通道异常）；`ToolExecutorImpl`（**批前导重构**：`tool/call` 全部落账 + 重复检测前移 → flush → fork；构造注入 `SessionStore`）；`ToolsPlugin`（显式 `require(SessionStore.KEY)`，审批在前）；`SessionStore.flush`（listener 失败 → `DurabilityException` 包装）+ 新增 `DurabilityException`；`Events.notifyAndWait`（失败传播语义，`invokePropagating`）；`AgentLoopPlugin`（mount 传 store；dispose flush 收拢形；resume 挂载处执行恢复收口）；`core/session` 恢复尾形分析纯函数（配对口径复用 `SessionInvariants`）；`SessionStore.create`（`putIfAbsent` + fail loud，修静默覆盖）；`FailureKind` + `CoreCodecs` + `StreamRenderer.failureText`（DISK 波及面）；`HeadlessMain`（`--resume` 占用/恢复出口）
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `session/persistence-jsonl/.../JsonlPersistence.java:flushBarrier`（:219-227） | fsync + 对账（已写行数追平 seq，不足即抛） | ✅ S-a |
| `.../JsonlPersistence.java:attach`（:59-70）/ `SessionWriter`（:183-245） | 写者锁获取（backfill/创建）与释放（DISPOSED/scope close）；同 JVM 重叠锁（`OverlappingFileLockException`）归 fail loud | S-b |
| `.../JsonlPersistence.java:load`（:83-122） | 占用检查（先试锁，占用即抛，再做撕裂尾修复） | S-b |
| `core/agent-loop/.../AgentLoopImpl.java:runStepLoop`（:344-428；请求派发 :364-372） | 请求派发前 flush；失败 → 不派发、turn Error | ✅ S-a |
| `core/agent-loop/.../AgentLoopImpl.java:构造与字段`（:84 区、:110 区） | 注入 `SessionStore`；派发屏障的调用封装 | ✅ S-a |
| `core/tools/.../ToolExecutorImpl.java:execute/executeOne`（:51-146） | **批前导重构**：`tool/call` 全部落账（重复 callId 检测搬家）→ flush → fork；屏障失败传播（按裁决 1/2 收口） | ✅ S-a |
| `core/agent-loop/.../AgentLoopImpl.java:failureKind`（:436-448） | 非-llm 分支识别 DISK 通道专用异常（不进 `LlmCallException.Kind`） | ✅ S-a |
| `core/session/.../SessionStore.java:create`（:41-55） | `putIfAbsent` + fail loud（修已存在 id 静默覆盖 / 旧 owner `onClose` 移除新会话） | S-b |
| `core/agent-loop/.../AgentLoopPlugin.java:Factory.mount/resume`（:83-103） | 传 store；resume 装载后执行恢复收口（追加恢复事实） | mount/dispose ✅ S-a；resume 收口 S-b |
| `core/session/...`（新：恢复尾形分析纯函数） | 未配对工具调用 / 开 turn 识别（配对口径复用 `SessionInvariants`）；恢复事实在 end-seed 后追加、`sourceEventSeqs` 引 seed 前 seq | S-b |
| `core/session/.../event/FailureKind.java` + `CoreCodecs`（:242/:247）+ `StreamRenderer.failureText`（:136-144） | +`DISK` 词表、wire 往返与渲染（未知→UNKNOWN 回退不回归） | ✅ S-a |
| `examples/headless/.../HeadlessMain.java:run`（:355-374） | `--resume` 占用/恢复出口（文案 + 退出码） | S-c |
| 测试：`JsonlPersistenceTest`（锁/对账/回归）、`AgentLoopTest`（屏障失败零副作用；**伪 writer/FLUSH 钩子进测试 rig**）、`HeadlessResumeTest`（恢复收口）、`examples/headless`（SIGKILL e2e + 占用 e2e） | 新用例（见验收 ①–⑤） | 屏障/对账 ✅ S-a；锁/恢复/SIGKILL S-b/S-c |
| 文档：03 / 04 / 09 / 12 §6 / `--help` / README ×2 | 同步 | S-a 触及面（01/09 屏障语义 + 03 seam 摘录）✅；其余收尾 |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a 派发前屏障**：`AgentLoopImpl` 派发段 + `ToolExecutorImpl` 批派发段（承载类全文）+ `flushBarrier` 对账口径 + **DISK 词表落地**（enum + codec + 渲染 + `failureKind` 通道）+ **隐含重构 #3/#4 披露** | 锚点 1、4、5、6、7、11 | 编辑与取证（聚焦 + 突变 + 复绿）完成——packet 已出，待放行 |
| **S-b 单写者锁 + 恢复收口**（跨模块 + 新词表：锁异常/占用行为、恢复追加事实的文案与 reason、分析纯函数） | 锚点 2、3、8、9、10 | 待开工 |
| **S-c CLI 出口与 SIGKILL e2e**：`--resume` 占用拒绝（文案 + 退出码）+ 子进程 SIGKILL 测试形态与夹具 + 12 §6 / `--help` | 锚点 12、13（+ 12 §6 / `--help` 文档面） | 待开工 |

（停点↔提交一一对应；证据约定延续：mutation 日志名带变体、日志内回显退出码、冒烟退出码持久化 `-result.log`。）

## 验收（证据 = 实际执行的命令与结果）

- [ ] ① SIGKILL：子进程 e2e（工具执行窗口或请求在飞中 `kill -9` → `--resume`；**kill 由观察盘上事实触发**，非 sleep）——恢复后工具执行计数不增（**不自动重放**）、日志含恢复收口（error `tool/result` 结果未知 + `turn/end(Aborted("interrupted"))`）、新任务可在同一会话继续；撕裂尾与恢复组合路径实跑
- [x] ② 写入失败：确定性注入（**伪 writer 钩子**注入屏障失败；另只读日志 → 追加被 contained 吞由对账捕获）→ 屏障 fail loud → **无副作用**（请求未派发 / 工具未执行，假 LLM / 工具计数器为 0）+ turn 收口 `Error(DISK)` —— S-a 完成，见上方「S-a 取证」（聚焦测试 7 则 + MUT A–E）
- [ ] ③ 双进程竞争：同 JVM 第二 Runtime 与真第二进程两例——第二写者 fail loud、首写者不受扰；`jh --resume=<占用中>` 文案 + **exit 3**（12 §6 词表「写者锁冲突」）；`SessionStore.create` 重复 id fail loud 回归（旧 owner `onClose` 不再移除新会话）
- [ ] ④ 恢复不自动重放 + 配对契约：工具执行次数不变；恢复后下一轮请求的消息投影合法（tool_use 有配对 result，文案「结果未知，可自行核验」）——不收口即 OpenAI 配对 400（必需性依据）；恢复事实事件序 = end-seed 后追加 + `sourceEventSeqs` 引 seed 前 seq
- [ ] ⑤ 撕裂尾回归：`JsonlPersistenceTest` 既有五则全绿 + 锁/屏障共存新例
- [ ] ⑥ 全量 `mvn -B package` 绿 + jlink 镜像实跑（SIGKILL 冒烟场景并入 `/tmp/jh-smoke.sh` 家族，退出码持久化 `-result.log`）；突变检查（屏障调用点 / 锁获取 / 恢复收口各设 MUT）
- [ ] ⑦ 文档同步：03 / 04 / 09 / 12 §6 / `--help` / README ×2

### 核验起点（2026-09-19 调研，引用前复核）

**屏障现状**：`flushBarrier` = `JsonlPersistence.java:219-227`（`FileChannel.force(true)`；无文件即返），经 `SessionEvents.FLUSH` 接在 `attach` :67-68；**唯一调用点 = dispose 链**（`AgentLoopPlugin.java:117`，agent 静止后 `store.flush(agentScope, session)`）；`SessionStore.flush` :72-75（`notifyAndWait(...).join()`——listener 异常不被 contained，会传播）。**派发前无任何屏障**：`runStepLoop` 在 :364-367 落 `llm/request` 后 :370 直接 `llm.stream(...)`；工具面 `ToolExecutorImpl.executeOne` :107 落 `tool/call` 后 :124 审批、:131 执行。**写失败传播洞**：append 侧观察者异常按契约 contained（03 §6 实现落定 :469「记日志不炸 append」）——`SessionWriter.writeEnvelope` :241 的 `writeString` 失败被吞、`writtenLines` :243 不推进，目前无第二道对账（这正是裁决 1/2 对账口径针对的洞）。

**单写者**：无锁。javadoc :34-35「单进程追加(多进程锁挂账 persistence 后续)」；挂账出处 `docs/plan/iteration-6.md:25`、`iteration-12.6.md:31`。`Session.append` 并发契约（`Session.java:87-95`）是**进程内**多写者（工具线程），非跨进程。**`SessionStore.create` 现存 bug**：:46 `store.put(id, session)` 对已存在 id 静默覆盖，且旧 owner 的 `onClose`（:47-52）`remove(id)` 会移除**新**会话——本迭代折入单写者面修复（`putIfAbsent` + fail loud）。

**恢复链**：`HeadlessMain.run` :355-374（`--resume` → `persistence.load` :363-364 → `SessionStore.create(seed)` :365-366 → `agents.resume` :367-369）；`AgentLoopPlugin.resume` :83-87 只做 `store.get` fail loud；无尾形检查、无占用检查。turn 号派生 = `AgentLoopImpl` :110-111（TurnStart 计数）。`Session` 侧：seed 装载后 `firstLiveSeq = log.size()`（:63），末尾由构造器补 `SessionEndSeedEvent`（:68-71，已以其结尾则不重标）——恢复事实须在其后追加。

**结果未知**：无任何检测。既有的「修复」只有撕裂尾 `repairTornTail` :137-167（load/续写打开时，字节级）。`SessionInvariants`（core/session）*有*「turn/step 单调且嵌套、tool/call 与 tool/result 同 step 配对」纯函数，但**只被测试调用**（`SessionInvariantsTest` / `SessionTest`），不在 load/resume 路径——本迭代可复用其配对口径，不必新造。

**事件与词表**：`ToolCallEvent(turn,step,callId,name,args)` / `ToolResultEvent(toolUseId→ToolResultBlock(isError))` / `TurnEnd(Completed | Aborted(cause) | Error(message,kind))`；`FailureKind` 无「磁盘/写入」变体（AUTH / RATE_LIMIT / SERVER / NETWORK / TIMEOUT / PROTOCOL / OVERFLOW / UNKNOWN）。

**CLI 出口**：12 §6 退出码 0/1/2/3/4（+130 halt）；「无 turn/end 同归 3」；`--resume=` 属冻结 flag（增加占用拒绝 = 行为补强，不改语法）。

**既有测试面**：`JsonlPersistenceTest`（`tornTailPartialLineTruncatedOnLoad` / `completeTailMissingNewlineHealedOnLoad` / `interiorCorruptionStaysFailLoud` / `repairThenResumeContinuesFromLastCompleteLine` / `flushBarrierSafeWithoutLogAndAfterEvents`）、`HeadlessResumeTest`、`SessionConcurrencyTest`、it18 `HeadlessSigintTest`（真信号用例风格 + POSIX 门控 `Assumptions` 先例）。

**it18 输入（放行附注）**：双 INT 冒烟的 journal 残形（止于 `llm/request` seq 4 无 `turn/end`）= 崩溃恢复设计输入；`/tmp/jh-smoke.sh` + hold server 可复用为恢复测试 fixture 生成器。

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
