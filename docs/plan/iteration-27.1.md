# 迭代 27.1 — MCP 硬化：Streamable HTTP、连接注册表与轮屏障热换代（状态：已收口·2026-10-20）

模块：`mcp/mcp`（Definition：新增连接注册表插件 `mcp-registry`，`McpClientService` 增注册面，`McpServerConnection` 增清单代际；零第三方不变）· `mcp/http`（**新增** Provider：Streamable HTTP，插件 id `mcp-http`；JDK `java.net.http` + Jackson 边界）· `mcp/stdio`（Provider：适配注册表与清单代际，退役会话冻结/DRIFTED）· `mcp/tool`（Consumer：轮屏障换代 + 256 KiB 结果上限 + 映射缓存）· `kernel/config`（敏感词表扩 `auth`/`cookie`，公开 `isSensitiveKey`）· `bundle/base`（装配行）· 行集同步面（`dist/jh` jlink / `examples/headless` / `integration/consumer-sample` / `docs/embedding.md`）· 设计文档 02/03/05/07/12 + `AGENTS.md`。

路线来源：0.3 顺序 2（`docs/design/README.md:160`）——「**MCP Tools**：先 stdio，再 Streamable HTTP」——stdio 已在 it27 交付，本迭代补齐 Streamable HTTP 并收口 it27 挂账。it27 明确挂账面：`02:835`（Streamable HTTP + transport 中立 provides/requires）、`05:648`（统一 MCP 结果字节上限）、`05:652`（T4 热换代与 Streamable HTTP 合并重裁）、`05:656`（dsh 五条随 HTTP 重访）、`iteration-27.md` 修正表（requires 绑死 transport / `entries()` 每请求重映射 / 统一结果上限 / 热换代）。

用户裁定（2026-10-18，AskUserQuestion 四项）：① 迭代范围 = **MCP 硬化全套**（it27.1：Streamable HTTP + transport 中立 + 统一结果上限 + entries 缓存 + T4 热换代重裁）；② HTTP 形态 = **新增 `mcp/http` + 连接注册表**（两 provider 可同时在场，消费面经注册表聚合）；③ T4 = **实现轮屏障热换代（generation swap）**，非维持冻结 + DRIFTED；④ 统一结果上限 = **256 KiB，超限截断**（与 fs/shell 现有缺省对齐，尾附截断标记），非 1 MiB 报错。

## 确认记录（2026-10-18，用户实跑评估后裁定）

- 四项主裁定 + **REQUEST → TURN_STOPPING / PRE_STEP 屏障修正**确认成立（`AgentLoopImpl:410-411` 实核：schemas 先读走、REQUEST 后派发）。
- T5 / T6 / T7 / T9 **按推荐通过**；T8 **改裁**：不加 generation 字段、`mcp/catalog` 事件形状不变。
- 三处补明确（已写入内容节）：① 换代失败路径（预检失败旧代保活、轮 Error、不推 FAILED、下一 PRE_STEP 重试）；② T6 词表与 `ConfigValues.masked` **单源化**；③ catalog 首落账随屏障移到 **PRE_STEP**（admit 之前），锚序测试重排。
- 同意从停 1 开工。

## 四确认（已裁定）

