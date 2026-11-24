# 迭代 39 — 0.6.0 发布工程（状态：进行中（2026-11-24）；四确认评审通过，停 1 待放行）

**裁决记录（2026-11-24，方向裁定）**：**it39 = 0.6.0 发布工程，单站发布，挂账全不动**。W5 自身已是完整能力相位（零写面不变量终结 + 写面显式 opt-in + 审批中继治理），发布站混功能改动会脏回退面；jobs 视图、T2 steer 升级、dump 键序 LinkedHashMap 化留 0.7 周期，按发布后的真实使用体感定优先级。流程复用 [release.md](../release.md) 与 it37（0.5.0）完整先例——零新发明。

**裁决记录（2026-11-24，四确认评审通过）**：草案四点全批——① 坐标 / bundle / 闭包预测全不变（71 / 1398 / 34），以翻转批机械复测与真 deploy 日志定案；② notes 三层叙事（行为不变承诺 / 写面 opt-in / 围栏先行半页因果）与裁定逐字对齐，标题结构照此走；③ 三流程门结构照准，**「审计发现非预期签名 / 事件 / 配置 / 坐标变化则升级为代码停点并停」条款保留**；④ release.md 78/77 POM 口径校正随 0.6.0 化落。用户点名：版本串硬前置是 pits 条目首次**在翻转前**抓住同族滞后（前两版均为收尾后知后觉），「犯错回填」闭环从「不再犯」进到「提前拦」；5 处改齐 + grep 不符即停。停 3 由用户照 0.5.0 同款独立复核 bundle 实物（1398 / 71 / `.asc` 配对 / examples-dist 零出现 + 写面新增 `ChatControl` / `ApprovalRelay` seam 在导出面点名）。

**基线**：it38 已关账（2026-11-23；CI 四腿全绿 run `45148236185`，关账批 `595002e`，其 CI run `45151862251` 亦四腿全绿）；工作版本 `0.6.0-SNAPSHOT`；工作树干净，HEAD `595002e` 与远端一致，无待推。

模块：无新增模块、无新发布坐标；发布面 = 既有 71 坐标（reactor 76 − `excludeArtifacts` 5）；改动面 = POM 版本树、版本串同步面、消费者示例、release.md、README / 设计状态与 current-state、release notes。

## 四确认

- **内容**：
  ① **版本翻转** `0.6.0-SNAPSHOT → 0.6.0`：reactor 76 POM `versions:set` + 非反应器 `integration/consumer-sample` 手动同步 pom + README；`dist/jh/target` 清理；机械复测 POM 版本串与坐标数；
  ② **`docs/release-notes-0.6.0.md` 撰写**（主题：交互工作台——零写面不变量显式终结，ADR 0012；升级承诺「行为不变、写面需显式开启」；因果讲圆「0.5.0 先船运 B36/B37 信任围栏 = 0.6.0 写面敢开的安全地基」；W5 四能力面 + 审批中继 300s fail-closed + SSE 修约；新 flag / 新方法 / 零破坏性变更如实）；
  ③ **稳定面差异审计 v0.5.0→0.6.0**（it32/it37 先例核对三法：导出面 diff / `jh --help` / `--verify` 计数；本版增量预告见下）；
  ④ **rc 排练**（`v0.6.0-rc1` tag 全链：四腿 + release-attach + draft 8 归档 + rc 清理走 gh api——0.3.0/0.4.0/0.5.0 先例）；
  ⑤ **deploy**（`-P release`，真签名；GPG pinentry 预案在案——`gpgconf --reload` + 探测签名热缓存）+ **Portal 审核 Publish（用户侧）**——**Portal 前对账 71 坐标**（三查 + bundle 硬门槛，核对单随取证落盘）；
  ⑥ **tag `v0.6.0` + GitHub Release**（四平台归档，release-attach 自动 draft，**Publish 恒用户侧**）；
  ⑦ **收尾批**（bump **`0.7.0-SNAPSHOT`**、README×2 Roadmap 翻转、发布腿重指 `0.5.0→0.6.0`、版本串同步面全局枚举、release.md 0.6.0 化、current-state / 设计 README 状态收口）。
- **目标**：0.6.0 上线 Maven Central（**坐标预期 71，不变**；翻转批机械复测定案）+ GitHub Release 四平台归档；**零功能改动、零破坏性变更**——缺省行为与 0.5.0 完全一致，写面仅在 `--web-write` 显式开启后出现。
- **为什么**：W5 已是一个完整、可独立讲述的能力相位——「零写面」的终结配 ADR 0012 与新 minor 号，patch 号不承载这种重量；发布站与功能站分离，回退面干净；「围栏先行（0.5.0 B36/B37）、写面后至（0.6.0 W5）」本身是治理叙事，值得在 notes 中占半页。0.7 的方向应由发布后的真实使用体感校准，而不是现在预判。
- **不做**：新增功能站（jobs 视图 / T2 steer 升级 / dump 键序 / ui.html 900 行 T4 重访——全部留 0.7 或后续批）；CI annotations 两条基础设施弃用警告（留到下次碰 `.github/` 的批）；任何新第三方依赖；默认打开写面；改动既有 CLI flag / config 键 / 事件 schema 语义；在发布站修产品缺陷（审计或 rc 发现问题即停，不顺手改）。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：`docs/release-notes-0.6.0.md`；`docs/plan/evidence/release-0.6.0/` 下发布证据档。
- **MODIFIED**：77 个 POM 的版本串（含版本串的 POM）；5 处硬编码版本标识（见下）——`0.5→0.6` / `0.5.0→0.6.0`；`docs/release.md` 0.6.0 化；`README.md` 与 `docs/design/README.md` 路线/状态行；`docs/plan/current-state.md`；`docs/embedding.md` 版本示例与升级路径；`integration/consumer-sample`（pom + README）；`integration/verify-consumer.sh` 发布腿。
- **REMOVED**：无。

