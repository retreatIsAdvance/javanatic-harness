# 迭代 37 — 0.5.0 发布工程（状态：草案待评审（2026-11-17）；用户新鲜点名后开工）

**裁决记录（2026-11-17）**：顺序裁定——**先 0.5.0，W5 = 0.6.0**（it38）。理由：版本叙事对齐治理叙事——0.5.0 把「零写面只读工作台」作为一个完整、自洽、可独立讲述的能力相位发布收口；「零写面不变量的终结」是叙事反转，配 ADR 配新 minor 号（0.6.0），patch 号不承载这种重量；发布后 W5 在已发布基线上动写面，回退面干净。全流程复用 [release.md](../release.md) 与 it32（0.4.0）完整先例——零新发明。B36 读面防御性回补 = 本迭代**决策项**（用户点名评估，见下节）。

基线：it36 已关账（W4 收官 = 0.5「个人工作台」四站全通，CI run 44043766139 四腿全绿），本地 HEAD `5c61f7c`（W5 预登记 + it36 CI 回填两笔文档批，push 已授权、落地取证随开工批入 `evidence/release-0.5.0/`），工作版本 `0.5.0-SNAPSHOT`。

## 四确认（草案，待评审）

- **内容**：
  ① **版本翻转** `0.5.0-SNAPSHOT → 0.5.0`（reactor 76 POM `versions:set` + 非反应器 `integration/consumer-sample` 手动同步 pom + README；翻转批机械复测定坐标数——grep 口径 release.md §3.2，翻转前全仓 77 POM 含 `0.5.0-SNAPSHOT`、翻转后 0）；
  ② **`docs/release-notes-0.5.0.md` 撰写**（主题：0.5 = 个人工作台——只读检视 + 配置干跑，零写面治理叙事；W1 五视图 / W2 TUI / W3 回环 server / W4 单文件 Web UI 四站能力面 + B29/B35 硬线 + 新坐标面 + JLine 依赖面如实）；
  ③ **rc 排练**（`v0.5.0-rc1` tag 全链：四腿 + release-attach + draft 归档 + rc 清理走 gh api——0.3.0/0.4.0 先例）；
  ④ **deploy**（`-P release`，真签名；GPG pinentry 预案在案——gpgconf --reload + 探测签名热缓存）+ **Portal 审核 Publish（用户侧）**——**Portal 前对账 67→71**（新 4 坐标口径 + 三查，核对单随取证落盘）；
  ⑤ **tag `v0.5.0` + GitHub Release**（四平台归档，release-attach 自动 draft，**Publish 恒用户侧**）；
  ⑥ **收尾批**（bump **`0.6.0-SNAPSHOT`**、README×2 Roadmap 翻转、发布腿重指 `0.4.0→0.5.0`、版本串同步面全局 grep 机械枚举、release.md 0.5.0 化）；
  ⑦ **B36 读面防御性回补（决策项，见下节——推荐带入）**；
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

## B36 读面防御性回补（用户点名评估项——决策点）

- **现状实核**（2026-11-17）：`RpcHttpServer.dispatch` 是唯一入口，Host/Origin 检查为零。读面 POST 已可被跨站驱动（SOP 只挡读响应不挡发请求）——风险低于写面但非零（读操作可被任意网站触发）。
- **回补形状**：纯函数信任围栏（Host 白名单 `127.0.0.1:<port>`/`localhost:<port>`，port = 实际绑定面 + Origin 存在即须同源）+ dispatch 头部守卫（先于路由）；零新依赖；UI 同源请求与 curl runbook（Host 天然匹配）不受影响；W4 T1 路由契约不变——围栏是横切前置条件，显式记契约增量（设计文档 + B36 行状态翻「读面已实施」）。
- **成本**：小——1 纯函数 + 1 守卫调用 + 测试矩阵（Host 白名单 / Origin 跨站拒 / 缺失 Host / 端口不匹配）+ 突变必红；纯函数提取使分支本地可测可突变（平台分支验证规矩）。
- **推荐：带入 0.5.0**（用户裁定方向「低成本高价值，0.5.0 里带上更好」）。带入则 it37 = 发布工程 + **1 个代码停点**（安全不变量，TDD + 突变 + packet 评审——it32 先例：发布迭代可携小项，彼次描述面、此次代码面带停点）；执行序 = 回补停点放行 → 翻转批（版本树承载回补）→ rc → deploy → Portal → tag → 收尾。
- **备选**：不带入，0.5.0 纯发布；B36 读面回补顺延 it38 首停（与写面准入一并落地）。
- **待用户裁**（评审本草案时一并点名）。

## 审查停点

- 若 B36 回补带入：**停 1 = 读面信任围栏**（TDD 红绿 + 突变必红 + packet，停下等放行）。
- 发布工程本体 = **流程节点门**（无代码停点）：rc 排练绿 → deploy → Portal 用户审核 → tag；每节点取证落盘。
- 草案评审 = 本文档用户裁定点（四确认 + 回补取舍 + 闭包口径）。

## 取证（docs/plan/evidence/release-0.5.0/，执行期落盘）

| 文件（预期） | 覆盖 |
|---|---|
| push-landing.txt | 两笔文档批 push 落地实核（gh api 远端 ref；四回合波折教训在案——后台 exit 0 不可信） |
| backfill-stop1-*.txt | （若带入）B36 读面回补 TDD + 突变 + packet |
| portal-checklist.txt | Portal Publish 前 71 坐标 eyeball 核对单（新 4 坐标口径 + 三查） |
| rc1-rehearsal.txt | rc1 排练全链（tag run 五绿 + master 复跑 + draft 8 归档 + rc 清理 API 404） |
| deploy-0.5.0.txt | deploy 实录 + bundle 硬门槛（1398 预期项 / 71 坐标 / 零 .asc 缺失） |
| publish-0.5.0.txt | 两处 Publish 落地核验 + repo1 抽检验签（扩 workbench 新坐标） |
| audit-stability-surface.txt | 稳定面差异审计 v0.4.0→0.5.0 + 核对三法重跑 |

## 验收（草案，执行期逐项勾选）

- [ ] 草案评审通过（四确认 + B36 回补取舍 + 闭包口径，用户新鲜点名）
- [ ] （若带入）B36 读面回补停 1 放行（TDD + 突变必红 + 契约增量同步）
- [ ] 版本翻转 + 坐标机械复测定案（71 预期）+ 全量 package 绿
- [ ] release-notes-0.5.0.md 定稿
- [ ] rc 排练全链绿（tag run 五绿 + master 复跑 + draft 8 归档 + rc 清理）
- [ ] deploy 成功 + bundle 硬门槛（.asc 齐全 / 零残留 / 坐标数对账）
- [ ] Portal Publish（用户侧）+ repo1 抽检验签（含 workbench 新坐标）
- [ ] tag v0.5.0 + GitHub Release 发布（用户侧 Publish）
- [ ] 收尾批（bump 0.6.0-SNAPSHOT / Roadmap 翻转 / 发布腿重指 / 版本串同步面 / release.md 0.5.0 化）+ CI 四腿绿
- [ ] 稳定面差异审计入档