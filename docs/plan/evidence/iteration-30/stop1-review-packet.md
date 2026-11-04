# it30 停 1 审查包：JobService seam 契约

- **不变量增量**：任务状态判别联合五态（Running/Succeeded/Failed/Cancelled/Lost）+ 终态穷尽 switch；快照即校验（fail loud）；任务会话所有权（list 只见本会话）。
- **裁决点**：①Lost 独立终态（诚实失联 vs 冒充可续接）；②输出超限 = 停捕获不杀任务；③事件 = job/snapshot 全量快照按 id fold（无 generation，B16 同精神）、输出块不事件化；④jobs 独立 capability 不复用 ShellExecutor。
- **风险自报**：事件 schema 是 W1/W2 面板零投影的地基，字段形状（variant 判别式）若有误在 W 段才暴露——本包请重点审 §2；Lost 的产生机制（重启扫描）留给停 2，契约只定语义。
- **证据入口**：[聚焦测试 + validate](stop1-definition-focused.txt)（TEST_EXIT=0 / VALIDATE_EXIT=0，模块汇总 17/0/0）；[seam 提案](stop1-seam-design.md)。
- **若只能改一处**：把事件载荷从「快照」改为「转移事件」是我认为最可能被挑战的设计——我的立场：全量快照让消费方 fold 即得现状（mcp/catalog 先例），转移事件要求消费方保序重放，面板实现更脆。

## 1. 承载类

`JobService`（接口全文见源码 `jobs/jobs/src/main/java/io/javanatic/harness/jobs/JobService.java`）：五方法 + close；Javadoc 承载会话所有权、事件口径、Lost 语义、fail-loud 边界。

## 2. 三行映射

| 设计增量（iteration-30.md） | 实现 | 测试 |
|---|---|---|
| JobService 契约 | JobService + JobException/JobNotFoundException | （接口无实现体；行为面停 2/3） |
| 状态判别联合 | JobState 五 record + terminal() 穷尽 switch | JobStateTest 5 项 |
| 请求/快照/切片校验 | JobRequest/JobSnapshot/JobSlice 构造校验 | JobRequestTest 6 + JobSnapshotTest 3 + JobSliceTest 3 |

## 3. 偏离清单

无设计文档级偏离（设计增量按锚点表落）。中途实撞：Id 包名 `io.javanatic.harness.kernel.brand`（非直觉的 `...harness.brand`）——已修，无语义变化，建议回填 pits 由停 1 批一并（见提交）。

## 4. 为什么是这个形状 / 若错靠什么发现

事件载荷选全量快照而非转移：面板 fold 末值胜 = 零投影（评审预告的重点）；若错，W1 投影测试即红。Lost 独立终态而非 Failed(reason=LOST)：Lost 不是任务执行的失败，是宿主生命期断裂的观测事实——混入 Failed 会让「失败率」语义失真；若错，it31 委派的父取消收敛验收会逼出。输出超限不杀任务：验收场景「长时间构建/测试」日志天然长，杀任务即假失败；若错，限额场景验收红。