- **内容**（六件）：

  1. **连接注册表与 transport 中立（it27 评审备注 2 的定论）**：
     - `mcp/mcp` 内置插件 `mcp-registry`（precedent：`skill/skill` 的 `SkillPlugin`）：把 `McpClientService` 以既有 `KEY` 提供进共享 scope，并新增 `register(McpServerConnection)` → `Disposable`（同一接口，对齐 `SkillService.register` 先例）。注册按 server id 全局唯一——重复即 fail loud（跨 transport 撞 id 不再可能静默遮蔽）；`connections()` 按 id 排序（稳定面），`connection(id)` 缺省空；注册面只服务于装配期，连接集合随各 provider scope 回收（R3）。
     - `mcp/stdio` 与 `mcp/http` 都 `requires "mcp-registry"`，自身不再 `provide McpClientService.KEY`；启动完连接后逐条注册，回收句柄挂各自 scope。`mcp-tool` 改为 `requires "tools" + "mcp-registry"`（不再绑 `mcp-stdio`）——两个 transport 可同时在场，消费面只经注册表聚合。
     - `bundle/base` 增 `mcp-registry` 行；`mcp-stdio` / `mcp-http` 都以 `servers: []` 缺省在场（零 server = 零噪声：无连接、无网络、无进程、无事件、无工具）。

  2. **Streamable HTTP Provider（`mcp/http`，插件 id `mcp-http`）**：
     - 手写最小 Streamable HTTP 客户端（不引官方 SDK，T1 立场延续）：POST JSON-RPC（`Accept: application/json, text/event-stream`）；响应按 `Content-Type` 分流——`application/json` 单响应 / `text/event-stream` 逐事件解析（`event:` + 多行 `data:`，空行分发；拿到本请求 id 的响应后即可收流）；`initialize` 后捕获并回传 `Mcp-Session-Id`，后续请求带 `MCP-Protocol-Version`（协议版本与 stdio 同钉 dsh 口径 `2026-07-28`，协商不一致 fail loud）；可选 GET SSE 通知通道（`tools/list_changed` 等）；`close` 尽力 DELETE 终止会话。
     - 会话语义：404（会话失效）→ 连接标记断裂、走有限重连（500ms→30s ×10，同 stdio）；**`tools/call` 不自动重试**（避免副作用重复），以工具错误结果/连接异常 fail loud，重连完成后后续调用恢复；`initialize`/`tools/list` 等安全请求可在重连内重做。GET 405 = 服务器不提供通知通道，降级 WARN、主连接不受影响（该 server 的清单变化在重连时对账；不轮询，成本纪律）。
     - 超时（server 级 `timeoutSeconds`，缺省 60，同 stdio）、取消（`AbortSignal` 传播：关闭响应流 + 尽力 POST `notifications/cancelled`；取消是正常路径，不落工具错误结果）、有限重连、错误分档与 stdio 同口径（超时/协议畸形/连接面失败）。
     - 配置（07/12）：`servers[]` 条目键 `id` / `url` / `headers` / `timeoutSeconds`（键白名单，未知键/缺键/坏值 fail loud 带 `servers[i]`）；`url` 仅 http/https、拒 userinfo、拒敏感 query（见内容 5）；`headers` 为静态请求头白名单（值仅字符串，拒 CR/LF 与控制字符）；保留头（`Host`/`Content-Length`/`Connection`/`Transfer-Encoding`/`Accept`/`Content-Type`/`Mcp-Session-Id`/`MCP-Protocol-Version`）由客户端自置，配置面拒绝。HttpClient 固定 `Redirect.NEVER`（凭据不随 3xx 转发）；TLS 走 JDK 默认信任面，不做自定义 trust store/代理配置（不做）。
     - **无子进程**：HTTP server 是远端端点，不存在进程回收；网络 egress 与凭据同样不受沙箱治理（治理边界 = 工具调用面，与 stdio 的进程边界表态并列写进 05/07）。

  3. **T4 重裁：轮屏障热换代（generation swap）**：
     - 契约：`McpServerConnection` 增 `catalogGeneration()`（该连接工具清单的代际，内容变化才递增；初始 1），`tools()` 语义由「会话启动冻结」改为「当前已知清单（可在轮间变化）」；`McpServerState` 退 `DRIFTED`（正常清单变化不再终态化），`McpCatalogDriftException` 更名为 `McpCatalogSwapException`（只在「新代无法落地」时 fail loud——撞名/非法 publicName 等）。
     - 清单刷新：stdio 收 `notifications/tools/list_changed` → 异步 `tools/list` 重取 + 规范化比较，内容变则 `catalogGeneration++`；重连握手遇清单变化同样**换代而非 DRIFTED**；HTTP 由 GET SSE 通知驱动同一路径（无 GET 通道则仅重连时对账）。刷新失败分档：网络/超时 WARN 保留旧代；协议畸形（非法 JSON/缺字段/重名）按连接断裂处理（fail loud → 重连/耗尽 FAILED，不静默吞）。
     - **换代屏障（关键落点）**：只在 `AgentEvents.TURN_STOPPING`（轮排空、关轮前，同步 notifyOrdered）与 `AgentEvents.PRE_STEP`（轮首、admit 之前）比较各连接 `catalogGeneration()` 并原子换代；**绝不在 `AgentEvents.REQUEST` 换代**——`AgentLoopImpl` 在 REQUEST 瀑布之前已读取 `tools.schemas(agentScope)`（`AgentLoopImpl.java:409-413`），在 REQUEST 内改注册表会让本次请求的 schema 与账本代际错位，直接破 R1。PRE_STEP 直订沿用 it26 F6 纪律（apply 期同步订阅，先于首轮 admit）。
     - 换代实现（含失败路径）：预构建完整新快照（publicName / schema 映射 / 快照内去重 / 与注册面现有非本代工具撞名预检）→ **全量预检通过后才触碰注册面** → 摘旧代句柄 → 注册新代（单线程轮屏障下无请求/工具派发，空窗不可观察；免双代机制）。**失败语义**：预检失败（撞名/非法 publicName）时旧代原样保活，抛 `McpCatalogSwapException`，当前轮以 `TurnEndReason.Error` 收口，**不把 server 推进 FAILED**（错的是清单不是进程），下一轮 `PRE_STEP` 重试采纳。实现注记：同一公开名的新旧代无法并存（`ScopedRegistry` 同层重名 fail loud），故「先注册新代、成功后才 dispose 旧代」不能按字面执行；等价保证 = **全量预检先于任何注册面改动**——预检失败零中间态，成功路径在单线程屏障内一次完成。
     - R1 账本：`mcp/catalog` **事件形状不变**（全量快照 `(time, entries)`；不加 generation 字段——日志序即代，fold 末值胜，顺序无歧义）。换代替换注册面后挂 pending；**下一次 `PRE_STEP`（admit 之前、user/message 落账之前）落账**（首代同点落账；变更才落、空清单退役落账；被拒绝的 turn 不落空账）。回放/重建只读账本（零联网零进程）；resume 时以实时清单对账账本——相同不落，不同作为新一代在首个屏障采纳并落账（不再要求重开会话）。
     - 轮内变化一律延后：本 turn 已构建的 schema/请求指纹不受影响；远端工具中途消失 → 调用得到工具级错误结果（fail loud 数据，不假装成功），换代在轮末发生。`FAILED`/`RECONNECTING` 仍是可用性事实（工具保留、调用快速失败），不是清单变化。单 agent 顺序假设显式登记：并发多 agent 共享 root 注册面时，换代屏障需升级为全局静默点（不在本迭代，子代理面未实装）。

  4. **统一结果字节上限与映射缓存（it27 挂账收口）**：
     - `mcp-tool` 行配置 `maxResultBytes`（缺省 262144 = 256 KiB，正数；与 fs `maxReadBytes` / shell `maxOutputBytes` 同口径）——在消费面调用桥对 `McpCallResult.content`（成功与工具级错误同待遇）按 UTF-8 **完整字符边界**截断，尾附 `" (output truncated)"`（与 `FsService.READ_TRUNCATED_MARKER` 同文本）；截断是内容事实，不是错误。
     - transport 读路径保持 it27 口径（1 MiB 单帧真帧无截断）；若 T7 通过，另加 8 MiB 单帧安全上限（超限协议错误/断裂重连），二者分层：传输层防无界内存，消费层定模型可见上限。
     - entries 缓存：schema 映射与 publicName 归一按 `(serverId, rawName, schema)` 缓存，跨代复用；只在换代替换时重算变化项，REQUEST 不再全量重映射（it27 评审备注 3 收口）。

  5. **凭据与 HTTP 配置硬化（新增安全面，随 HTTP 首次出现）**：
     - `ConfigValues` 敏感词表由 `apikey|token|secret|password|key` 扩为再含 `auth`、`cookie`（子串、不区分大小写、宁多掩不漏掩）——覆盖 `Authorization` / `Cookie` / `X-Auth-*` / OAuth 类键；公开 `ConfigValues.isSensitiveKey(String)` 供 HTTP URL 校验复用。**单源化**：`masked` 的递归脱敏与 HTTP 配置校验共用同一个 `SENSITIVE_MARKERS` 常量，不另起一份词表（防「MCP 检查点已脱敏、会话 header 却明文」劈叉）。dump 与 header 写入的路径不变（键与键序保留、非字符串键 fail loud）。
     - `mcp-http` URL 规则：拒 userinfo（`https://user:pass@host`，fail loud）；query 参数名命中敏感词表 → fail loud（引导凭据只走 `headers`，不把 token 写进 URL/清单）；`headers` 保留头拒绝、CR/LF/控制字符拒绝、键名大小写不敏感去重 fail loud。
     - 诊断/错误文本不回显 header 值与会话 id 全量（前缀脱敏）；`--verify` / dump 只出现 `****`。

  6. **装配、同步面与取证**：
     - `bundle/base` 增 `mcp-registry` + `mcp-http` 两行（`mcp-http` 缺省 `servers: []`）；base 行 30 → 32 = **19 可配 + 13 无配**（`mcp-tool` 因 `maxResultBytes` 由无配转可配，`mcp-registry` 无配）；`--verify` 示例 enabled rows 25 → 27、discovered 30 → 32，`mcp:` 行点名在场四行与工具（`mcp__*`，工具恒非豁免）。
     - 行集同步面：根 `pom.xml`（`mcp/http` 注册）、`dist/jh` jlink 模块清单、`examples/headless` 叠加依赖、`integration/consumer-sample` 闭包、`docs/embedding.md`（mcp 三坐标 → 四坐标：mcp-mcp 契约件 + mcp-stdio / mcp-http / mcp-tool 三行）。push 前 `verify-consumer.sh candidate`。
     - 夹具与测试矩阵：stdio 夹具沿用并加换代档（`list_changed` 通知 / 重连变清单 / 刷新畸形）；新增 HTTP 单文件夹具（`com.sun.net.httpserver`，JDK 直跑；JSON/SSE 双响应、会话头、404 失效、GET 通知流、405、畸形、超时、1 MiB 大帧、`tools/call` 副作用标记）；双 transport 并存 + 跨 transport 撞 id；jlink jh keyless 真跑（HTTP + stdio 混合、两轮换代、审计与 `--verify`）；R1 回放跨代；**锚序测试重排**：`loopAppendsCatalogBeforeFirstRequest` 随首落账移到 `PRE_STEP`——断言 catalog 先于 `user/message` 与 `llm/request`（同步于 admit 之前）；停 1 评审备注 1 承接：通知刷新用例断言化（先等夹具侧刷新完成信号，再纯断言代际递增；超时仅兜底）；模型在环 runbook 待回填（it26/it27 同款，不伪造）。
     - 文档同步：`02`（模块/seam/依赖图/Jackson 白名单/ dsh 映射；模块表补 `mcp-registry` 命名理由——机制名 = 独立可发现聚合插件，非笔误）、`03`（`mcp/catalog` 按代全量快照、PRE_STEP 落账、resume 对账；无 generation 字段）、`05`（§7.1 双 Provider + 注册表 + 换代 + 结果上限 + 凭据面）、`07`（mcp-http 配置键、mcp-tool.maxResultBytes、脱敏词表、base 行与 verify 计数）、`12`（40 模块/44 包、seam 行、配置键、事件 schema、迁移注记、base 32 = 19+13）、`AGENTS.md`（收口）、`docs/embedding.md` 计数。