## 坐标面（草案算术，翻转批机械复测定案）

0.5.0 后**零新增 POM、零新增模块**（it38 H1/H2 均在既有模块内完成：`bundle/base` 加 `requires interaction.approval`，`examples/headless` 加 delegation/jobs requires；两者均非新坐标）。

| 面 | 0.5.0 | 0.6.0 预期 | 口径 |
|---|---|---|---|
| 发布坐标 | 71 | **71（不变）** | reactor 76 − `excludeArtifacts` 5（examples 3 + dist 2） |
| POM 总数 | 78 | **78（不变）** | 含版本串 77 = reactor 76 + `integration/consumer-sample`；`integration/consumer-sample/negatives/no-version/pom.xml` 无版本串、不入翻转（release.md 旧记「77 POM」口径据此校正） |
| bundle 项 | 1398 | **1398 预期（不变）** | 233 主件（54 叶 ×4 + 17 pom-only）+ 233 `.asc` + 932 校验和；以真 deploy 日志为准 |
| 新坐标 | — | **无** | — |
| 消费方闭包 | 34 | **34（不变）** | workbench 非 bundle 行；`candidate-closure` 维持空 |
| 镜像模块 | 69 | **69 预期（不变）** | 执行期复测 |

**版本串同步面（翻转批硬前置，命中不符即停）**：

```sh
grep -rl "0.6.0-SNAPSHOT" --include=pom.xml . | wc -l   # 翻转前 77；翻转后 0
grep -rnE --include='*.java' 'javanatic-harness/[0-9]+\.[0-9]+' .   # 4 处，全部改为 0.6
grep -rnE --include='*.java' 'clientInfo\.put\("version", "[0-9]' .  # 1 处，改为 0.6.0
```

当前实核（2026-11-24）：UA 四处仍为 `javanatic-harness/0.5`（`PinnedHttpClient` / `DeepSeekWire` 主源 + 同名两测试），Jdtls clientInfo 仍为 `0.5.0`——**与 0.4.0/0.5.0 同族第三次滞后**；本次翻转必须在同一批改齐，不留给收尾批后知后觉。`integration/consumer-sample` 的 pom + README 不在 `versions:set` 范围，手动同步。

**稳定面增量预告**（v0.5.0→0.6.0，执行期核对三法坐实）：

- **零新模块 / 零新导出包 / 零新发布坐标**（55 代码模块 / 59 包不变）；
- `workbench.rpc` 导出包内纯增量：写面 seam `ChatControl` / `ApprovalRelay` + SSE 实现类型；`WorkbenchRpc` / `RpcHttpServer` 增构造重载（既有签名保留）；
- `boot.AppBoot` 增 `bootReported(BootOptions, ApprovalPrompt)` 重载；`ApprovalPrompt` 获得「启动期可替换实现」语义（H1，签名不变）；
- CLI flag **+1**：`--web-write`（缺省关；无 `--serve` exit 2；`--verify --serve --web-write` 为 keyless 纯预检）；
- JSON-RPC 方法 **+4**（`session/start` / `session/send` / `session/cancel` / `approval/respond`）+ **SSE `GET /events`**（显式修约 W4 T1，无条件过 B36/B37 围栏；已在 [12 §6](../design/12-api-stability.md) 列面）；
- **零新会话事件**（`SessionEvent` permits 不变；`chat.questionPending` 是 UI 事实位）、**零新配置键**、**零新 bundle 行**（base 41 行不变）、**零新第三方依赖**；
- keyless `--verify` **34/41/0 不变**（摘要 `composition: 34 rows, 41 discovered, 0 unreferenced`），新增 `web-write: off（缺省）` / `on（--web-write）` 行；
- 非稳定面：审批中继与宿主桥实现落 `examples/headless`；`ui.html` 重设计（830 行）。

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点 | 预期改动 | 完成 |
|---|---|---|
| 77 个含版本串 POM | `0.6.0-SNAPSHOT → 0.6.0` | [ ] |
| `web/fetch-http` `PinnedHttpClient.USER_AGENT` + 同名测试；`web/search-deepseek` `DeepSeekWire.USER_AGENT` + 同名测试 | `javanatic-harness/0.5 → /0.6` | [ ] |
| `lsp/jdtls` `JdtlsProvider` clientInfo | `0.5.0 → 0.6.0` | [ ] |
| `integration/consumer-sample` pom + README | 候选缺省 `0.6.0-SNAPSHOT`、发布件注释 `0.6.0` | [ ] |
| `integration/verify-consumer.sh` | 发布腿 `0.5.0 → 0.6.0` | [ ] |
| `docs/release.md` | 标题 / §0 发布面 / §3 序列 / §4 校验点 / §5 收尾清单 0.6.0 化；校正 78/77 POM 口径 | [ ] |
| `README.md`、`docs/design/README.md`、`docs/plan/current-state.md` | 0.6 系列 ✅ / 0.7 当前行；状态基准收口 | [ ] |
| `docs/embedding.md` | 版本示例与「0.5.0 → 0.6.0 升级面」指针 | [ ] |
| `docs/release-notes-0.6.0.md` | 新建并定稿 | [ ] |

