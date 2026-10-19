# it27.1 停 2 review packet：mcp/http 协议面 + 夹具故障矩阵 + T7 帧帽

- 基线：`2ee5514`（it27 收口，已推送）；停 1 已按放行提交
- 范围：`mcp/http` Streamable HTTP Provider（协议面 / 配置校验 / 装配）+ HTTP
  夹具与故障矩阵 + 两 transport 8 MiB 帧帽；**不 commit、不进停 3**
- 工作树：`mcp/pom.xml` 注册新模块；`mcp/stdio` 3 处（有界行读、
  `BoundedLineReader`、T7 恢复用例）；`mcp/http` 13 个新文件（4 生产类 +
  `module-info` + `pom.xml` + 5 测试类 + 2 夹具）；本停点两份取证档；计划锚点与
  停点表已标「停 2 完成，待放行」
- 证据：`docs/plan/evidence/iteration-27.1/stop2-http-focused.txt`

## 1. 交付面

### 1.1 模块与装配（`McpHttpPlugin` / `module-info` / `pom.xml`）

- 插件 id `mcp-http`，`requires "mcp-registry"`；只向 `McpClientService` 注册连接，
  不提供该服务——与 `mcp-stdio` 同时在场，消费面 transport 中立（T3 形态）。
- 零 server 即零噪声：`servers` 缺省/空 → 直接 return，不建 `HttpClient`、不起
  线程、不发请求（由 `absentServersKeyLoadsSilentlyWithEmptyRegistry` 钉住）。
- `HttpClient.newBuilder().followRedirects(NEVER).connectTimeout(10s)`；建连超时与
  单请求 timeout 分离（连接不可达快速失败）。
- scope 回收 LIFO：先注册的 `httpClient::close` 最后执行；连接先关（停通知 +
  DELETE），客户端后收。装配中 scope 已被并发关闭 → 自收当前连接并 fail loud
  （与 stdio 同口径的廉价护栏）。
- 退避档：缺省 500ms→1/2/4/8/16/30×4（共 10 档，数组长度 = 最大尝试次数）；
  测试注入短档。
- `module-info`：`requires kernel / kernel.config / mcp / llm.llm / jackson.databind
  / java.net.http`；`provides Plugin`；`exports` + `opens` 同包（JUnit 反射）。
  Jackson 仅此边界模块（第六边界，与设计 02 一致）。

### 1.2 配置校验（`McpHttpServerConfig`）

`servers[]` 条目键白名单 `id / url / headers / timeoutSeconds`；未知键、缺键、
坏值、重名 server id 全部 fail loud 且带 `servers[i]` 定位：

- `id`：kebab-case `[a-z][a-z0-9]*(-[a-z0-9]+)*`；拒含 `__`（工具名
  `mcp__<server>__<raw>` 的歧义防护与 stdio 同口径）。
- `url`：仅绝对 `http/https`、必须有 host；拒 userinfo（凭据只走 headers）、拒
  fragment（不上线，写了就是误配）。
- `headers`：值必须字符串；拒传输保留头（`host / content-length / content-type /
  accept / mcp-session-id / mcp-protocol-version / connection / transfer-encoding`）、
  拒非法 token 名、拒 CR/LF/控制字符、大小写不敏感重名 fail loud；按原样发送。
- `timeoutSeconds`：Number 或可 trim 数字串；必须正数；缺省 60s。

**已知延后**：URL **敏感 query 参数名**（及其词的 `auth`/`cookie` 扩充）不在本停点
实现——它与 `ConfigValues.isSensitiveKey` 单源化绑定，按计划锚点在停 3 落地
（类 Javadoc 已写明；计划取证表 `stop3-cap-and-masking.txt` 也列了「URL/头校验」）。
本停点的 URL 校验只覆盖 userinfo/fragment/绝对性。

### 1.3 会话协议面（`HttpRpcSession`）

`request()`：POST JSON-RPC；`Accept: application/json, text/event-stream`；
`Content-Type: application/json`。响应按 `Content-Type` 分流：

- `application/json` → 有界读 + `ObjectMapper.readTree`；
- `text/event-stream` → 有界行读，匹配 `id` 的事件返回；`event:/id:/retry:`
  忽略，`:` 注释/keepalive 忽略；
