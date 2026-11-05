# it30 停 2 设计基线：jobs/local Provider（开工记录，随实现细化）

日期：2026-11-05。停 1 已关闭；评审预告三重点为本文档骨架。

## 0. 侦察结论（2026-11-05）

- 事件入账路径：插件持 `Session` 引用直接 `session.append(事件)`（skill 先例：`SkillToolPlugin:130 session.append(new SkillCatalogEvent(...))`）——jobs/local 依赖 `core/session`（Session + 事件类型），`JobSnapshotEvent` 形态照 `SkillCatalogEvent`（record 承载 JobSnapshot）。codec：第七 `SessionEventCodec` 实例，形状照 skill/tool 现有实例（实现时核对其登记方式：ServiceLoader vs 显式注册表）。
- 进程纪律照抄 it29 停 2 生命周期矩阵：进程组 + 排水宽限 + TERM→KILL 升级 + wait 复验；≥3 启停取证含忽略 TERM 路径。

## 1. 输出文件与游标（评审重点②）

- 布局：`<sessionRoot>/<sessionId>/jobs/<jobId>/output.part` + `state.json`（会话持久化树内、随会话生命周期）。
- 捕获：追加写 output.part 至 outputCapBytes，达限停止捕获、置 outputTruncated；任务继续跑（裁决②）。
- 游标：read 直接按字节区间读文件（fromByte ≤ 文件长度=capturedBytes），UTF-8 完整边界。
- 终态后：文件保留至会话清理（与 jsonl 同生命周期）；**Lost 后只读**（进程已不在，文件即遗物）。清理归属 = 会话目录清理面，不另设定时器。

## 2. 生命周期与 Lost 机制（评审重点①）

- start：分配 id → state.json(Running) → spawn（ProcessBuilder，工作目录=JobRequest）→ 事件 job/snapshot(Running)。
- 看门狗：虚拟线程等待进程退出（waitFor + 超时 deadline）；超时 → TERM→排水宽限→KILL → Failed(TIMED_OUT)。
- cancel：Running 才收敛（TERM→宽限→KILL）→ Cancelled；终态 false。
- 宿主退出：owning scope close = 逐任务收敛（Cancelled）+ 事件入账；JVM 强杀兜底 = 下次重启的 Lost 扫描。
- **重启扫描**：服务装配时读会话 jobs/ 目录，state.json 为 Running 且无存活进程可证 → 就地改写 Lost + 入账事件（真机复现取证：两进程宿主序列，宿主 A 强杀 → 宿主 B 装配 → 断言 Lost 事件与可读遗物输出）。

## 3. 待实现清单

jobs/pom.xml 增 `local` 模块；`LocalJobsPlugin`（provides JobService，持 Session）+ `JobSnapshotEvent` + codec + 进程/捕获/看门狗/扫描实现 + 行为测试（含限额、超时、取消、Lost 复现）+ 真机取证。R3：插件卸载 = 全任务收敛。
