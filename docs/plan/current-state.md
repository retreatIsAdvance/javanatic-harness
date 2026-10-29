# 当前状态卡

> 一页纸事实源，2026-10-28（it28 已关账；it29 四确认已落盘，技术待裁）。详细设计见 [docs/design/](../design/README.md)，逐迭代验收见 [plan/](README.md)，边界规则见[边界登记表](boundary-register.md)。本卡只写"现在是什么"，不写历史。
>
> **维护说明：每收口必更新**。核对验收、已提交/待提交、已推送/待推送和挂账状态；当前结论反转时检索旧措辞并同步所有现状入口，冻结评审原文不改写。

## 版本与发布

- 工作版本：`0.3.0-SNAPSHOT`；最近发布：**0.2.0**（Maven Central + 四平台归档）。
- 0.3 目标：工具生态。it27 交付 MCP stdio；it27.1 交付 Streamable HTTP + 注册表 + 轮屏障换代 + 结果预算；it28 交付网络资料获取（有界网页读取 + 结构化搜索）。**0.3 前三项（Skills / MCP / Web）已落地，仅剩顺序 4 LSP。**

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
| Web | 四模块：web/web（双服务契约）+ web/fetch-http（钉地址公网抓取）+ web/search-deepseek（原生搜索，keyless 行 disabled）+ web/tool（`web_fetch`/`web_search` + HTML 提取 + 分层截断 + 信任前缀）；零新事件 |
| 人工协作 | approval 三模式（非交互 300s fail-closed）、ask_user、slash 命令 |
| 沙箱 | 平台链 darwin seatbelt / linux bwrap→landlock / win32 windows-acl（低完整性，PARTIAL 两洞如实） |
| 组合 | kernel/config + bundle/base（YAML 三层 + CompositionManifest）+ headless CLI + dist/jh jlink 镜像 |

## 组合与治理

- base bundle：**35 行**（22 可配 + 13 无配；MCP 四行 + Web 三行 = `web-fetch-http` / `web-search-deepseek`（keyless disabled）/ `web-tool` 可配）。
- `--verify`（headless 默认 profile）：**29 rows, 35 discovered, 0 unreferenced**（keyless；keyed = 31/35）；`mcp:` 行点名四行，`web:` 行点名 `web-fetch-http + web-tool`（keyless）与 `web_fetch`，两族工具恒 `approval-required`。
- MCP 缺省 `servers: []` = 零进程、零网络、零工具、零事件；Web 抓取行挂载即授权公网 HTTPS（出口不经沙箱，见 B17）。
- 外部闭包：候选腿 `verify-consumer.sh candidate`（skill 三件 + mcp 四坐标 + web 四坐标 = 11 坐标）；发布腿对 Central 0.2.0。

## 验证入口

| 面 | 命令 | 最近证据 |
|---|---|---|
| 门禁 | `mvn -B -q validate` | 各停点 `*-validate` / 收尾证据 |
| 全量 | `mvn -B -q package` | [分层脚本实跑 full-02](evidence/iteration-29/verify-stop-full-02/11-package.txt)（不汇总旧 XML） |
| 消费方闭包 | `integration/verify-consumer.sh candidate` | `docs/plan/evidence/iteration-28/stop3-closeout-consumer.txt` |
| 镜像真跑 | `dist/jh/target/jlink-image/bin/jh --verify` | `docs/plan/evidence/iteration-28/stop3-jlink-network.txt` |

## 当前进行中

- **it28（网络资料获取）已关账**：停 1–3 与收口批全部提交；最终评审见 [收口记录](evidence/iteration-28/stop3-closeout-review.txt)。基线 `cd39e36` 已推送，该基线 CI run `40639843523` 四腿通过。本次文档整理、分层验证脚本与 it29 草稿已获单提交授权；本批不 push，实际提交与待推范围以 Git 为准。
- **it29：Java 诊断 / 定义 / 引用最小闭环**。范围已批准；[四确认与 T 清单](iteration-29.md)已落盘，技术项待逐项裁定。停 0 JDT LS 裸进程探针尚未执行、具体 build 尚未钉定；未开始 JH 实现，失败须停止并重裁候选。
- 方法论前置：[verify-stop.sh](../../integration/verify-stop.sh) 已实现并完成取证：22 个行为测试、11 项突变通过，修复后真实 full 层 validate + package 返回 0；独立审查三项发现均已复现修复。[脚本停点 packet](evidence/iteration-29/verify-stop-review-packet.md)已获用户点名放行，见[验收记录](evidence/iteration-29/verify-stop-acceptance.txt)，不等于 it29 技术项获批；接口与验证边界见 [it29](iteration-29.md#verification-prerequisite)。AGENTS 已知坑迁至 [.agents/pits.md](../../.agents/pits.md)，根索引与全仓适用范围保留。
- **另批挂账，不混入 it29**：B4 capability graph 最小生成器（[出处](evidence/iteration-26/roadmap-diff-proposal.md#b4-capability-graph-机械化建议挂账)）；MCP `_hash8` 退路属命名算法变更，须重裁（边界 B13）。MCP 消费结果预算使用编号 `B-MCP-RESULT`，不再与图生成器 B4 混称。
- **已接受边界，不是待修缺陷**：Web 搜索辅助 token 不进预算账（it28 T9，边界 `B-WEB-SEARCH-BUDGET`）；MCP 首版不做 Resources / Prompts / Sampling / Roots / Elicitation（边界 B12）。
