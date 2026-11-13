# 停 1 设计定稿：workbench/rpc 契约 + wire + 绑定面（W3 JSON-RPC server）

四确认已裁（2026-11-13）：T1–T6 全批；三条停 1 设计点登记（游标机制 / 端口占用失败面 / Follower 生命周期）。本档把方法面、错误分档、帧、响应预算与绑定面钉死；实现与测试随 S2（TDD）。

## 0. 交付物与分层

- 新模块 `workbench/rpc`（artifactId `harness-workbench-rpc`，JPMS `io.javanatic.harness.workbench.rpc`）。**零 Plugin**（headless 直依赖接线，it34 tui 先例——四确认裁决）；**零新第三方**（Jackson 已在树、边界归属 +1；`jdk.httpserver` = JDK 模块，镜像 67→68 + `bin/jwebserver` 回归已登记）。
- 三层形状（可测性纪律：纯函数核 + 薄壳）：
  1. **`JsonRpcWire`**（纯函数）：JSON-RPC 2.0 帧——请求解析 → 方法派发 → 结果/错误序列化。词表 record：`RpcRequest` / `RpcResponse` / `RpcError`（全部不可变，无注解）。
  2. **`WorkbenchRpc`**（handler）：构造器注入 `SessionPersistence` + `SessionTail` + `maxResponseBytes`；三方法 face；域错误抛内部 `RpcException`（wire 层翻译成错误对象）。
  3. **`RpcHttpServer`**（薄壳）：`jdk.httpserver` 恒 loopback 绑定；POST / → wire 派发；虚拟线程；`close()` 停止（R3 收口）。

## 1. 方法面三件（v1）

| 方法 | params | result |
|---|---|---|
| `workbench/sessionsList` | `{max: 正整数}`（缺省 20；非正/非数 → -32602） | `{sessions: [SessionSummary 映射], total: N}`——摘要字段：`id` / `createdAt` / `lastActivityMillis` / `eventCount` / `cwd`（可空）/ `busy`；截断语义 = Catalog 原样（`sessions.size() < total` 即截断，消费方渲染） |
| `workbench/sessionViews` | `{session: "<id>"}`（缺失 → -32602） | 五视图 + header 全量快照。**load 语义**（it34 §4 口径）：零新事件、可能修撕裂尾、外来写者先探锁——检视快照面 |
| `workbench/sessionTail` | `{session, lastSeq}`（lastSeq 整数：`-1` = 从头；`< -1` → -32602） | `{events: [seq > lastSeq 的增量], nextSeq: N}`（无增量 nextSeq = lastSeq；-1 对空账回 -1）。**排空至游标后收集**——每请求开 Follower 从头推进到 lastSeq 再取增量，O(n) 在 JH 规模（1593 事件）余量充足（裁决设计点 1：seq 是唯一稳定游标面，Follower 字节游标是私有实现）。**游标超前当前尾** → -32602（日志回滚/错会话只可能是客户端错误，fail loud）。**真零写**（tail 四契约——与 load 的正相反） |

每请求 Follower 开→读→**关**闭环（裁决设计点 3；close 今天空操作，停 2 赋义时即成真释放）；并发请求天然无共享态，无锁 seam 支持并发读——契约 Javadoc 写明。

## 2. 错误分档（JSON-RPC 2.0）

标准码：`-32700` parse error（畸形 JSON）/ `-32600` invalid request（非对象、batch 数组、`id: null` 通知——v1 请求-响应 only，四确认「不做」）/ `-32601` method not found / `-32602` invalid params（max 非正、session 缺失、lastSeq 非法/超前）/ `-32603` internal error（意外 IOException 等兜底，fail loud 不外泄堆栈）。

域码（server error range `-32000..-32099`，翻译自 seam 契约异常）：

| 码 | 名 | 翻译源 | 语义 |
|---|---|---|---|
| -32001 | SESSION_NOT_FOUND | `NoSuchElementException`（load / tail open） | 会话不在盘上 |
| -32002 | SESSION_LOCKED | `WriterLockException` | 写者锁冲突（CLI exit 3 同族，词面沿 12 §6） |
| -32003 | LOG_CORRUPT | `IllegalStateException`（seq 破损 / 未知不可忽略类型） | 日志破损，如实上抛 |
| -32004 | RESPONSE_TOO_LARGE | 序列化响应超预算 | fail loud，不静默截断 |

id 回显：number / string 原样。错误对象恒含 `code` / `message`；`data` 仅域码携带（如会话 id）。

HTTP 层（transport 守卫，协议语义归 wire 层）：非 POST → 405（`Allow: POST`）；POST 非 `/` → 404；响应 `Content-Type: application/json; charset=UTF-8`。

## 3. 响应预算（B35 草案）

