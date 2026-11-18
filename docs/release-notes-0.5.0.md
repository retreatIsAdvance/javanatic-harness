# javanatic-harness 0.5.0

> 本文件 = `v0.5.0` GitHub Release 正文**采用稿**（评审可改）；发布执行（版本翻转 / 签名 / 上传 / tag / Publish）属用户侧，见 [release.md](release.md) §0。

Plugin-based agent harness on the JVM — **Java 25 LTS / JPMS / Maven**. Ports the engineering ideas of DeepSeek Harness (dsh) to the Java ecosystem: **ideas carry over, shapes do not**.

0.5.0 的主题是**个人工作台——只读检视 + 配置干跑，零写面**（0.5 路线四站）：会话不再只能靠回放日志事后拼读，而是有了五视图投影、全屏 TUI、本地回环 JSON-RPC server 与单文件 Web UI 四层只读观察面；组合配置可以在落盘前干跑先验校验。四站都走既有治理通道（事件账只读 fold / R1 可重建 / keyless 可跑），**v1 零 server 写面**——观察与干跑不碰用户数据，写面（聊天推进 / 审批中继 / 取消）留待 0.6（W5），并以读面信任围栏为准入条件。

## 升级必读（0.4.0 → 0.5.0）

**无破坏性变更**——全部为纯增量：新模块 4 个坐标（`workbench` 聚合器 1 + 叶 3：`projection` / `tui` / `rpc`，随 3 个新导出包 `io.javanatic.harness.workbench.projection/tui/rpc` 入场）；既有导出包 `session.persistence.jsonl` 内**新增只读流式 tail seam 类型**（`SessionTail` 四契约 + `Follower.close` 赋义 = 语义终结），既有类型与签名不变；CLI flag **+7**（`--session` / `--session-view` / `--workbench` / `--follow` / `--serve` / `--port` / `--dump`）；新第三方依赖 **JLine 4.6.0 三坐标**（`jline-terminal` + `jline-terminal-ffm` + `jline-native`——`native` 为 `terminal` 模块描述符硬 `requires` 的传递依赖，**不可排除**：jlink 全图解析缺之即败；launcher 烧入 `--enable-native-access=org.jline.terminal.ffm,org.jline.nativ`）；`jdk.httpserver` 由 `static`（测试假服务端）转**真运行时消费**回 jlink 镜像（JDK 模块，非第三方）。**零新事件**（`SessionEvent` permits 不变）、**零新 bundle 行**（base 41 行不变）、**零新配置键**；既有模块导出面、既有 CLI flag 语义**零变化**，keyless `--verify` **34/41/0 不变**。

## 零写面：观察与干跑，不碰用户数据（设计取舍）

- **只读检视**：五视图投影（`turns` / `tools` / `usage` / `timeline` / `messages`）是事件账的纯函数 fold，`--session` / `--workbench` / `--serve` 全部只走 load 读侧或 `SessionTail` 流式读面，**零写路径**——检视会话不改会话；
- **配置干跑**：Web UI 组合编辑器经 `workbench/verifyComposition` 把编辑内容写**临时 profile** 干跑 `--verify` 断言，违规清单逐项返回，**零用户数据写**；下载经 blob 落盘由**用户手动**触发，server 不写盘；`--dump` 是 keyless 只读旁路（不建 agent）；
- **读面也设防**（it37 停 1，B36/B37）：`--serve` 的 Host 白名单 + Origin 同源 + POST 仅收 `application/json` 是**横切前置条件，先于路由、覆盖 ALL 方法含 GET**——Host 检查兼关 **DNS rebinding 对 HTML 页读取面**（不只是 CSRF），Content-Type 闸堵死简单请求绕预检变体；
- **写面留待 0.6**：聊天推进 / 审批中继 / 取消（W5）复用既有执行面、R2 不开新通道，**B36 Host/Origin 信任围栏 = 写面准入条件**；fail-closed 不降格（审批 300s 不放宽）。

