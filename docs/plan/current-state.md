# 当前状态卡

> 一页纸事实源，2026-10-28（it28 收口）。详细设计见 [docs/design/](../design/README.md)，逐迭代验收见 [plan/](README.md)，边界规则见[边界登记表](boundary-register.md)。本卡只写"现在是什么"，不写历史。

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
| 全量 | `mvn -B -q package` | `docs/plan/evidence/iteration-28/stop3-closeout-fresh-gates.txt` |
| 消费方闭包 | `integration/verify-consumer.sh candidate` | `docs/plan/evidence/iteration-28/stop3-closeout-consumer.txt` |
| 镜像真跑 | `dist/jh/target/jlink-image/bin/jh --verify` | `docs/plan/evidence/iteration-28/stop3-jlink-network.txt` |

## 当前进行中

- **it28（网络资料获取）已关账**：停 1（契约 + 钉地址抓取）、停 2（原生搜索）、停 3（消费面 + 装配 + 验收）全部提交；停 3 裁决 2 的结果头字段实况修正随收口批落地；最终评审见 `evidence/iteration-28/stop3-closeout-review.txt`。
- 未推送：仅 it28 四提交 `4245700`（pre-work）/ `b8f9631`（停 1）/ `3db2dd2`（停 2）/ `1a76d0a`（停 3 + 收口）；it26 尾至 it27.1 的提交均已在 `origin/master`。推送待用户点名（归档时 `ls-remote` 连 github 超时，连通性需先确认）。
- 下一项：0.3 顺序 4 **LSP**（优先 Java 诊断/定义/引用）；建议先开一次「0.3 阶段小结 + it29 议程」规划对话。
- 挂账（转下一迭代议程）：push（it28 四提交）；B4 capability graph 最小生成器；MCP `_hash8` 撞名退路（hash12/前缀转义）属命名算法变更，须重裁；Resources / Prompts / Sampling / Roots / Elicitation 首版不做；Web 搜索辅助 token 不进预算账（T9 显式口径）；**AGENTS.md 超拆分阈值**（实测 210 行 > 200；「已知坑」节 :175–:204）——0.3 小结时拆子文件、根文件留索引。