## 审查停点（发布工程无产品代码停点；节点门 = 不可逆动作前的评审门）

| 停点 | 覆盖 | 状态 |
|---|---|---|
| 停 1 = 翻转批 + notes 定稿 + 稳定面审计 | 版本树 / notes / 审计三法 / 坐标复测 / 版本串 5 处；出 packet 等放行后才能进 rc | 未开工 |
| 停 2 = rc 排练全链 | tag `v0.6.0-rc1` → 五腿绿 + master 复跑 + draft 8 归档 + rc 清理实核；出 packet 等放行后才能进 deploy | 未开工 |
| 停 3 = deploy 前对账门 | release 剖面干跑 + bundle 硬门槛预检 + Portal 71 坐标核对单；**真签名上传前须用户点名放行**（Portal Publish 与 GitHub Release Publish 恒用户侧） | 未开工 |
| 草案评审 | 本文件 | 待评审（2026-11-24） |

**升级路径**：若稳定面审计发现非预期签名变化 / 新事件 / 新配置键 / 坐标漂移，停 1 升级为代码停点并报告，不在发布站顺手修。

## 取证（docs/plan/evidence/release-0.6.0/，执行期落盘）

| 文件（预期） | 覆盖 |
|---|---|
| flip-evidence.txt | 翻转批：POM 77→0、总数 78 口径、坐标 71、版本串 5 处、validate / package / `--verify` 复核 |
| audit-stability-surface.txt | 导出面 diff、`jh --help`、`--verify` 计数、permits / 配置键 / bundle 行 / 依赖面 |
| rc1-rehearsal.txt | rc 排练全链（tag run 五绿 + master 复跑 + draft 8 归档 + rc 清理 API 404 实核） |
| deploy-prep.txt | release 剖面干跑绿（gpg.skip；54 叶四件套 + 17 pom-only；bundle 预检） |
| portal-checklist.txt | Portal Publish 前 71 坐标 eyeball 核对单 + 三查 |
| deploy-0.6.0.txt | deploy 实录 + bundle 硬门槛（1398 预期 / `.asc` 齐 / deploymentId / Central 校验） |
| publish-0.6.0.txt | 两处 Publish 落地核验 + repo1 抽检验签 + tag / draft Release 核验 |
| push-landing.txt | 各批 push 落地实核（远端 ref == 本地） |

## 验收（草案，执行期逐项勾选）

- [ ] 四确认评审通过（本草案，2026-11-24）
- [ ] 版本翻转 + 坐标机械复测定案（71 不变；POM 版本串 77→0、总数 78 口径；5 处硬编码版本串改齐；全量 package EXIT=0；`--verify` 34/41/0）
- [ ] release-notes-0.6.0.md 定稿（ADR 0012 叙事 + 围栏先行因果 + 升级必读「行为不变、写面 opt-in」+ 零破坏性变更 + 已知边界）
- [ ] 稳定面差异审计入档（三法重跑：导出面 diff / `jh --help` / `--verify`；零新事件 / 配置键 / bundle 行 / 第三方依赖）
- [ ] rc 排练全链绿（`v0.6.0-rc1`：五腿 + draft 8 归档 + rc 清理无残留）
- [ ] deploy 预检干跑绿（`-P release -Dgpg.skip=true package` EXIT=0；坐标 71 / name 71/71；Portal 核对单落盘）
- [ ] deploy 成功 + bundle 硬门槛（真签名；每主件 `.asc` 齐；deploymentId + Central 校验通过）——**Publish 仍待用户侧**
- [ ] Portal Publish（用户侧，0.6.0 已发布）
- [ ] repo1 抽检验签（71 坐标 200 / sha1 一致 / EDDSA 验签 / metadata latest=0.6.0）
- [ ] tag `v0.6.0` 推送并实核 + tag CI 五腿绿 + draft Release 8 归档核验
- [ ] GitHub Release Publish（用户侧）
- [ ] 收尾批（bump `0.7.0-SNAPSHOT` / Roadmap 翻转 / 发布腿重指 / 版本串同步面 / release.md 0.6.0 化）+ CI 四腿绿 + it39 关账

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| — | — | — |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理 |
|---|---|---|---|
| — | — | — | — |