- **目标**：MCP 从「stdio 单 transport + 会话内冻结」硬化到「stdio 与 Streamable HTTP 双 transport 可并存、清单可在轮间安全换代、结果有统一字节上限、凭据面可脱敏」；R1–R4 零新范式，it27 全部挂账在本迭代收口或明确再挂。路线「先 stdio，再 Streamable HTTP」以真跑与测试矩阵取证。

- **验收问题**（README §Validation 句式）：*同一套 MCP 工具服务器，从「只能本机 stdio、清单一次冻结」变成「stdio 与 HTTP 端点可并存、轮间可安全换代、结果有统一上限且治理不破」？*——it27 后用户只能配本机子进程、改清单必须重开会话；it27.1 后：既有 stdio 配置不动，另加 HTTP server 一行即可；工具清单在轮与轮之间换代（轮内绝不变），`mcp/catalog` 每代可直接回放；统一 256 KiB 结果上限；`Authorization`/`Cookie` 不进会话文件。

- **为什么**（现状证据）：
  - **it27 挂账已点名**：`02-module-layout.md:835`（Streamable HTTP 与 transport 中立 provides/requires 挂 it27.1）、`05-capability-seam.md:648/652/656`（统一结果上限、热换代、dsh 五条）、`iteration-27.md:198-202`（requires 绑死 / entries 重映射 / 结果上限）。
  - **transport 绑死是实况**：`McpToolPlugin.requires()` 固定 `Set.of("tools", "mcp-stdio")`（`McpToolPlugin.java:66-68`）；`McpClientService.KEY` 由 stdio 独占提供，kernel 同 scope 重复注册 fail loud（`ScopeImpl.java:54-56`）——不建注册表就无法双 transport 并存。
  - **冻结语义是实况**：`StdioMcpConnection` 重连对账不一致即 `DRIFTED` 终态（`StdioMcpConnection.java:231-237,269-276`）；`JsonRpcPeer` 对 `notifications/tools/list_changed` 只 WARN 不改行为（`JsonRpcPeer.java:275-280`）；`McpServerState.DRIFTED` 为终态（`McpServerState.java:16-23`）。
  - **换代屏障有代码级硬约束**：`AgentLoopImpl` 先取 `tools.schemas(agentScope)` 再走 REQUEST 瀑布（`:409-413`）——REQUEST 内换代必错位；`TURN_STOPPING` 同步 notifyOrdered（`:323-324`）与 PRE_STEP 轮首（skill/tool 直订先例 `SkillToolPlugin.java:92-95`）是仅有的安全点；单线程轮屏障使「免双代」成立。
  - **entries 每请求重映射是实况**：`McpToolPlugin.entries()` 每次 REQUEST 全量 `McpSchemaMapping.map`（`McpToolPlugin.java:202-217`）；清单换代后只需在换代点重算。
  - **结果无上限是实况**：调用桥直接返回 `result.content()`（`McpToolPlugin.java:174-181`）；`ToolExecutor` 无通用字节上限（it27 已订正）。
  - **凭据面缺口**：`ConfigValues` 敏感词表 `apikey|token|secret|password|key`（`ConfigValues.java:21-22`）不含 `Authorization`/`Cookie`——HTTP headers 是第一个把 Bearer/Cookie 搬进会话 header 的新面。
  - **机制先例齐备**：`SkillPlugin` 注册表插件 + `SkillService.register`（`skill/skill`）、`ScopedRegistry` 注册/注销、`ExtensionEvent` + codec、`com.sun.net.httpserver` 假服务端（8 处既有测试先例）、`AbortSignal` 取消链——无需新范式。

