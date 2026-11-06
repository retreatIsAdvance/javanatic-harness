# it31 停 2 设计基线：delegation/local in-JVM Provider

日期：2026-11-06。四评审重点逐一直答；实现随本基线展开。

## 0. 侦察结论（2026-11-06）

- 子代创建：`AgentRegistry.create(owner, CreateAgentOptions(sessionId, AgentOptions(provider, model), seed, header, **Consumer&lt;Scope&gt; setup**))`——**setup 回调 = 继承快照执法点**（「挂 preset、scoped 工具等；失败即整体回滚且不发布」）。
- 问句判定：`concludingQuestion(live)` 配方（HeadlessMain 同款 fold，本模块重实现）——末个 `TurnEnd(reason=Completed)` 轮内 `ToolResultEvent.concludesTurn()` 的 content 即问题；终局非 Completed 恒无问。
- 终答提取：`finalAnswerText(live)` 同源配方。
- TurnEndReason 三因：`Completed / Aborted(cause) / Error(message, kind)`——预算耗尽的表面形态实现期核对（疑走 Aborted/Error cause，映射 `BUDGET_EXHAUSTED` 的判据随实现取证）。
- 子会话持久化：轮末 `SessionPersistence.save(childSession)`（HeadlessMain:580 同款）。

## 1. 评审重点①：Lost-恢复关系的显式落点

**v1 显式否决恢复、Lost 即人工介入点**：重启后 fold 事件账标 Lost（it30 同构）；子会话虽 durable（`--resume <childSessionId>` 理论可续），但**委派树的管理关系不跨宿主复活**——父失联后子会话作为独立会话存在，人工可查可续，服务不再认领。理由：跨宿主的树收口（cancel 级联、预算树账）无 durable 状态承载（事件账在各会话内，无树级账本），v1 认领 = 假恢复。重访条件：树级 durable 状态面立项时。

## 2. 评审重点②：预算贯通的 R1 回放

`DelegationRequest(budgetTokens)` 进 start 的 delegation/snapshot 事件载荷（请求字段全量入账：task 摘要 + budgetTokens + childSessionId）——**指纹可重建**：回放父账即知每次委派的预算授予；子侧消耗入子会话账（轮/步 token 事件既有面），树总账 = 父账授予值 + 子账消耗值的回放期聚合，无运行期隐藏数。预算执法（超限停子）映射 BUDGET_EXHAUSTED 终局事件（判据随实现取证）。

## 3. 评审重点③：继承快照 + 结构性测试

- **快照**：setup 回调内装配子面——工具面 = 父行配置的子集声明（`tools` 白名单键，缺省 = 全量继承）、沙箱 = 同策略同 workspace（v1 不放宽不另设）、审批档 = 不低于父、预算 = 请求值（四项只有工具面与预算 v1 可收窄；沙箱/审批 v1 恒等继承——「恒等」也是显式决定）。
- **结构性测试**：ArchUnit 断言 delegation/local 不出现「装配后扩口」形态——具体：子 setup 完成后 Provider 不持有可向子 scope provide/register 的引用（源级断言 setup 内注册只经装配期 + 运行期路径无 scope 注册调用）。等效兜底：集成测试在 setup 后尝试扩注 → fail loud。

## 4. 评审重点④：双闸准入的树语义

- `maxDepth`（缺省 1，0 禁）：深度按**会话血缘**计——子 setup 时携带 depth-1，子再委派时校验；执法点在 Provider.start（depth ≤ 0 抛 DelegationException）。
- `maxActive`（缺省 8，预留-放行-还槽）：**按根会话共享一个池**（dsh manager 同构）——v1 单父进程内 = 服务实例内 ConcurrentHashMap 计数 + 语义上属树；跨进程树池无承载（与 §1 同理，v1 不跨宿主）。降额不杀驻留。
- 双闸均为 delegation-local 行配置（B32 候选）。

## 5. 生命周期与事件

start → 子 agent followup(task)（虚拟线程）→ whenIdle → 分类：concludesTurn 问 → NeedsParentInput（save）；Completed 无问 → Succeeded(finalAnswerText)（save）；Aborted(我方 cancel) → Cancelled；Error → Failed(MODEL_ERROR)；预算 → Failed(BUDGET_EXHAUSTED)。每转换先 delegation/snapshot 入账（父会话）后内存。cancel = 子轮 abort + Cancelled 终局。codec 第八实例（六态 variant 判别式，jobs 同构）。close = 级联取消。
