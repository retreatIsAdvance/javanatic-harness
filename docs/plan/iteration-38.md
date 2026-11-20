# 迭代 38 — W5 交互工作台（状态：四确认已通过（2026-11-20），开工首批进行中；预登记 2026-11-17；开工前置 it37 已关账——0.5.0 已发布）

基线：it37 已关账（0.5.0 全链闭环：Central 71 坐标 + GitHub Release 8 归档 + tag；最终 head `b568ef8`），工作版本 `0.6.0-SNAPSHOT`。**版本号与顺序已裁（2026-11-17）：先 0.5.0 发布（it37），W5 = 0.6.0**——版本叙事对齐治理叙事：0.5.0 把「零写面只读工作台」作为完整自洽的能力相位发布收口；「零写面不变量的终结」是叙事反转，配 ADR 配新 minor 号，patch 号不承载这种重量；发布后 W5 在**已发布基线**上动写面，回退面干净。路线表新起行（0.6.0），不混入已关账的 0.5 行：W5 显式终结零写面不变量，属新能力相位。

**出处**：用户裁定消息 2026-11-17（it36 收口终审通过 + push 授权之后续消息）；同日三项裁决消息（push 2 笔授权 + dsh 三发现全采纳 + 顺序裁定）。本文件即预登记包——防会话压缩丢上下文，四确认草案的输入现在就位。**预登记 ≠ 开工授权**（[plan/README](README.md) 生命周期：路线落盘不等于单轮开工）。原编号 it37，2026-11-17 顺序裁定后重编号 it38（it37 = 0.5.0 发布工程）；evidence 目录随迁 `evidence/iteration-38/`。

## 裁决记录（2026-11-17，三项全落）

1. **dsh 侦察三发现全部采纳**：① **Host/Origin 信任围栏升格 B 行准入条件（B36）**——不是 T 项候选：B29 回环恒绑防的是网络面（远程主机连不进来），防不了浏览器面（恶意网页可从用户浏览器向 `127.0.0.1:<port>` 发 POST，SOP 只挡读响应不挡发请求）；读面时代风险低，**写面一开就是「你访问的任何网站都能驱动你的 agent」**。要求与重访条件见下节 + [boundary-register B36](boundary-register.md)。② **审批超时口径**——JH 300s fail-closed 是 it22 自有契约（dsh 无此面，grep 零命中实证），中继不放宽 = 四确认**硬约束**。③ **SSE vs WS**——「写面是一次性 POST → 单向下行足够」推理成立，零新依赖纪律保持；`/events` 走显式修约（不动 W4 T1 契约的静默破口）纪律保持。
2. **顺序裁定：先 0.5.0，W5 = 0.6.0**——it37 = 0.5.0 发布工程（it32 全模板复用），it38 = W5（0.6.0 周期首站）。
3. **B36 预登记随 it37 文档批先行落位**（本批）；对既有读面的防御性回补由 it37 评估（低成本高价值，0.5.0 带上更好——评估结论与取舍见 [iteration-37](iteration-37.md)）。

## 四确认评审裁决（2026-11-20，通过）

- **ADR 0012 批准**：四条终结条件即评审标准；状态「已裁决（实施中）」，note 已落 `notes/0012-zero-write-surface-end.md`（2026-11-20，开工首批）。
- **内容九项全过；T1–T7 全批**，附三处注记/增补：
  1. **T1 精度**（停 1 评审重点）：seam 接口设计必须让 rpc 无 headless 可测——接口进 rpc、实现进 headless，与 `SessionPersistence`/`SessionTail` 同款。
  2. **T2 注记**（同步写入「明确不做」）：inbox 的 `NEXT_TURN`/`NEXT_STEP` 机制是现存的 steer 通道——「拒绝」是自觉选择，不是无知；steer 支持挂账为升级路径。
  3. **T4 增补**：`/events` 必须**无条件**过 B36/B37 围栏（GET 同样在 dispatch 面），写入设计，防实现时漏。
  4. **R4 增补**（收进内容⑦）：写面成为治理相关能力后，`--verify` 与 serve 启动 banner 应可证明其状态——增 `web-write: off（缺省）` / `on（--web-write）` 行，兼入停 3 verify 项。