- **不做**：
  - **Resources / Prompts / Sampling / Roots / Elicitation**（首版边界不变；HTTP 不改变该裁定；二版随 Resources 重访）；
  - **OAuth / 动态客户端注册 / 远程认证 / 市场目录**（凭据面只支持静态 headers）；
  - **MCP server 端**（JH 不对外暴露自身工具）；
  - **server 配置热重载**（改配置 = 重开会话/进程；与「清单换代 ≠ 配置热重载」并列写清）；
  - **官方 MCP Java SDK / 任何第三方协议框架**；
  - **server 进程沙箱化 / HTTP 网络 egress 沙箱化**（治理边界 = 工具调用面；进程边界表态延续 it27）；
  - **工具级豁免配置面**（MCP 工具恒 `approvalExempt=false`，无出口）；
  - **server instructions 进提示面**；
  - **传输帧无界内存的无限承诺**（T7 已裁：加 8 MiB 单帧上限；与消费面 256 KiB 分层）；
  - **轮询式清单刷新**（只做通知/重连驱动；GET 405 的 server 静默变化不主动发现）；
  - **`tools/call` 的 404 自动重试**（避免副作用重复；安全请求才重做）；
  - **自定义 TLS trust store / 代理配置 / 重定向跟随**（JDK 默认信任面，`Redirect.NEVER`）；
  - **并发多 agent 的换代屏障**（单 agent 顺序假设显式登记；子代理面未实装）；
  - **旧账兼容垫片**（T8 已裁：无 generation 字段，事件形状不变，兼容问题不产生）；
  - **不动命名算法 / schema 白名单 / 审批 / 审计 / 执行路径语义**（T2 归一、`ValueSchema.Opt`、`ToolExecutor` pipeline 全复用；dsh 五条按 T9 结论维持）。

