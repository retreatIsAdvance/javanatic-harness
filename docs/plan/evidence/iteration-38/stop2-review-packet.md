# Stop 2 — 宿主集成交付评审包（终包）

不变量增量：H1/H2 已于 2026-11-23 双裁，实现已按「CLI 七红转绿 → 宿主 / banner / exit 2 / SSE / 聊天 UI」收口。不新增模块、不加 base 行；R2 工具审批路径、R4 装配期依赖检查、停 1 契约保持原状；`workbench/rpc` 未新增 `interaction-approval` 依赖边。
裁决点：H1 只开 `ApprovalPrompt` 类型注入口（适配器落 `examples/headless`）；H2 回收政策取 (a) 按轮挂载——每轮从持久化事实重组组合、轮毕即 close，后台工作（delegation 子代 / jobs）的 runtime 留到其终局。
风险自报：本包是**停 2 交付评审包，不是停 2 验收通过结论**。五项评审与停 3 浏览器检视仍归用户；本包未 commit、未 push、未进入停 3，未重跑停 1 的 300s 真钟哨兵。
证据入口：[CLI 首红](stop2-cli-r4-red-result.txt) → [二次红](stop2-cli-r4-red2-result.txt) → [终绿](stop2-cli-r4-final-result.txt)、[聚焦终轮](stop2-final-focus2-result.txt)、[全量 package](stop2-full-package2-result.txt)、[validate](stop2-final2-validate-result.txt)、[exit 2 真机](stop2-exit2-final-result.txt)、[banner 两行](stop2-banner-final-result.txt)、[UI 行数 / IME](stop2-ui-lines-result.txt)、[H1 边界](stop2-h1-boundary-result.txt)、[突变总账](stop2-mut-summary2-result.txt)、[终包自审](stop2-final-audit-result.txt)。
若只能改一处：让真实 ToolExecutor 捕获的 HUMAN_GATE 确实调用浏览器租约，不能只在 agent scope 覆盖一个永远不被消费的 ApprovalService。

## 0. 状态与门

基线 `27b2d88`（停 1 已按授权提交）。工作树含停 2 全部实现、测试、同步面与全部证据文件；**未提交、未 push、未进入停 3**。按仓库纪律，新增承载契约（H1 注入口）先裁决后实现；本包只请求停 2 评审，不把本轮绿测当作停 3 用户检视的替代。终包自审：`stop2-final-audit.py` 对源码锚点、H1 否定断言、包内链接、两份源文件 SHA、证据存在性与冻结规则共 20 项全部 PASS（[结果](stop2-final-audit-result.txt)），审计只读、不含构建。

常设约束执行情况：未读取凭据 / 密钥 / 会话 transcript；未 push / amend / 跳过 hook / 改 git config；变异只改生产代码并逐字节恢复；未使用 `sleep 30`；未重跑停 1 的 300s 真钟哨兵（`WebApprovalRelayContractTest` 保留 1 skipped，属既有豁免）；计数一律取同窗 surefire XML 求和，不把 “Tests run” 文本当通过。

## 1. 源码事实（F1–F3，含 H1 落点更新）

### F1 — 解析不等于消费；H1 以窄重载补上注入口

- [ToolsPlugin.java](../../../../core/tools/src/main/java/io/javanatic/harness/tools/ToolsPlugin.java):26 在 `apply` 时 `scope.require(ApprovalService.KEY)`；[ToolExecutorImpl.java](../../../../core/tools/src/main/java/io/javanatic/harness/tools/ToolExecutorImpl.java):36 把它存进 `private final ApprovalService approval`，:150 直接调用字段——执行期不重新解析，agent scope 覆盖无效。
- [AgentLoopPlugin.java](../../../../core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopPlugin.java):93 把该 executor 交给 mount，create/resume 都不重装 executor；[ApprovalAskPlugin.java](../../../../interaction/approval/src/main/java/io/javanatic/harness/interaction/approval/ApprovalAskPlugin.java):30 虽有 `ApprovalAskPlugin(ApprovalPrompt)`，但 H1 前 `AppBoot` 只装 ServiceLoader 发现的实例。
- **H1 落地**：[AppBoot.java](../../../../bundle/base/src/main/java/io/javanatic/harness/boot/AppBoot.java):204 新增 `bootReported(BootOptions, ApprovalPrompt)`，:220 `assemble` 统一装配，:272 `injectApprovalPrompt` 只替换已启用的 `approval-ask` 实例：ask 未启用 → fail loud（不静默改 auto/deny 档），实例类型不符 → fail loud 点名实际类型；装配失败路径统一关闭已创建 Runtime 并保留首因（:248–261）。
- 调用边界：仅 [WebChatControl.java](../../../../examples/headless/src/main/java/io/javanatic/harness/examples/headless/WebChatControl.java):373 在需要浏览器通道时传入 `hub.prompt()`；其余 headless 调用点仍是一参重载。H1 专项测试 [AppBootPromptInjectionTest.java](../../../../bundle/base/src/test/java/io/javanatic/harness/boot/AppBootPromptInjectionTest.java) 7/0/0/0。

