# 0006 — 结果预算分层：transport 8 MiB 帧帽 / 消费面 256 KiB

- 状态：已实施（it27.1）
- 日期：2026-10-19

## 背景

it27 挂账「没有统一 MCP 结果字节上限」：stdio 读侧对单帧无行上限（仅 1 MiB 真帧取证），远端一次大结果可把进程内存与模型上下文一起打爆。it27.1 同时新增 HTTP transport，两个 transport 都需要读侧上限；消费面又需要更小的上下文面。

## 裁决（T7）

- **transport 读侧 8 MiB**：stdio 单帧（换行帧）与 HTTP 响应体、单条 SSE 事件各自设 8 MiB 帽；超限是协议级失败，走异常分档（不静默截断）。
- **消费面 256 KiB**：`mcp-tool.maxResultBytes`（正整数，缺省 262144）；UTF-8 **完整字符边界**截断（不劈半个码点），追加 `" (output truncated)"` 标记且**标记不计入上限**；成功与工具级错误同口径。
- 两层职责不同：帧帽防单帧爆内存（协议安全），结果帽控模型上下文（成本与可读性）；不合并成一个数字。

## 推翻对象

- it27 挂账：「工具级 byte cap 不在本版……挂 it27.1」——已执行。
- 「一个全局 MCP 字节上限」的简化提案——无法同时满足读侧协议安全与消费面上下文预算。

## 后果

- 超 8 MiB 是错误（fail loud），超 256 KiB 是截断（数据面可预期）；两者测试面不同。
- 截断可能落在多字节字符中间；实现按码点累计 UTF-8 字节，保证输出恒为合法 UTF-8。
- `maxResultBytes` 是 base bundle 显式行配置（`262144`），MCP tool 行因此从无 config 变为可配（base 行计数 19 可配 + 13 无配 = 32）。

## 证据

- [stop3-cap-and-masking.txt](../../docs/plan/evidence/iteration-27.1/stop3-cap-and-masking.txt)：截断边界与配置校验。
- [stop2-http-focused.txt](../../docs/plan/evidence/iteration-27.1/stop2-http-focused.txt)：8 MiB 帧帽矩阵。
- 设计同步：[05 §Consumer](../../docs/design/05-capability-seam.md)、[07 §5](../../docs/design/07-profile-bundle.md)、[12 §5](../../docs/design/12-api-stability.md)。
