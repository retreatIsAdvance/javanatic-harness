# 迭代 37 — 0.5.0 发布工程（状态：已评审放行，开工中（2026-11-17）；停 1 = B36 读面围栏）

**裁决记录（2026-11-17，顺序裁定）**：**先 0.5.0，W5 = 0.6.0**（it38）。理由：版本叙事对齐治理叙事——0.5.0 把「零写面只读工作台」作为一个完整、自洽、可独立讲述的能力相位发布收口；「零写面不变量的终结」是叙事反转，配 ADR 配新 minor 号（0.6.0），patch 号不承载这种重量；发布后 W5 在已发布基线上动写面，回退面干净。全流程复用 [release.md](../release.md) 与 it32（0.4.0）完整先例——零新发明。

**裁决记录（2026-11-17，草案评审三项全落）**：
- **① 四确认通过**：坐标算术自洽（77 POM / 71 坐标，机械复测定案老规矩）；消费闭包口径认可（workbench 三叶非 bundle 行、基础块 34 不变、candidate-closure 默认不挂——Portal 对账 + repo1 抽检双覆盖足够）；稳定面增量预告与用户账完全吻合（+7 CLI flag 逐个对、零新事件/行/键、JLine 4.6.0 三坐标如实入 notes 与审计——「native 不可排除」披露是对外用户需要的诚实）；版本叙事（零写面收口）正确。
- **② B36 读面回补带入 0.5.0，附测试矩阵增项**：方向维持（低成本高价值，发布的是围栏不是欠条）；形状全对（纯函数围栏 + dispatch 前置守卫 + T1 契约不动 + B36 行翻「读面已实施」）。**增项 = Content-Type 严格性**：跨站 JSON POST 理论上被 CORS 预检墙挡，**但「简单请求」形态（form-encoded / text/plain）不触发预检**——若 RPC 端点不强制 `application/json`，简单请求能绕预检直达。钉「**POST 仅收 `Content-Type: application/json`，其余 415**」进围栏（第二层防御，与 Host/Origin 正交，同批突变必红）→ 新边界 **B37**。执行序确认：**回补停点（带增项）→ 翻转批承载 → rc → deploy → Portal 对账 67→71 → tag → 收尾 bump 0.6.0-SNAPSHOT**。
- **③ 文档批 push 授权**：`468297b`（it37 草案 + B36 落位）纯文档，搭在 CI 绿的 `5c61f7c` 上。

基线：it36 已关账（W4 收官 = 0.5「个人工作台」四站全通，CI run 44043766139 四腿全绿）；两笔文档批（`656f60d` + `5c61f7c`）已推送落地（gh api 实核，CI run 44052395779 四腿全绿）；草案批 `468297b` push 已授权（裁决③），工作版本 `0.5.0-SNAPSHOT`。

## 四确认（2026-11-17 评审通过）

- **内容**：
  ① **版本翻转** `0.5.0-SNAPSHOT → 0.5.0`（reactor 76 POM `versions:set` + 非反应器 `integration/consumer-sample` 手动同步 pom + README；翻转批机械复测定坐标数——grep 口径 release.md §3.2，翻转前全仓 77 POM 含 `0.5.0-SNAPSHOT`、翻转后 0）；
  ② **`docs/release-notes-0.5.0.md` 撰写**（主题：0.5 = 个人工作台——只读检视 + 配置干跑，零写面治理叙事；W1 五视图 / W2 TUI / W3 回环 server / W4 单文件 Web UI 四站能力面 + B29/B35 硬线 + 新坐标面 + JLine 依赖面如实）；
  ③ **rc 排练**（`v0.5.0-rc1` tag 全链：四腿 + release-attach + draft 归档 + rc 清理走 gh api——0.3.0/0.4.0 先例）；
  ④ **deploy**（`-P release`，真签名；GPG pinentry 预案在案——gpgconf --reload + 探测签名热缓存）+ **Portal 审核 Publish（用户侧）**——**Portal 前对账 67→71**（新 4 坐标口径 + 三查，核对单随取证落盘）；
  ⑤ **tag `v0.5.0` + GitHub Release**（四平台归档，release-attach 自动 draft，**Publish 恒用户侧**）；
  ⑥ **收尾批**（bump **`0.6.0-SNAPSHOT`**、README×2 Roadmap 翻转、发布腿重指 `0.4.0→0.5.0`、版本串同步面全局 grep 机械枚举、release.md 0.5.0 化）；
  ⑦ **B36 读面防御性回补（已裁带入 + Content-Type 增项 → B37，见下节）**；
  ⑧ **稳定面差异审计 v0.4.0→0.5.0**（it32 先例核对三法：导出面 diff / `jh --help` / `--verify` 计数；本版增量预告见下）。
- **目标**：0.5.0 上线 Maven Central（**坐标预期 71**，翻转批机械复测定案）+ GitHub Release 四平台归档；流程零新发明——全部照 0.4.0 先例机械复用。
- **为什么**：0.5 四站验收齐（it33–it36 全关账）；「零写面只读工作台」作为完整能力相位发布收口（治理叙事干净）；首批 0.5.0 用户得到观察 + 配置干跑的完整工作台，写面留 0.6.0 配 ADR 显式终结零写面不变量。
- **不做**：W5 任何写面（it38，B36 写面准入条件在那站落地）；jobs 面（W4 T2 延后，it38 四确认议程）；运行中热重载（维持不做，重访条件在 boundary-register）；B36 写面实现（本站只评估读面回补）。

