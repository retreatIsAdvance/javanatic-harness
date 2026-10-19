# 0001 — MCP 清单热换代走双轮屏障；删除 `DRIFTED`

- 状态：已实施（it27.1）
- 日期：2026-10-19

## 背景

it27 把 MCP 工具清单在**会话启动时冻结**：重连只续命，对账不一致落 `DRIFTED`（终态），消费面以 `McpCatalogDriftException` 终止轮次并提示「请重开会话」。该形状能保证请求 schema 与执行账本永不错位，但把远端正常的 `notifications/tools/list_changed` 与重连对账都变成「不可恢复」——长会话里 MCP server 升级一次工具就必须重开，与「工具生态」目标冲突。

## 裁决（T4 + T5）

- 采纳新清单只在**轮屏障**：`TURN_STOPPING`（notifyOrdered）换面；`PRE_STEP`（waterfall）补换代并落账。**轮内绝不换**——REQUEST 之前 schema 已被读取，换面会造成请求 schema 与执行账本错位。
- 单线程轮屏障下**免双代**：不存在「请求代 / 当前代」并存的必要；旧代在新代完成预检前保活。
- 候选快照**全量构建 + 预检先于注册面改动**：外部撞名 → `McpCatalogSwapException`，旧代保活、本轮 `TurnEndReason.Error`、server 仍 `CONNECTED`，下一 `PRE_STEP` 重试。
- `McpServerState` 删除 `DRIFTED`（三态：CONNECTED / RECONNECTING / FAILED）；删除 `McpCatalogDriftException`。清单变化不是状态，只是 `catalogGeneration()` 的代际差异。

## 推翻对象

- it27 设计：05 §7.1「清单会话启动冻结 / 对账不一致 → DRIFTED → 落轮终 + 请重开会话」；12 §3 契约行「清单冻结」。
- it27 挂账：「热换代（generation swap）……不进 it27——与 Streamable HTTP 合并重裁（it27.1/28）」——it27.1 已裁并落地。

## 后果

- 轮内到达的变化推迟到轮末或下一轮首，模型看到的工具面在单轮内稳定。
- `TURN_STOPPING` 与 `PRE_STEP` **各自必要**：删任一即漏换代（突变 M2/M3 实证）；预检若晚于注册面改动，旧代会丢（M1 实证）。
- 被拒轮（`PreStepDecision` 非 `Enter`）既不采纳也不落账，避免日志出现未实际使用的代。

## 证据

- [stop3-swap-and-ledger.txt](../../docs/plan/evidence/iteration-27.1/stop3-swap-and-ledger.txt)：25 用例绿 + M1–M5 突变（预检先于改动 / 双屏障各自必要 / 缓存复用 / 字节截断）。
- [stop1-registry-and-generation.txt](../../docs/plan/evidence/iteration-27.1/stop1-registry-and-generation.txt)：代际契约与 stdio 侧换代。
- 设计同步：[05 §7.1](../../docs/design/05-capability-seam.md)、[02 §MCP](../../docs/design/02-module-layout.md)、[03 §扩展事件](../../docs/design/03-session-event-sourcing.md)。