### F2 — PRE_STEP 与工具不共享 signal identity

[AbortController.java](../../../../core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AbortController.java):30 的 `signal()` 每次返回新视图；[AgentLoopImpl.java](../../../../core/agent-loop/src/main/java/io/javanatic/harness/agentloop/AgentLoopImpl.java):287/396 各自取视图，:411 把同一局部 signal 放进 REQUEST waterfall，:466 再把它交给 executor。**结论**：PRE_STEP 与工具共享取消状态但不共享对象 identity；REQUEST 与工具共享 identity，租约只能挂 REQUEST。错挂 PRE_STEP 的变体见 §5 M1（10/12 必红）。

### F3 — delegation 单父、子代独立轮

[LocalDelegationService.java](../../../../delegation/local/src/main/java/io/javanatic/harness/delegation/local/LocalDelegationService.java):75 首绑即父、后续不重绑；:200 子代由独立虚拟线程 `followup → whenIdle`，父轮静止不推出子轮静止；[DelegationChildSetup.java](../../../../delegation/local/src/main/java/io/javanatic/harness/delegation/local/DelegationChildSetup.java):16 审批档恒等继承；[LocalDelegationPlugin.java](../../../../delegation/local/src/main/java/io/javanatic/harness/delegation/local/LocalDelegationPlugin.java):47 依赖异步 CREATED 首绑。**结论**：子代审批必须用自己的 session / turn 建租约、进父 owner feed；父轮完成不得顺手关子代租约。

## 2. 已裁 H1/H2（2026-11-23 用户裁决，原文保留）

### H1 — 窄审批通道注入（已裁）

- AppBoot 新增仅接受 `ApprovalPrompt` 的启动期重载；一个接口、两个实现（CLI 终端提示 / serve 中继），组合时选实现，原重载保留原用途。
- 只替换已发现、经组合求值启用的 `ApprovalAskPlugin` 实例；行序、配置校验、manifest、discovered / unreferenced 计数、policy 检查全部保持；ask 未启用 / 类型不符 → fail loud；不新增 ask 行、不替换 auto / deny。
- headless 仅在最终组合选中 ask 且需要 Web 通道时调用重载；auto / deny 仍走原装配。`idleTimeoutSeconds` 由宿主读取并交给既有 `ApprovalPrompt.effectiveIdleTimeout`；Web 人闸视为非终端交互，缺省 300s，显式 0 的既有含义不变。
- **边界钉死（用户裁决原文）**：适配器落在 **examples/headless**，`workbench/rpc` **不得**新增 `interaction-approval` 的 requires 边——`harness-workbench-rpc` 是 0.5.0 已发布坐标，0.6.0 里静默加依赖边 = 对下游消费者的兼容性变更（12 §7 应记之事）；适配器放 headless（非稳定面、自由改）完全规避。**若实现时发现 rpc 侧确需该边，回来重裁，不静默突破。**
- 成本：`bundle/base` 的 interaction-approval 依赖由 test 升为 compile，并在 JPMS 加对应 requires；这是新的内部编译依赖与导出签名，没有新第三方依赖、新发布坐标或 base 行；最终镜像模块数由 package 实跑覆盖。

### H2 — 按轮挂载 + 显式后台例外（已裁，政策取 (a)）

