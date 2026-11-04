# it30 停 1 seam 提案：JobService（jobs/jobs Definition）

日期：2026-11-04。仅契约与事件形状；无进程、工具、第三方依赖。参照先例：LspService（it29 停 1 终裁通过）与 McpClientService（it27）。

## 1. 契约面

- `JobService`（`ServiceKey<"jobs">`，AutoCloseable）：`start(JobRequest)` / `snapshot(Id)` / `read(Id, fromByte, maxBytes)` / `cancel(Id)` / `list()` / `close()`。虚拟线程友好；未知 id 抛 `JobNotFoundException`（fail loud，不以空冒充）。
- `Id<Job>` 品牌类型（`Job.newId` 工厂，kernel.brand 同用法）。任务由**所属会话持有**：list 只见本会话，跨会话不可见——为 0.4 二期委派的父子所有权留位，不提前实现。
- `JobRequest(command, workingDirectory, timeout, outputCapBytes)`：装配期校验（非空命令、绝对路径、正超时、正上限）。超时覆盖全生命周期；输出上限按捕获字节计——**超限停止捕获并标记，不终止任务**（长构建日志长不是死罪，磁盘无界才是问题）。
- `JobState` 判别联合五态：`Running` / `Succeeded(exitCode)` / `Failed(reason, exitCode)` / `Cancelled` / **`Lost(startedAt, markedAt)`**。`terminal()` 穷尽 switch——新增变体编译期强制补齐。`Lost` 是重启后对前生命期 Running 任务的**诚实标记**：无法证明仍在运行就如实说失联，不冒充可续接（路线原词 → R1 语义）。
- `JobSnapshot(id, commandSummary, state, capturedBytes, outputTruncated)`：查询面与事件共用的事实形状。
- `JobSlice(text, nextFromByte, capturedBytes, outputTruncated)`：游标读取，UTF-8 完整字符边界（web/tool 同口径）；`endOfCapture()` 区分「暂无更多」与「读尽」。

## 2. 事件形状（W1/W2 零投影的关键）

- 事件名 `job/snapshot`；载荷 = JobSnapshot 全量 JSON，状态以 `variant` 判别（running/succeeded/failed/cancelled/lost + 各自字段）。
- **按 id fold 末值胜、无 generation 字段**（B16 同精神，日志序即代）——面板/查询方 fold 即得现状，零投影消费。
- 发射时机：启动、终态、Lost 标记，各一次；**输出块不事件化**——read 游标读取，R1 账面不被输出洪灌。
- codec 实现随 Provider/Consumer（停 2/3）落 `SessionEventCodec` 第七实例；ExtensionEvent `ignorable=true`（旧读取方跳过未知）。

## 3. 边界与不做

- 与 shell seam 的关系：**独立 capability**，Provider（停 2）自管进程（参照 shell/local 与 B25 TERM→KILL 经验）；不复用 ShellExecutor（其契约为一次性同步执行）。
- 审批：jobs 工具恒需审批（B14 同口径），停 3 落。
- Lost 的产生与入账归 Provider（停 2）；本契约只定义状态与语义。

## 4. 若错靠什么发现

- 五态穷尽 switch：编译期拦漏。
- 快照/请求/切片的构造校验 + 17 项聚焦测试：坏输入在装配点红。
- 事件 schema 的消费方验收（W1 面板零投影）与 R1 回放零进程重建在停 3 / W 段各设硬闸。
