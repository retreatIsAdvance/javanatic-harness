# dsh 参照说明

Javanatic Harness（JH）的设计源自对 **DeepSeek Harness（dsh）** 架构与工程约定的移植分析。本仓库独立演进，dsh 是设计期的参照系与对照实现。

## 参照关系

| 项目 | 仓库 | 角色 |
|---|---|---|
| Javanatic Harness (JH) | 本仓库 | Java 25 + JPMS + Maven 的独立实现 |
| DeepSeek Harness (dsh) | 设计分析时的本地仓库（TypeScript/Cordis/pnpm monorepo） | 架构思想来源与对照实现 |

## 文档中的 dsh 路径约定

本目录（`docs/design/`）的设计文档里出现的以下路径形式，**均指 dsh 仓库内的路径**，不在本仓库解析：

- `packages/<group>/<pkg>/...` — dsh 的 npm workspace 包（如 `packages/core/session/src/types.ts`）
- `vendor/cordis/...` — dsh 内嵌的 Cordis 框架源码（Context/Fiber/Events 的原始实现）
- `docs/architecture.md`、`docs/glossary.md` 等 — dsh 的架构文档
- `.agents/notes/...` — dsh 的 Agent Note（决策记录）

## 核心移植映射（速查）

| dsh 概念 | JH 对应 | 设计文档 |
|---|---|---|
| Cordis Context/Fiber/Events | `kernel.*` 模块 | [01-kernel.md](design/01-kernel.md) |
| SessionEventMap（事件溯源） | sealed `SessionEvent` + Surface 投影 | [03-session-event-sourcing.md](design/03-session-event-sourcing.md) |
| Agent Loop（Turn/Step） | `core.agent-loop` 状态机 | [04-agent-loop.md](design/04-agent-loop.md) |
| Capability Seam 三角色 | JPMS `requires` 编译期隔离 | [05-capability-seam.md](design/05-capability-seam.md) |
| Scope / ScopedLayers | `kernel.scope` | [06-scope.md](design/06-scope.md) |
| Profile/Bundle/Patch | `~/.harness/` 三层叠加 | [07-profile-bundle.md](design/07-profile-bundle.md) |

完整的映射速查表见 [design/00-overview.md](design/00-overview.md) 与 [design/README.md](design/README.md)。