## 坐标面（草案算术，翻转批机械复测定案）

0.4.0 后新增 workbench 组 **4 POM**（聚合器 1 + 叶 3：projection / rpc / tui）——全仓 73→**77 POM**（reactor 76 + 非反应器示例 1）。

| 面 | 0.4.0 | 0.5.0 预期 | 口径 |
|---|---|---|---|
| 发布坐标 | 67 | **71**（+4） | pom-only 17（根 1 + 聚合 16，排除 examples/dist 聚合 2）+ 叶 54（排除 examples 叶 2 + dist-jh 1 + consumer-sample 1） |
| 新坐标 | — | `harness-workbench-aggregator`（pom-only）+ `harness-workbench-projection` / `-rpc` / `-tui`（jar 四件套） | 四 POM 均自带 `<name>`（it16 教训预检已过，2026-11-17 实核） |
| bundle 项 | 1320 | **1398 预期** | 233 主件（54 叶 ×4 + 17 pom-only）+ 233 `.asc` + 932 校验和（4 种 ×233）；以真 deploy 日志为准 |
| 排除面 | 5 | 5 不变 | `excludeArtifacts` 实闸不动（examples 3 + dist 2） |

**消费方闭包口径**：workbench 三叶是发布坐标但**非 bundle 行**（headless 直依赖面，it35 停 2 装配）——基础块 34 条不变、`candidate-closure` 维持空；发布腿重指 0.5.0；repo1 抽检清单**加 workbench 新 4 坐标**（十项抽检扩面）。备选：candidate-closure 暂挂三叶做解析验证（发布后不折基础块——非 bundle 行）；草案默认不挂（Portal eyeball + repo1 抽检已覆盖），评审可推翻。

**稳定面审计增量预告**（v0.4.0→0.5.0，执行期核对三法坐实）：纯增量 4 新模块 / workbench 三新导出包 + `session/persistence-jsonl` tail seam 新导出（it34）+ `Follower.close` 赋义（it35）；CLI flag +7（`--session`/`--session-view`/`--workbench`/`--follow`/`--serve`/`--port`/`--dump`）；**零新事件、零新 bundle 行、零新配置键**（it33–it36 各站收口明示）；`--verify` 34/41/0 不变（it36 收口实核）；**新第三方依赖 = JLine 4.6.0 三坐标**（it34，native 不可排除——release notes 与审计如实记）+ jdk.httpserver 回镜像（JDK 模块非第三方）。

## B36 读面防御性回补（已裁：带入 0.5.0 + Content-Type 增项）

- **现状实核**（2026-11-17）：`RpcHttpServer.dispatch` 是唯一入口，Host/Origin 检查为零。读面 POST 已可被跨站驱动（SOP 只挡读响应不挡发请求）——风险低于写面但非零（读操作可被任意网站触发）。
- **回补形状**：纯函数信任围栏（Host 白名单 `127.0.0.1:<port>`/`localhost:<port>`，port = 实际绑定面 + Origin 存在即须同源）+ dispatch 头部守卫（先于路由）；零新依赖；UI 同源请求与 curl runbook（Host 天然匹配）不受影响；W4 T1 路由契约不变——围栏是横切前置条件，显式记契约增量（设计文档 + B36 行状态翻「读面已实施」）。
- **成本**：小——1 纯函数 + 1 守卫调用 + 测试矩阵（Host 白名单 / Origin 跨站拒 / 缺失 Host / 端口不匹配）+ 突变必红；纯函数提取使分支本地可测可突变（平台分支验证规矩）。
- **推荐：带入 0.5.0**——**用户已裁带入**（裁决②，2026-11-17），附 Content-Type 严格性增项（B37）。it37 = 发布工程 + **1 个代码停点**（安全不变量，TDD + 突变 + packet 评审——it32 先例：发布迭代可携小项，彼次描述面、此次代码面带停点）；执行序 = 回补停点放行 → 翻转批（版本树承载回补）→ rc → deploy → Portal → tag → 收尾。
- **备选（已否）**：不带入顺延 it38——裁决②取带入。
- 停 1 设计稿：[stop1-fence-design.md](evidence/iteration-37/stop1-fence-design.md)（形状 / 测试矩阵 / 突变清单 / 同步面 / 不做）。

## 审查停点