- `maxResponseBytes` **构造器注入**（测试可注入小值；缺省恒量 8 MiB——mcp/stdio T7 同族）。序列化结果超预算 → `-32004`。
- B35 行登记四件（boundary-register 维护规则：新协议字段 = 新外部输入）：方法面三字段（max / session / lastSeq）+ 响应预算 + 恒 loopback 绑定（引 B29 转正）+ 实现校验 + 测试。零新配置键（CLI flag 面属停 2）。

## 4. 绑定面（B29 硬线）

- `RpcHttpServer.start(int port)`：`HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)`——**构造面不收 bind 地址**（「无 --host」的 Java 形状：编译期形状即硬线）。运行时断言面：`server.getAddress().getAddress().isLoopbackAddress()`。
- `port = 0` = ephemeral（测试与取证实录用；`--serve` 停 2 用缺省 8787）。
- bind 失败（端口占用）→ `IOException` 上抛——**不翻译不吞**；CLI 停 2 映射 exit 3 + 「换 `--port=` 或结束占用进程」指引（裁决设计点 2：按写者锁冲突同族，勿新造退出码）。
- 虚拟线程 executor（`Executors.newVirtualThreadPerTaskExecutor()`）。

## 5. JSON 面（Jackson 边界 +1）

- `RpcJson` 内部适配器：请求 params **手工 JsonNode 读取**（型错 fail loud）；结果/视图**手工映射**至 JsonNode（domain record 零注解——03 §6 纪律）。
- 视图映射：SessionViews 六组件（header / turns / tools / usage / timeline / messages）全字段；`ToolStep.Pair.arguments` 与 `TimelineDetail.ToolCall.arguments` 是**原始 JSON 串**——按字符串透传（投影层契约，不二次解析）。Message 面：User / Assistant 变体 + ContentBlock 三变体 → `{source: …, blocks: [{type: text|toolUse|toolResult, …字段}]}`——确切字段 S2 以真实 record 形状 TDD 钉死（TextBlock.text / ToolUseBlock{id,name,arguments} / ToolResultBlock{toolUseId,content,isError}）。
- 载荷清洗纪律（mcp/web 同族）：wire 输出不含本地文件路径外的敏感面；错误 message 不含堆栈。

## 6. 测试矩阵（S2，TDD 红绿 + 突变）

纯 wire（13 用例）：合法请求 id 回显（number/string 原样）/ 畸形 JSON → -32700 / 非对象·batch 数组·id null 通知·缺 id·缺 method·错 version → -32600 / params 非对象 → -32602 / RpcException 翻译（域码 + data 携带、标准码原样透传）/ 意外 RuntimeException → -32603「Internal error」不泄堆栈。

handler（23 用例，夹具 = in-memory fakes——module-info 只挂 API 模块，真实 jsonl 后端实现类编译期不可见；撕裂尾容忍等 jsonl 后端既有保证归停 3 真机 curl 实测面）：sessionsList 摘要映射 + 截断 total / sessionViews 五视图 JSON 形状断言 / **域码翻译三件（SESSION_NOT_FOUND·SESSION_LOCKED·LOG_CORRUPT）——域码在 handler 层生成，断言 handler 输出的 errorCode（停 1 评审裁决②：断言面在哪层就在哪层测）** / 意外 IOException → -32603 / method not found → -32601 / sessionTail 游标矩阵（-1·中间·尾部·超前·< -1·缺失·空账）+ 跨批排空累积 / 预算注入小值 → RESPONSE_TOO_LARGE + 预算内通过。

绑定面（7 用例，ephemeral 端口真 HTTP 往返，java.net.http.HttpClient）：loopback 断言（`isLoopbackAddress`）/ bind 失败原样上抛（不翻译不吞）/ 405 / 404 / 端到端三方法闭环 / 并发 tail 请求无共享态 / close 停止。

突变 M1–M4：域码翻译破坏 → `unknownSessionYieldsSessionNotFound` 必红；游标 clamp 摘除 → `tailAheadOfLogYieldsInvalidParams` 必红；loopback 换 wildcard → `bindsToLoopbackAddressOnly` 必红；预算判断摘除 → `oversizedResponseYieldsResponseTooLarge` 必红。写者锁冲突夹具（fake 抛 WriterLockException）承接 it33 停 2 既有手法。

## 7. 同步面（随停点批）

- `02 §6` Jackson 边界清单 +1（workbench/rpc）；02 模块树 rpc 叶子。
- boundary-register：**B29 转正**（预登记 → 正式，四确认裁决）+ **B35** 新行（本档 §3）。
- `12` 导出面：`io.javanatic.harness.workbench.rpc` 新导出包（0.5 增量面；非稳定面，12 不背兼容）。
- AGENTS.md 现状段 / current-state / design README（收口批，随迭代推进）。
