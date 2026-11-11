# 迭代 33 — 0.5 工作台 W1：事件投影与查询（状态：进行中）

基线：it32 已关账（0.4.0 发布完成，CI 四腿全绿 run 42763536094）；工作版本 `0.5.0-SNAPSHOT`。0.5 路线（[总体计划](../design/README.md#phased-evolution-plan)）：W1 事件投影与查询 → W2 TUI 工作台（终端库选型为 W2 四确认置顶必裁项）→ W3 JSON-RPC server（B29 已预登记：仅 127.0.0.1 回环）→ W4 本地 Web UI（无构建链单文件 vanilla JS）。

模块：新增 `workbench/` 组（本迭代 `workbench/projection` 一个叶）；改 `examples/headless`（CLI 接线）；同步面 02/00/12/AGENTS/README/现状卡。

## 四确认（2026-11-10 评审通过——两裁决点照推荐批，附四处精化）

- **内容**：
  1. 新模块组 `workbench/`：读侧投影与查询层 `workbench/projection`——**纯库模块 + CLI 接线，不是 capability 三角**（无 Provider/Consumer；artifactId `harness-workbench-projection`，02 模块表按库模块列，core/todo 同族）。基于既有 `SessionPersistence` seam（load/list）读持久化会话，纯函数投影**五视图**：timeline（seq/type/time 全事件；**未知 ExtensionEvent 按 type 字符串渲染、不解码不失败，未知类型负载不进输出**——投影层只依赖核心事件 + 类型字符串的边界声明）、turns（轮/步状态与时长）、tools（call/result 配对 + isError + 耗时）、usage（token/预算 rollup）、messages（对话本体的可读视图）。
  2. CLI 最小查询入口：`--session=<id>` + `--session-view=<turns|tools|usage|timeline|messages>`（缺省 turns），纯文本输出供脚本与后续 TUI 复用；`--sessions` 列表不变。两条配套要求：① `--help` 与互斥矩阵**显式区分 `--session`（只读检视）与 `--resume`（续跑写侧）**；② 互斥面沿 `--sessions` 先例（与任务文本 / `--resume` / `--verify` 互斥）。
  3. 零新事件、零写路径；只走 load 读侧，不触碰写者锁。
- **目标**：复盘一次运行不手工翻 JSONL——哪个 turn 卡了多久、工具调用与失败、token 花在哪，一条命令可查；该层成为 W2 TUI 与 W3 server 的唯一读侧地基（三站不各自手搓解析）。
- **为什么**：append-only 日志是事实源，查询是派生视图——读侧与写侧分离；CLI/TUI/server 共用一层（一个事实一个家）；投影是纯函数，可单测且复用 R1 的 load+derive 路径。workbench 独立成组：投影与持久化是不同域（派生视图 vs 事实存储），且它是 W3 server / W4 UI 的组屋——0.5 伞形从第一站就位。
- **不做**：写侧一切（改/删会话、追加事件）；实时 tail/流式订阅（live 面属 W2 TUI）；JSON-RPC server 与本地 Web UI（W3/W4，B29 预登记维持）；动态配置投影（属后续站，W3 四确认时裁）；事件 schema 任何改动（W1 只读既有事件）。

## 设计增量

- **ADDED**：`workbench/projection` 模块（JPMS `io.javanatic.harness.workbench.projection`；纯库——无 plugin、无 base bundle 行、无 ServiceLoader 双通道；依赖 session + session/persistence seam；五视图纯函数契约）
- **ADDED**：未知事件边界声明（按 type 字符串渲染、不解码、未知负载不进输出）
- **ADDED**：CLI `--session=` / `--session-view=` 两 flag + 互斥矩阵行 + `--help` 读/写区分文案
- **ADDED**（停 1 评审钉死）：tools 视图配对键 = `callId`（`ToolResultBlock.toolUseId ↔ ToolCallEvent.callId`，与 R2 审计同一把键）——全系统一把配对键，乱序并行结果不错配；usage 视图保留「未报告计量」的轮（usage=null 与 turns 同序——「没报告」与「报告为零」严格分界）
- **ADDED**（停 1 评审挂账）：core `Session.create` 空 seed 潜在 IndexOutOfBoundsException → 边界登记表 **B34** 挂账（第二次现形，首现 it30 停 2 工作日志「空 seed 触发 log.get(-1)」；投影层 `isEmpty` 短路为正确防御，非 core 修复；core 修复需自带 fail-loud 测试，不阻塞 it33）
- **MODIFIED**：02 模块表（workbench 组 + 新叶）、00 模块总览、12 稳定面（CLI flag 加法，纯增量）、AGENTS.md 布局节、docs/design/README.md、current-state.md、README.md（模块清单同步面）
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `examples/headless/src/main/java/.../HeadlessMain.java`（RunnerOptions / parse / USAGE / runSessions） | 增 `--session=`/`--session-view=` 解析、互斥矩阵、USAGE 行、检视执行路径 | 否 |
| `examples/headless/src/test/java/.../HeadlessOptionsTest.java` | 钉死面：新 flag 解析 / 互斥 / 缺省视图 / help 行 | 否 |
| `examples/headless/src/test/java/.../HeadlessSessionsTest.java` | 检视面复用其夹具风格 | 否 |
| `session/persistence` SessionPersistence.java | 只消费（load/list），不改 | 是（停 1 只读消费，未改） |
| `examples/headless/pom.xml` + `module-info.java` | 编译域依赖 + requires | 否 |
| `dist/jh/pom.xml` | moditect 模块清单登记 workbench.projection（pits #38④） | 否 |
| 根 `pom.xml` + `workbench/pom.xml` | workbench 组注册 | 是（停 1） |
| `docs/design/02` / `00` / `12`、`AGENTS.md`、`README.md`、`current-state.md` | 同步面 | 否 |

## 审查停点（2 停，按评审精化）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1：投影契约 + 五视图（夹具驱动） | workbench/projection 全模块 | 已过（2026-11-10 评审放行） |
| 停 2：CLI 接线 + 真实会话验收（双臂夹具 + 检视实录） | HeadlessMain 解析/执行面 + jlink 真跑 | 出包等评审（2026-11-11） |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-33/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| [stop1-evidence.md](evidence/iteration-33/stop1-evidence.md) | 停 1：红 30 错 → 3 断言修钉 → 终绿 30 测 → validate → 突变 M1–M5 → 残留 grep |
| [stop1-review-packet.md](evidence/iteration-33/stop1-review-packet.md) | 停 1 评审包：契约面、三行映射、偏离清单 |
| [stop2-evidence.md](evidence/iteration-33/stop2-evidence.md) | 停 2：聚焦 36/36 + 突变 M1–M3 必红→逐字节还原 + validate 0 违例 + verify 计数 8/8 + 真实会话五视图实录（委派树乱序 callId 配对 / aborted 多轮 / error 工具） |
| [stop2-review-packet.md](evidence/iteration-33/stop2-review-packet.md) | 停 2 评审包：承载面（parse 分支/旁路/失败词面/渲染器）、三行映射、偏离清单、携带要求核对 |

## 验收（证据 = 实际执行的命令与结果）

- [ ] 五视图（turns/tools/usage/timeline/messages）在夹具会话上输出正确（聚焦测试绿 + 突变检查）
- [ ] 未知 ExtensionEvent（jobs/delegation/skill/mcp 实型）按 type 渲染不失败、负载不进输出
- [ ] `--session`/`--session-view` 解析 + 互斥矩阵 + `--help` 读/写区分（钉死测试）
- [ ] 真实入口验收：jlink `jh --session=<it31 双臂真实会话 id>` 检视实录（委派树 / 真实 usage / 工具失败 / 长轮次）
- [ ] 设计同步面（02/00/12/AGENTS/README/现状卡）与模块表一致
- [ ] validate + 全量 package 绿；dist/jh 镜像 `--verify` 无回归
- [ ] 零写路径证明（检视全程无写者锁、无日志追加）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
