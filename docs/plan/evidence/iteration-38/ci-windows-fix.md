# it38 收口 CI Windows 修复证据（2026-11-23）

停 3 收口批推送后 CI 首跑 Windows 两腿红；修复分两笔，均已完成本地聚焦 + 全量门禁 + 四腿全绿。

## 提交与 CI 链

| 提交 | 内容 | 本地证据 | CI |
|---|---|---|---|
| `1e80197` | 停 3 呈现面重设计 + 状态机修复 + banner 留痕 | 见 [stop3-evidence.md](stop3-evidence.md) | run `45142833538`：build / macos 绿；windows / windows-arm 红（headless 7 失败） |
| `a0cd0a5` | JSON 内路径转义 + 遗物捕获条件等待 | 聚焦 18/0/0/0（WebChatControlTest 16、TwoHostLostProbeTest 1、R1ReplayHashTest 1）；全量 202 份 = 1670/0/0/25 | run `45145642612`：build / macos 绿；windows / windows-arm 各余 1 失败 |
| `f4b0879` | 并发根会话用例等 A 的 runtime 真正退休 | WebChatControlTest 16/0/0/0；全量 202 份 = 1670/0/0/25 | run `45148236185`：**四腿全绿**（macos / build / windows / windows-arm） |

全量计数为 fresh surefire XML 求和（`-q` 下无逐类行可数）；2026-11-23 17:29–17:34 窗口，与 pre-push 钩子的全量 package 同窗。1670 = 停 3 的 1669 + 新增转义钉子 1。

## 根因（四个，同一平台面）

1. **JSON 内路径未转义**：`WebChatControlTest` 把 `Path.toString()` 直接拼进工具参数 JSON。Windows 反斜杠使 `fs_read` / `delegate` / `job_start` 拿到非法 JSON——两例直接报 `IllegalArgumentException: tool arguments are not valid JSON`，四例因委派/任务未起在 `await` 20s 超时。统一经 `jsonString()` 双写反斜杠，并加本机可跑的转义钉子（`C:\tmp` → JSON `C:\\tmp`）。
2. **遗物捕获竞态**：宿主 A 只等 Running 快照就 `save` + `halt(0)`。pwsh 启动比 POSIX shell 慢，首行输出可能在硬崩前尚未进 `output.part`，宿主 B 读到空遗物。改为 `ProbeHostA` 轮询 `jobs.read` 直到 relic 标记可见（30s 截止，超时 `PROBE_A_FAILED` 大声失败），再 fsync / halt。
3. **retirement 滞后**：`concurrentRootSessionsKeepFeedsAndAuditSeparate` 等 `completedTurns(a)==1` 后立即断言 `openRuntimeCount()==1`；轮收尾与 runtime close 是两步，Windows 两腿看到 2。await 条件补 `openRuntimeCount()==1`。
4. **静默降级**：`R1ReplayHashTest` 同类路径在 Windows 上虽绿，但首轮 `fs_read` 实际是非法 JSON 错误结果（replay 只证哈希一致，不证工具调用合法）。一并转义，恢复测试语义。

产品代码零改动；全部为测试面/探针的构造与时序修正，未放宽任何断言（20s/30s 截止、失败诊断与计数均保留）。

## 证据原始件

- Windows 首跑失败日志：`/private/tmp/it38-win.log`（本地留存，不入仓；按 run `45142833538` 下载）
- 修复后 Windows 失败日志：`/private/tmp/it38-win2.log`（run `45145642612`）
- windows-arm 失败日志：`/private/tmp/it38-winarm2.log`（run `45145642612`）
- 聚焦复跑：`/private/tmp/it38-winfix-focused{,2,3}.log`
- 最终四腿：run `45148236185`（`repos/retreatIsAdvance/javanatic-harness/actions/runs/45148236185`）
