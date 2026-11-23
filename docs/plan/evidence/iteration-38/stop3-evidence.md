# it38 停 3 证据档 —— 用户真机验收 + 小观察修复 + 收口门禁（2026-11-23）

## 测量面（分开记，不混加）

| 面 | 命令 | 结果 | 原始件 |
|---|---|---|---|
| 停 3 用户真机 | 用户本机 Chrome + `jh --serve --web-write` + 真 key | 通过（DOM 12/12、审批双路径、exit-5 落盘） | [stop3-acceptance.txt](stop3-acceptance.txt)、[stop3-user-runbook.md](stop3-user-runbook.md) |
| 全量 package | `mvn -B package` | EXIT=0；fresh surefire XML 202 份 = **1669/0/0/25**（窗口 ≥ 16:39:50，2026-11-23） | [stop3-full-package.log](stop3-full-package.log) |
| validate | `mvn -B -q validate` | EXIT=0 | [stop3-validate.log](stop3-validate.log) |
| keyless verify | `jh --verify` | EXIT=0；`web-write: off（缺省）` | [stop3-verify.log](stop3-verify.log) |
| R4 纯预检 | `jh --verify --serve --web-write` | EXIT=0；`web-write: on（--web-write）`；端口 8787 无监听（`NO_LISTENER`） | [stop3-verify-serve.log](stop3-verify-serve.log) |
| B29/B36 真 serve | `jh --serve --web-write --port=8791` + curl | 监听仅 `127.0.0.1:8791`；同源 GET/POST 200；坏 Host GET `/`、`/events` 403；跨站 Origin POST 403；SIGINT 后 `PORT_FREE` | [stop3-b36-live.log](stop3-b36-live.log) |
| UI 状态机 | Node 假 DOM 执行真实 ui.html 脚本 | `HARNESS_PASS` + `ABORT_PASS` | [stop3-ui-harness.txt](stop3-ui-harness.txt) |
| rpc 模块 | fresh XML 同窗口 | 12 份 = **179/0/0/0**（`WorkbenchUiTest` 12/0/0/0） | 上表 package 同窗 |
| headless 模块 | fresh XML 同窗口 | 35 份 = **190/0/0/1** | 上表 package 同窗 |

计数链：**1669 = 1665（停 2 终态）+ 4**（停 3 新增：shell 结构钉 1、状态机钉 1、banner 覆盖集成 1、纯函数 1）。300s 真钟哨兵本窗口未重复跑（慢测单点，不重跑，沿用停 1 实测 300.013325667s）。

## 小观察：根因与修复（用户点名核对项）

用户观察：提问后徽标显示「已收尾」，runbook 预告「等待答复」。核对结论：**不是措辞，是状态机显示面缺陷**。

事实链：`ask_user` 是数据驱动停轮——工具结果带 `concludesTurn=true`，而 `TurnEndReason` 只有 `Completed` / `Aborted` / `Error`，提问后的 `turn/end` 恒为 `completed`。原实现 `turn/end` 无条件 `setChatState("done")`，覆盖了 `concludesTurn` 设置的 `waiting-answer`。

修复（`ui.html` 脚本层，零 RPC/契约变化）：新增 `chat.questionPending` 事实位——`tool/result.concludesTurn === true` 置位；`turn/start` 与 `user/message` 清零；`turn/end` 按 `questionPending ? "waiting-answer" : "done"`，`aborted`/`error` 先清零并追加理由。静态钉 `WorkbenchUiTest.uiKeepsWaitingAnswerAfterQuestionDriveStopTurnEnd`；行为 harness 红→绿：修复前 `after turn/end: 已收尾 / state=done / HARNESS_FAIL`，修复后见上表（`等待答复 / waiting-answer`，abort 路径仍 `done`）。

## UI 重设计落点

三栏壳（`app-shell` + `sidebar` / `main` / `rightbar`），浅色 token 为主 + `prefers-color-scheme` 自动深暗（暗色底 `#151517`）；≤1180px 右栏转固定审批浮卡，≤820px 侧栏转顶条。脚本 555 行原样保留，仅 `selectSession` 增 `switchTab("sessions")`；零 `innerHTML`、IME 双守卫、`body:not(.web-write)` 三选择器与 `.chat-only` 原样。终值 **830 行**（软预算修订 900 + T4 重访条款，见 [iteration-38](../iteration-38.md)）；`sha256(ui.html) = ee07712c5b392614570b2bd48841f3dbfb742ae172c3efe0dd6a35a98d719890`。

## §4 banner 补丁

`--web-write` 启动时若组合行 `approval-ask.idleTimeoutSeconds` 与中继实际等待界不同，打一行：`approval timeout: <N>s（组合行 idleTimeoutSeconds=<M> 被中继覆盖）`；中继 0 陈述为「不设限」。仅 CLI stdout，不触 UI 行数。测试：`HeadlessServeTest.serveBannerNamesIgnoredCompositionIdleTimeout` + `HeadlessWebWriteOptionsTest.approvalTimeoutOverrideReportsOnlyRealMismatch`。
