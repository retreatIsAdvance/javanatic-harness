# javanatic-harness 0.6.0

> 本文件 = `v0.6.0` GitHub Release 正文**采用稿**（评审可改）；发布执行（版本翻转 / 签名 / 上传 / tag / Publish）属用户侧，见 [release.md](release.md) §0。

Plugin-based agent harness on the JVM — **Java 25 LTS / JPMS / Maven**. Ports the engineering ideas of DeepSeek Harness (dsh) to the Java ecosystem: **ideas carry over, shapes do not**.

0.6.0 的主题是**交互工作台——零写面不变量的终结**（W5 一站）。0.5.0 把「只读观察 + 配置干跑」作为完整能力相位发布收口；0.6.0 在已发布基线上打开会话控制写面：浏览器里可以发消息开轮、流式接收回复、中继审批决定、回答 exit 5 提问、取消在途轮。写面全部复用既有执行面（ToolExecutor 管线 + ApprovalService），R2 不开新通道；审批中继不降格，300s fail-closed 原样；写面由 `--web-write` 显式开启，缺省行为与 0.5.0 完全一致。这项承诺反转由 [ADR 0012](../.agents/notes/0012-zero-write-surface-end.md) 显式裁决，不是静默破约。

## 升级必读（0.5.0 → 0.6.0）

**无破坏性变更，缺省行为与 0.5.0 完全一致**——`--serve` 不带 `--web-write` 时仍是只读工作台：方法面、UI 控件、监听地址、围栏、退出码全部原样。写面需要显式选择。

- **CLI flag +1**：`--web-write`（缺省关；必须与 `--serve` 同用，否则用法错误 exit 2；`--verify --serve --web-write` 为 keyless 纯预检，零监听 / 零轮 / 零工具）；
- **JSON-RPC 方法 +4**：`session/start` / `session/send` / `session/cancel` / `approval/respond`，走既有 `POST /` 通道；**SSE `GET /events`** 为下行流（显式修约 W4 T1「仅精确 `/` GET/HEAD」，与其它方法同过 B36/B37 围栏）；
- **零新模块、零新发布坐标**（仍为 71 坐标）；**零新会话事件**（`SessionEvent` permits 不变）、**零新配置键**、**零新 bundle 行**（base 41 行不变）、**零新第三方依赖**；JLine 4.6.0 三坐标与 0.5.0 相同；
- `workbench.rpc` 导出包内为纯增量：写面 seam `ChatControl` / `ApprovalRelay` 与 SSE 实现类型；`WorkbenchRpc` / `RpcHttpServer` 增构造重载，既有签名保留；`boot.AppBoot` 增 `bootReported(BootOptions, ApprovalPrompt)` 重载，`ApprovalPrompt` 获得「启动期可替换实现」语义（H1，签名不变）；
- keyless `--verify` 计数不变：`composition: 34 rows, 41 discovered, 0 unreferenced`，新增可断言行 `web-write: off（缺省）` / `on（--web-write）`（R4）；
- 无需迁移：既有 profile / bundle / 事件日志 / 工具与审批配置继续可用。

## 围栏先行，写面后至（治理顺序）

0.6.0 的写面不是凭空打开的：**0.5.0 已经先船运了 B36/B37 信任围栏**——Host 白名单（`127.0.0.1:<port>` / `localhost:<port>`，port = 实际绑定面）+ Origin 存在即须同源 + POST `/` 仅收 `Content-Type: application/json`，先于路由、覆盖 ALL 方法含 GET。0.6.0 的写方法组与 `/events` 无条件过同一围栏，测试钉死拒绝阶梯并突变必红。

这个顺序本身就是承诺：**先有一个版本的只读围栏，再有一个版本的写面**。B29（仅 127.0.0.1 回环、单用户、无 TLS）不变——写面扩大的是「本机浏览器能做什么」，不是「谁能连进来」。

## Highlights