- **停 1 评审重点（预告）**：① seam 接口形状；② 审批中继一次性结算状态机 + 300s 真时钟集成（fail-closed 在中继下的语义，本站最重的一件）；③ B36 写面复验突变；④ SSE 围栏覆盖 + `Last-Event-ID=seq` 续传语义（seq = R1 信封序，回放安全的地基）。
- **执行序**：开工首批 = ADR 0012 入 notes + TwoHostLostProbe 手术 + 停 1 seam 契约；push 两笔（`00768be` + `be8df00`）已授权并落地（远端 head `be8df00` 实核）。

## 准入条件：B36 Host/Origin 信任围栏（预登记，写面开工前必须落地）

- **要求**（用户原文口径）：Host 头白名单 `127.0.0.1:<port>` / `localhost:<port>`（port = 实际绑定端口）+ Origin 校验拒绝跨站；**突变必红**。
- **定位**：信任围栏非 auth 层（dsh api-request-trust 先例同口径——每请求必绑 Host，防 DNS rebinding + 跨站驱动）；B29 单用户假设不变，围栏是 B29 在浏览器面的补充而非替代。
- **形状输入**（四确认细化，不预裁）：纯函数围栏 + dispatch 头部守卫（先于路由）；缺失 Host / 端口不匹配 / Origin 跨站的拒绝阶梯；`Sec-Fetch-Site` 是否入 defense-in-depth 列 T 项；W4 T1 路由契约不变——围栏是横切前置条件，须显式记契约增量不静默。
- **读面回补**：it37 评估项（0.5.0 候选携带）——若 it37 已回补读面，本站围栏在其上扩展写面语义，不重复建设。

## 一句话范围（用户原文）

在 W4 工作台上加聊天面——发消息开轮、看流式回复、审批弹窗在浏览器里点、exit 5 等答复的问题在对话框里答。终端 REPL 的全部能力搬进浏览器，治理不降一格。

## 五个预登记锚（用户原文；四确认草案须逐项对齐）

1. **写面方法组**（JSON-RPC 增量）：`session/start` / `session/send`（含 resume 等答复会话）/ `approval/respond` / `session/cancel`——**全部复用既有执行面**（headless run 机制与 agent followup，R2 不开新通道）；B29 回环恒绑不变。
2. **审批中继（最难的一件）**：ask 档弹窗浮进 UI，**fail-closed 语义原样保留**（300s 超时即拒、`--approval-timeout` 口径不变）——中继层只搬运提示与决定，绝不放宽等待界。响应需防伪（至少 request 级配对令牌，四确认定形状）。
3. **流式推送**：`assistant/chunk` 到浏览器——单向下行 SSE 足够（WS mux 双向动因在 JH 不存在，2026-11-17 裁决采纳）；SSE 独立路径 vs 增强轮询的实现取舍仍列 T 项（侦察与裁决均倾向 SSE 腿）；注意 W4 T1 裁过「仅精确 `/` GET」——SSE 若走 `/events` 需显式修约，别静默破契。
4. **轮生命周期 UI 状态机**：running / waiting-approval / **waiting-answer（exit 5 语义）** / done——与 headless 退出码契约一一对应；等答复态就是浏览器里的问题+答复框（`session/send` 即 resume）。
5. **「零写面不变量」的终结 ADR**：W1–W4 读侧零写路径是刻意承诺，W5 显式终结它——ADR 记推翻对象与理由（写面全部过既有管线 + 审批中继不降格 = 终结的条件）。it35 T6 挂账（会话控制写）由本站收口。

## 四确认（草案，待评审 2026-11-19）

### 内容

