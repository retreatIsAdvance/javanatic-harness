# Stop 1 Write Seam — 写面、中继与 SSE 契约审查稿

日期：2026-11-20。**未放行、未提交。** 四确认 T1–T7 / ADR 0012 是上位依据；本稿区分可执行接缝与尚未接入的生产宿主，不把停 2/3 能力冒充当前能力。

## 0. Implementation Boundary

本停有代码：rpc 的 ChatControl/四写方法 gate 与参数派发；ApprovalRelay 的注册级 deadline、一次性终态、有界墓碑和 close；headless 的每轮 RelayApprovalPrompt 租约与审批恢复快照；rpc 的可注入 SSE source、完整 codec 编码器、有界队列和 HTTP 传输。零新增模块、零新增发布坐标、零 base 行、零新依赖。

尚未接入：WebChatControl、生产 SessionTail→SseEventSource follower、prompt 租约与 Agent/AppBoot 生命周期桥、CLI --web-write、banner/verify 状态行、浏览器 UI。默认构造 RpcHttpServer 不注入 source，合法 GET /events 仍 404；新构造只在显式注入时开流。未建立第二执行通道，R2 不变。

测试生产 prompt + ApprovalAskPlugin + ToolExecutor 不等于生产 serve 已装配。真实 AssistantChunkCodec 的 HTTP 往返不等于生产持久化 follower 已接线。精确命令、测量面、EXIT 和最终源码匹配见 [证据档](stop1-tdd-evidence.txt) 与 [审查包](stop1-review-packet.md)。

## 1. ChatControl Contract

```java
public interface ChatControl {
    String start(String text);
    void send(String session, String text);
    void cancel(String session);
    void respondApproval(String requestId, boolean allow);
}
```

接口进 workbench/rpc，生产实现将进 examples/headless（AppBoot 组合点）。WorkbenchRpcWriteTest 不装 agent/AppBoot、无 key、无 headless 依赖。

- chat == null：四方法均 -32601，开放检查先于参数校验；保留既有 long maxResponseBytes 构造重载。
- start/send 是 launch，不等轮终局；工具、审批、失败、流式和 exit 5 状态从事件面观察。副作用仍经 ToolExecutor + ApprovalService。
- 在途 send → -32006，不排队、不 steer。既有 inbox NEXT_TURN/NEXT_STEP 是现存 steer 通道，此处拒绝是自觉选择，后续 steer 挂账。
- cancel：无在途轮幂等；未知会话 -32001；沿用 aborted / exit 4。

| 方法 | params | result | 域错误 |
|---|---|---|---|
| session/start | text 非空串 | {sessionId} | -32602 参数；-32603 内部失败 |
| session/send | session、text 非空串 | {ok:true} | -32001 未知；-32006 在途 |
| session/cancel | session 非空串 | {ok:true} | -32001 未知 |
| approval/respond | requestId 非空串、allow 布尔 | {ok:true} | -32007 未知；-32008 已结算；-32009 已过期/取消 |

响应仍经 call() 字节预算；它不是副作用事务回滚承诺。

## 2. Approval Relay Contract

### 2.1 State / Deadline

open(Duration) → PENDING → ALLOWED / REJECTED / EXPIRED / CANCELLED。server 发 UUID，活跃/保留窗内碰撞 fail loud；Request 私有构造，对外 CompletionStage 只读，观察副本不能结算，跨 relay 的 await/poll 拒绝。

登记 open 时以单调时钟固定 deadline，正 Duration 从登记瞬间计时，0/负不设限。settle、await(Request)、poll(Request) 共用截止；now - deadline >= 0 从边界起 EXPIRED，等待线程未调度也不能迟到 ALLOW。重复等待不重新计时，nanoTime 有符号回绕以差值处理。deadline 前已结算的终态不被后续截止覆盖。包内 LongSupplier 是测试缝，生产固定 System.nanoTime；不开放可变全局时钟。

状态迁移、活跃表移除和墓碑登记在同一短锁，future 一律锁外通知，不在状态锁中执行观察者。成功终态重放 -32008；EXPIRED/CANCELLED 重放 -32009；未知 -32007。两个并发 respond 至多一赢家；两者均在截止之后则双 -32009。expire/cancel 在保留窗内幂等且不覆盖首个终态。

### 2.2 Retirement / Close

