# 决策记录（ADR seeds）

设计文档（`docs/design/`）承载**目标态**；本目录承载**决策史**——为什么这么选、推翻了什么、证据在哪。目标态变化仍须同提交同步设计文档，本目录不替代设计文档。

## 约定

- 一决策一文件，命名 `NNNN-kebab-topic.md`；编号递增、不复用。
- 固定小节：**背景 / 裁决 / 推翻对象 / 后果 / 证据**；「推翻对象」必须点名旧结论所在处（设计文档、迭代文档或前一条决策）。
- 状态只三种：`已裁决（实施中）` / `已实施` / `已归档`。归档冻结、不删除；后续裁决另开编号并在文首互链。
- 触发面（见 AGENTS.md「修改本文件」）：口径反转、删除既有承诺、外部输入边界调整、跨模块契约形状选择。
- 证据链接一律指向仓内落盘文件（`docs/plan/evidence/...`），不引用会话。

## 索引

| 编号 | 决策 | 状态 | 迭代 | 证据 |
|---|---|---|---|---|
| [0001](0001-mcp-catalog-hot-swap-dual-barrier.md) | MCP 清单热换代走双轮屏障；删除 `DRIFTED` | 已实施 | it27.1 | [stop3-swap-and-ledger](../../docs/plan/evidence/iteration-27.1/stop3-swap-and-ledger.txt) |
| [0002](0002-mcp-catalog-no-generation-field.md) | `mcp/catalog` 每代全量快照、无 generation 字段 | 已实施 | it27.1 | [stop3-swap-and-ledger](../../docs/plan/evidence/iteration-27.1/stop3-swap-and-ledger.txt) |
| [0003](0003-mcp-public-name-normalization.md) | MCP 公开名确定性归一 + `_hash8`，publicName 入账 | 已实施 | it27 | [stop3-t2-normalization](../../docs/plan/evidence/iteration-27/stop3-t2-normalization-and-bigframe.txt) |
| [0004](0004-mcp-registry-transport-neutral.md) | 注册表居中：`mcp-registry` 聚合，transport 不 provide | 已实施 | it27.1 | [stop1-registry-and-generation](../../docs/plan/evidence/iteration-27.1/stop1-registry-and-generation.txt) |
| [0005](0005-mcp-external-input-boundaries.md) | 凭据词表单源 + HTTP URL/header 外部输入校验 | 已实施 | it27.1 | [stop3-cap-and-masking](../../docs/plan/evidence/iteration-27.1/stop3-cap-and-masking.txt) |
| [0006](0006-mcp-result-budget-layering.md) | 结果预算分层：transport 8 MiB 帧帽 / 消费面 256 KiB | 已实施 | it27.1 | [stop3-cap-and-masking](../../docs/plan/evidence/iteration-27.1/stop3-cap-and-masking.txt) |
| [0007](0007-web-egress-pinned-public-only.md) | Web 出口：钉地址公网抓取，egress 不受沙箱治理 | 已实施 | it28 | [stop1-pinning-and-redirect](../../docs/plan/evidence/iteration-28/stop1-pinning-and-redirect.txt) |
| [0008](0008-web-search-provider-and-token-gap.md) | 搜索 Provider：原生结构化来源，keyless 双态与 token 缺口 | 已实施 | it28 | [stop2-real-probe](../../docs/plan/evidence/iteration-28/stop2-real-probe.txt) |

## 与停点评审的关系

review packet 是**当次裁决的提案**，本目录是**裁决落地后的长期记录**。停点被用户修改后，以修改后的裁决更新对应 note；packet 原文保留在 `docs/plan/evidence/`，不回写。
