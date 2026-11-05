# 迭代 30 — 可管理的后台任务（状态：进行中——停 1 准备中）

日期：2026-11-04（同日四确认获准并转进行中）。0.4 第一步（[总体路线 §0.4](../design/README.md#0-4--managed-background-work-and-delegation)：「先后台任务，再受控委派」）。基线：0.3.0 已发布（Central + GitHub Release），master = `d6c1442`（it30 草稿批 `6cbe0b2`），工作版本 `0.4.0-SNAPSHOT`。

**三项裁决已确认（2026-11-04）**：任务体 = shell 命令后台化；布局 = 独立 `jobs` 组三角色；产品面 = API + 工具 + 装配面（REPL/CLI 操作面留停 3 裁决）。同日路线裁决：it30 → it31 受控委派 → 0.4.0 发布 → 0.5「个人工作台」（TUI 先行，再 JSON-RPC server + 本地 Web；动态配置 = 只读投影 + 新会话换组合，热重载维持不做）——0.5 预告行随本迭代收尾批落盘，Web UI 非目标改判推迟到 server 段（W3）开工前。

路线评审预登记（2026-11-04，四条，不改阶梯）：① W2 终端库选型（JLine 3 / Lanterna / 裸 ANSI 对比：依赖面 / JPMS 兼容 / 维护度）为 W2 四确认置顶必裁项（新增第三方依赖须用户确认）；② it31 四确认时先定稿「与单 Agent 收益对比」的验收任务集——委派是 it22 以来最深的 core 语义改动，排期最大单点；③ W3 JSON-RPC server 的监听边界已预登记 [B29](boundary-register.md)（仅 127.0.0.1 回环、单用户、无远程、无 TLS）；④ W4 Web UI 首版约束 = 无构建链单文件 vanilla JS（server 直接服务 .html，不引入 node 工具链），组合编辑器复用 `dump`/`--verify` 干跑做「启动前校验」（fail-loud 直接变 UX）。

## 四确认

- **内容**：`jobs` capability 三角色新组——`jobs/jobs` Definition（JobService 契约：`Id` 品牌任务 ID、所属会话、状态判别联合、输出游标读取）、`jobs/local` Provider（shell 命令后台执行：输出限额、超时、取消、完成通知、宿主退出清理、重启后失联如实标记）、`jobs/tool` Consumer（模型操作面：启动 / 状态 / 读输出 / 等待 / 取消工具，恒需审批口径随停 3 定）。完成通知走 ExtensionEvent + codec 通道（第七实例）。装配面：base bundle 行 + `--verify` 摘要行 + R1 入账；缺省组合零后台任务零噪声。
- **目标**：验收覆盖 0.4 路线点名四场景——长时间构建/测试（真实 mvn 长任务后台跑 + 游标增量读取）、取消（含进程树收敛与事件入账）、宿主退出（清理可证 + 重启后 LOST/UNKNOWN 如实标记，不冒充可续接）、输出限额（截断可验证）；模型可见面全部入账、R1 回放零进程重建；keyless 全绿。
- **为什么**：0.4 序的执行地基——先证明后台任务可管理，委派才有挂载点（路线进入条件明文）；任务体取 shell 命令正对验收面「长时间构建/测试」，复用 shell seam 与 sandbox 的既有进程经验；对齐 dsh jobs 的思想、形状不照搬（JH 走 capability seam 三角色 + ExtensionEvent 通道 + 事件溯源入账）。
- **不做**：多 Agent 委派 / 父子所有权 / 权限继承（0.4 二期）；任意工具调用后台化（超出验收面，契约复杂度失控）；分布式 Agent 集群、跨机器工作流平台、subagent 桥接（既有非目标）；REPL `/jobs` 与 headless `--jobs` 操作面（停 3 裁决或下迭代）；it29 遗留 T4/T6/T7（agent-loop 轮屏障消费衔接等）不混入，仍按后续裁决推进。

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：JobService seam 契约与状态判别联合、job 事件 schema（ExtensionEvent 第七 codec 实例）、`jobs` 组三角色模块（jobs/jobs + jobs/local + jobs/tool）、jobs 配置键（停 1 提案定键名与默认值）。
- MODIFIED：ExtensionEvent codec 实例集（六 → 七）、base bundle 行集（37 行 → 含 jobs 行）、`--verify` 摘要面（增 `jobs:` 行，口径随停 3）。
- REMOVED：无。

## 锚点（本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| 根 `pom.xml` `<modules>` + `dependencyManagement` | 增 `jobs` 组三模块 | |
| `bundle/base/…/META-INF/harness/bundle.yml` | 增 jobs 行（缺省零任务零噪声） | |
| `examples/headless/…/HeadlessMain.java:729`（治理摘要） | `--verify` 增 `jobs:` 摘要行 | |
| SessionEventCodec SPI 实例集（ServiceLoader 登记） | 新 job 事件 codec 第七实例 | |
| `shell/local` 进程管理先例（TERM→KILL、进程树收敛） | 参照不改（停 2 复用经验） | |

## 审查停点

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1 | JobService 契约 / 状态联合 / 游标模型 / job 事件 schema——承载契约 + 新词表 | **已通过并关闭**（2026-11-05 评审放行：四裁决点全批；快照 `outputTruncated` 位满足「捕获已停可辨」要求；R1 依据已补 seam 提案 §2——输出到模型的唯一通道是 tool/result 落账，面板读游标不是模型可见面） |
| 停 2 | jobs/local Provider——后台执行 + 限额/超时/取消/宿主退出清理/重启失联标记，跨模块效应（进程所有权、durable 面） | **复跑前置已落（cancel 竞态修复 + dataDir 改判 + 三裁决入案），等用户独立复跑**（[竞态修复取证](evidence/iteration-30/stop2-race-fix.txt)；[设计基线](evidence/iteration-30/stop2-provider-design.md) / [审查包](evidence/iteration-30/stop2-review-packet.md) / [取证](evidence/iteration-30/stop2-provider-focused.txt)：31 绿 + validate EXIT=0 + Lost 扫描突变红绿；真缺陷 randomSuffix 钓出并修复；裁决点：任务树根 tmpdir、EXIT_NONZERO 增补、双宿主真机复现挂账同 JVM 模拟） |
| 停 3 | Consumer + 装配面 + 验收（base 行 + `--verify` 双态 + R1 回放 + 模型在环） | **已出包，等用户评审**（四工具 + 措辞禁词突变、装配四件套齐、[双宿主真机探针](evidence/iteration-30/stop3-two-host-lost-probe.txt) 1/0/0、jlink 冒烟 EXIT=0、[模型在环 runbook](evidence/iteration-30/stop3-model-in-loop-runbook.md) 待用户执行） |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-30/）

| 文件 | 覆盖（停点/验收项） |
|---|---|

## 验收（证据 = 实际执行的命令与结果）

- [ ] 长任务：真实 mvn 构建/测试后台执行 + 游标增量读取
- [ ] 取消：在途任务取消收敛（进程树 + 事件入账 + 状态终局）
- [ ] 宿主退出：清理可证；重启后失联任务如实标记，不冒充可续接
- [ ] 输出限额：超限截断行为可验证
- [ ] R1：任务相关模型可见面入账，回放零进程重建
- [ ] keyless 单测全绿；`--verify` 缺省/启用双态取证
- [ ] **双宿主真机复现（必验，停 2 放行裁决升格）**：杀真 jh 宿主进程 → resume 装配 → 观察 Lost 事件与遗物可读；含「未重开会话不标 Lost」懒标记负例
- [ ] 模型在环（真 key，用户执行）
- [ ] 文档同步：设计增量落盘、12-api-stability 配置键表、（若闭包变）embedding 指南

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