1. **按轮重组**：每次 `session/start|send` 从持久化事实（profile + overlays + policy + 会话 header/log）重组组合，开轮、轮毕即 close；R3 干净排水、零累积。
2. **后台工作例外**：若该轮启动了后台工作（delegation 子代 / jobs），runtime 不在轮末关闭，保留为该会话的活动 runtime；**关闭条件 = 其后台工作终局 + 无后续轮**；保留期间同一会话 `send` 复用该 runtime，轮末重新评估。
3. **租约形状**：开轮前装 REQUEST 监听；以实际 carrier 的 session / turn 建每轮 `RelayApprovalPrompt`，把 REQUEST 的真实 signal identity 关联到租约；同轮多 step 复用，重复 REQUEST 不重置期限；未知 signal 明确 fail-closed，不退回 stdin 或 AUTO。
4. **delegation 父绑定**：首次 followup 之前同步 `LocalDelegationService.bindSession`；不改 CREATED 通知模式。子代审批用子代自己的 session / turn 租约，UI / 快照带归属；不得默认拒绝子代。
5. **取消与所有权**：`session/cancel` 只取消指定会话在途轮——先标记该代、关闭该代租约，再调既有 cancel；**无父取消级联**；一 session 一所有者；宿主关闭才停止接新请求、撤销整树租约、收口资源；第二次 SIGINT 逃生门不变。
6. **换代**：旧回调只删自己的一代；子轮有自己的清理路径，不依赖父 whenIdle；APPENDED TurnEnd 只作辅助终态信号，绝不在持有 session 锁的回调里 join 或执行阻塞 teardown。

## 3. 三处映射与停 2 必验项（13/13）

右列均为已落地的真实测试或已归档突变，不再是“拟新增”。

| # | 必验项 | 证据（测试 / 突变） |
|---|---|---|
| 1 | 真 Agent 审批 | `approvalAllowCompletesRoundAndRetiresRuntime`、`deniedApprovalSurfacesHumanGateDenialToTheModel`（真 HTTP 脚本 + 真工具 `fs_read`） |
| 2 | PRE_STEP 错绑变体必红 | M1：订阅改挂 `AgentEvents.PRE_STEP` → `WebChatControlTest [12, 10, 0, 0]` 全为审批不达 / 轮不完 |
| 3 | 重试 / 多 step 不换租约 | `multiStepSameTurnKeepsSingleLease`、`WebApprovalHubTest.rebindingSameSignalKeepsOneLeaseForTheTurn`、`RelayApprovalPromptTest` 16/0/0/0 |
| 4 | 两并发根会话不串 requestId / 审计 | `concurrentRootSessionsKeepFeedsAndAuditSeparate`；`WebApprovalHubTest.feedsAreIsolatedPerOwningSession` |
| 5 | 普通轮轮毕 Runtime 已关闭 | 同 1（`openRuntimeCount()==0`）；`sendAfterRetiredRoundColdBootsAgain` 第二轮 `runtimeBoots==2` |
| 6 | 后台例外留到终局 + 无后续 | `retainedRoundReusesRuntimeForNextSendThenRetiresAtJobTerminal`、`delegationChildApprovalStaysInParentFeedAndTreeRetiresAtTerminal` |
| 7 | 保留期 send 复用 | `retainedRoundReusesRuntimeForNextSendThenRetiresAtJobTerminal`（复用同一 runtime 至 job 终局） |
| 8 | 子代在父 whenIdle 后仍能审批 | `delegationChildApprovalStaysInParentFeedAndTreeRetiresAtTerminal` |
| 9 | 父 cancel 不偷撤子租约 | `cancelParentDoesNotRevokeChildLease` |
| 10 | 子代单独 cancel | `cancelChildSessionAloneStopsOnlyChildTurn`；M6 撤 `service.cancel` → 20s 必红 |
| 11 | 旧清理不删新代 | `staleGenerationCleanupDoesNotRevokeNewRoundLease`（代守卫先于关共用 `st.signal`）；M8 去掉代守卫 → 必红；`WebApprovalHubTest.closeSignalCancelsOnlyThatGenerationAndKeepsSiblingLeases` |
| 12 | 同 session 不双重 resume | `sendWhileTurnInFlightIsRejected`（-32006）+ `awaitRetirement` 有界等待 |
| 13 | 停服全部撤销 | `closeRevokesAllLeasesAndRetiresEveryRuntime`、`closedHostRefusesLateBootAndReleasesWriterLock`；M5 关停竞态守卫改 false → 必红 |

