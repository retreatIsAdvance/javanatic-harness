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
- **重启扫描**：服务装配时 fold 事件账（按 id 末值胜）取「崩溃时在跑的任务」，末值 Running → 标记 Lost + 入账事件（state.json 只作私有索引，见 §4.B；真机复现取证：两进程宿主序列，宿主 A 强杀 → 宿主 B 装配 → 断言 Lost 事件与可读遗物输出，含「未重开会话不标 Lost」懒标记负例）。

## 3. 待实现清单

jobs/pom.xml 增 `local` 模块；`LocalJobsPlugin`（provides JobService，持 Session）+ `JobSnapshotEvent` + codec + 进程/捕获/看门狗/扫描实现 + 行为测试（含限额、超时、取消、Lost 复现）+ 真机取证。R3：插件卸载 = 全任务收敛。

## 4. 修订（2026-11-05 实现前评审定死两条）

**A. 异步 append 的合法性依据（两句，缺一不可）**
- **并发安全是显式依赖**：`Session.append` 的并发性质有 jqwik 背书（`SessionConcurrencyTest`——并发 append 下 seq 恒连续唯一）；jobs/local 在任意后台线程（看门狗）append，依赖的是该性质测试，不是「碰巧没炸」。实现批如触碰 append 路径，该性质测试随批复跑。
- **R1 无扰动的另一半**：`job/snapshot` **不进 prompt 组装**（非模型可见面——模型见到 job 事实的唯一通道是停 3 的工具调用 `tool/result`）。异步落账不触碰任何请求指纹，这是后台线程 append 合法性的语义半边。
- **Session 引用获取 + 角色新注记**：照抄 skill 先例（经 `CREATED` 订阅拿 Session）；jobs/local 是**第一个以 Provider 角色、在轮管线时点之外 append 会话事件的模块**（先例 skill/mcp 的 catalog 全在轮管线单线程时点）——偏离注记入案，停 2 审查包偏离清单点名。

**B. 单一事实源裁定：Lost 扫描以事件账 fold 为唯一依据**
- 会话恢复载日志时顺路 fold（按 id 末值胜）——「崩溃时哪些 job 在 Running」的答案**只来自事件账**。
- `state.json` 严格降格为**可重建索引**（provider 私有加速：游标刷盘点等）；日志权威。正确性要求：删除全部 state.json 后扫描/读取行为不变（索引丢失只影响速度）。
- `output.part` 不属本裁定范围——它是输出本体（终态遗物语义），不是状态副本。

**停 2 出包评审重点（评审预告，入案）**：①先持久后可见（禁内存缓冲先推计数器）；②Lost 真机复现含懒标记负例；③B 条裁定落地后的扫描实现。