## 待裁

| # | 事项 | 裁定 |
|---|---|---|
| T1 | 迭代范围：单项硬化 vs 全套 | **用户已裁（2026-10-18）**：MCP 硬化全套（Streamable HTTP + transport 中立 + 统一结果上限 + entries 缓存 + T4 热换代重裁） |
| T2 | HTTP 形态：并入 mcp/stdio vs 新增 mcp/http + 注册表 | **用户已裁**：新增 `mcp/http` + 连接注册表；两 provider 可同时在场，消费面经注册表聚合 |
| T3 | T4：维持冻结 + DRIFTED vs 轮屏障热换代 | **用户已裁**：实现轮屏障热换代（generation swap） |
| T4 | 统一结果上限：1 MiB 报错 vs 256 KiB 截断 | **用户已裁**：256 KiB，超限截断（与 fs/shell 对齐，尾附截断标记） |
| T5 | `DRIFTED` 与异常词表：删除/更名 vs 保留重定义 | **用户已裁**：删 `McpServerState.DRIFTED`；`McpCatalogDriftException` → `McpCatalogSwapException`；0.3.0 未发布，无兼容垫片，12 记迁移注记；换代失败路径按内容 3 补明确 |
| T6 | HTTP 凭据面：仅脱敏 vs 脱敏 + URL/头规则 | **用户已裁**：敏感词表加 `auth`/`cookie`；URL 拒 userinfo 与敏感 query；保留头/控制字符 fail loud；`Redirect.NEVER`；**词表与 `ConfigValues.masked` 单源化** |
| T7 | 传输帧安全上限：加 vs 不加 | **用户已裁**：加 8 MiB 单帧上限（stdio 行 / HTTP 响应体或单 SSE 事件；超限协议错误，stdio 断裂重连、HTTP 请求失败），与消费面 256 KiB 分层；文档写明「传输帧上限 ≠ 消费上限」 |
| T8 | 旧 `mcp/catalog`（无 `generation`）读取 | **用户改裁（2026-10-18）**：**不加 generation 字段，事件形状不变**——全量快照 + 日志序即代，fold 末值胜；顺序无歧义，旧事件兼容问题不产生 |
| T9 | dsh 五条挂账重访结论 | **用户已裁**：维持——`oneOf` 等组合语义仍不映射（排除 + WARN）；`enum`/`const` 仍只提示不执法；`outputSchema` 仍不消费；`taskSupport` 仍不实现；`_hash8` 撞名退路仍为 hash12/前缀转义。附带结论：dsh 的 HTTP 面整体外包官方 SDK（headers 原样透传、无 redirect/userinfo/敏感词防护），JH 手写路线的 T6/T7 加严是 SDK 白拿安全属性的最低自建替代 |

