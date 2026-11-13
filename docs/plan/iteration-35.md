# 迭代 35 — W3 JSON-RPC server（状态：四确认已裁（2026-11-13）——四确认通过、T1–T6 全批；T3 两处事实修正 / T6 论据补强；三条停 1 设计点登记；开工停 1）

基线：it34 已关账（0.5 W2 TUI 工作台落地），master `026c397`，工作版本 `0.5.0-SNAPSHOT`。0.5 路线（[总体计划](../design/README.md#phased-evolution-plan)）：W1 事件投影与查询（it33）→ W2 TUI 工作台（it34）→ **W3 JSON-RPC server（B29 预登记：仅 127.0.0.1 回环）** → W4 本地 Web UI（无构建链单文件 vanilla JS）。

**裁决记录（2026-11-13）**：W3 四确认讨论稿评审通过——**四确认通过、T1–T6 全批**；锚点三处对实（B29 原文 / 00 §6 ACP 措辞 / it16 出链史）+ Jackson 零新第三方核验。两处事实修正：T3 体积面「40→41」是 it16 历史数字——**当前镜像实测 67 模块**（`--list-modules` 实录），W3 增量按现值写 **67→68**；jdk.httpserver 回补会**让 `bin/jwebserver` 回归镜像**（模块自带 launcher，无法单独排除，无害但可见），登记进 T3 代价面。一处论据补强：T6 依据补 00 §6 L199「不做 ACP/Codex/Claude Code subagent 桥接」——同一节「ACP 等价物」作形态类比与「不做 ACP 桥接」并存，读侧 v1 非退让而是原文自洽；T2 决策记录 0011 顺带承载该措辞解释（「ACP 等价物 = 形态类比依据，非控制面授权」一句入记录）。装配面（零 bundle 行、headless 直依赖按 tui 先例）与 8787 缺省端口均批；嵌入可组合性若未来有人要，另站再裁，不入本迭代。三条停 1 设计点登记（不停裁，停 1 钉死）：tail 游标机制 / 端口占用失败面 / 每请求 Follower 生命周期。

## 四确认（草案）

- **内容**：①`workbench/rpc` 新模块（artifactId `harness-workbench-rpc`，JPMS `io.javanatic.harness.workbench.rpc`）——JSON-RPC 2.0 over HTTP 本地回环 server（JDK `jdk.httpserver`，仅绑定 127.0.0.1），纯消费 W1 投影（workbench/projection 五视图）+ W2 tail seam（SessionTail 四契约）；方法面 v1 三件 = 会话清单 / 单会话五视图快照 / tail 增量事件（形状停 1 设计钉死）；wire JSON 用 Jackson（边界归属 +1，02 §6 清单同步）。②CLI `jh --serve [--port=<N>]` 独立 flag（缺省端口提案 8787，停 1 钉死；**无 --host 参数**——B29 硬线）；与任务文本 / `--resume` / `--verify` / `--sessions` / `--session` / `--session-view` / `--workbench` 互斥（exit 2，同 --workbench 口径）；无 key 可跑（keyless，读侧）。③装配面按 it34 tui 先例：headless 直接依赖并接线（**零 bundle 行、零新配置键**，`--verify` 计数不变）；dist/jh moditect 清单 +2 行（`io.javanatic.harness.workbench.rpc` + `jdk.httpserver` 回镜像——it16 曾出链，登记体积回补）；缺省组合零监听（socket 只在 `--serve` 创建）。④同步面：02 / 00 / README / AGENTS / current-state / boundary-register（B29 转正 + 新 B 行）+ notes 决策记录（T2）。

- **目标**：0.5 第三站——同一读侧地基的第三个消费面：本地回环 JSON-RPC server，W4 Web UI 的协议地基；00 §6「headless runner + JSON-RPC（ACP 等价物）足够」的形态首现。

- **为什么**：W1/W2 已把读侧收敛成「投影五视图 + 流式 tail」两个纯函数面，server 只是把同一地基暴露成回环协议——「三站不各自手搓解析」的兑现；B29 预登记（it30 路线评审）的授权依据 = 00 §6，W4 需要无构建链 vanilla JS 直连的协议地基。

- **不做**：写侧 / 控制面一切（新会话 / 任务提交 / 审批 / 答复 / 取消——T6）；静态文件服务（W4 面）；动态配置投影（T4）；server 侧增量投影机制（T5）；认证 / 多用户（回环即边界，T1）；远程暴露（B29）；jobs 任务面（W4/W5 面）；SSE / WebSocket 推送（v1 轮询面，tail 按偏移拉取——推送需求出现再裁）；JSON-RPC batch / 通知（v1 请求-响应 only，停 1 钉）。

## 待裁（四确认一并裁定）

| # | 待裁项 | 拟案 | 依据 |
|---|---|---|---|
| T1 | **B29 转正**（预登记 → 正式边界） | 照预登记原文转正，无改动：仅绑定 **127.0.0.1 回环**、无远程暴露、无 TLS（本地回环语义）、单用户（v1 无认证——回环即边界；同机其他用户可达 loopback 属已知语义，如实文档化）——W3 验收硬线 = 监听地址证据 + 非回环访问拒绝断言 | [boundary-register.md](boundary-register.md) B29 行「W3 四确认时转正」；[iteration-30.md](iteration-30.md) L7③ |
| T2 | **Web UI 非目标改判**（决策记录 + 同步面） | 口径反转：本地回环 Web UI 入 0.5 W4（约束已预登记：无构建链单文件 vanilla JS、server 直接服务 .html、不引入 node 工具链——[iteration-30.md](iteration-30.md) L7④）；远程 / 多租户 Web UI 维持不做。决策记录 `.agents/notes/0011-web-ui-non-goal-reversal.md`（背景 / 裁决 / 推翻对象 / 后果 / 证据；**顺带承载 T6 措辞解释**——「ACP 等价物 = 形态类比依据，非控制面授权」一句入记录）；同步面 = [00 §6](../design/00-overview.md)「不做 Web UI」行改写 + [README](../design/README.md) L88 Out of Scope 行 + 全局 grep 旧措辞核对（release.md L125 已同步 0.5 行无需动） | [boundary-register.md](boundary-register.md) B29 行「Web UI 本体的非目标改判另行决策记录（W3 开工前，全局 grep 旧措辞同步）」；[iteration-30.md](iteration-30.md) L5「推迟到 server 段（W3）开工前」；[iteration-32.md](iteration-32.md) L12「W3 前不动」 |
| T3 | **HTTP 层选型 = `jdk.httpserver`（零新第三方）** | JDK 模块直用 + 虚拟线程 executor。代价：镜像模块 **67 → 68**（当前镜像实测 67 模块，`--list-modules` 实录；jdk.httpserver 曾于 it16 出链）+ **`bin/jwebserver` 回归镜像**（模块自带 launcher，无法单独排除，无害但可见）——体积登记按现值写。否决替代：手写 socket HTTP（自有 wire 面 bug 农场，且与「每 transport 私有实现」纪律无关——那是协议帧层的纪律）；Netty / Jetty（新第三方依赖，越边界） | it27.1 夹具先例 8+ 处（`com.sun.net.httpserver`，JDK 直跑）；[iteration-16.md](iteration-16.md) 收窄评估（requires static 闭环、镜像 41→40 出链史 + `bin/jwebserver` 消失） |
| T4 | **动态配置投影归属**（it33 挂到 W3 裁） | W3 **不做**（另站）：配置投影是配置工作台域，与 JSON-RPC 会话读侧不同域，混入稀释承载契约面。重访条件 = W4 配置页需求实况点名 | [iteration-33.md](iteration-33.md) L15「动态配置投影（属后续站，W3 四确认时裁）」 |
| T5 | **增量投影立场**（it34 挂到 W3 裁） | 维持 W2 立场：server 无状态每请求现算；tail 面增量交付事件、消费端全量重投影；**不做** server 侧投影缓存 / 增量机制 | [iteration-34.md](iteration-34.md) L12「增量投影……W3 server 若需要再裁」+ §5 每 poll 全量重投影 |
| T6 | **控制面 / 写侧范围** | v1 只读（清单 / 五视图 / tail）；新会话、任务提交、审批、答复、取消**不做**——00 §6「ACP 等价物」措辞 = server 形态依据，**不是** W3 全量控制面授权。重访条件 = W4 UI 点名单项控制动作需求 | [00 §6](../design/00-overview.md#6-非-target明确不做)「headless runner + JSON-RPC（ACP 等价物）足够」；**同节 L199「不做 ACP/Codex/Claude Code subagent 桥接」**——「ACP 等价物」形态类比与「不做 ACP 桥接」并存，读侧 v1 原文自洽；W1/W2 两站只读立场延续 |

## 停 1 设计点（裁决登记，不停裁——停 1 钉死）

1. **tail 方法的游标机制**：无状态 HTTP + 增量交付 ⇒ 客户端带 `lastSeq` 游标，服务端每请求开 Follower 排空至游标后返回增量 + 新游标——每请求 O(n) 在 JH 规模（1593 事件）余量充足；Follower 字节游标是私有实现，seq 是唯一稳定游标面。形状停 1 钉死。
2. **端口占用失败面**：`--serve` 撞占用端口属环境错误，词面 + exit 码停 2 定义——按「写者锁冲突」同族处理（exit 3 + 「换 `--port=` 或结束占用进程」指引），勿新造退出码。
3. **每请求 Follower 生命周期**：开→读→关每请求闭环（close 今天空操作，停 2 赋义时即成真释放）；并发请求天然无共享态，无锁 seam 支持并发读——这条写进停 1 契约 Javadoc。

随 W3 开工的既有裁决（无需重裁）：it34 停 2 放行附言遗留——`SessionTail.Follower.close()` 今天空操作、装配透传已就位（[stop3-review-packet.md](evidence/iteration-34/stop3-review-packet.md) 突变实证），W3 长驻 server 消费 tail 时赋义（关闭 = 释放游标 / 轮询线程），列入停 2 锚点。

## 锚点

- [iteration-33.md](iteration-33.md) L13：投影层「成为 W2 TUI 与 W3 server 的唯一读侧地基（三站不各自手搓解析）」；L15：「JSON-RPC server 与本地 Web UI（W3/W4，B29 预登记维持）」「动态配置投影（属后续站，W3 四确认时裁）」。
- [iteration-34.md](iteration-34.md) L9：tail seam「W2 TUI 与 W3 server 的唯一读侧地基」；L12：增量投影「W3 server 若需要再裁」、JSON-RPC server（W3，B29 预登记在案）；停 2 放行附言（[stop2-review-verdict.md](evidence/iteration-34/stop2-review-verdict.md) 附注）：Follower 无关闭钩子「W3 长驻面才有意义」。
- [boundary-register.md](boundary-register.md) B29 行：预登记原文 + 转正条件 + Web UI 改判决策记录要求。
- [iteration-30.md](iteration-30.md) L5/L7：Web UI 非目标改判推迟到 W3 开工前；③B29 预登记；④W4 首版约束。
- [iteration-32.md](iteration-32.md) L12：Web UI 非目标改判（W3 前不动）。
- [00 §6](../design/00-overview.md#6-非-target明确不做)：「不做 Web UI。headless runner + JSON-RPC（ACP 等价物）足够。」（T2 改判对象）
- [README](../design/README.md) L123：0.5 路线行（W3 = JSON-RPC server，B29 仅回环）；L88：Out of Scope 行含 Web UI（T2 同步面）。

## 审查停点（草案，3 停）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1：承载契约 + wire + 绑定面 | `workbench/rpc` 契约（方法面三件 / 错误分档 / JSON-RPC 帧 / 响应预算）+ `jdk.httpserver` 绑定面（恒 127.0.0.1 构造面）+ B29 硬线校验（响应预算 B 行登记，四件套）+ 停 1 设计点三条（游标机制 / 端口占用失败面 / Follower 生命周期）钉死 | 已开工 |
| 停 2：消费面 + CLI + 装配面 | 五视图 / tail / 清单复用接线 + `Follower.close()` 赋义 + `--serve`（HeadlessOptions 词表 / 互斥 / usage）+ headless 依赖 + dist/jh moditect 两行（含 jdk.httpserver 回镜像）+ 同步面（02/00/README/AGENTS/current-state/boundary-register/notes 0011） | 未开工 |
| 停 3：真机验收 + 收口 | jlink 镜像 `--serve` 实录（curl 三方法 + keyless 面）+ B29 硬线证据（监听地址实录 + 非回环访问拒绝）+ CI 四腿 + 验收清单全勾 + 收口批 | 未开工 |

停 1/停 2 出包按 review packet 仪式（首行自问「新增模块 / base 加行」——本迭代新增模块 `workbench/rpc`、零 base 行（headless 直依赖，it34 tui 先例）；四件套中「META-INF/services 双通道」不适用（无 Plugin），其余照勾）。

## 验收清单（骨架，随停点细化）

- [ ] 停 1/停 2 评审放行（packet 落盘 + 裁决归档）
- [ ] 停 3 真机验收：jlink `--serve` curl 三方法闭环 + keyless 面 + B29 硬线证据
- [ ] validate EXIT=0 + 全量 package EXIT=0（门禁）
- [ ] keyless `--verify` 计数不变（34/41/0——零 bundle 行、零新配置键的预期面）
- [ ] CI 四腿全绿（push 授权后）+ run 号回填
- [ ] 同步面 grep 零旧措辞残留（T2 改判 + 其余）