活跃表只保存未决 Entry；终态立即移除重对象。墓碑只含 id/outcome/retiredAt，最多 4096，最多保留 600 秒；FIFO 容量淘汰或 TTL 到期后该 id 返回 -32007。TTL 在访问/迁移时惰性清理，无清扫线程；无流量时最多仍留 4096 个轻量墓碑，不承诺准时物理销毁。旧 Request 仍可观察原终态，观察不能复活墓碑。

close 与 open 同锁：关闭后不能新登记，取消所有 pending，清空两表；旧 id 返回 -32007，旧句柄仍可观察 CANCELLED。活跃请求总数未另加上限，宿主必须限制在途轮/并行请求；本停不擅自引入新拒绝域码。

### 2.3 Production Prompt / Recovery

RelayApprovalPrompt 是 examples/headless 包内的每 session/turn 不可复用租约，构造参数为共享 relay、Id<Session>、turn、configuredSeconds、interactive；宿主将每轮创建、通过 ApprovalAskPlugin 注入，并在该轮 finally close。当前不自动接 Agent，不能宣称宿主生命周期已经验证。

ApprovalService.ApprovalRequest 只有 toolName/summary/arguments，不含 session/turn；本适配器显式绑定，不靠 ThreadLocal/ScopedValue 继承工具虚线程上下文，不改 core 接口。Pending 包含 requestId/session/turn/toolName/summary，未额外发送 arguments；**summary 本身可含工具参数，这不是脱敏边界**。

ask 初始及等待/返回/异常翻译前检查 checkAbort，同时 onCancel 快速唤醒；默认 no-op onCancel 由 50ms 轮询补齐。relay.poll 对登记 deadline 负责，future 只做短等待。ALLOWED 返回 true；REJECTED/EXPIRED 返回 false；取消/租约关闭抛 AbortedException，不伪装成拒绝。finally 撤销未决并清空取消监听引用盒；AbortSignal 无注销接口，因此监听盒不保留重载荷。

恢复为实例内 subscribe 与初始完整快照原子登记；pending 表变化、revision 与订阅在同锁中线性化。每订阅容量 1，合并成最新完整 Snapshot(revision,pending)，不是丢增量；客户端完整替换 pending 列表。读取排除已完成 future，关闭发布最后空快照并唤醒。revision 不是 LoggedEvent.seq，控制帧不能带 SSE id。不同 session/turn 租约独立，生产桥须只订阅目标会话，不混投。

### 2.4 Timeout / Modes

Duration 唯一来源为 ApprovalPrompt.effectiveIdleTimeout(configuredSeconds,interactive)：未配置且非交互为 300s，显式秒数优先，0 不设限。自定义 prompt 必须消费此值，不能假定 ApprovalAskPlugin 代管超时。

真时钟哨兵只在最终源码跑一次：生产 RelayApprovalPrompt → ApprovalAskPlugin → ToolExecutor，真实等待至少300秒，工具执行0、error result、迟到ALLOW -32009。deadline 破坏矩阵用可注入时钟快速测试；1s覆盖值、常量断言、纳秒用例均不能替代真钟。历史300.046872625秒属于旧测试适配器，仅作历史，不冒充当前生产prompt证据。

保持 ask/auto/deny 原义；只有 ask 使用人闸中继，不新增 web-write 必须 ask 的限制。UI 只提供允许/拒绝，cancelled/unavailable 归 host。

## 3. Common Fence

公共 dispatch 顺序 Host/Origin → 方法/路径 → POST / Content-Type → JSON-RPC。B36 适用所有路径和方法；GET /events 不得绕过。B37 仍只要求 POST / 为 application/json，GET 不要求 JSON 请求体，不新增 JSON 类型要求。这不是 SSE 例外旁路。

四写方法必须有恶 Origin403/缺失或错误类型415/合法派发正例；SSE 必须有恶 Host/Origin403 且 source.open=0，以及合法GET200正例。只读未注入404不能替代该正例。B29 回环绑定不变，不增加 bind 参数，源码使用 InetAddress.getLoopbackAddress，不声称硬编码IPv4。

## 4. SSE Contract

### 4.1 Source / Route

RpcHttpServer 保留原构造，增加 (Handler,SseEventSource,SessionCodecRegistry) 注入。source.open(Id<Session>,afterSeq) 返回 Opened：head() 是打开时稳定已验证的日志头（空为-1），next() 返回 Optional<SseFrame>，空表示永久EOF；close 幂等并唤醒next，不等socket writer。source负责打开时快照与增量无缝衔接，不能只取快照后再订阅。生产 follower 的这项证明留停2，不由内存夹具代替。