## Highlights

- **W1 事件投影与查询（it33）**：`workbench/projection` 五视图纯函数投影 + `--session=<id> [--session-view=<view>]` 只读检视旁路（keyless、零写路径、缺省 `turns`、互斥矩阵、id 不存在或写者锁冲突 exit 3）；`tools` 配对键 = `callId`（与 R2 审计同键），`usage` 视图含「未报告」（usage=null）与「报告为零」分界。
- **W2 全屏 TUI（it34）**：`session/persistence-jsonl` 只读流式 tail seam（`SessionTail` 四契约）+ `workbench/tui` 纯库（渲染纯函数 + 按键解析 + 端子薄壳，JLine 4.6.0）+ `--workbench --session=<id> [--follow]`（检视态 = load→投影一次定值快照 / 跟随态 = tail 每 poll 全量重投影 live tail；键 1-5 切五视图；无真 TTY exit 2、id 不存在或写者锁冲突 exit 3；无 key 可跑）。
- **W3 本地回环 JSON-RPC server（it35）**：`workbench/rpc` 纯库（`JsonRpcWire` 翻译点唯一 + `WorkbenchRpc` 构造注入 + `RpcHttpServer` jdk.httpserver 薄壳 **loopback 恒绑**）+ `--serve [--port=<N>]`（缺省 8787、端口占用 exit 3、SIGINT 首按 close 再按 halt(130)、keyless 可跑）；响应预算 **8 MiB** 超限 `-32004`（B35）；`Follower.close` 赋义 = 语义终结（关后 header/nextEvents 拒读 fail loud）；非回环拒绝 exit 7（B29）。
- **W4 本地 Web UI（it36）**：`RpcHttpServer` 静态分派（仅精确 `/` GET/HEAD 服务单文件 `ui.html`，其余 GET 404、POST 走 JSON-RPC、其余方法 405）+ 单文件 vanilla JS `ui.html`（会话清单 / 五视图 / tail 轮询 + 组合编辑器：`workbench/compositionDump` 只读暴露 + `workbench/verifyComposition` 干跑先验校验 + blob 下载落盘用户手动，**v1 零 server 写面**）+ CLI `--dump` keyless 只读旁路；`ui.html` 441 行（软预算 ~800）、零新模块、零 innerHTML（XSS 纪律）。
- **读面信任围栏（it37 停 1，B36/B37）**：Host 白名单 `127.0.0.1:<port>` / `localhost:<port>`（port = 实际绑定面）+ Origin 存在即须同源（拒 = 403 具名理由行）+ POST `/` 仅收 `Content-Type: application/json`（charset 等参数放行，其余 415）；围栏先于路由，W4 T1 矩阵在放行域内逐字成立；`::1` 显式不在白名单（与 IPv4-only 绑定自洽，fail-closed 不扩面）。

## 已知边界（如实登记）

- **回环单用户无 TLS（B29）**：`--serve` 仅绑 `127.0.0.1`，非回环拒绝 exit 7——本机单用户信任模型，无 TLS / 无多用户 / 无远程访问；
- **零 server 写面 v1**：工作台只观察 + 干跑，不写用户数据；写面（聊天 / 审批 / 取消）= W5 / 0.6，B36 围栏为写面准入条件（重访条件见 [boundary-register.md](plan/boundary-register.md)）；
- **`::1` 不在白名单**：IPv4-only 绑定下 `::1` 在 connect 层即拒（浏览器 happy-eyeballs 回落 `127.0.0.1`），窄白名单 fail-closed，不扩面；
- **dump 行内 config 键序跨 JVM 不稳定**：HashMap 迭代序（it27 以来既有行为，**行序契约未违反**，RPC/CLI 同源比对坐实）——观察项，LinkedHashMap 化挂账搭 B4 生成器家族或下次触 dump 码的批；
- 工作台为 0.5 新增面，稳定面语义以 [12-api-stability.md](design/12-api-stability.md) 为准。