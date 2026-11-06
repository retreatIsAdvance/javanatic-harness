# it31 停 1 审查包：DelegationService seam 契约

**新增模块自问（首行必答）**：是——delegation/delegation 新模块；**base 未加行**（Definition-only，装配面停 3）。四件套：本停仅①services 双通道中 Plugin 无（Definition 无插件，codec 停 2 随 Provider）——如实登记：Definition 阶段无 services 文件是正确形态（jobs/jobs 同期亦无），四件套检查在停 2/3 加行时触发。

- **不变量增量**：问败分界（NeedsParentInput 独立暂停态，枚举层面不可混入失败）；childSessionId 树链接必在（快照四要素）；resume 唯一前态 = 暂停态；穷尽 switch 双谓词（terminal/paused）。
- **裁决点**：①Lost 与 jobs 同义但**不预设可恢复性**（子代 durable 会话使「失联≠不可续」成为可能——恢复与重标的关系留停 2，本契约只锁「如实标记」）；②预算是继承面四项中唯一进请求的项（请求不稀释快照语义）。
- **风险自报**：NeedsParentInput 的「暂停可续」在 seam 层只有状态语义，续跑的轮管线衔接（答复 = 子代下一轮 user message）依赖 Provider 实现细节——it22 exit-5 形态的映射正确性要到停 2/3 才被行为验证。
- **证据入口**：[聚焦测试](stop1-definition-focused.txt)（10/0/0 + VALIDATE_EXIT=0）；[seam 提案](stop1-seam-design.md)。
- **若只能改一处**：NeedsParentInput 的字段面（question 单字段 vs question+context）——我方立场：单字段逐字入账足够，答复路由归父；若评审认为子代需要携带结构化上下文（如选项列表），此处是唯一改点。

## 1. 承载类
`DelegationService`（六方法 + Javadoc 承载异步口径/快照继承/单闸路由预告）；`DelegationState`（六态 + 双谓词）。

## 2. 三行映射
| 设计增量（iteration-31.md） | 实现 | 测试 |
|---|---|---|
| 状态联合（问败分界 + Lost） | DelegationState 六态 | DelegationStateTest 5 |
| 请求校验（预算收窄项） | DelegationRequest | DelegationRequestTest 4（含 user.dir 跨平台夹具——it30 pits 首用） |
| 树链接快照 | DelegationSnapshot.childSessionId | DelegationSnapshotTest 1 |

## 3. 偏离清单
无（Definition-only，无装配触碰）。

## 4. 为什么是这个形状 / 若错靠什么发现
六态 = jobs 五态 + NeedsParentInput：委派比任务多一个「问」的动作，且问必须可续（exit-5 先例）——独立暂停态是 it30 教训（Lost≠Failed）的同类前置（问≠败）。树链接用 id 关联不复制账面：R1 的会话边界不可被父快照内嵌子事件破坏（各归各账本，回放各自零进程）。若错：停 3 双臂问答场景与 R1 树回放红。