1. **写面方法组**（JSON-RPC，走既有 POST `/` 通道）：`session/start` / `session/send` / `session/cancel` / `approval/respond`。`session/start` 开新会话并跑首轮；`session/send` 对既有会话送下一轮 user message（`--resume` 等价，即 exit 5 等答复的答复通道）；`session/cancel` 复用 it18 协作取消（aborted 落账、exit 4 语义）；`approval/respond` 只处理审批中继决定。
2. **执行归属**：`workbench/rpc` 增写面契约 seam（名称停 1 钉死），实现由 `examples/headless` 组合注入——与该模块现有 projection / tail 双 seam 同构；底层只用 `core/agent` 的 `Agent.start/followup/cancel` 与既有 AppBoot 组合，**R2 不开新通道**、不新增发布坐标、不改镜像模块数（69）。
3. **审批中继**：注入 `ApprovalPrompt` 实现（`ApprovalAskPlugin(ApprovalPrompt)` 的显式注入口已是现成接缝）——server 每次请求发 UUID，下行推送「toolName + summary + 参数摘要」；`approval/respond` 按 UUID 一次性结算；**300s fail-closed 与 `--approval-timeout` 原口径不变**（非交互缺省仍是 `NON_INTERACTIVE_IDLE_TIMEOUT`，中继只搬运提示与决定）；超时/取消后迟到的「允许」必须丢弃，UI 给「已过期」明确态。
4. **流式推送**：SSE（`GET /events`）——`Last-Event-ID` 直接映射 `LoggedEvent` 的 seq 游标，keep-alive 注释帧，浏览器 EventSource 原生长活重连；消费 `assistant/chunk` 等既有事件，**事件源与 R1 面不动**。若评审改裁轮询腿，则零路由修约、活跃会话短间隔 poll 既有 tail 方法。
5. **轮生命周期 UI 状态机**：running / waiting-approval / **waiting-answer（exit 5）** / done；等答复态 = 浏览器内问题 + 答复框（`session/send` 即 resume）；**不引入问题超时**——JH exit 5 语义本就是无限等（与 dsh TimedQuestionWait 的差异登记在案，不随 dsh）。
6. **安全准入**：B36 写面复验——it37 读面围栏（Host 白名单 + Origin 拒跨站）已在 `dispatch` 前置、覆盖一切方法；本站以测试钉死写面方法组的拒绝阶梯与突变必红，B29 回环恒绑不变。
7. **写面开关**：新增 `--web-write`（缺省关）——关时行为与 0.5.0 完全一致（只读 UI）；开时 UI 出现聊天 / 审批 / 答复控件。见待裁 T6。R4 可断言面：`--verify` 与 serve 启动 banner 显示 `web-write: off（缺省）` / `on（--web-write）`（2026-11-20 评审增补，停 3 verify 项同收）。
8. **开工首批（独立于三停）**：观察账手术——`ProbeHostA` 固定 `Thread.sleep(800)` 改条件等待（job 日志 / Running 快照出现，带截止与失败诊断），`halt(0)` 前给出已 fsync 的可观察证据；不动产品代码。
9. **同步面**：`docs/design/12`（CLI flag + JSON-RPC 方法面稳定面增量）、`05`/`00`（写面准入与 ADR 引用）、README / roadmap / current-state、AGENTS（如需）、0.6.0 release notes 后置。

### 目标

浏览器内完成「开轮 → 流式 → 审批 → 答复 → 取消」全链，治理不降一格：所有副作用仍过 ToolExecutor 管线与 ApprovalService，浏览器只是人闸/观察通道的替换。0.6.0 周期首站；ADR 0012 显式终结零写面不变量。

### 为什么

- 写面复用的都是既有执行面（`core/agent` + AppBoot 组合 + tools pipeline），不是新执行通道——R2 成立的条件下，浏览器与终端只是同一治理面的两个前端。
- 审批中继的注入点现成（`ApprovalPrompt` 是函数接口，插件已有显式注入构造器），300s 等待界由 `ApprovalPrompt.effectiveIdleTimeout` 单源控制，中继改不动它。
- SSE 是 JDK `HttpServer` 长活响应 + 虚拟线程的天然形状，零新依赖、零 node 工具链；W4 已把「单文件 vanilla JS」定死。
- B36 在读面已落地，写面准入不是重复建设，而是把既有围栏的 POST 面用测试钉死。

### 明确不做

steer / prompt queue（在途轮打断与排队——JH 无队列面，新语义；注记：inbox `NEXT_TURN`/`NEXT_STEP` 即现存 steer 通道，拒绝为自觉选择，steer 支持挂账为升级路径）；附件上传；多用户 / 认证 / TLS / 远程暴露（B29 硬线）；jobs 运行中任务面（T7 延后）；问题超时（exit 5 语义不变）；WebSocket；动态配置热重载；node / 前端构建链；执行面重构或向后端迁移。

## 待裁（T1–T7，拟案）