## 设计增量（ADDED / MODIFIED / REMOVED）（开工前补齐）

**ADDED**：
- `mcp/mcp` 插件 `mcp-registry`（`McpRegistryPlugin`）：`provide(McpClientService.KEY, registry)`；`McpClientService.register(McpServerConnection): Disposable`；server id 全局唯一（重复 fail loud）、`connections()` 按 id 排序、`connection(id)` 缺省空；注册表随插件 scope 收口；
- `McpServerConnection.catalogGeneration(): long`（内容变化才递增；初始 1）；`tools()` 语义改「当前已知清单」；`McpCatalogSwapException`（替代 `McpCatalogDriftException`）；`McpServerState` 去 `DRIFTED`；
- 模块 `mcp/http`（`harness-mcp-http`，JPMS `io.javanatic.harness.mcp.http`，插件 id `mcp-http`）：Streamable HTTP 客户端（POST JSON/SSE、`Mcp-Session-Id`、`MCP-Protocol-Version`、GET 通知流、404 会话失效、DELETE 收尾、`Redirect.NEVER`、超时/取消/重连、错误分档）+ `McpHttpServerConfig`（id/url/headers/timeoutSeconds 校验）；
- `mcp-tool.maxResultBytes`（缺省 262144；UTF-8 边界截断 + `" (output truncated)"`；成功/错误同待遇）；
- `ConfigValues.isSensitiveKey(String)`（公开）＋ 敏感词表加 `auth`/`cookie`；
- HTTP 夹具（单文件、`com.sun.net.httpserver`、JDK 直跑）：JSON/SSE 双响应、会话头、404、GET 通知、405、畸形、超时、1 MiB、副作用标记；
- base 行 `mcp-registry` + `mcp-http`（`servers: []`）；
- （T7 通过）传输帧 8 MiB 安全上限（stdio 有界行读 / HTTP 有界响应读）。

**MODIFIED**：
- `mcp/stdio`：`requires "mcp-registry"`；启动后向注册表注册（不再 provide）；`JsonRpcPeer` 通知回调 → 异步刷新/换代；重连与刷新采用新清单（不再 DRIFTED）；删 `failDrift`/`StdioClientService`；`McpStdioPluginTest` / `McpStdioRecoveryTest` 语义更新；
- `mcp/tool`：`requires "mcp-registry"`；`TURN_STOPPING` + `PRE_STEP` 换代屏障；`PRE_STEP` 落 pending 账本（不改请求期注册面）；`entries()` 缓存与换代重算；结果截断；删 `throwIfDrifted`/逐请求对账 fail loud；测试矩阵扩换代/上限/缓存；
- `kernel/config` `ConfigValues`：词表与 `isSensitiveKey`（03/07/12 同步）；
- `bundle/base`：两行；`examples/headless` `HeadlessMain.mcpText` 认四行；`HeadlessVerifyTest` 计数与断言；
- 根 `pom.xml`、`dist/jh`、`examples/headless/pom.xml`、`integration/consumer-sample`、`docs/embedding.md`：模块/坐标/计数同步；
- 设计文档 `02`（mcp/http 行、依赖、图、Jackson 白名单第六边界、dsh 映射与五条结论）、`03`（按代全量快照 + PRE_STEP 落账 + resume 对账；无 generation 字段）、`05`（§7.1 双 Provider/注册表/换代/上限/凭据；状态与 T4 重写）、`07`（mcp-http 配置、mcp-tool.maxResultBytes、脱敏词表、base 32 = 19+13、verify 27/32）、`12`（40 模块/44 包、seam 行、配置键、事件 schema、迁移注记、base 计数）；
- `AGENTS.md`：现状段（mcp 四坐标 / 40 模块 / Jackson 六边界）与已知坑（HTTP 夹具、换代屏障）收口时同步。

