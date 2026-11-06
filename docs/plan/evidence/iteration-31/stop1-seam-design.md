# it31 停 1 seam 提案：DelegationService（delegation/delegation Definition）

日期：2026-11-05。仅契约与事件形状；无子代执行、工具、第三方依赖。参照：JobService（it30 停 1 终裁）+ dsh 委派面侦察（同目录）。

## 1. 契约面

- `DelegationService`（`ServiceKey<"delegation">`）：`start(DelegationRequest)` 立即返 Running 快照（异步完成，dsh「立即返名」同构）；`resume(id, answer)` —— **NeedsParentInput → Running**（问题与答复逐字入账）；`cancel(id)`（Running / NeedsParentInput 收敛，终态 false）；`snapshot` / `list`（父会话域，按启动序）/ `close`（拥有 scope：级联取消 = 树收口）。
- `DelegationRequest(task, workingDirectory, budgetTokens)`：装配期校验；预算项 = 继承面四项中唯一进请求的收窄值（其余三项经 Provider 行配置快照——请求不逐项展开，快照语义不稀释）。
- `DelegationState` 六态判别联合：Running / **NeedsParentInput**（暂停非终局、question 非空逐字入账）/ Succeeded / Failed(MODEL_ERROR|EXECUTION_ERROR|BUDGET_EXHAUSTED) / Cancelled / Lost。`terminal()` 与 `paused()` 双谓词穷尽 switch——**问与败严格分界**（it30 Lost-vs-Failed 教训前置应用：需要输入不是失败，枚举层面就进不来）。
- `DelegationSnapshot(id, taskSummary, childSessionId, state)`：**childSessionId = 会话树链接**（评审重点②的契约落点）——子代 durable 会话 id；父快照不内嵌子事件（账本各归各会话，树经 id 关联不复制）。

## 2. 事件形状

- `delegation/snapshot` 全量快照按 id fold 末值胜、无 generation、ignorable=true 不进 prompt 组装——job/snapshot 同族（it30 机器复用的直接延续）。
- 发射时机：启动、暂停（NeedsParentInput）、续跑（resume 回 Running）、终局、Lost 标记；codec 随 Provider（停 2）落第八实例。

## 3. 边界与不做

- Lost 语义微差（与 jobs 的诚实对照）：子代有 durable 会话，「失联后可否经 childSessionId 恢复」是停 2 议题——本契约只定「重启后首次观测如实标记」，恢复关系不预设。
- 双闸（maxDepth/maxActive）与树总预算贯通归 Provider/装配面（停 2/3）；权限继承快照的执法与结构性测试归停 2（四确认裁定②证据形态）。
- resume 的唯一合法前态 = NeedsParentInput，他态抛 IllegalStateException（fail loud）。

## 4. 若错靠什么发现

- 问败分界错（NeedsParentInput 误入终局/枚举）→ 状态测试 + 停 3 双臂验收的问答场景红；
- 树链接缺失/复制账面 → R1 树回放测试（停 3）红；
- 快照次序错（内存先于事件）→ 停 2 Provider 继承 it30 先持久后可见。
