# 迭代 37 — W5 交互工作台（状态：预登记（2026-11-17 用户裁定）；四确认未开稿——it36 CI 回填关账后开稿）

基线：it36 已关账（W4 收官 = 0.5「个人工作台」四站全通），工作版本 `0.5.0-SNAPSHOT`。**版本号（0.5.x vs 0.6）与同 0.5.0 发布的先后未裁**——四确认时顺带使用体感定（用户明示不阻塞开工）。路线表新起行（版本号待裁），不混入已关账的 0.5 行：W5 显式终结零写面不变量，属新能力相位。

**出处**：用户裁定消息 2026-11-17（it36 收口终审通过 + push 授权之后续消息）。本文件即预登记包——防会话压缩丢上下文，四确认草案的输入现在就位。**预登记 ≠ 开工授权**（[plan/README](README.md) 生命周期：路线落盘不等于单轮开工）。

## 一句话范围（用户原文）

在 W4 工作台上加聊天面——发消息开轮、看流式回复、审批弹窗在浏览器里点、exit 5 等答复的问题在对话框里答。终端 REPL 的全部能力搬进浏览器，治理不降一格。

## 五个预登记锚（用户原文；四确认草案须逐项对齐）

1. **写面方法组**（JSON-RPC 增量）：`session/start` / `session/send`（含 resume 等答复会话）/ `approval/respond` / `session/cancel`——**全部复用既有执行面**（headless run 机制与 agent followup，R2 不开新通道）；B29 回环恒绑不变。
2. **审批中继（最难的一件）**：ask 档弹窗浮进 UI，**fail-closed 语义原样保留**（300s 超时即拒、`--approval-timeout` 口径不变）——中继层只搬运提示与决定，绝不放宽等待界。响应需防伪（至少 request 级配对令牌，四确认定形状）。
3. **流式推送**：`assistant/chunk` 到浏览器——SSE 独立路径 vs 增强轮询（活跃会话短间隔）是 **T 项**；注意 W4 T1 裁过「仅精确 `/` GET」——SSE 若走 `/events` 需显式修约，别静默破契。
4. **轮生命周期 UI 状态机**：running / waiting-approval / **waiting-answer（exit 5 语义）** / done——与 headless 退出码契约一一对应；等答复态就是浏览器里的问题+答复框（`session/send` 即 resume）。
5. **「零写面不变量」的终结 ADR**：W1–W4 读侧零写路径是刻意承诺，W5 显式终结它——ADR 记推翻对象与理由（写面全部过既有管线 + 审批中继不降格 = 终结的条件）。it35 T6 挂账（会话控制写）由本站收口。

## 流程锚（用户原文）

- **dsh 侦察先行**（it27/it28 惯例）：dsh web client 的聊天/审批/流式三面先勘察成三张清单；
- it36 CI 回填关账后 W5 四确认开稿；JLine TUI 的渲染/按键先例与 W4 的 ui.html 是双参照；
- **jobs 面挂账**（W4 T2）可评估并入本站（同是「工作台完整度」，四确认时一并裁）。

## 顺序问题（用户留四确认，不阻塞开工）

W5 与 **0.5.0 发布**的先后：先发 0.5.0（观察+配置工作台）再发 0.5.x（交互），还是 W5 完了一起发——两个都成立，W5 四确认时顺带使用体感定。

## 既有裁决接口（四确认引用，不重裁本体）

| 接口 | 现状 | W5 关系 |
|---|---|---|
| it35 T6（会话控制写 v1 只读） | 重访条件 = W4 UI 点名单项控制动作需求 | 预登记锚 1 点名写面方法组——本站收口该挂账 |
| W4 T1（仅精确 `/` GET/HEAD） | it36 停 1 契约钉死 | SSE 若走 `/events` 须显式修约（锚 3 已点名，别静默破契） |
| B29（仅 127.0.0.1、单用户、无 TLS） | 硬线，UI 面同成立 | 写面同硬线，回环恒绑不变 |
| R2（副作用唯一受控路径） | ToolExecutor pipeline + 架构测试 | 写面方法组全部复用既有执行面，不开新通道 |
| 审批等待界（it22） | 非交互 300s fail-closed + `--approval-timeout` | 中继只搬运不放宽（锚 2） |
| 零写面不变量（W1–W4） | 刻意承诺，it36 收口仍在 | W5 显式终结——ADR 记推翻对象与理由（建议编号 0012，四确认时定；notes 现有 0010/0011） |
| jobs 面（W4 T2 延后） | 重访条件 = 用户点名 jobs 视图 | 本预登记列入四确认议程（并站评估） |

## 侦察交付登记（已落盘，2026-11-17）

dsh 三张清单落 `evidence/iteration-37/`；dsh 对照基线 `5badb15009`（[dsh-reference.md](../dsh-reference.md) 口径，tag dsh-v0.2.1-alpha.1；dsh 工作树 HEAD 已动到 fcc0ad4621，取证一律 `git show/grep 5badb15009`，不用工作树文件）。

| 清单 | 文件 | 对四确认最有分量的发现 |
|---|---|---|
| 聊天推进（写面） | [recon-chat.md](evidence/iteration-37/recon-chat.md) | ISession 动词表（prompt queue/steer 双模——JH 无 steer，首版处置列 T 项）；beginSubmission 回声 + SessionRequestId 一次性结算 = 防伪配对先例；**api-request-trust Host 围栏**——回环恒绑 ≠ 浏览器跨站免疫，W5 写面须 Host/Origin 校验（建议列 T 项）；dsh 问题面有 claim 制倒计时 vs JH exit 5 无限等（差异登记） |
| 审批中继 | [recon-approval.md](evidence/iteration-37/recon-approval.md) | ApprovalRequestId = service 发 UUID + asked/decided 审计对 + turn-enclosed + 一次性结算（锚 2 令牌形状现成参照）；unavailable fail-closed 归一 + abort 迟到丢弃；**dsh 审批无超时**（grep 零命中实证）——JH 300s 是 it22 自有契约，中继不放宽；UI = composer takeover 非模态（Enter/Escape + IME 守卫），形状列 T 项 |
| 流式推送 | [recon-streaming.md](evidence/iteration-37/recon-streaming.md) | dsh = WS mux（双向，故需上行字节预算），**无 SSE**；JH 写面 = 一次性 POST → 单向下行 SSE 足够（零新依赖）；SSE 若走 `/events` 须显式修约 W4 T1；LoggedEvent seq 天然 cursor ↔ dsh startedAfterSeq 重连基线；轮询备选腿零新面（W2 tail 先例） |

三清单均含：机制要点 + 基线路径引证 + 实际执行命令 + JH 映射/差异注记 + 未读缺口。侦察完成 ≠ 四确认开稿（开稿仍以 it36 CI 回填关账为前置）。

## 审查停点 / 验收清单

四确认开稿时填写（预登记阶段不设；写面 + 审批中继承载契约，停点密度预期不低于 it35）。