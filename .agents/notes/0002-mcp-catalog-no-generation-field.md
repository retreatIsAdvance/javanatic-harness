# 0002 — `mcp/catalog` 每代全量快照、不带 generation 字段

- 状态：已实施（it27.1）
- 日期：2026-10-19

## 背景

清单换成每代可采纳后，账本需要表达「这是第几代」。自然的诱惑是给 `McpCatalogEvent` 加 `generation` 字段，与运行期 `catalogGeneration()` 对齐。

## 裁决（T8）

- 事件**只带全量快照**（`time` + `entries`），**不加 generation 字段**：会话日志本身是全序的，日志出现序即代际；fold 末值胜。
- 落账时点固定在 `PRE_STEP`（`PreStepDecision.Enter` 之后、user message 之前）；首非空 / 内容变化 / 空集退役才 append，与上一代相等则静默；被拒轮不采纳也不落账。
- resume 以日志末代重建注册面；与装配期实况的差异由首个 `PRE_STEP` 对账补齐。

## 推翻对象

- it27 形状：「冻结快照 + `logged` 已连接子集 + `DRIFTED` 保持冻结面」；以及评审中曾考虑的「显式 generation 计数器」提案。
- it27 挂账「T8」原为待裁，本裁决为终局。

## 后果

- 读取方必须保持日志顺序（JSONL 天然有序），不能按字段重排。
- 代际只是**运行期**比较工具（`catalogGeneration()`，内容变化才递增、仅顺序变化不算），不落账；日志与运行期不会因 resume/fork 出现两个互相矛盾的计数器。
- 全量快照体积随工具数线性增长；变化才落账 + 空集退役保证零噪声与可退役。

## 证据

- [stop3-swap-and-ledger.txt](../../docs/plan/evidence/iteration-27.1/stop3-swap-and-ledger.txt)：落账矩阵（首代 / 变化 / 相等静默 / 空退役 / 被拒轮）。
- [stop3-assembly-and-verify.txt](../../docs/plan/evidence/iteration-27.1/stop3-assembly-and-verify.txt)：装配与 verify 面。
- 设计同步：[03 §扩展事件](../../docs/design/03-session-event-sourcing.md)、[05 §7.1](../../docs/design/05-capability-seam.md)。