| # | 待裁项 | 拟案 | 依据 |
|---|---|---|---|
| T1 | **写面代码归属与 seam 形状** | **批（workbench/rpc 内契约 + headless 注入实现）**：增写面 seam 接口（`session/start`、`send`、`cancel`、`approval/respond` 四动作），实现类放 `examples/headless`（唯一 AppBoot 组合点，与 projection/tail 注入同构）；零新发布坐标、零新模块、镜像 69 不动。备选（若停 1 评审认定 seam 过大）：独立 `workbench/chat` 模块（+1 发布坐标，须重议 0.6.0 发布面） | 预登记锚 1「全部复用既有执行面」；`WorkbenchRpc` 现有双 seam 构造注入先例；`core/agent` 公开 `start/followup/cancel` |
| T2 | **在途轮的 `session/send` 处置** | **批拒绝**：running 轮的重复 send 返回 JSON-RPC 业务错误（「turn in flight」，code 停 1 钉死），不排队、不打断。重访条件 = 用户点名后台排队或 steer。**注记（2026-11-20 评审）：inbox `NEXT_TURN`/`NEXT_STEP` 是现存 steer 通道——拒绝是自觉选择，不是无知；steer 支持挂账为升级路径** | dsh `prompt` 有 queue/steer 双模，JH 无队列面；首版最小语义，避免隐式并发 |
| T3 | **审批中继令牌与结算形状** | **批（server 发号 + 一次性结算）**：server 每次审批发 UUID；下行事件携带 requestId + toolName + summary；`approval/respond` 必带 requestId，首答结算、迟到/重复回业务错误并在 UI 显「已过期/已结算」；300s 与 `--approval-timeout` 不动；UI 词表只暴露「允许 / 拒绝」（cancelled/unavailable 归 host） | dsh `ApprovalRequestId` + 一次性 retire 先例；JH `ApprovalService.ApprovalRequest` 目前无 ID（需中继层发号）；it22 等待界契约 |
| T4 | **流式推送实现腿** | **批 SSE**：`GET /events` 显式修约 W4 T1（仅精确 `/` GET/HEAD），`Last-Event-ID` = seq、keep-alive、慢消费者有界队列满即断开由 EventSource 重连续传；停 1 重钉 dispatch 测试。**`/events` 必须无条件过 B36/B37 围栏（GET 同在 dispatch 面，2026-11-20 评审增补）**。备选轮询腿（零修约）保留为评审可翻 | recon-streaming T 项三行成本/收益；SSE 零新依赖；W4 T1 用户原话「别静默破契」 |
| T5 | **审批 / 问题 UI 形状** | **批 composer takeover 非模态**：接管输入区，不浮层；审批 Enter = 允许 / Escape = 拒绝，问题 Enter = 提交（Shift+Enter 换行）；IME 组合守卫（composition 事件）防中文输入误提交 | dsh ApprovalPanel 先例；vanilla JS 可做；中文受众 IME 必要 |
| T6 | **写面默认开关** | **批缺省关 + `--web-write` opt-in**：无该 flag 时行为与 0.5.0 完全一致（只读 UI、零写方法）；带 flag 才暴露写方法组与控件；`--web-write` 无 `--serve` = 用法错误（exit 2）。备选默认开——与「0.6 主题即写面」一致，但改变既有 `--serve` 语义；拟案取 fail-closed 优先 | B29 单用户信任模型 + fail-closed 传统；0.5.0 的 `--serve` 承诺是只读面；用户可显式选择 |
| T7 | **jobs 面并站** | **批延后**：W5 不做，重访条件 = 用户点名 jobs 视图（沿用 W4 T2 口径） | it34/it36 两次挂账；与本站主题不同轴 |

## 首裁：ADR 0012 草案「零写面不变量的终结」

- **背景**：W1–W4（it33–it36）把「读侧零写路径」作为刻意承诺：投影、TUI、JSON-RPC、Web UI 全部只读，0.5.0 的发布叙事以「零写面」为卖点（[release-notes-0.5.0](../release-notes-0.5.0.md) §零写面；[design/README](../design/README.md) L124）。W5 引入会话控制写面，该承诺在产品后续版本中不再成立。
- **裁决**：显式终结「零写面不变量」；终结的条件 = ①写面全部复用既有执行面（ToolExecutor 管线 + ApprovalService，R2 不开新通道）；②审批中继不降格（300s fail-closed 原样）；③B36 Host/Origin 围栏对写方法组同样生效（突变必红）；④写面由 `--web-write` 显式开启，缺省仍只读。
- **推翻对象**：0.5.0 发布承诺「v1 零 server 写面」在 0.6.0 不再适用（[release-notes-0.5.0](../release-notes-0.5.0.md) §零写面）；[iteration-35](iteration-35.md) T6「会话控制写 v1 只读」的挂账由本站收口；[iteration-36](iteration-36.md) T3 的「server 零写路径」只是 W4 首版划界，本节不追溯改判历史文档。**不否定 0.5.0 已发布事实**——0.5.0 仍是一段完整、可独立讲述的只读能力相位。
- **后果**：稳定面增量（新 CLI flag + 新 JSON-RPC 方法）须记入 [12](../design/12-api-stability.md)；写面成为 0.6.0 的正式承诺；后续版本不得以「兼容」名义把写面默认打开而不经新的裁决。
- **状态**：`已裁决（实施中）`——note 已落 [notes/0012](../../.agents/notes/0012-zero-write-surface-end.md) 并与索引互链（2026-11-20）；实现随停 1–3 推进。