- **停 1 = 读面信任围栏 + Content-Type 闸——已放行（2026-11-18 用户评审通过，围栏形状全对）**：用户独立复跑 rpc 98 例全绿（含停 1 的 46 面）+ validate EXIT=0 + 树净。三处设计质量点名：① 围栏覆盖 ALL 方法含 GET——raw Socket 恶 Host 闸前 200 UI / 闸后 403 钉死的不只是 CSRF，**DNS rebinding 对 HTML 页读取面也关了**（rebinding 下 Host=攻击者域名被拒——Host 检查比 Origin 更本质的那一半，测试面一并覆盖）；② `::1` 显式不在白名单与 IPv4-only 绑定自洽（::1 在 connect 层即拒，浏览器 happy-eyeballs 回落 127.0.0.1），窄白名单姿态正确；③ 两处如实注成立（+4 例钉规范化规则否则突变不可观察；M3 null 早退分支由 `missingContentTypeRejected` 独立钉死，覆盖自洽）。TDD 红绿两段 + 突变 M1–M4 必红复绿 + 全量 1521/0/0/31 + keyless 34/41/0 不变；取证 [stop1-tdd-evidence.md](evidence/iteration-37/stop1-tdd-evidence.md)，packet [stop1-review-packet.md](evidence/iteration-37/stop1-review-packet.md)，设计稿 [stop1-fence-design.md](evidence/iteration-37/stop1-fence-design.md)。**放行 → 翻转批起步**。
- 发布工程本体 = **流程节点门**（无代码停点）：rc 排练绿 → deploy → Portal 用户审核 → tag；每节点取证落盘。
- 草案评审 = 已通过（2026-11-17 三项裁决，见首部）。

## 取证（docs/plan/evidence/release-0.5.0/，执行期落盘）

| 文件（预期） | 覆盖 |
|---|---|
| push-landing.txt | 文档批 push 落地实核（gh api 远端 ref；伪完成通知 + 3 轮耗尽再重启教训在案） |
| （evidence/iteration-37/） | B36/B37 读面回补：stop1-fence-design.md + stop1-tdd-evidence.md + stop1-review-packet.md |
| portal-checklist.txt | Portal Publish 前 71 坐标 eyeball 核对单（新 4 坐标口径 + 三查） |
| rc1-rehearsal.txt | rc1 排练全链（tag run 五绿 + master 复跑 + draft 8 归档 + rc 清理 API 404） |
| deploy-prep.txt | deploy 预检：release 剖面干跑绿（gpg.skip，54 叶四件套齐全 / 1521/0/0/31 / 0.5.0 .asc=0）+ 坐标 71/name 71/71 复核 + release profile 配置实核 + 用户侧门 |
| deploy-0.5.0.txt | deploy 实录 + bundle 硬门槛（1398 预期项 / 71 坐标 / 零 .asc 缺失） |
| publish-0.5.0.txt | 两处 Publish 落地核验 + repo1 抽检验签（扩 workbench 新坐标） |
| audit-stability-surface.txt | 稳定面差异审计 v0.4.0→0.5.0 + 核对三法重跑 |

## 验收（草案，执行期逐项勾选）

- [x] 草案评审通过（四确认 + B36 回补带入 + Content-Type 增项 + 闭包口径，2026-11-17 用户三项裁决）
- [x] B36/B37 读面回补停 1 放行（TDD + 突变必红 + 契约增量同步；2026-11-18 用户评审通过，rpc 98 例独立复跑全绿）
- [x] 版本翻转 + 坐标机械复测定案（71 = reactor 76 − examples 3 − dist 2；grep 0.5.0-SNAPSHOT POM 77→0；全量 package EXIT=0，187 XML = 1521/0/0/31 不变；[flip-evidence.txt](evidence/release-0.5.0/flip-evidence.txt)）
- [x] release-notes-0.5.0.md 定稿（零写面治理叙事 + 四站 Highlights + B36/B37 围栏节 + 升级必读纯增量枚举 + JLine 三坐标 native 不可排除如实 + 已知边界；[notes](../release-notes-0.5.0.md)）
- [x] rc 排练全链绿（tag v0.5.0-rc1→d321d06；tag run 44228781902 五绿 + master run 44226672669 attempt 2 四绿（windows McpStdioRecoveryTest spawn flake，rerun 绿）+ draft 449484005 8 归档占位正文 + rc 清理远端 tag ref 404 实核无残留；[rc1-rehearsal.txt](evidence/release-0.5.0/rc1-rehearsal.txt)）
- [x] deploy 预检干跑绿（`mvn -B -P release -Dgpg.skip=true package` EXIT=0，BUILD SUCCESS；54 叶四件套齐全 + 17 pom-only；187 XML = 1521/0/0/31 不变；0.5.0 .asc=0 属 gpg.skip 预期；坐标 71 / name 71/71 复核；[deploy-prep.txt](evidence/release-0.5.0/deploy-prep.txt) / [portal-checklist.txt](evidence/release-0.5.0/portal-checklist.txt)）
- [ ] deploy 成功 + bundle 硬门槛（.asc 齐全 / 零残留 / 坐标数对账）——**用户侧真签名（GPG passphrase 框输）+ Portal 上传；停在对账门等用户**
- [ ] Portal Publish（用户侧）+ repo1 抽检验签（含 workbench 新坐标）
- [ ] tag v0.5.0 + GitHub Release 发布（用户侧 Publish）
- [ ] 收尾批（bump 0.6.0-SNAPSHOT / Roadmap 翻转 / 发布腿重指 / 版本串同步面 / release.md 0.5.0 化）+ CI 四腿绿
- [ ] 稳定面差异审计入档