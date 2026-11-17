# W5 侦察清单三：流式推送

日期：2026-11-17。用途：it37（W5 交互工作台）四确认草案输入——预登记锚 3（SSE vs 增强轮询 T 项）。**预登记侦察 ≠ 开工授权。**

## 口径

dsh 本地仓 `/Users/abel_yang/study/deepseek/deepseek-harness`，一律打基线 `5badb15009`（tag dsh-v0.2.1-alpha.1）；读法 `git show/grep/ls-tree 5badb15009`（工作树 HEAD 已动，不用工作树文件）。

## dsh 机制要点

### 1. 传输选型：WebSocket 多路复用，无 SSE

- **精确路径 `/api/remote.mux`**（`REMOTE_STREAM_MUX_PATH`）——一条 WS 多路复用全部 Typert Remote streams。
- **无浏览器 SSE**：grep `EventSource|text/event-stream` 的命中全部是类型名（`TypertRemoteEventSource` / `SessionEventSource` 等），不是 SSE 传输（gateway/index.ts、types.ts、session-controller events.ts 逐条核对）。
- dsh 选 WS 的动因：**双向**——上行（prompt/答复/取消）也走流，故需要每流上行字节预算；下行只是其中一半。

### 2. 帧词表（stream-protocol.ts 全文实读）

| 帧 | 语义 |
|---|---|
| `ready` | clientId 绑定代（连接身份 + 代际） |
| `emit` | NOTIFY 单向事件下行 |
| `waterfall` | eventId + agentId + request——需要回程的逻辑流（审批/问题即此类） |
| `cancel` | 流取消 |

逻辑流 `$events` + **一元 HTTP 回程 `$events/result`**（waterfall 的答复不走 WS 上行，走独立 POST）。

### 3. server 面（stream-server.ts 头 60 行实读）

- `noServer` WS（upgrade 自管）；**心跳 ping，MAX_MISSED_HEARTBEATS = 2** 判死。
- **streamInboxBytes 每流上行字节预算**；peer 升级时固定（连接期不可变）。

### 4. client 面（gateway/src/client/remote-events.ts 头 90 行实读）

- **代际重连**：`registerGenerationSource`——断线重连换代，旧代帧不混入新代。
- 回程 outcome 三态：**result / next（委托）/ rejected**；`REMOTE_EVENT_NEXT` symbol 链尾标记。

### 5. 流累积与重连基线（assistant-stream.ts 头 45 行实读）

- `SessionAssistantStreamAccumulator`：进程内把稠密 chunk 帧 fold 成**每 revision 一份不可变重连基线**（attemptId + `startedAfterSeq` cursor）。
- 重连 follower 拿 **基线 + 增量**，不从头重放——对照 JH W2 TUI 每 poll 全量重投影。

## 对 JH W5 的映射与差异注记

| dsh | JH W5 | 注记 |
|---|---|---|
| WS mux（双向） | **SSE 足够（若裁推送）** | JH 写面 = 一次性 POST JSON-RPC（锚 1），上行不走流 → 只需单向下行推送；jdk.httpserver 长活 GET 响应可承载 SSE，零新依赖、零新工具链（W4 约束延续）。dsh 的 WS 动因（上行预算/多路复用）在 JH 形状下不存在 |
| `/api/remote.mux` 精确路径 | SSE 若走 `/events` | **W4 T1 契约「仅精确 `/` GET/HEAD」须显式修约**（用户锚 3 原文警告）——四确认列修约项，停点重钉 dispatch 测试，不静默破契 |
| ready 帧 clientId 绑定代 | SSE 连接身份 | 单用户回环（B29）无多 client 代际问题；Last-Event-ID 即续传身份 |
| MAX_MISSED_HEARTBEATS=2 | SSE keep-alive 注释帧 | 浏览器 EventSource 原生自动重连 + Last-Event-ID 续传——心跳/重连机制大半是浏览器白送的 |
| startedAfterSeq cursor + revision 基线 | **LoggedEvent seq 天然 cursor** | JH 事件信封本就有 seq；SSE 断点续传 = Last-Event-ID 映射 seq，tail seam（it34）已支持游标增量——投影纯库不必全量重折 |
| waterfall 下行 + `$events/result` 回程 | 推送（T 项）+ `approval/respond` POST | 审批中继的「提示下行 / 决定上行」分离与 dsh 同构（见清单二） |
| 增强轮询备选 | W2 tail 先例 | 若四确认裁轮询：活跃会话短间隔 poll 既有 tail 方法，零路由修约、零新传输——最保守腿；代价 = 延迟与请求量 |

**T 项输入小结**（四确认裁 SSE vs 轮询时的证据面）：

1. SSE 腿成本 = 路由修约（W4 T1）+ 长活响应管理（虚拟线程天然适配）+ keep-alive 帧；收益 = 真流式延迟、浏览器原生重连。
2. 轮询腿成本 = 零新面（tail 方法已在）；收益 = 契约不动；代价 = chunk 级流式感靠短间隔逼近。
3. 两腿都不改事件源（`assistant/chunk` 落账 it14 已在，R1 面不动）——推送只是读侧新消费通道，与 W1–W4「投影纯库」纪律一致。

## 实际执行命令（取证口径）

```sh
D=/Users/abel_yang/study/deepseek/deepseek-harness
git -C $D show 5badb15009:packages/api/gateway/src/stream-protocol.ts
git -C $D show 5badb15009:packages/api/gateway/src/stream-server.ts              # 头 60 行
git -C $D show 5badb15009:packages/api/gateway/src/client/remote-events.ts       # 头 90 行
git -C $D show 5badb15009:packages/api/session-controller/src/assistant-stream.ts      # 头 45 行
git -C $D grep -ln "EventSource\|text/event-stream" 5badb15009 -- packages/client packages/api
git -C $D grep -n "text/event-stream\|EventSource" 5badb15009 -- packages/api/gateway/src/index.ts packages/api/gateway/src/types.ts packages/api/session-controller/src/client/contract/events.ts
```

## 未读 / 缺口

- gateway journal-stream / snapshot-stream 细节未读（会话事件窗的快照/日志分流）——JH 对应物 = W1 投影五视图 + tail，形状已定，不依赖 dsh 细节。
- WS mux 的背压/丢帧策略未深读——JH 若裁 SSE，背压面 = 有界队列 + 慢消费者处置，四确认/停点设计时按 JH 09 并发模型自定，不照搬。