## 流程锚（用户原文）

- **dsh 侦察先行**（it27/it28 惯例）：dsh web client 的聊天/审批/流式三面先勘察成三张清单（**已完成**，见下节）；
- 顺序裁定后开工前置 = it37 0.5.0 发布完成（在已发布基线上动写面）；JLine TUI 的渲染/按键先例与 W4 的 ui.html 是双参照；
- **jobs 面挂账**（W4 T2）可评估并入本站（同是「工作台完整度」，四确认时一并裁）。

## 顺序问题（已裁，2026-11-17）

**先 0.5.0（it37 发布工程），W5 = 0.6.0（it38）**。理由：版本叙事对齐治理叙事（见基线段）；发布后 W5 在已发布基线上动写面，回退面干净。jobs 面（W4 T2）并站评估仍留四确认议程。

## 既有裁决接口（四确认引用，不重裁本体）

| 接口 | 现状 | W5 关系 |
|---|---|---|
| it35 T6（会话控制写 v1 只读） | 重访条件 = W4 UI 点名单项控制动作需求 | 预登记锚 1 点名写面方法组——本站收口该挂账 |
| W4 T1（仅精确 `/` GET/HEAD） | it36 停 1 契约钉死 | SSE 若走 `/events` 须显式修约（锚 3 已点名，别静默破契） |
| B29（仅 127.0.0.1、单用户、无 TLS） | 硬线，UI 面同成立 | 写面同硬线，回环恒绑不变 |
| B36（Host/Origin 信任围栏） | 预登记（2026-11-17 用户裁定，随 it37 文档批落位） | **写面准入条件**（非 T 项候选）——Host 白名单 + Origin 拒跨站 + 突变必红；读面防御性回补由 it37 评估（0.5.0 候选携带） |
| R2（副作用唯一受控路径） | ToolExecutor pipeline + 架构测试 | 写面方法组全部复用既有执行面，不开新通道 |
| 审批等待界（it22） | 非交互 300s fail-closed + `--approval-timeout` | 中继只搬运不放宽（锚 2） |
| 零写面不变量（W1–W4） | 刻意承诺，it36 收口仍在 | W5 显式终结——ADR 记推翻对象与理由（建议编号 0012，四确认时定；notes 现有 0010/0011） |
| jobs 面（W4 T2 延后） | 重访条件 = 用户点名 jobs 视图 | 本预登记列入四确认议程（并站评估） |

## 侦察交付登记（已落盘，2026-11-17）

dsh 三张清单落 `evidence/iteration-38/`（原 `evidence/iteration-37/`，随重编号迁移）；dsh 对照基线 `5badb15009`（[dsh-reference.md](../dsh-reference.md) 口径，tag dsh-v0.2.1-alpha.1；dsh 工作树 HEAD 已动到 fcc0ad4621，取证一律 `git show/grep 5badb15009`，不用工作树文件）。

