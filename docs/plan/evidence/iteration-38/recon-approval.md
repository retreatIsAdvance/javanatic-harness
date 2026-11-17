# W5 侦察清单二：审批中继

日期：2026-11-17。用途：it38（W5 交互工作台，原编号 it37，2026-11-17 顺序裁定后重编号）四确认草案输入——预登记锚 2「最难的一件」。**预登记侦察 ≠ 开工授权。**

## 口径

dsh 本地仓 `/Users/abel_yang/study/deepseek/deepseek-harness`，一律打基线 `5badb15009`（tag dsh-v0.2.1-alpha.1）；读法 `git show/grep 5badb15009`（工作树 HEAD 已动，不用工作树文件）。

## dsh 机制要点

### 1. 请求身份与审计对（防伪先例）

出处：`packages/interaction/user-approval/src/types.ts` 全文 + `src/index.ts` L1–260 实读。

- **ApprovalRequestId**：service 每次 request **新发 UUID**——request 级配对令牌的现成形状。
- **`approval/asked` ↔ `approval/decided` log-only 审计对**：按 ApprovalRequestId 配对落会话日志。
- **turn-enclosed 前置**：request 必须发生在 open turn 内——审计对须被 turn 包络，否则 reload 时当 crash-tail 垃圾丢弃（事件溯源的崩溃尾纪律）。

### 2. 结果封闭词表与 fail-closed 归一

- `ApprovalOutcome` = **allowed-once / rejected / cancelled / unavailable**（封闭词表）。
- **unavailable = fail-closed**：缺失答复、答复抛异常、越词表答复 → 一律归一 unavailable。
- abort → **cancelled + 迟到答复丢弃**。
- policy：ask / never 两档 + 会话级 `approval/policy` override 事件（fold 取最后一条）。

### 3. 无超时机制（关键差异）

grep 实证：user-approval 包内**无 timeout/deadline 机制**——dsh 审批等待不设超时（UI 不答就一直等，靠 turn abort 收敛）。**JH 的 300s fail-closed + `--approval-timeout` 是 it22 自有契约，W5 中继必须原样保留、绝不放宽**（用户锚 2 原文）。对照：dsh 的**问题面**（user-questions）反而有 TimedQuestionWait（见清单一）——审批与问题两面的超时语义在 dsh 是反的，JH 两面都有自己的既定契约，不随 dsh。

### 4. 回程通道（决定怎么回到 host）

出处：`packages/api/gateway/src/stream-protocol.ts` 全文实读（帧词表）+ `packages/api/gateway/src/client/remote-events.ts` 头 90 行。

- 下行：**waterfall 帧**（eventId + agentId + request）——审批请求作为 waterfall 逻辑流推给 client。
- 上行：client 决定后经 **`$events/result` 一元 HTTP 回程**（不走 WS 上行）；outcome 三态 = result / next（委托）/ rejected。
- 一次性结算：eventId 配对，答复即结算，迟到/重复无效。

### 5. UI 形状（composer takeover，非模态弹窗）

出处：`packages/client/ui-approval/src/client/ApprovalPanel.tsx` 头 70 行 + `contract/slots.ts` L1–110 实读。

- **ApprovalPanel = composer takeover**：审批请求接管输入框区域，不是浮层模态弹窗。
- 键位：**Enter = allowed-once / Escape = rejected**；IME 安全守卫（keyCode 229 / isComposing / compositionEnded）防中文输入法误提交。
- waiting ref **一次性守卫 + 失败回滚**；`pending.answerable` 旗标；callId 关联 tool 详情 slot。
- `PendingApproval` 类：kind 判别字面量（'approval'）；opaque key `approval:N`（渲染身份 + 一次性 remount 轴）；`result: Promise<ApprovalDecision>`——**client 侧词表只有 allowed-once | rejected**（cancelled/unavailable 是 host 侧归一，不给 UI）；`#settled` 一次性结算旗标 + delegated symbol（委托标记）；sessionId 标明归属。
- approval 与 question 各自独立 pending-settlement 生命周期（slots.ts）。

## 对 JH W5 的映射与差异注记

| dsh | JH W5 | 注记 |
|---|---|---|
| ApprovalRequestId（service 发 UUID） | `approval/respond` 配对令牌 | 用户锚 2「至少 request 级配对令牌，四确认定形状」——dsh 形状 = **server 发号 + 审计对 + 一次性结算**，可直接参照 |
| asked/decided 审计对 | JH 审批事件落账（it7 起） | JH 已有审批事件；W5 中继不新增事件种类则 R1 面不动（四确认核对） |
| turn-enclosed | JH turn 包络（it22 aborted 落账） | 审批事件须在 turn 内——JH 事件溯源同纪律，崩溃尾处理已有先例（it19） |
| unavailable fail-closed 归一 | 中继通道断 = 拒绝 | 浏览器断连/关闭不得挂起等待——fail-closed 原样（300s 界内未答即拒） |
| abort → cancelled + 迟到丢弃 | 超时/取消后迟到点击丢弃 | 300s 超时拒绝后浏览器迟到的「允许」必须丢弃并给 UI 明确反馈（已过期态） |
| 无超时 | **300s fail-closed 保留** | 差异登记：中继只搬运提示与决定，等待界口径不变（`--approval-timeout` 同） |
| waterfall 下行 + HTTP 回程 | 推送通道（T 项）+ `approval/respond` POST | JH 写面 = 一次性 POST JSON-RPC，与 dsh「下行流 + 一元回程」同构；不需要 WS 上行 |
| composer takeover + Enter/Escape | UI 形状 T 项 | 用户锚说「弹窗浮进 UI」；dsh 先例是 takeover 非模态——vanilla JS 约束下两者都可做，四确认裁形状；IME 守卫对中文用户必要（JH 受众含中文社区） |
| client 词表收窄（只 allowed-once/rejected） | UI 只暴露允许/拒绝 | cancelled/unavailable 归 host 侧——UI 不造词，词表纪律同 JH sealed 风格 |

## 实际执行命令（取证口径）

```sh
D=/Users/abel_yang/study/deepseek/deepseek-harness
git -C $D show 5badb15009:packages/interaction/user-approval/src/types.ts
git -C $D show 5badb15009:packages/interaction/user-approval/src/index.ts        # L1–260
git -C $D grep -in "timeout\|deadline" 5badb15009 -- packages/interaction/user-approval/src  # 零命中（无超时实证）
git -C $D show 5badb15009:packages/api/gateway/src/stream-protocol.ts
git -C $D show 5badb15009:packages/client/ui-approval/src/client/ApprovalPanel.tsx   # 头 70 行
git -C $D show 5badb15009:packages/client/ui-approval/src/client/contract/slots.ts   # L1–110
```

## 未读 / 缺口

- ApprovalPanel 完整渲染（70 行后）与 tool 详情 slot 联动细节未读——形状要点已够四确认输入。
- dsh 审批与 sandbox/policy 的联动（JH 对应物 = restriction + policy 档位）未对照——JH 侧契约以 it7/it12 既有实现为准，不随 dsh。