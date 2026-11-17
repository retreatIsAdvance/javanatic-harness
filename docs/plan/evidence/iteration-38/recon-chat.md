# W5 侦察清单一：聊天推进（写面）

日期：2026-11-17。用途：it38（W5 交互工作台，原编号 it37，2026-11-17 顺序裁定后重编号）四确认草案输入。**预登记侦察 ≠ 开工授权。**

## 口径

- dsh 本地仓：`/Users/abel_yang/study/deepseek/deepseek-harness`；工作树 HEAD 已动（fcc0ad4621，0.2.2-rc.1），**本清单一律打基线 `5badb15009`**（tag dsh-v0.2.1-alpha.1，[dsh-reference.md](../../../dsh-reference.md) 口径）。
- 读法：`git -C <dsh> show 5badb15009:<path>` / `git -C <dsh> grep <pattern> 5badb15009 -- <paths>`（下文路径均相对 dsh 仓根）。

## dsh 机制要点

### 1. Session 写面动词（ISession）

出处：`packages/api/session-controller/src/client/contract/session.ts`（头 120 行实读）。

| 动词 | 语义 |
|---|---|
| `prompt(content, mode: 'queue'\|'steer', signal?, requestId?)` | queue = 追加轮；steer = 打断在途轮。业务结果 = `{ok}` 或 `{ok:false, error:{code,message}}`（错误即数据） |
| `beginSubmission({...onRetire})` | 本地提交回声：同步进 session snapshot，`SessionRequestId` 配对，retire 结算 observed/failed |
| `cancel()` | 取消在途轮；排队工作 FIFO 续跑 |
| `updateQueue` / `rename` / `readAttachment` | 队列编辑 / 会话改名 / 附件读 |
| projections | 读面（快照投影） |

会话创建：`contract/sessions.ts` `create(opts?)`（L86 grep 实证）+ 引用计数共享 `Session.open()`。

### 2. 发送路径实证（composer → host）

出处：`packages/client/ui-conversation/src/client/service.ts` L209–300 实读。

- 纯文本 `send(text)`：`session.prompt([{type:'text',text}], 'queue')`；业务失败**双落**——session snapshot 的 `promptError`（对象层状态）+ 此处 throw（调用方编排用：composer 据此恢复草稿）。
- 带附件 `sendSession(...)`：`beginSubmission` 回声先进 snapshot → `nextPaint()`（让浏览器先画出回声）→ 序列化附件 → `session.prompt(content, mode, signal, submission.requestId)` → `onRetire` 结算；**retire reason ≠ 'observed' 即失败**；失败时附件全部保留注册以便 composer 恢复；序列化前失败走 `submission.abandon()`。
- subagent 会话分支：无回声，直接 prompt。

### 3. 防伪配对先例（对应用户锚 2「request 级配对令牌」）

`SessionRequestId`：beginSubmission 本地发号 → prompt 携带 → host  admission 后以 observed/failed 结算回声。**发号方 = 提交侧、结算方 = host、一次性 retire**——与审批面的 ApprovalRequestId（见清单二）同构。

### 4. 等答复面（user-questions，exit 5 对照物）

出处：`packages/interaction/user-questions/src/index.ts` 头 30 行 + `src/timed-wait.ts` 头 60 行实读；UI 包 `packages/client/ui-user-questions/src/client/`（QuestionComposer / QuestionReplyView / PlanReviewPanel / draft-store，ls-tree 实证）。

- 服务定义：UI-backed service，暂停 agent 工具调用直到人答复；模型面工具在 `dsh-tool-ask-user`；UI 包在 Agent-scoped waterfall 上组合 answerer。
- **TimedQuestionWait = claim 制倒计时**：deadline 计时器**只在无 answer UI 持有时走**；`attach(signal)` 每个答复 UI 持一个 claim（yield `{remainingMs}` 帧），UI 流关闭即释放 claim、计时恢复；父 Turn abort 联动取消。问题持久态在 projection，前台等待生命周期单独管。

### 5. 浏览器信任围栏（写面安全前置）

出处：`packages/client/connection/src/api-request-trust.ts` 头 40 行实读。

- 防两条 confused-deputy 路径：**DNS rebinding + 跨站请求**。
- **Host 头每请求必绑**——rebinding 伪造不了 Host；loopback / LAN IP 字面量 / trustedHosts 放行。
- 明示**不是 auth 层**（单用户本机信任模型内的围栏）。

## 对 JH W5 的映射与差异注记

| dsh | JH W5 | 注记 |
|---|---|---|
| `prompt` queue | `session/start` / `session/send` | 复用 headless run 机制与 agent followup（it22 一问一答：exit 5 答复 = 下一轮 user message）；R2 不开新通道 |
| `prompt` steer | **JH 无对应** | 打断在途轮 = 新语义；首版建议不做（在途轮时 send 的处置——拒绝/排队——列四确认 T 项） |
| `cancel()` | `session/cancel` | 复用 it18 协作取消（aborted 落账、exit 4 语义）；排队 FIFO 续跑 JH 无队列面，不适用 |
| beginSubmission 回声 + SessionRequestId | 乐观回声 + 配对令牌 | UI 先显用户消息、事件投影确认后结算；令牌形状参照（发号/携带/一次性结算） |
| TimedQuestionWait（UI 持有暂停倒计时） | **waiting-answer 无超时** | JH exit 5 = 进程退出等人，语义上无限等；浏览器对话框即答复面（`session/send` 即 resume）。**差异须登记**：dsh 问题有 deadline、JH 没有——W5 不引入问题超时（与 exit 5 契约一致） |
| api-request-trust Host 围栏 | **B29 之外的补充设计点** | 回环恒绑 ≠ 浏览器跨站免疫（恶意网页可向 127.0.0.1 发 POST / DNS rebinding）；W5 写面须 Host/Origin 校验——dsh 先例在案，建议列四确认 T 项 |
| 附件/上传面 | 不做 | 预登记锚未点名；范围外 |

## 实际执行命令（取证口径）

```sh
D=/Users/abel_yang/study/deepseek/deepseek-harness
git -C $D show 5badb15009:packages/api/session-controller/src/client/contract/session.ts   # 头 120 行
git -C $D grep -n "create" 5badb15009 -- packages/api/session-controller/src/client/contract/sessions.ts  # L86
git -C $D grep -n "\.prompt(\|beginSubmission" 5badb15009 -- packages/client/ui-chat/src packages/client/ui-conversation/src
git -C $D show 5badb15009:packages/client/ui-conversation/src/client/service.ts            # L195–310
git -C $D show 5badb15009:packages/interaction/user-questions/src/index.ts                 # 头 30 行
git -C $D show 5badb15009:packages/interaction/user-questions/src/timed-wait.ts            # 头 60 行
git -C $D ls-tree --name-only 5badb15009 packages/client/ui-user-questions/src/client/
git -C $D show 5badb15009:packages/client/connection/src/api-request-trust.ts              # 头 40 行
```

## 未读 / 缺口

- ui-chat 渲染细节（transcript-view / conversation-nodes）未读——JH 双参照 = JLine TUI（it34）+ ui.html（it36），不依赖 dsh 渲染形状。
- dsh jobs 面（ui-jobs）未读——四确认若裁并站再补侦察。
- `updateQueue` 队列编辑细节未读（JH 无队列面，不适用）。