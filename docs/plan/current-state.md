# 当前状态卡

> 一页纸事实源，2026-11-11（it30 / it31 / it32 已关账；**it33 已关账**——0.5 工作台 W1 事件投影与查询：`workbench/projection` 纯库 + `--session` 只读检视旁路五视图；0.4 两站齐（后台任务 + 受控委派）；**0.4.0 已发布**——2026-11-10 上线 Maven Central（67 坐标 / deploymentId `09491845-16fb-4654-a91c-b3cf9c290a0a`）、repo1 抽检验签全过、`v0.4.0` tag 已推、GitHub Release 已发布（8 归档）；收尾批（bump 0.5.0-SNAPSHOT + Roadmap/版本串同步 + consumer 发布腿重指 0.4.0 + candidate-closure 折叠）随本批落）。详细设计见 [docs/design/](../design/README.md)，逐迭代验收见 [plan/](README.md)，边界规则见[边界登记表](boundary-register.md)。本卡只写"现在是什么"，不写历史。
>
> **维护说明：每收口必更新**。核对验收、已提交/待提交、已推送/待推送和挂账状态；当前结论反转时检索旧措辞并同步所有现状入口，冻结评审原文不改写。

## 版本与发布

- 工作版本：`0.5.0-SNAPSHOT`；最近发布：**0.4.0**（Maven Central，2026-11-10；四平台归档随 v0.4.0 GitHub Release）。
- 0.3 目标：工具生态（Skills / MCP / Web / LSP 四站，it26–it29，0.3.0 已发布）。0.4 目标：可管理的后台任务与受控委派——it30 已关账（2026-11-05）、**it31 受控委派已收口（2026-11-09）**（delegation 三角色落地：六态联合问败分界、影子封禁防递归、预算随载荷入账、双臂验收过——H 对父上下文降 61.6% 决定性达成，token 判据修正 ADR 0010、边界发现 ≥~40k 才有收益）；**it32 发布工程已关账（2026-11-10）**：委派策略指引入 delegate 族工具描述（判据源 [ADR 0010](../../.agents/notes/0010-dual-arm-token-criterion.md)）、release notes 定稿、rc 排练 + deploy + 两处 Publish + repo1 抽检全链闭环（[证据](evidence/release-0.4.0/publish-0.4.0.txt)）、retention 裁决落 B33（v1 不自动清理 + 两条重访条件）。**0.4 两站齐；0.4.0 已发布（2026-11-10 上线 Central）。** 挂账：0.5 工作台四站（W1–W4，另开迭代）——W1 = it33 已关账（2026-11-11，见「当前进行中」）。

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
| LSP | 三模块：lsp/lsp（`LspService` 三能力契约 + 观察时点纯值模型）+ lsp/jdtls（`JdtlsProvider` 长驻进程 Provider，Content-Length 字节帧自有 wire、进程所有权、四态观察；真实 JDT LS 1.63.0 生命周期取证 3 轮；停 3 S1 增 `JdtlsPlugin` 双通道装配件）+ lsp/tool（`lsp_diagnostics`/`lsp_definition`/`lsp_references` 三工具 Consumer，双态零工具 + 四态逐字措辞 + 双帽截断；停 3 S2）；装配面已落（base 两行：`lsp-jdtls` disabled + `lsp-tool`，四处消费面，`lsp:` 摘要行；停 3 S3）；产品验收已随停 3 完成（S4 三腿：R1 回放零进程 + 措辞纪律双突变必红含「项目无错误」、jlink 三态真跑、真机三能力探针；S5 模型在环用户真 key 实跑通过；[终裁](evidence/iteration-29/stop3-acceptance.txt)） |
| 工作台 | `workbench/projection`（it33）：五视图纯函数投影（turns/tools/usage/timeline/messages）+ `--session=<id> [--session-view=<view>]` 只读检视旁路（keyless、零写路径、缺省 turns、互斥矩阵、id 不存在或写者锁冲突 exit 3） |
| 人工协作 | approval 三模式（非交互 300s fail-closed）、ask_user、slash 命令 |
| 沙箱 | 平台链 darwin seatbelt / linux bwrap→landlock / win32 windows-acl（低完整性，PARTIAL 两洞如实） |
| 组合 | kernel/config + bundle/base（YAML 三层 + CompositionManifest）+ headless CLI + dist/jh jlink 镜像 |

## 组合与治理