- **浏览器内完整轮生命周期**：`session/start` 发消息开首轮，`session/send` 对既有会话送下一轮（即 exit 5 等答复的答复通道），`session/cancel` 复用既有协作取消（aborted 落账），`approval/respond` 一次性结算审批。轮状态机可见四态：运行中 / 等待审批 / **等待答复** / 已收尾——`ask_user` 数据驱动停轮后不再被 `turn/end` 的 `completed` 误显示为「已收尾」。
- **审批中继不降格**：server 每次审批发 UUID，下行推送工具名 + 摘要；首答结算，迟到 / 重复答复被拒并显「已过期 / 已结算」。等待界仍由 `ApprovalPrompt.effectiveIdleTimeout` 单源控制——非交互 300s fail-closed 与 `--approval-timeout` 口径不变；组合行 `idleTimeoutSeconds` 被中继固定 300s 覆盖时，启动 banner 打显式提示（配置被忽略必留痕）。
- **SSE 下行流**：`GET /events` 以 `Last-Event-ID` 映射 `LoggedEvent.seq` 游标、keep-alive 注释帧、慢消费者有界队列满即断开由 EventSource 重连续传；零新依赖、零 node 工具链，事件源与 R1 面不动。
- **写面显式 opt-in**：`--web-write` 缺省关；无该 flag 时 UI 不出现聊天 / 审批 / 答复控件，方法面与 0.5.0 完全一致；`--verify` 与 serve 启动 banner 可证明 on/off。
- **工作台视觉与交互**：三栏壳（会话 / 主区 / 审批与详情），浅色 token 为主 + `prefers-color-scheme` 自动深暗（暗色底 `#151517`）；审批 composer takeover 非模态，Enter 允许 / Escape 拒绝；提问 Enter 提交、Shift+Enter 换行，IME 双守卫；单文件 vanilla JS、零 `innerHTML`、无前端构建链。

## 设计取舍

- **写面归属**：写面契约在 `workbench/rpc`，实现由 `examples/headless` 注入（与 projection / tail 双 seam 同构）——零新发布坐标、零新模块；`workbench/rpc` 不依赖 `interaction-approval`，浏览器与终端只是同一治理面的两个前端。
- **按轮挂载 Runtime（H2）**：`session/start` / `session/send` 每轮从持久化事实重组组合，轮毕即 close；delegation 子代与 jobs 等后台工作保留 runtime 到终局，无父取消级联、一 session 一所有者。
- **不做 steer / 队列**：在途轮重复 `session/send` 返回「turn in flight」业务错误，不排队、不打断；inbox `NEXT_TURN` / `NEXT_STEP` 是现成升级路径，留待后续裁决。
- **问题无超时**：exit 5 是 JH 的无限等语义（提问与答复都是日志事实），不随外部实现的倒计时形状改变。

## 已知边界（如实登记）

- **回环单用户无 TLS（B29）**：`--serve` 仅绑 `127.0.0.1`，无多用户 / 认证 / 远程访问；写面同硬线；
- **写面缺省关**：不显式加 `--web-write` 就仍是 0.5.0 的只读工作台；后续版本不得以「兼容」名义默认打开写面而不经新的裁决；
- **审批等待界固定 300s（中继档）**：组合行 `idleTimeoutSeconds` 在该档被覆盖，启动 banner 显式提示；`--approval-timeout` 对终端审批档的口径不变；
- **SSE 是单向下行**，不承载上行控制；上行仍是既有 JSON-RPC POST；
- **jobs 运行中任务面未并站**：重访条件 = 用户点名 jobs 视图；
- **dump 行内 config 键序跨 JVM 不稳定**：既有 HashMap 迭代序观察项，行序契约未违反，LinkedHashMap 化挂账；
- 工作台写面与 UI 为 0.6 新增面，稳定面语义以 [12-api-stability.md](design/12-api-stability.md) 为准；非稳定面（`examples/*` 宿主实现、`ui.html` 内部结构）保留调整自由。

## 0.5.0 仍然成立

0.5.0 是「个人工作台——只读观察 + 配置干跑，零写面」的完整、可独立讲述的能力相位，其发布承诺在 0.5.0 版本内继续成立。0.6.0 终结的是**后续版本的零写面承诺**，不否定 0.5.0 已发布事实。