| 清单 | 文件 | 对四确认最有分量的发现 |
|---|---|---|
| 聊天推进（写面） | [recon-chat.md](evidence/iteration-38/recon-chat.md) | ISession 动词表（prompt queue/steer 双模——JH 无 steer，首版处置列 T 项）；beginSubmission 回声 + SessionRequestId 一次性结算 = 防伪配对先例；**api-request-trust Host 围栏**——回环恒绑 ≠ 浏览器跨站免疫，**已升格 B36 准入条件**（2026-11-17 裁决，原建议 T 项）；dsh 问题面有 claim 制倒计时 vs JH exit 5 无限等（差异登记） |
| 审批中继 | [recon-approval.md](evidence/iteration-38/recon-approval.md) | ApprovalRequestId = service 发 UUID + asked/decided 审计对 + turn-enclosed + 一次性结算（锚 2 令牌形状现成参照）；unavailable fail-closed 归一 + abort 迟到丢弃；**dsh 审批无超时**（grep 零命中实证）——JH 300s 是 it22 自有契约，中继不放宽（**四确认硬约束**，2026-11-17 裁决）；UI = composer takeover 非模态（Enter/Escape + IME 守卫），形状列 T 项 |
| 流式推送 | [recon-streaming.md](evidence/iteration-38/recon-streaming.md) | dsh = WS mux（双向，故需上行字节预算），**无 SSE**；JH 写面 = 一次性 POST → 单向下行 SSE 足够（零新依赖，**推理成立已采纳**）；SSE 若走 `/events` 须显式修约 W4 T1；LoggedEvent seq 天然 cursor ↔ dsh startedAfterSeq 重连基线；轮询备选腿零新面（W2 tail 先例） |

三清单均含：机制要点 + 基线路径引证 + 实际执行命令 + JH 映射/差异注记 + 未读缺口。侦察完成 ≠ 四确认开稿（顺序裁定后开工前置 = it37 0.5.0 发布完成；四确认开稿等用户点名）。

## 审查停点（草案，开工批 + 3 停）

| 停点 | 覆盖 | 交付 |
|---|---|---|
| 开工批（非停） | TwoHostLostProbe 手术（观察账第二次 CI 再现） | 条件等待 + fsync 可观察证据 + 本机重复跑（含 CPU 饱和）+ 聚焦测试绿；随停 1 前提交 |
| 停 1：写面契约 + B36 写面复验 + 审批中继契约 | `workbench/rpc` 写面 seam 形状（T1）、方法组语义（T2/T3）、`--web-write` 开关（T6）、B36 对 POST 的拒绝阶梯与突变必红、ADR 0012 落盘 | 契约设计稿 + packet（首部五行 + 「若只能改一处」）+ 聚焦测试与突变证据 |
| 停 2：中继实现 + 推送 + 生命周期 + UI 写控件 | 审批中继 UUID/一次性结算实现、SSE `/events` 修约与重连续传（T4）、四态状态机、composer takeover + IME（T5） | 实现 + 测试矩阵（含迟到/重复答复、超时、断连重连）+ packet |
| 停 3：装配/同步面 + 真机验收 + 收口 | headless 注入装配、`--help`/`--verify` 计数面、jlink 真机（我跑面 + **用户检视实录**）、同步面（12/05/README/current-state/notes 0012）、CI 四腿 | 验收全勾 + 证据档 + 收口批 |

停点密度说明：写面与审批中继各承载独立契约；若停 1 评审认为两件事耦合过重，可拆为停 1（写面）/停 1b（中继），不降级为「合并放行」。

## 验收清单（骨架，随停点细化）

- [x] 四确认通过并落盘（2026-11-20 评审：ADR 0012 批准、T1–T7 全批，含 T2/T4/R4 三处注记增补）
- [x] 开工批：TwoHostLostProbe 手术（条件等待 + fsync 证据）完成——聚焦 1/0/0/0 + CPU 饱和 5/5 绿 + 突变 M1 必红复绿（[证据](evidence/iteration-38/startup-batch-flake-surgery.txt)）
- [x] ADR 0012 入 notes 并互链（[0012-zero-write-surface-end.md](../../.agents/notes/0012-zero-write-surface-end.md)，2026-11-20）
- [ ] 停 1 评审放行（写面契约 + B36 写面复验 + 中继契约 + ADR 0012）
- [ ] 停 2 评审放行（中继实现 + SSE + 状态机 + UI）
- [ ] 停 3 真机验收：jlink `--serve --web-write` → 浏览器跑通开轮/流式/审批/答复/取消 + 用户检视实录
- [ ] B29/B36 复验（监听地址实录 + 跨站 Host/Origin 拒绝 + 突变必红）
- [ ] 门禁：validate EXIT=0 + 全量 package EXIT=0 + 计数（surefire XML mtime 窗口）+ keyless `--verify`
- [ ] 同步面全勾 + CI 四腿全绿 + run 号回填 + 关账