- base bundle：**41 行**（24 可配 + 17 无配；0.3.0 的 37 行 + jobs 两行 + delegation 两行——四新行均无 base config 块）。
- `--verify`（headless 默认 profile）：**34 rows, 41 discovered, 0 unreferenced**（keyless 实测，验收口径；keyed 36/41 未跑——0.3.0 先例）；`mcp:` 行点名四行，`web:` 行点名 `web-fetch-http + web-tool`（keyless）与 `web_fetch`，`lsp:` 行点名 `lsp-tool`（keyless，零工具），`jobs:` 行点名 `jobs-local + jobs-tool` 与四工具（approval-required），`delegation:` 行点名 `delegation-local + delegation-tool` 与四工具（approval-required）；jobs/delegation 两族工具恒 `approval-required`。
- MCP 缺省 `servers: []` = 零进程、零网络、零工具、零事件；Web 抓取行挂载即授权公网 HTTPS（出口不经沙箱，见 B17）。
- 外部闭包：候选腿 `verify-consumer.sh candidate`（基础块 34 条；candidate-closure 已随 0.4.0 折空）；发布腿对 Central 0.4.0。

## 验证入口

| 面 | 命令 | 最近证据 |
|---|---|---|
| 门禁 | `mvn -B -q validate` | 各停点 `*-validate` / 收尾证据 |
| 全量 | `mvn -B -q package` | [停 1 full-01](evidence/iteration-29/stop1-full-01/11-package.txt)（不汇总旧 XML；其后仅测试增补另有最终聚焦） |
| 消费方闭包 | `integration/verify-consumer.sh candidate` | `docs/plan/evidence/iteration-28/stop3-closeout-consumer.txt` |
| 镜像真跑 | `dist/jh/target/jlink-image/bin/jh --verify` | `docs/plan/evidence/iteration-28/stop3-jlink-network.txt` |

## 当前进行中

