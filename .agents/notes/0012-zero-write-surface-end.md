# 0012 零写面不变量的终结：W5 写面入 0.6.0

日期：2026-11-20。裁决人：用户（it38 W5 四确认评审）；追认：AI。

## 背景

W1–W4（it33–it36）把「读侧零写路径」作为刻意承诺：投影、TUI、JSON-RPC server、Web UI 全部只读，[release-notes-0.5.0](../../docs/release-notes-0.5.0.md) §零写面把它写进发布叙事（「v1 零 server 写面」）。W5 引入会话控制写面（开会话 / 送轮 / 取消 / 审批答复），该承诺在产品后续版本中不再成立，须显式裁决而非静默破约。

## 裁决

显式终结「零写面不变量」。终结条件四条，同时成立才算终结：

1. 写面全部复用既有执行面——ToolExecutor 管线 + ApprovalService，R2 不开新通道；
2. 审批中继不降格——300s fail-closed 与 `--approval-timeout` 原口径不变，中继只搬运提示与决定；
3. B36 Host/Origin 信任围栏对写方法组同样生效且突变必红（`/events` 等 GET 面也在 dispatch 围栏内）；
4. 写面由 `--web-write` 显式开启，缺省仍只读（0.5.0 行为原样）。

## 推翻对象

- [release-notes-0.5.0](../../docs/release-notes-0.5.0.md) §零写面「v1 零 server 写面」承诺在 0.6.0 不再适用；
- [iteration-35](../../docs/plan/iteration-35.md) T6「会话控制写 v1 只读」的挂账由 it38 收口；
- [iteration-36](../../docs/plan/iteration-36.md) T3「server 零写路径」是 W4 首版划界，本记录不追溯改判其历史正确性。

**不否定 0.5.0 已发布事实**——0.5.0 仍是一段完整、可独立讲述的只读能力相位；终结只约束后续版本的承诺面。

## 后果

- 稳定面增量（`--web-write` flag + 写面 JSON-RPC 方法组）记入 [12-api-stability](../../docs/design/12-api-stability.md)；
- 写面成为 0.6.0 的正式承诺；后续版本不得以「兼容」名义默认打开写面而不经新的裁决；
- `--verify` 与 serve 启动 banner 增 `web-write: off（缺省）` / `on（--web-write）` 行（R4 可断言面）；
- B36 由读面围栏扩展为写面准入条件，读面回补保留在 it37 已落实现上。

## 证据

- [iteration-38.md](../../docs/plan/iteration-38.md) 四确认与 2026-11-20 评审裁决（ADR 0012 批准、T1–T7 全批）；
- [release-notes-0.5.0.md](../../docs/release-notes-0.5.0.md) §零写面（被推翻承诺原文）；
- [iteration-35.md](../../docs/plan/iteration-35.md) T6、[iteration-36.md](../../docs/plan/iteration-36.md) T3（历史划界）；
- [boundary-register.md](../../docs/plan/boundary-register.md) B36 行（信任围栏准入）。
