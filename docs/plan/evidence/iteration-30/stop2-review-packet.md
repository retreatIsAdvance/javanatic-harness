# it30 停 2 审查包：jobs/local Provider

- **不变量增量**：终局三步 = 等排干 → **事件先入账 → 内存后可见**（先持久后可见，无内存先行计数器）；终局恰一次（finalized CAS，cancel 与看门狗竞态仅一者落账）；Lost = 事件账 fold 唯一依据（**state.json 不存在**——B 条裁定的最严格实现：零第二事实源）；job/snapshot `ignorable=true` 不进 prompt 组装。
- **裁决点**：①任务树根 `<tmpdir>/jh-jobs/<sessionId>/`（偏离基线「会话持久化树内」——Session API 无路径出口，取 B27 homeDir 先例；代价：清理不随会话、遗物靠 OS tmp；备选 = 停 3 接持久化根）；②`JobFailureReason.EXIT_NONZERO` 增补（停 1 后契约加常量：非零退出是任务失败不是成功带码）；③stdout/stderr 合并捕获，行序非确定（单流各自线程）。
- **风险自报**：双宿主 kill -9 真机复现**当前为同 JVM 双实例模拟**（事件账 fold 语义等价），进程级复现挂账（需持久化装载路径，建议停 3 或作放行前置）；达限后捕获线程排水不存，进程写满管道将阻塞至超时被杀（诚实但值得评审）。
- **证据入口**：[聚焦 + validate + 突变红绿](stop2-provider-focused.txt)（31 绿 / VALIDATE_EXIT=0 / Lost 扫描突变必红 + 还原绿；突变首轮钓出 randomSuffix 真缺陷已修）。
- **若只能改一处**：任务树根的位置（tmpdir vs 会话树）——它决定遗物生命周期与停 3 装配面接线方式。

## 1. 承载类
`LocalJobService`（start/snapshot/read/cancel/list/close + bindSession 扫描；TERM 含后代→排水宽限 2s→KILL→复验 5s，it29 矩阵）；`LocalJobsPlugin`（provide + agent/created 绑定 + scope 关闭收敛）；`JobSnapshotEvent`/`JobSnapshotCodec`（第七 codec 实例，variant 判别式 + 布尔 0/1 编码）。

## 2. 三行映射
| 设计增量（stop2-provider-design.md） | 实现 | 测试 |
|---|---|---|
| 生命周期矩阵 + 取消/超时 | killTree + 看门狗 + finalized CAS | cancelConverges / timeoutFailsTimedOut（8 项行为面） |
| 限额停捕获不杀 + 游标 UTF-8 边界 | cap 检查→排水、trimPartialUtf8 | capStopsCaptureWithoutKilling / read 断言 |
| Lost = fold 唯一依据（懒标记） | bindSession fold + Running→Lost 入账 | ledgerFoldMarksPriorLifeRunningAsLost + 突变必红 |
| 事件形状端到端 | codec 五态往返 | JobSnapshotCodecTest 6 项 |

## 3. 偏离清单
| 偏离 | 为什么 | 待裁决 |
|---|---|---|
| 任务树根 tmpdir 而非会话树 | Session 无路径出口；B27 先例 | 本包裁决点① |
| EXIT_NONZERO 枚举增补 | 非零退出语义归位 | 停 1 契约 additive 变更通报 |
| 双宿主复现降为同 JVM 模拟 | 持久化装载路径未建 | 挂账（停 3 或放行前置） |
| 首个 Provider 角色轮管线外 append | 设计 §4.A 已裁 | 已入案（A 条两句依据） |

## 4. 为什么是这个形状 / 若错靠什么发现
fold 唯一依据 + 无 state.json：删索引不变式的最强形式是根本没有索引；若错，Ledger 测试与突变红。append→内存次序：倒序会在账面丢失时内存超前（R1 破坏）；若错，杀进程重放比对即红。达限排水不杀：裁决②原文；若错，cap 测试红（Succeeded 断言）。
