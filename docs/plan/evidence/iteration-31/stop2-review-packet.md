# it31 停 2 审查包：delegation/local in-JVM Provider

**新增模块自问（首行必答）**：是——delegation/local 新模块；**base 未加行**（装配面停 3）。四件套中 services 双通道已就位（Plugin + Codec 两文件——本批即写，it30 教训生效）；②③④（Maven 承载/钉死面/moditect）随停 3 加行触发。

- **不变量增量**：四路终局映射纯函数钉死（问态 = Completed + concludesTurn，**不走 TurnEndReason 三因**——评审映射精度注意的兑现）；每次转换先父账（delegation/snapshot 含 budgetTokens）后内存；终局/暂停恰一次（finalized CAS）；首绑即父（自建子代的 created 不重绑）；cancelling 先置后 cancel（it30 竞态纪律复用）；服务不持有子 scope（DelegationChildSetup = 唯一触碰点）。
- **裁决点**：①Lost 扫描标注非终局即标（Running 与 NeedsParentInput 皆失联候选）；②BUDGET_EXHAUSTED 的轮内拦截表面（无 TurnEnd 的预算停）实现期核到 Aborted/Error cause 映射，纯函数层不预设——停 3 集成取证；③maxActive 准入为同步检查 + 终局释放（预留-放行-还槽的最小形态）。
- **风险自报**：**服务级集成测试挂账停 3**（真装配 + replay 子代全链 + maxActive 拒绝 + 取消级联的运行时证据）——本停钉死纯函数层（映射/编解码/校验），生命周期驱动的行为面要装配面就位才可测，如实登记非静默跳过；结构性测试双路（ArchUnit + 扩注探针）随集成测试同批（探针需要真子 scope）。
- **证据入口**：[聚焦 + validate](stop2-provider-focused.txt)（19/0/0 + EXIT=0）；[设计基线](stop2-provider-design.md)（四重点直答已获准）。
- **若只能改一处**：driveTurn 的虚拟线程驱动面（followup/whenIdle 的异常与超时路径）——纯函数测不到的运行时缝隙，停 3 集成若红，第一怀疑点在此。

## 1. 承载类
`LocalDelegationService`（start/resume/cancel/close + bindSession Lost 扫描 + driveTurn）；`TurnClassifier`（四路纯函数）；`DelegationChildSetup`（装配期唯一触碰点）；`LocalDelegationPlugin`（provider/model/maxActive 行配置 + 首绑策略）；`DelegationSnapshotEvent/Codec`（第八实例）。

## 2. 三行映射
| 设计增量（stop2-provider-design.md） | 实现 | 测试 |
|---|---|---|
| 四路终局映射（§5） | TurnClassifier | TurnClassifierTest 6 项（逐路钉死） |
| 预算随载荷（§2） | DelegationSnapshot.budgetTokens + codec | CodecTest 往返 + Definition 快照校验 |
| 先账后内存 + 恰一次（§5） | appendAndShow + finalized CAS | （运行时面挂账停 3 集成） |
| 首绑即父 + Lost 懒标记 | bindSession | （同上） |

## 3. 偏离清单
| 偏离 | 为什么 | 处理 |
|---|---|---|
| 服务级集成测试不在本停 | 需装配面（AgentRegistry+持久化+Scope）就位；纯函数层已钉评审重点①② | 挂账停 3（与工具面/装配同批），非静默 |
| budgetTokens 快照增补（停 1 契约 additive） | R1 裁决②：预算进载荷 | 包内通报（it30 EXIT_NONZERO 同规） |

## 4. 为什么是这个形状 / 若错靠什么发现
分类器独立成纯函数：四路映射是评审第一查点，合成事件逐路钉死（问态构造 = concludesTurn=true 的 ToolResultEvent + Completed TurnEnd——正是 it22 出口契约的账面形态）。首绑即父：子代 created 重绑会把父账写进子会话（账本错位）——结构性防呆。若错：停 3 集成红（runbook 同 it30 探针路径）。