- 其他 Content-Type → `McpProtocolException`（不猜）。

其余协议面：

- **会话头**：initialize 响应捕获 `Mcp-Session-Id`（空头忽略），其后全部请求回传；
  同会话内 id 变化 → 协议错误（防会话混用）。
- **协议版本**：initialize 请求带 `2026-07-28`；响应必须同版本，否则启动 fail loud；
  initialize 后的请求带 `MCP-Protocol-Version`。
- **GET 通知通道**：405 → WARN 降级（明确不轮询）；404 → 会话失效（invalidate）；
  其他非 200 / 流关闭 → WARN 降级。`notifications/tools/list_changed` 交给连接层；
  server 请求 `ping` 应答空 result，未知方法回 `-32601`。
- **DELETE 收尾**：2s 超时尽力发送；失败只 DEBUG（关闭路径无补救面）。
- **重定向**：3xx（`Redirect.NEVER` 下收到的裸响应）→ 连接面失败，不跟随。
- **404**：关闭响应体 → `invalidate` → 抛 `session expired`（连接层重连；`tools/call`
  不自动重试）。
- **超时**：`HttpRequest.timeout` 只覆盖响应头；正文由 `BodyGuard` 看门狗按 timeout
  关流唤醒。JDK 自带 `HttpTimeoutException` 与看门狗同归超时档（`McpTimeoutException`），
  **不失效会话、不触发重连**。
- **取消**：`AbortSignal.onCancel` 取消 future / 关正文流；`AbortedException` 是正常
  路径；在途请求尽力发 `notifications/cancelled`（响应头前与正文阶段都覆盖）。
- **帧安全（T7）**：`MAX_FRAME_BYTES = 8 MiB`；JSON 响应体按累计字节拦截；SSE 按
  **单事件累计 UTF-8 字节**（多行 `data:` 之间计换行）拦截；行读另有字节上限。
  超帽 = 该请求 `McpProtocolException`，HTTP 请求互相独立，会话保持 CONNECTED。
- **分页**：`tools/list` 跟 `nextCursor`，最多 100 页；页超限 / 重名工具 /
  缺 `inputSchema` 全部 fail loud（不静默截断清单）。

### 1.4 连接层（`HttpMcpConnection`）

- 状态机与 stdio 同形：`CONNECTED → RECONNECTING → CONNECTED | FAILED`；装配完成
  前断裂走同步异常（不后台重连），装配后断裂才转 RECONNECTING。
- 清单快照 `Catalog(generation, frozenTools, descriptors)` 一次性发布（volatile 读
  一次拿全，无撕裂）；代际只在内容（name/description/schema）变化时 +1，顺序变化
  不算。
- 通知驱动刷新：读线程只置位 + 拉虚拟线程，重复通知合并；结果仅在同一会话上采纳；
  响应畸形 → `invalidate` 断链重连；超时/连接失败保留旧代并 WARN。
- 有限重连：每档重开会话 + initialize + tools/list；内容变化才换代；成功后关闭
  stale 会话（尽力 DELETE）；退避耗尽 → `FAILED` + 原因。
- `call()`：closed/FAILED/RECONNECTING/invalid 快速失败；**`tools/call` 永不自动
  重试**（副作用不重复）；失效判定的窗口期同快速失败，不把调用漂到待弃置会话上。

### 1.5 T7：两 transport 同批

- **stdio**：新增 `BoundedLineReader(maxBytes)`；`JsonRpcPeer` 读循环用它，超帽抛
  `McpProtocolException` → `broke(...)` → 断裂重连（与畸形帧同一路径）。夹具
  `--big-bytes 9000000` 真发单行 9 MB 帧。
- **HTTP**：响应体帽 + 单 SSE 事件帽（见 1.3）；超帽只失败该请求，不失效会话。
- 两档上限同值 `8 * 1024 * 1024`；消费面 256 KiB 结果截断在停 3（分层：transport
  安全上限 > 消费面展示上限）。

## 2. 夹具与测试矩阵

HTTP 夹具：`mcp/http/src/test/resources/mcp-http-fixture/FixtureHttpServer.java`，
单文件、零依赖、`java --add-modules jdk.httpserver` 直跑；marker 一次性故障注入
（一个进程文件支撑重连/换代/超时全矩阵），虚拟线程执行器。旗标：