**REMOVED**：
- `McpServerState.DRIFTED`、`McpCatalogDriftException`（更名）、`StdioClientService`、`StdioMcpConnection.failDrift`/重连对账抛漂移、`JsonRpcPeer` 对 `list_changed` 的「冻结只 WARN」行为、`McpToolPlugin.throwIfDrifted`/逐 REQUEST 清单不一致 fail loud、`mcp-tool` 对 `mcp-stdio` 的 requires 绑定。

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `mcp/mcp` `McpClientService` | 增 `register`；Javadoc 从「transport 提供」改「registry 提供」 | ☑（停 1） |
| `mcp/mcp` `McpServerConnection` / `McpServerState` / 异常 | 增 `catalogGeneration`；`tools()` 语义；删 `DRIFTED`；异常更名 | ☑（停 1） |
| `mcp/mcp` 新 `McpRegistryPlugin` + `module-info` | 插件 id `mcp-registry`、provides Plugin | ☑（停 1） |
| `mcp/stdio` `McpStdioPlugin` / `StdioMcpConnection` / `JsonRpcPeer` | requires 注册表 + 注册；刷新/重连换代；通知回调；删漂移终态 | ☑（停 1） |
| `mcp/http`（新模块） | HTTP 客户端 + 配置 + 夹具 + 测试 | ☑（停 2，已提交） |
| `mcp/tool` `McpToolPlugin` | requires 注册表；双屏障换代（预检先于注册面改动）；PRE_STEP 落账；截断；缓存 | ☑（停 3） |
| `kernel/config` `ConfigValues` | 词表加 `auth`/`cookie`；公开 `isSensitiveKey`（与 `masked` 单源） | ☑（停 3） |
| `bundle/base` `bundle.yml` | `mcp-registry` + `mcp-http` 行 | ☑（停 3） |
| `examples/headless` `HeadlessMain` | `mcp:` 行认 `mcp-registry`/`mcp-http`；计数 | ☑（停 3） |
| 根 `pom.xml` / `dist/jh` / `examples/headless/pom.xml` / `integration/consumer-sample` / `docs/embedding.md` | 模块与坐标/计数同步 | ☑（停 3） |
| 设计文档 `02`/`03`/`05`/`07`/`12` + `AGENTS.md` | 上表同步（同一提交） | ☑（停 3） |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1（承载契约 + 共享词表） | `mcp/mcp` 注册表 + `catalogGeneration` + `DRIFTED` 退役/异常更名 + `mcp/stdio` 适配（通知/刷新/重连换代） | ☑（已提交，评审记录 `evidence/iteration-27.1/stop1-review-round1.txt`） |
| 停 2（新协议面） | `mcp/http` 客户端 + 配置校验 + HTTP 夹具与故障矩阵（会话/SSE/404/DELETE/超时/取消/大帧）+ T7 8 MiB 帧帽（stdio 有界行读 + HTTP 有界响应/单 SSE 事件，两 transport 同批） | ☑（已提交，评审记录 `evidence/iteration-27.1/stop2-review-round1.txt`） |
| 停 3（消费面 + 装配 + 验收取证） | `mcp/tool` 换代屏障（预检先于注册面改动）/PRE_STEP 落账/结果上限/缓存 + `kernel/config` 词表 + base 两行 + 四处同步面 + `--verify` + jlink 真跑 + R1 跨代回放 + 文档 | ☑（已提交，评审记录 `evidence/iteration-27.1/stop3-review-packet.md`） |

## 停 3 评审记录（2026-10-19）

**总判：通过放行 → 收口**——门禁独立复绿（`mcp/tool,bundle/base -am` EXIT=0）；新流程件（五行 TL;DR、ADR、状态卡）首次实战到位；`adopt` 实现与 packet「若只能改一处」逐行吻合。五个裁决点全部批准：① 账在 `PRE_STEP` 且仅在 `Enter` 后落（拒绝轮不采纳不落账）；② 空清单退役落账、首次全空不落账；③ URL query 先 percent-decode 再判；④ `mcp/http` 补 `META-INF/services`（classpath 消费必需）；⑤ `--verify` 计数 27 rows / 32 discovered、base 32 = 19 可配 + 13 无配。

犯错回填（评审点名，属 it27 重犯）：新 Provider 模块 `module-info provides` 与 `META-INF/services` **双通道缺一不可**——已作为出厂检查单一行写入 `AGENTS.md` 已知坑（it27 stdio / it27.1 http 两次实撞，防第三次）。

