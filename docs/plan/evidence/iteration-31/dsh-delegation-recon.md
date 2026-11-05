# it31 侦察：dsh 委派面（subagent 组 / team 组；起草四确认前的前置输入）

日期：2026-11-05。方法同 it27/it28：读包 README + it26 勘察 §3-7 对照输入；基线 dsh `5badb15009`。
读者注意：dsh 是设计期参照系——**思想照搬，形状不照搬**；本文只登记事实与可搬性，不做实现承诺。

## 1. dsh 委派面实况（包 README 事实）

- **seam（subagent/subagent）**：命名 provider 注册表 + 一次性运行（越进程后端）与可续子代（进程内 spawn/fork，durable session 可续聊）+ 发现；「每个子代共享 admission、cancellation、ownership、discovery」。
- **权限/所有权**：「child keeps its parent's origin metadata and **permission roots**」；子代自有 cwd 跟随继承历史、续跑/重启后仍有效、独立于父的后续变更。
- **深度策略**：`maxDepth` 缺省 **1**（仅直接子代）、0 = 禁委派、工具级覆盖优先——深度是策略旋钮不是常数。
- **容量准入**：`maxActiveSubagents` 缺省 **8**/树——创建与冷恢复**先预留后放行**，清理完成才还槽；pending inbox 与停机中的激活仍计数；发消息给驻留子代复用其槽。降额不杀驻留者、只拒新准入。
- **异步完成**：工具调用**立即返回子代名**；后台激活落定后父收到完成通知（与 JH jobs 的异步事件口径同构）。
- **零噪声**：仅挂载服务不启用任何委派（须 provider + 工具行）。
- **fork 语义**：以父的已完成历史做种子的进程内子代（prior conversation matters 场景）。
- **team 组（experimental）**：子代树共享任务板 + 保存 Team 模式；创建/发现/消息/中断留在 subagent 组。
- **越进程后端**：ACP / Codex / Claude Code / DSH SDK——JH 非目标（00 §6），不跟。

## 2. 对照 JH it31 的三张清单

**思想可搬**：①深度缺省 1 + 工具级覆盖 + 0 禁用（有界调用深度的策略面）；②容量准入的「预留-放行-还槽」三段式 + 降额不杀驻留；③异步完成通知（立即返名 + 终局事件——与 job/snapshot 同精神，委派版事件可走同族 fold）；④权限根继承的显式措辞（继承且只能收窄——评审重点②的 dsh 侧证词）；⑤可续子代用 durable session（JH session 本就 durable，天然底座）；⑥发现/列表面（父视角子代清单，含 stored）。

**形状不搬**：命名 provider 注册表抽象（JH v1 单一 in-JVM provider，seam 留位不建注册表）；projection registry 依赖（JH 无投影层，W1 才有）；settings 页面（宿主 UI 面）。

**明确不跟**：ACP/Codex/Claude/SDK 越进程后端（非目标）；team 共享任务板（experimental，0.4 委派最小闭环不含——0.4 路线原文「受控委派」六要素无一需要树内共享板）；workflow/goal/schedule/PTC（it26 §3-7 已记 0.4 对照输入，属后续裁决不属 it31）。

## 3. 对四个预立评审点的直接输入

1. **验收量化标准**：dsh 侧无现成收益判定标准（README 无对照实验口径）——JH 需自定（这正是「无先例可抄」的确认）。
2. **权限继承**：dsh 词汇 "permission roots" 继承 + 不随父后续变更——支持「子 scope + 继承快照」而非「活引用」。
3. **与 it30 分工**：异步完成通知/终局事件/取消语义可直接复用 jobs 先例（有界并发 = 容量准入的 jobs 版）；`ask_user` 路由 dsh 无先例记载——必新做（预立重点③成立）。
4. **深度/容量双闸**：maxDepth(1) + maxActive(8) 是 dsh 的双闸——JH 0.4 路线「有界并发、调用深度」原文对应，量纲可对照。