```text
--port-file / --session-id / --protocol-version
--sse / --sse-multiline / --tools / --pages / --endless-pages / --big-bytes
--variant-tools / --garble-once / --wrong-id-once / --missing-result-once
--rpc-error-once / --delay-once / --stall-body-once / --expire-once / --expire-on
--redirect-once / --notifications / --list-changed-once / --garble-refresh-once
--record-call-to / --record-cancel-to / --record-delete-to / --record-headers-to
--record-get-to / --record-init-to
```

跨 transport 撞 id 用例另起 `MinimalStdioServer.java` 真子进程。

测试矩阵（最终树）：HTTP 49 例 = 配置 22 + 调用 18 + 恢复 4 + 装配 5；stdio 39 例
（含新增 T7 超帽恢复例）；mcp/mcp 3 例。判别点：JSON/SSE、会话头时点、GET 405
降级、重定向拒绝、畸形/错 id/缺 result/rpc error、响应头超时、正文停滞超时、
响应头阶段取消、正文阶段取消、1 MiB 真帧、8 MiB JSON 超帽、8 MiB SSE 事件超帽、
分页合并/页帽、404 换代、通知换代、刷新畸形重连、退避耗尽、插件装配/零 server/
scope DELETE/跨 transport 撞 id、config 全矩阵。

## 3. 三列映射（计划 → 承载 → 证据）

| 计划条目（停 2 范围） | 承载与行为 | 证据（落盘） |
|---|---|---|
| Streamable HTTP 客户端（POST JSON/SSE） | `HttpRpcSession.request` 分流 + `readJson`/`readEventStream` | call：json/sse 两例 |
| 会话头与协议版本 | `captureSessionId`/`requestBuilder`/`open` 版本校验 | call：requestHeaders 例 |
| GET 通知通道与降级 | `startNotifications`/`notificationLoop`；405 WARN、404 invalidate | call：405 例；recovery：list_changed 例 |
| 404 会话失效 + 有限重连 | `execute` 404 → invalidate；`reconnectLoop` 换代 | recovery：sessionExpiry 例 |
| DELETE 收尾 | `closeBestEffort` | call：json 例；plugin：scopeClose 例 |
| 超时 / 取消 | `execute` 头阶段看门狗 + `BodyGuard` 体阶段看门狗 + cancelled 通知 | call：timeout / bodyStall / cancel / cancelDuringBodyStall |
| 重定向拒绝 | 插件与测试 client 均 `Redirect.NEVER`；3xx fail loud | call：redirect 例 |
| 配置校验 | `McpHttpServerConfig` 全矩阵 | config 22 例 |
| T7 8 MiB 帧帽（两 transport） | HTTP 体/单事件帽；stdio 有界行读 | call 两例 + stdio recovery 一例；突变 M4/M5/M6/M7 |
| 分页与清单完整性 | `fetchTools` 游标、100 页帽、重名/schema fail loud | call：paginated/endless 两例 |
| 装配与注册表 | `McpHttpPlugin` + `McpClientService.register` | plugin 5 例 |

## 4. 停 1 三条备注处置

1. **突变 3 的「红」改为断言红**：本停点 M3（404 不置失效）实测
   `Failures: 1` + `AssertionError: timed out awaiting: reconnected with swapped catalog`
   （10.86s 内收口），不是挂起/超时器红。
2. **设计 02 模块表补「插件 id `mcp-registry` 的理由」一句**：未在本停点动手——停 2
   不改设计文档；该句随停 3 的 02 同步批次一起落（与 40 模块/44 包/mcp 行同提交）。
3. **stdio 侧 T7 帧帽**：确认停 1 未做；本停点与 HTTP 同批实现并钉死
   （`overCapFrameCascadesAndReconnects`）。

## 5. 偏离与未覆盖

- **D1（延后，计划内）**：URL 敏感 query 名校验与 `auth`/`cookie` 词表扩充、
  `ConfigValues.isSensitiveKey` 单源化在停 3（与消费面脱敏同批）；本停点只覆盖
  userinfo/fragment/保留头/控制字符。含义：当前 `mcp-http` 允许 URL query 携带
  token 之类参数，配置面不 fail loud——**这是停 2 的已知边界**，不是遗漏。