## 验收（证据 = 实际执行的命令与结果）

- [x] HTTP transport：初始化/`Mcp-Session-Id` 捕获回传、JSON 与 SSE 双响应、GET 通知、404 会话失效（`tools/call` 不自动重试、安全请求重做）、DELETE 收尾、405 降级、超时/取消/有限重连——`mcp/http` 测试矩阵 + HTTP 夹具
- [x] 连接注册表：stdio + HTTP 同时在场、消费面 transport 中立、跨 transport 撞 server id fail loud、`connections()` 排序稳定——`mcp/mcp` 注册表测试 + 装配测试
- [x] T4 换代：轮间变化在 `TURN_STOPPING`/`PRE_STEP` 采纳、轮内变化延后、请求 schema 与账本代际一致、换代失败旧代保活 + 轮 Error + 下一轮重试、`mcp/catalog` 按代全量快照（日志序即代）、resume 对账采纳——换代矩阵 + R1 回放
- [x] 结果上限：256 KiB 缺省、UTF-8 完整字符、截断标记、成功/错误同待遇、配置覆盖；transport 1 MiB 单帧仍无截断——`mcp/tool` + `mcp/stdio` 测试
- [x] 凭据：`Authorization`/`Cookie`/`X-Auth-*` 在 dump/header 恒 `****`；URL userinfo/敏感 query/保留头/控制字符 fail loud——`ConfigValuesTest` + `mcp/http` 配置测试
- [x] 治理不破：MCP 工具恒非豁免（`--verify`）、`tool/call` + `tool/result` 审计不变、执行仍走 `ToolExecutor` pipeline
- [x] 大输出/帧安全：1 MiB 真帧；T7 通过则 8 MiB 超限 fail loud（stdio 断裂重连 / HTTP 请求失败）
- [x] 行集同步面四处 + 根 pom + `--verify` 计数（base 32 = 19 可配 + 13 无配；示例 27 rows / 32 discovered）
- [x] jlink `jh` keyless 真跑：stdio + HTTP 混合、两轮换代、`mcp/catalog` 与审计落账、启停与零噪声面
- [x] R1：回放/重建零联网零进程；跨代账本 fold == 注册面（逐字段）；请求指纹闭包
- [x] 全量 `mvn -B -q package` + `mvn -B -q validate` + `verify-consumer.sh candidate`
- [x] 设计文档 02/03/05/07/12 + `AGENTS.md` + `docs/embedding.md` 同步
- [x] 模型在环 runbook——真实 `deepseek-chat` 双 transport + 轮间换代 + 审批人闸实跑（2026-10-20；`evidence/iteration-27.1/stop3-model-in-loop.txt`）

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 `docs/plan/evidence/iteration-27.1/`）

| 文件（拟） | 覆盖（停点/验收项） |
|---|---|
| `stop1-registry-and-generation.txt` | 停 1：注册表/代际/stdio 刷新与重连换代聚焦测试 + 突变（注册去重、代际递增、通知触发）+ validate |
| `stop2-http-focused.txt` | 停 2：HTTP 矩阵（JSON/SSE/会话/404/DELETE/405/超时/取消/畸形）+ 1 MiB 帧 + T7 8 MiB 两 transport + 夹具真跑 + 突变 7 |
| `stop3-swap-and-ledger.txt` | 停 3：双屏障换代、轮内延后、换代失败旧代保活、按代全量快照、resume、R1 跨代 fold + 突变 |
| `stop3-cap-and-masking.txt` | 停 3：256 KiB/UTF-8/标记/配置覆盖；auth/cookie 脱敏与 URL/头校验 + 突变 |
| `stop3-assembly-and-verify.txt` | 停 3：base 32 行、四处同步面、`--verify` 27/32、mcp 四行 |
| `stop3-jlink-realrun.txt` | 停 3：jlink jh + stdio/HTTP 夹具真跑、两轮换代、审计、零噪声 |
| `stop3-model-in-loop.txt` | 停 3：模型在环 A/B（真实 deepseek-chat；双 transport + 轮间换代 + 审批人闸） |
| `stop3-closeout-package.txt` / `stop3-closeout-consumer.txt` | 收口：全量 package 计数 + consumer candidate（候选腿 + 负例）EXIT=0 |

---

本文件已于 2026-10-18 裁定：四项主裁定 + 屏障修正 + T5/T6/T7/T9 按推荐 + T8 改裁（不加 generation 字段）+ 三处补明确。自停 1 开工。