仅注入时 GET /events?session=<id> → text/event-stream; charset=utf-8 + Cache-Control:no-store。HEAD /events405（Allow:GET），POST /events404；未注入保持404。根路径UI/JSON-RPC原义不变。

未知会话404、参数或游标错400、open时发现日志损坏500，均在200头之前返回具名错误。next/codec 在200之后失败时不能改HTTP状态，记录具名日志并断流；未知codec不静默投影降级。

### 4.2 Frames / Cursor

SseFrame 为 Persisted(LoggedEvent) / Control(JsonValue.Obj) 联合。Persisted 帧 id=真实seq，data完整保留 seq/type/ignorable/data；data由实际SessionEventCodec编码，不使用丢弃扩展载荷的timelineEntry。中文/换行经JSON转义，不能注入伪id行。真实AssistantChunkCodec的delta/tool-use/usage/finish四变体均需HTTP往返验证。

Last-Event-ID header优先 lastSeq query，缺省-1，回放排他区间(afterSeq,head]包含seq0；非法/<-1/超head400，不钳位。重复参数、非法URL编码拒绝。source输出必须严格递增；transport拒绝重复/倒退。Control和15秒keepalive注释无id，不推进浏览器重连游标。控制消息恢复依靠完整权威快照，不靠重放临时消息。

### 4.3 Backpressure / Close

每连接有一个source reader与HTTP writer；队列最多64帧且编码后字节合计不超过8MiB，任一超帽即主动断流，不能丢帧继续或同步阻塞生产者冒充队列。EOF排空已入队帧再终结；溢出直接丢弃本连接队列，浏览器按最后收到id重新接续。

连接关闭撤销source、exchange、reader/writer；server.close先停止底层socket再回收连接，避免欠缺GET请求体的排水阻塞，包括open/close交错时迟回的Opened，不等下一次写失败。正常EOF的自有writer仅在未被中断时临时中断请求体关闭，再清除人为标志以保留正常HTTP末尾chunk；既有中断不被清除。TCP断连检测仍依赖写入/心跳，阻塞socket不承诺心跳准点。队列预算不是连接数量或编码前对象大小总预算，生产宿主需另限资源。

## 5. Opt-in / R4 — Pending Ruling

已批T6不动：缺省只读；--serve --web-write才开写面；单独--web-write exit2。当前HeadlessMain:492–493明确拒serve+verify；--web-write尚未解析。以下是**待裁目标矩阵，不是现状或已批准实现**。

建议仅为verify增加纯预检组合，verify优先：不监听、不创建运行轮、不执行工具，只验证计划装配状态。既有policy治理失败仍exit1，用法错误exit2。预检不要求模型API key，也不等于运行连通性验证。

| 参数组合 | 建议执行面 | web-write状态 | 端口/轮/工具 |
|---|---|---|---|
| --verify | 既有组合预检 | off | 均无 |
| --serve | 只读server | off（banner） | 仅监听 |
| --serve --web-write | 写面server | on（banner） | 监听；请求到来才开轮 |
| --verify --serve | 新增纯预检组合 | off（verify） | 均无 |
| --verify --serve --web-write | 新增纯预检组合 | on（verify） | 均无 |
| --web-write | 用法错误exit2 | 不装配 | 均无 |
| --verify --web-write | 用法错误exit2（缺serve） | 不装配 | 均无 |
| --verify --serve --port=N | 仅检验N取值，不探测占用/绑定 | off | 均无 |

保持其他既有互斥（任务文本/resume/sessions/session/workbench/dump），不借此次放宽。替代案是保留serve/verify互斥：verify只能证明自身off，on全归serve banner；这弱于R4完整解释。**未裁前不修改CLI**。

## 6. Explicit Exclusions

不做steer/prompt queue、附件、多用户/认证/TLS/远程暴露、jobs、问题超时、WebSocket、热重载、node构建链或执行面重构。IME composing Enter不发送、composer takeover、exit5无限等待均沿用T5，不重新裁决。

## 7. Verification / Stop

已执行与待执行步骤以 [packet](stop1-review-packet.md) 和 [证据档](stop1-tdd-evidence.txt) 为准。测试类先核存在，再用Java25/Maven3.10.0、-pl examples/headless -am、准确逗号并集执行；XML按mtime窗口记录tests/failures/errors/skipped和EXIT，不拼不同轮。突变只改生产目标，不改测试字面阈值，逐字节还原并复绿。

局部全绿不表示停点放行。本停不commit、不push、不进入停2，直到用户新鲜点名裁决。
