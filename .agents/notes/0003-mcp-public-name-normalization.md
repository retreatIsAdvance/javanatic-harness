# 0003 — MCP 公开名确定性归一 + `_hash8`

- 状态：已实施（it27，it27.1 复核维持）
- 日期：2026-10-19

## 背景

MCP 工具名由远端 server 决定，字符集与长度不受本机控制；模型面需要稳定的工具名，注册表不能静默丢工具，日志回放也不能依赖会演进的归一算法。

## 裁决（T2）

- 公开名 = `mcp__<serverId>__<raw>`；server id 配置面拒含 `__`（消除拼接二义）。
- raw 合法（非空、无 `__`、仅 `[A-Za-z0-9_-]`、总长 ≤ 64）→ 原样；否则清洗（非法字符→`_`、折叠 `_` 串、去边界 `_`）+ 按剩余预算截断 + 追加 `_<hash8>`（`sha256(serverId \0 rawName)` 前 8 hex）并 WARN。
- `mcp/catalog` 条目**直接记 publicName**；fold 不重跑算法；读侧缺 `publicName` fail loud。命名算法版本属于**事件 schema 事实**，变更按迁移处理。

## 推翻对象

- 评审早期提案：非法名直接拒绝注册（会静默丢工具）；或按 raw 原样注册（模型面命名不稳定、可能超长）。
- fold 时按 raw 重算 publicName 的提案——算法演进会使旧日志重建出不同工具名。

## 后果

- 同名确定性、长度有界、不丢工具；代价是 WARN 噪声与「清洗后可能重名」需要 `_hash8` 消歧。
- 残余风险：`_hash8` 与合法 raw 名理论上可撞（如 raw 恰为 `x_1234abcd`）；注册表撞名 fail loud，届时退到 hash12 或前缀转义——那是命名算法变更，须重裁 catalog 算法版本（挂账，不在 it27.1 执行面）。

## 证据

- [stop3-t2-normalization-and-bigframe.txt](../../docs/plan/evidence/iteration-27/stop3-t2-normalization-and-bigframe.txt)。
- 设计同步：[05 §公开命名](../../docs/design/05-capability-seam.md)、[12 §4](../../docs/design/12-api-stability.md)。