- **D2（停点边界，沿用停 1）**：`bundle/base` / `examples/headless` 尚未挂
  `mcp-http` 行（计划停 3），装配产物中该插件尚不可发现；停 3 补两行后 D2 转绿。
- **D3（测试依赖）**：`mcp/http` 的 pom 有 **test-scope** `harness-mcp-stdio` 依赖，
  只为跨 transport 撞 id 的装配级用例；生产依赖集不含 stdio。
- **D4（夹具细节）**：`--sse-multiline` 只拆 >4 MB 响应为两条 `data:` 行，小响应
  保持单行合法 JSON（否则 initialize/tools-list 也会被拆，矩阵会测不到目标）。
- **D5（协议取舍）**：GET 通知通道 405 / 流关闭不轮询（计划明确）；SSE 可恢复性
  （`Last-Event-ID`）不实现——断线走新会话重连 + 清单换代，不做事件流续传。
- **D6（超时上界）**：头阶段与体阶段各有一个 timeout 看门狗，最坏总耗时约
  2×`timeoutSeconds`；换取「头到体分开收口、体停滞可唤醒」的确定性。
- **D7（未覆盖分支，如实列出）**：同会话 `Mcp-Session-Id` 变化、GET 通道 404、
  server 主动请求 `ping`/未知方法、initialize 版本不一致、DELETE 失败路径没有
  直接用例（实现有分支，矩阵未钉）。这些不阻塞停 2 主线，可在停 3 收口或后续迭代补。

## 6. 论证

1. **为什么手写而不是官方 SDK**：T6/T7 的安全属性（拒重定向、拒 userinfo/敏感
   query、保留头/控制字符校验、8 MiB 帧帽）在官方 SDK 上要么白拿不到，要么不可控；
   Jackson 已在 stdio 边界存在，HTTP 手写的边际成本低于引入新运行时。
2. **为什么 `tools/call` 绝不重试**：MCP 工具可产生副作用；HTTP 的 404/断连无法
   区分「服务器没执行」与「执行了但响应丢了」。快速失败把不确定性交给上层，而不是
   用重试制造重复副作用。
3. **为什么超时不算会话失效**：超时是可用性事实，不证明会话不可信；失效会让一次
   慢请求拖垮整个 server 的工具面。只有 404（服务端明确宣告会话终止）与协议畸形
   才失效/断链。
4. **为什么 SSE 需要「单事件累计帽」**：行帽只能约束单行；`data:` 多行可把事件
   做到无界。按 UTF-8 字节累计 + 事件边界清零，才能在流式读里给出内存上界。
5. **为什么两 transport 同批做帧帽**：T7 是传输面不变量；只做 HTTP 会让 stdio 成为
   「更宽松的绕过面」。同值上限 + 同「协议错误」语义，消费面无需区分 transport。
6. **为什么清单代际按内容而非顺序**：顺序变化不改变工具语义；按顺序换代会产生
   无意义的账本重写。连接层只负责「当前已知清单 + 代际」，轮屏障采纳留给停 3。
7. **为什么零 server 直接 return**：配置启停是常态事实；零 server 不应产生客户端、
   线程或网络面，否则「配置为空」也会有可观测噪声（与 stdio 的零进程口径一致）。

## 7. 验证摘要

- 聚焦测试 `mvn -B -pl mcp/mcp,mcp/stdio,mcp/http -am test`：`EXIT=0`；
  mcp/mcp 3 / mcp/stdio 39 / mcp/http 49。
- 突变七杀：SSE 分流 / 会话头 / 404 失效 / HTTP JSON 帽 / SSE 事件帽 / stdio 行帽 /
  正文看门狗关流——全部预期红 `EXIT=1`；还原后最终树复绿。
- `mvn -B -q validate`：`EXIT=0`（仅 spotless 内部 `sun.misc.Unsafe` 警告）。
- 完整命令、输出行、shasum 与未覆盖分支：`stop2-http-focused.txt`。

## 8. 停点声明

停 2 完成，等放行；**未 commit、未进入停 3**。放行后按计划进入停 3（消费面双屏障/
PRE_STEP 落账/256 KiB 截断/缓存 + ConfigValues 词表 + base 两行 + 四处同步面 +
`--verify` + jlink 真跑 + R1 回放 + 文档）。
