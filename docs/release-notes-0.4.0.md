# javanatic-harness 0.4.0

> 本文件 = `v0.4.0` GitHub Release 正文**采用稿**（评审可改）；发布执行（版本翻转 / 签名 / 上传 / tag / Publish）属用户侧，见 [release.md](release.md) §0。

Plugin-based agent harness on the JVM — **Java 25 LTS / JPMS / Maven**. Ports the engineering ideas of DeepSeek Harness (dsh) to the Java ecosystem: **ideas carry over, shapes do not**.

0.4.0 的主题是**可管理的后台任务与受控委派**（0.4 路线两站）：长任务不再阻塞会话，子任务可以派出而不污染父上下文——两站都走既有治理通道（事件账 / 审批 / R1 可重建），零新越权面。

## 升级必读（0.3.0 → 0.4.0）

**无破坏性变更**——全部为纯增量：新模块 8 个（`jobs` 组 4 + `delegation` 组 4，随 8 个新导出包入场）；新工具 8 个（`job_start/status/read/cancel` + `delegate/delegate_list/delegate_answer/delegate_interrupt`，**恒需审批**，无豁免出口）；新扩展事件 2 类（`job/snapshot`、`delegation/snapshot`——`ignorable=true`，旧读取方跳过不毁 R1）；新配置键 3 组（B30 `jobs-local.dataDir`、B31 `jobs-tool.maxReadBytes`、B32 `delegation-local provider/model/maxActive`）。既有模块导出面、`SessionEvent` permits、CLI flag 面**零变化**。

## 何时委派（实测判据，双臂验收一手数据）

- **用**：中间产物大而结论小的任务（多源检索汇总、批量排查、长文深读提炼）——放大实测中父终上下文 **41.7k → 16.3k（−61.6%）**，父会话只持汇总态；
- **不用**：微任务与强依赖链（下一步要吃上一步中间态）——每代各背系统提示基线，树总 token **约为直做的 3~4 倍**（结构性地板，非调优可消除）；实测收益需单臂规模 **≥~40k token** 才显现；
- **与压缩的分工**：compaction 是事后补救（满了再压，有损），委派是事前预防（中间产物根本不进父上下文）——两者互补不互代；
- 这套判据同时写进了 `delegate` 工具描述——模型每次请求自带决策校准，不必重新发明。

## Highlights

- **可管理的后台任务（it30）**：任务 ID / 五态判别联合 / 输出游标读取；输出限额**停捕获不杀任务**；TERM→KILL 进程矩阵；宿主强杀后重启**如实标记 Lost**（事件账 fold 唯一事实源，不冒充可续接）；`job/snapshot` 全量快照按 id fold。
- **受控委派（it31）**：子代 = 独立 durable 会话（`childSessionId` 树链接，各归各账）；**NeedsParentInput 是暂停不是失败**（问败分界锁在枚举层；`delegate_answer` 答复 = 子代下一轮 user message）；delegate 工具在子面**影子封禁**（防递归的结构执法）；预算随 `delegation/snapshot` 载荷入账——树总账回放期聚合，零运行期隐藏数。
- **委派策略指引（it32）**：实测行为经济学入工具说明书（见上节）。

## 已知边界（如实登记）

- **遗物保留**：jobs/delegation 输出文件 v1 **不自动清理**（用户 dataDir 自管理；B33，与 sessions 目录同策略）；
- **Lost 不自动恢复**：前宿主生命期的任务/委派标记失联后**服务不认领**（子会话可独立 `--resume`，人工介入点；重访条件见 B33/设计文档）；
- 稳定面语义以 [12-api-stability.md](design/12-api-stability.md) 为准。
