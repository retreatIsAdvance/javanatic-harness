# 当前状态卡

> 一页纸事实源，2026-10-19（it27.1 收口）。详细设计见 [docs/design/](../design/README.md)，逐迭代验收见 [plan/](README.md)，边界规则见[边界登记表](boundary-register.md)。本卡只写"现在是什么"，不写历史。

## 版本与发布

- 工作版本：`0.3.0-SNAPSHOT`；最近发布：**0.2.0**（Maven Central + 四平台归档）。
- 0.3 目标：工具生态。it27 交付 MCP stdio；it27.1 交付 Streamable HTTP + 注册表 + 轮屏障换代 + 结果预算。

## 能力面（已实现）

| 面 | 现状 |
|---|---|
| 内核 | kernel brand / core（Scope、Events、Plugin、Effect、ServiceKey） |
| 会话 | 事件溯源 + JSONL 持久化 + R1 双哈希回放；扩展事件 codec：todo、plan、command、assistant/chunk、skill/catalog、mcp/catalog |
| Agent | core/agent + agent-loop（Turn/Step 状态机）、compaction、budget、durable resume、request-context |
| LLM | llm seam + openai-compat + deepseek + replay（keyless） |
| 工具 | core/tools pipeline（校验 → 审批 → 超时 → 执行 → 审计）；fs 三模块；shell 四模块（POSIX bash / pwsh / docker）；todo、plan、skill 装载 |
| 技能 | skill Definition + 四根 local + tool（it26） |
| MCP | 四模块：mcp/mcp（契约 + `mcp-registry`）+ mcp/stdio + mcp/http + mcp/tool；两 transport 可并存；轮屏障换代；结果 256 KiB、帧 8 MiB |
| 人工协作 | approval 三模式（非交互 300s fail-closed）、ask_user、slash 命令 |
| 沙箱 | 平台链 darwin seatbelt / linux bwrap→landlock / win32 windows-acl（低完整性，PARTIAL 两洞如实） |
| 组合 | kernel/config + bundle/base（YAML 三层 + CompositionManifest）+ headless CLI + dist/jh jlink 镜像 |

## 组合与治理

- base bundle：**32 行**（19 可配 + 13 无配；MCP 四行 = `mcp-registry` 无配、`mcp-stdio` / `mcp-http` / `mcp-tool` 可配）。
- `--verify`（headless 默认 profile）：**27 rows, 32 discovered, 0 unreferenced**；`mcp:` 行点名四行，MCP 工具恒 `approval-required`。
- MCP 缺省 `servers: []` = 零进程、零网络、零工具、零事件。
- 外部闭包：候选腿 `verify-consumer.sh candidate`（skill 三件 + mcp 四坐标）；发布腿对 Central 0.2.0。

## 验证入口

| 面 | 命令 | 最近证据 |
|---|---|---|
| 门禁 | `mvn -B -q validate` | 各停点 `*-validate` / 收尾证据 |
| 全量 | `mvn -B -q package` | `docs/plan/evidence/iteration-27.1/stop3-closeout-package.txt` |
| 消费方闭包 | `integration/verify-consumer.sh candidate` | `docs/plan/evidence/iteration-27.1/stop3-closeout-consumer.txt` |
| 镜像真跑 | `dist/jh/target/jlink-image/bin/jh --verify` | `docs/plan/evidence/iteration-27.1/stop3-jlink-realrun.txt` |

## 当前进行中

- **无进行中迭代**。it27.1 已收口并提交（验收清单全勾，含真实模型在环；证据 `evidence/iteration-27.1/`），**未推送**。
- 待用户点名：push（含 it27.1 的 3 个提交 `d69484d` / `6b3b0bb` / `4722928`）。模型在环 runbook 已于 2026-10-20 用真实 `deepseek-chat` 回填（`stop3-model-in-loop.txt`）。
- 下一项：0.3 顺序 3「网络资料获取」——四确认待启动（按新格式：内容 / 目标 / 为什么 / 不做）。
- 挂账（转下一迭代议程）：MCP `_hash8` 撞名退路（hash12/前缀转义）属命名算法变更，须重裁；Resources / Prompts / Sampling / Roots / Elicitation 首版不做。