- **0.4.0 发布验收——已收口（发布完成，2026-11-10）**：稳定面差异审计 v0.3.0→0.4.0 树收口（既有 45 叶模块导出面 / SessionEvent permits / CLI flag / 既有 config 键零变化；纯增量 8 新模块 / 8 新导出包 / 8 恒审批新工具 / 2 类 ignorable 扩展事件 / 3 组新配置键，[证据](evidence/release-0.4.0/audit-stability-surface.txt)）；核对三法重跑（导出面 diff、`jh --help` EXIT=0、`--verify` 34/41/0）；rc1 排练全链绿（[实录](evidence/release-0.4.0/rc1-rehearsal.txt)：tag run 五绿 + master 复跑绿 + draft 8 归档，rc 清理随收口补记）；deploy 通过（[实录](evidence/release-0.4.0/deploy-0.4.0.txt)：deploymentId `09491845-16fb-4654-a91c-b3cf9c290a0a`、bundle 硬门槛 1320 项 / 67 坐标 / 零 .asc 缺失）；两处 Publish 用户侧完成——Portal Publish + GitHub Release 正式发布（publishedAt 2026-11-10T00:49:59Z，8 归档），repo1 收录后抽检十项 200、sha1 一致、EDDSA 验签完好、metadata latest=0.4.0（[证据](evidence/release-0.4.0/publish-0.4.0.txt)）；`v0.4.0` tag 已推（CI tag run `42588548148` 五绿）。收尾批随本批落（bump `0.5.0-SNAPSHOT` + Roadmap 翻转 + 版本串同步面 + consumer 发布腿重指 0.4.0 + candidate-closure 折叠）；0.5 工作台 W1 = it33 已关账（见下）。
- **it33（0.5 工作台 W1：事件投影与查询）已关账（2026-11-11）**：停 1（投影契约 + 五视图，夹具驱动）与停 2（CLI 接线 + 真实会话验收）均评审放行——三裁决（tools 配对键 = callId 与 R2 审计同键、usage 视图含 usage=null 轮「未报告」与「报告为零」分界、core Session 空 seed 边缘入边界 **B34** 挂账不阻塞）全批；停 2 两处 P3 packet 引文保真度瑕疵已逐字修正。[停 1 评审包](evidence/iteration-33/stop1-review-packet.md) / [停 2 评审包](evidence/iteration-33/stop2-review-packet.md) / [停 2 取证](evidence/iteration-33/stop2-evidence.md) / [停 2 评审结论](evidence/iteration-33/stop2-review-verdict.md)；收口门禁 validate EXIT=0 + 全量 package EXIT=0 + keyless `--verify` 34/41/0 计数不变；jlink 真实会话五视图实录（主会话 1593 事件委派树乱序 callId 配对正确 + 委派子会话 + aborted 多轮 + error 工具）。零新事件、零写路径；只走 load 读侧。W2 TUI 未开工（等四确认）。
- **it28（网络资料获取）已关账**：停 1–3 与收口批全部提交；最终评审见 [收口记录](evidence/iteration-28/stop3-closeout-review.txt)。基线 `cd39e36` 已推送，该基线 CI run `40639843523` 四腿通过。文档整理、分层验证脚本与 it29 草稿已提交为 `7c8d6a7`；停 0 证据、停 1 Definition 与状态回填随停 1 裁决批提交；停 2 Provider 收口批已提交为 `01d997a`；停 3 S1–S3（`d9a37ec`）、S4（`3934f7f`）与状态回填（`92b2d81` / `1e09873`）已提交；it29 收口批（含 Windows 修复 `b2f284b` / `8639712`）已全量 push（CI 四腿绿），无待推。
- **it29：Java 诊断 / 定义 / 引用最小闭环——已关账**。[停 0 已通过并关闭](evidence/iteration-29/stop0-acceptance.txt)：固定官方 JDT LS `1.63.0 / 202610191322` 裸进程闭环成立，compiler API 备选不触发。[停 1 已通过并关闭](evidence/iteration-29/stop1-acceptance.txt)：三能力 seam 契约与观察时点模型落定（[审查包](evidence/iteration-29/stop1-review-packet.md)，[验证汇总](evidence/iteration-29/stop1-verification-summary.txt)：17 项纯值测试、10 项突变、full EXIT=0）。[停 2 已通过并关闭](evidence/iteration-29/stop2-acceptance.txt)：`lsp/jdtls` Provider 交付（13 个测试类 177/0/0；T5 三条实现边界落地），真实 JDT LS 证据跑 3 轮启停（每轮 exit=0 / unanswered=0 / residual-free）+ 四态真机区分 + 措辞纪律自检，7 突变红绿、full 304/0/0；QUIET_WINDOW 的区分证据来自夹具测试而非真机。[停 3 已通过并关闭](evidence/iteration-29/stop3-acceptance.txt)：S1 `JdtlsPlugin` 装配件、S2 `lsp/tool` 三工具 Consumer、S3 装配面（base 两行 + 四处消费面 + `lsp:` 摘要行）已交付；S4 产品验收三腿已取证（[回放](evidence/iteration-29/stop3-replay.txt)：LspReplayTest 双腿全绿 + 措辞纪律双突变必红含「项目无错误」→ 3/22 红 SHA 还原复绿；[jlink](evidence/iteration-29/stop3-jlink.txt)：三态 verify keyless 30/37/0、启用档 31/37/0 三工具、行序负例 31/37/0 零工具，均 EXIT=0 零残留；[真机三能力](evidence/iteration-29/stop3-real-three-capabilities.txt)：3 轮启停 ALL ASSERTIONS PASSED，definition/references REQUEST_RESPONSE 取证）；S5 模型在环由用户真 key 实跑并通过（[实录](evidence/iteration-29/stop3-model-in-loop.txt)：Run A 诊断 / Run B 定义 + 引用超时 / Run C 分类探针，两轮全过 + 一处真实偏离已分类为非缺陷，四态措辞「超时——不是无诊断」被模型在环逐字验证）。`LspService` 依赖 `kernel/core` + `llm/llm`；`lsp/jdtls`、`lsp/tool` 为聚合叶子（双通道 SPI、B27/B28 配置键、base 两行与四处消费面）。hash 锚定 JH 拉取时点，不证明 LS 分析来源；空结果仅称”拉取时点无诊断”。无 version、未见空数组、协议 / 尾流偏离、写盘 / 下载 / 主动宿主 JDK 发现原样留存。**it29 关账 = 0.3 四站全落（Skills / MCP / Web / LSP）；push 已清（it29 收口批全量已推、CI 四腿绿）；「0.3.0 发布验收」已到发布执行口（rc1 排练全链通过、版本树已翻 0.3.0；deploy / 签名 / Portal Publish / tag 属用户侧）。** T4/T6/T7 未整体批准；轮屏障消费衔接仍按后续裁决推进。
- 方法论前置：[verify-stop.sh](../../integration/verify-stop.sh) 已实现并完成取证：22 个行为测试、11 项突变通过，修复后真实 full 层 validate + package 返回 0；独立审查三项发现均已复现修复。[脚本停点 packet](evidence/iteration-29/verify-stop-review-packet.md)已获用户点名放行，见[验收记录](evidence/iteration-29/verify-stop-acceptance.txt)，不等于 it29 技术项获批；接口与验证边界见 [it29](iteration-29.md#verification-prerequisite)。AGENTS 已知坑迁至 [.agents/pits.md](../../.agents/pits.md)，根索引与全仓适用范围保留。
- **另批挂账，不混入 it29**：B4 capability graph 最小生成器（[出处](evidence/iteration-26/roadmap-diff-proposal.md#b4-capability-graph-机械化建议挂账)）；MCP `_hash8` 退路属命名算法变更，须重裁（边界 B13）。MCP 消费结果预算使用编号 `B-MCP-RESULT`，不再与图生成器 B4 混称。
- **已接受边界，不是待修缺陷**：Web 搜索辅助 token 不进预算账（it28 T9，边界 `B-WEB-SEARCH-BUDGET`）；MCP 首版不做 Resources / Prompts / Sampling / Roots / Elicitation（边界 B12）。