其余面：SSE 修约（[WebEventSource.java](../../../../examples/headless/src/main/java/io/javanatic/harness/examples/headless/WebEventSource.java) 打开即排空定 head、空批不是 EOF、破损 500 具名终结、控制帧不占 seq）+ `WebEventSourceTest` 7/0/0/0 + `RpcHttpServerSseTest` 22/0/0/0；CLI R4 七红已转绿（`HeadlessWebWriteOptionsTest` 7 + `HeadlessOptionsTest` 29 = 36/0/0/0）；UI 零 `innerHTML`、IME 双守卫、写面哨兵与行数见 §5。

## 4. 偏离与范围边界

1. **原四确认技术前提不完整**：H1 前 AppBoot 没有 prompt 注入口；该承载接口经 H1 裁决后补上，未静默实现。
2. **候选方案被证伪**：PRE_STEP identity 路由不成立，改为 REQUEST；没有为迁就错误候选改 `AbortController` 全局契约。
3. **多会话宿主隐含假设暴露**：delegation 单父 + 子代独立运行迫使 H2 明确 runtime 归属；未借机把 delegation 多租户化或加父取消级联。
4. **中继等待界刻意取窄**：注入 prompt 后组合行 `idleTimeoutSeconds` 不再被读；serve 中继按非交互缺省 300s，不继承交互终端“不设限”（T3 只搬运、不放宽）。这是单源计算下的刻意选择，不是遗漏。
5. **停 2 完成面**：CLI 生产改动、banner off/on、真实进程 exit 2、宿主（start/send/cancel/respond/close）、SSE follower、聊天 / 审批 UI 均已实现并取证；停 3 的真浏览器检视与生产 serve 验收仍由用户亲手执行。
6. **历史证据保留**：修复前的 [全量 package（1664）](stop2-full-package-result.txt) 与 [聚焦（119）](stop2-final-focus-result.txt) 保留为过程记录；终包以 `full-package2` / `final-focus2`（1665 / 120）为准。

## 5. 执行证据（本终包，同窗 XML 求和）

### 5.1 终轮计数

| 面 | 结果 | 入口 |
|---|---|---|
| CLI R4（7 红转绿） | 36/0/0/0，EXIT=0 | [结果](stop2-cli-r4-final-result.txt) / [日志](stop2-cli-r4-final.log) |
| 停 2 聚焦 11 类 | 120/0/0/1，EXIT=0（skip 为 `WebApprovalRelayContractTest` 既有 300s 真钟哨兵，按纪律不重跑） | [结果](stop2-final-focus2-result.txt) |
| 全量 package | 1665/0/0/25，EXIT=0（25 skipped 为既有平台门控 / 真钟哨兵，本批未新增） | [结果](stop2-full-package2-result.txt) / [日志](stop2-full-package2.log) |
| validate | EXIT=0（spotless + checkstyle） | [结果](stop2-final2-validate-result.txt) |

聚焦逐类：`WebChatControlTest 15`、`WebApprovalHubTest 10`、`WebEventSourceTest 7`、`RelayApprovalPromptTest 16`、`WebApprovalRelayContractTest 2（1 skip）`、`WebSseCodecContractTest 1`、`WebReplayTest 1`、`HeadlessWebWriteOptionsTest 7`、`HeadlessOptionsTest 29`、`RpcHttpServerSseTest 22`、`WorkbenchUiTest 10`。

### 5.2 真机 CLI / banner / UI

- `dist/jh/target/jlink-image/bin/jh --web-write`（无 `--serve`）→ **EXIT=2**，stdout 空，stderr 具名「`--web-write` 需与 `--serve` 同用（写面是 server 的开关）」；镜像 SHA256 记于 [结果](stop2-exit2-final-result.txt)。
- `jh --verify` → `web-write: off（缺省）`；`jh --verify --serve --web-write` → `web-write: on（--web-write）`；两次均 EXIT=0、零监听（R4）。见 [结果](stop2-banner-final-result.txt)。
- [ui.html](../../../../workbench/rpc/src/main/resources/io/javanatic/harness/workbench/rpc/ui.html)：HEAD 441 行 → 工作树 762 行（`wc -l`，无尾换行；增量 +321，diff 328/7）；`innerHTML` 0 次；IME 双守卫逐字位于 :736 / :743；`__JH_WEB_WRITE__` 哨兵 :210。见 [结果](stop2-ui-lines-result.txt)；`WorkbenchUiTest` 10/0/0/0。

