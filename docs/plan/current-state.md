# 当前状态卡

> 一页纸事实源，2026-10-31（it28 已关账；it29 停 0 限定通过、T1–T3 方向已裁，`lsp/lsp` Definition 已写、停 1 已通过并关闭；T5 已裁自有边界并随停 2 落地，停 2 Provider 已交付、真实生命周期取证完成并获[用户通过放行](evidence/iteration-29/stop2-acceptance.txt)，当前停 3 Tool Consumer + 装配进行中；T4/T6/T7 未整体批准、产品验收未执行）。详细设计见 [docs/design/](../design/README.md)，逐迭代验收见 [plan/](README.md)，边界规则见[边界登记表](boundary-register.md)。本卡只写"现在是什么"，不写历史。
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
| LSP | 两模块：lsp/lsp（`LspService` 三能力契约 + 观察时点纯值模型）+ lsp/jdtls（`JdtlsProvider` 长驻进程 Provider，Content-Length 字节帧自有 wire、进程所有权、四态观察；真实 JDT LS 1.63.0 生命周期取证 3 轮）；无可挂载 `lsp-tool`、无装配行（停 3 进行中） |
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
| 全量 | `mvn -B -q package` | [停 1 full-01](evidence/iteration-29/stop1-full-01/11-package.txt)（不汇总旧 XML；其后仅测试增补另有最终聚焦） |
| 消费方闭包 | `integration/verify-consumer.sh candidate` | `docs/plan/evidence/iteration-28/stop3-closeout-consumer.txt` |
| 镜像真跑 | `dist/jh/target/jlink-image/bin/jh --verify` | `docs/plan/evidence/iteration-28/stop3-jlink-network.txt` |

## 当前进行中

- **it28（网络资料获取）已关账**：停 1–3 与收口批全部提交；最终评审见 [收口记录](evidence/iteration-28/stop3-closeout-review.txt)。基线 `cd39e36` 已推送，该基线 CI run `40639843523` 四腿通过。文档整理、分层验证脚本与 it29 草稿已提交为 `7c8d6a7`；停 0 证据、停 1 Definition 与状态回填随停 1 裁决批提交；本地相对 `origin/master` 待推 2 个提交，未 push。
- **it29：Java 诊断 / 定义 / 引用最小闭环**。[停 0 已通过并关闭](evidence/iteration-29/stop0-acceptance.txt)：固定官方 JDT LS `1.63.0 / 202610191322` 裸进程闭环成立，compiler API 备选不触发。[停 1 已通过并关闭](evidence/iteration-29/stop1-acceptance.txt)：三能力 seam 契约与观察时点模型落定（[审查包](evidence/iteration-29/stop1-review-packet.md)，[验证汇总](evidence/iteration-29/stop1-verification-summary.txt)：17 项纯值测试、10 项突变、full EXIT=0）。[停 2 已通过并关闭](evidence/iteration-29/stop2-acceptance.txt)：`lsp/jdtls` Provider 交付（13 个测试类 177/0/0；T5 三条实现边界落地），真实 JDT LS 证据跑 3 轮启停（每轮 exit=0 / unanswered=0 / residual-free）+ 四态真机区分 + 措辞纪律自检，7 突变红绿、full 304/0/0；QUIET_WINDOW 的区分证据来自夹具测试而非真机。`LspService` 依赖 `kernel/core` + `llm/llm`，JPMS `io.javanatic.harness.lsp` exports / opens 同名包；`lsp/jdtls` 增为聚合叶子；无 Plugin / ServiceLoader 注册、配置键、事件或装配行新增。hash 锚定 JH 拉取时点，不证明 LS 分析来源；空结果仅称“拉取时点无诊断”。无 version、未见空数组、协议 / 尾流偏离、写盘 / 下载 / 主动宿主 JDK 发现原样留存。当前进停 3（获 [2026-10-31 点名授权](evidence/iteration-29/stop2-acceptance.txt)）：Tool Consumer + 装配 + 验收；停 3 评审重点（用户预告）：工具措辞纪律、装配面老三样、`--verify` 计数与工具双态、R1 回放 + 模型在环。无可挂载 `lsp-tool`；T4/T6/T7 未整体批准、产品验收未执行。
- 方法论前置：[verify-stop.sh](../../integration/verify-stop.sh) 已实现并完成取证：22 个行为测试、11 项突变通过，修复后真实 full 层 validate + package 返回 0；独立审查三项发现均已复现修复。[脚本停点 packet](evidence/iteration-29/verify-stop-review-packet.md)已获用户点名放行，见[验收记录](evidence/iteration-29/verify-stop-acceptance.txt)，不等于 it29 技术项获批；接口与验证边界见 [it29](iteration-29.md#verification-prerequisite)。AGENTS 已知坑迁至 [.agents/pits.md](../../.agents/pits.md)，根索引与全仓适用范围保留。
- **另批挂账，不混入 it29**：B4 capability graph 最小生成器（[出处](evidence/iteration-26/roadmap-diff-proposal.md#b4-capability-graph-机械化建议挂账)）；MCP `_hash8` 退路属命名算法变更，须重裁（边界 B13）。MCP 消费结果预算使用编号 `B-MCP-RESULT`，不再与图生成器 B4 混称。
- **已接受边界，不是待修缺陷**：Web 搜索辅助 token 不进预算账（it28 T9，边界 `B-WEB-SEARCH-BUDGET`）；MCP 首版不做 Resources / Prompts / Sampling / Roots / Elicitation（边界 B12）。