### 5.3 H1 边界实核

`workbench/rpc/src/main/java/module-info.java` 与 `pom.xml` 对 `interaction.approval|interaction-approval` **零命中**；rpc 内 `ApprovalPrompt` 仅 3 处 Javadoc（`ApprovalRelay.java:24`、`ChatControl.java:11`、`ApprovalRelayTest.java:23`），无 import / 类型使用；适配器与组合在 `examples/headless` 5 个主源文件及 :373 调用点。见 [结果](stop2-h1-boundary-result.txt)。

### 5.4 定向突变（只改生产代码、逐字节还原、红→绿、零残留）

| 编号 | 改坏点 | 必红证据 | 结论 |
|---|---|---|---|
| M1 | REQUEST 改挂 PRE_STEP | [日志](stop2-mut-request-pre-step-red.log) `[12, 10, 0, 0]` | signal identity 路由成立 |
| M2 | `retire()` 不复位 `retiring` | [日志](stop2-mut-retiring-reset-red.log) | 冷启动路径依赖退役标记 |
| M3 | 写面哨兵恒 `false` | [日志](stop2-mut-write-sentinel-red.log) `[32, 2, 0, 0]` | UI / SSE 根页双面捕获 |
| M4 | 删两处 IME 守卫 | [日志](stop2-mut-ime-guard-red.log) | 逐字守卫被钉住 |
| M5 | 停服竞态 `shutdown=false` | [日志](stop2-mut2-shutdown-guard-red.log) | close 后不得再落位（阻断项修复敏感） |
| M6 | `cancelDelegation` 返回 false | [日志](stop2-mut2-child-cancel-red.log) | 子代单独 cancel 敏感 |
| M7 | `snapshot` 为任意查询建 feed | [日志](stop2-mut2-feed-growth-red.log) | 宿主状态有界 |
| M8 | `finishRound` 去代守卫 | [日志](stop2-mut3-gen-guard-red.log) `[15, 1, 0, 0]` | 旧清理不碰新代租约 |

还原复绿与两文件终态 SHA256 见 [突变总账](stop2-mut-summary2-result.txt)（`WebChatControl` `74c81c31…`、`WebApprovalHub` `34823995…`）；无测试常量改动、无突变残留。

### 5.5 独立审查处置（1 阻断 + 5 建议）

- **阻断（已修）**：`close()` 快照早于 `bootFresh` 落位时，会话随后仍可开轮 → Runtime / Agent / 写者锁泄漏。修复：`bootFresh` 锁内复查 `closing`，已停服则 dispose handle + close runtime 并抛 -32603；`drive` 加同窗守卫；M5 证明用例敏感。
- **建议 1（已修）**：`finishRound` / `retire` 的换代清理——`awaitRetirement` + 入口/出口 gen 守卫；本轮进一步把代守卫移到关共用 `st.signal` 之前，并新增第 11 项用例（M8）。
- **建议 2（已修）**：`wire` 失败只关 runtime 不关 handle → 移入 try，失败连 handle 一并收口。
- **建议 3（已处置）**：REQUEST 形状漂移原本静默失败关闭 → 加 WARNING 留痕，仍 fail-closed，不装配错租约。
- **建议 4（已修）**：任意 session `snapshot` 会建 feed、宿主状态无界增长 → 只为真实租约持有者建 feed，新增 `feedCount()` 观测面与用例（M7）。
- **建议 5（刻意保留）**：注入 prompt 后组合行 `idleTimeoutSeconds` 不再被读 → 单源取窄（serve 中继按非交互 300s，不放宽），代码注释与 §4.4 明示。

## 6. 评审请求

请评审停 2 五项：H1 注入口与边界、H2 回收 / 例外与 13 项必验、CLI / banner / exit 2、SSE 修约重钉、聊天与审批 UI（含 IME 与 `ui.html` 行数）。本包不含 commit / push / 停 3；获具名放行后再收口提交。
