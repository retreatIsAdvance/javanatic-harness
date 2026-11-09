# 迭代 32 — 0.4.0 发布工程 + 委派策略指引（状态：进行中——2026-11-09 四确认获准）

**裁决记录（2026-11-09）**：八件内容 + 零新发明 + 流程节点门全批；**retention = A（v1 不自动清理）**——理由：静默删用户数据违背显式性品格 / 与 ~/.harness/sessions 无自动清理一致（最小惊讶）/ 每任务受 cap 限累积有界；B33 重访条件两条（磁盘压力实测 / 用户请求会话级联动清理，候选形状：dataDir/<sessionId>/<jobId>/ 重构使删会话即清遗物成自然语义——代码改动留后续迭代）。坐标数 65 系草案笔误，正确算术 59+4+4=**67**，翻转批机械复测定案（grep 口径同步 release.md §3.2）。

基线：it30/it31 均已关账（0.4 两站齐），master `ff4a90a`，工作版本 `0.4.0-SNAPSHOT`。全流程复用 [release.md](../release.md) 与 0.3.0 完整先例（run 实录 `evidence/release-0.3.0/deploy-0.3.0.txt`：0.12.0 工具链、gpg-agent kill/launch 预案、aliyun 代理滞后 + 直连复核、tag 指向纪律）。

## 四确认

- **内容**：①版本翻转 `0.4.0-SNAPSHOT → 0.4.0`（reactor 全 POM + 非反应器同步：consumer-sample 缺省值与 02 POM 片段；candidate-closure 现有 jobs/delegation 四坐标 = 本版候选面，发布后随收尾折回基础块）；②**委派策略指引**（it32 小项：delegate 族工具描述三合一——何时委派决策判据（ADR 0010 + ≥~40k 边界）/ 问态可发现性 / 等待-轮询引导；草稿已备，随版本树入 0.4.0）；③`docs/release-notes-0.4.0.md` 撰写（主题：0.4 = 可管理后台任务 + 受控委派；含 ADR 0010 判据与边界发现的使用指引——对外用户同样需要「何时委派」）；④rc 排练（`v0.4.0-rc1` tag 全链：四腿 + release-attach + draft 归档）；⑤deploy（`-P release`，0.12.0 已在树）+ Portal 审核 Publish（**用户侧**）；⑥tag `v0.4.0` + GitHub Release（四平台归档，Publish 用户侧）；⑦收尾批（bump `0.5.0-SNAPSHOT`、README×2 Roadmap 翻转、发布腿重指 `0.3.0→0.4.0`、版本串同步面）；⑧**retention 裁决**（B30 挂账收口：jobs dataDir 遗物清理策略——拟裁「v1 不自动清理，遗物随用户 dataDir 自管理」+ B 行重访条件，或给出策略，二选一用户裁）。
- **目标**：0.4.0 上线 Maven Central（预期坐标 65：it30/it31 各增 5 模块）+ GitHub Release 四平台归档；委派指引随 0.4.0 交付（模型面自带实测判据）；流程零新发明——全部照 0.3.0 先例机械复用。
- **为什么**：0.4 两站验收齐（it30 双宿主真机 + 模型在环；it31 双臂价值主张 61.6% 决定性达成），路线进入条件满足；指引与发布同批使首批 0.4.0 用户直接受益于双臂结论（行为经济学入说明书，模型面与人面 W 段共用）。
- **不做**：0.5 工作台任何面（W1–W4 另开迭代，B29/终端库裁决已在案）；问态可发现性的**机制**改动（只做描述层——机制面 NeedsParentInput 已达且集成证绿）；Web UI 非目标改判（W3 前不动）。

## 审查停点

无代码停点（发布工程 = 流程节点门：rc 排练绿 → deploy → Portal 用户审核 → tag；工具描述改动属文案面，随 rc 排练与模型在环自验）。retention 裁决 = 本文档内用户裁定点。

## 取证（docs/plan/evidence/release-0.4.0/）

| 文件 | 覆盖 |
|---|---|
（翻转 / rc 排练 / deploy 实录 / 收尾，照 release-0.3.0 目录形制随节点落） |

## 验收

- [ ] 版本翻转 + 全绿（validate + 全量 package）
- [ ] 委派指引入描述 + 聚焦测试更新（描述文本钉死面）
- [ ] release-notes-0.4.0.md 定稿（含判据指引节）
- [ ] rc 排练全链绿（tag CI 四腿 + release-attach + draft 归档，rc 清理）
- [ ] deploy 成功 + bundle 硬门槛（.asc 齐全 / 零残留 / 坐标数对账）
- [ ] Portal Publish（用户侧）+ repo1 抽检验签
- [ ] tag v0.4.0 + GitHub Release 发布（用户侧 Publish）
- [ ] 收尾批（bump / Roadmap / 发布腿 / 同步面）+ CI 四腿绿
- [x] retention 裁决落 B 行（B33 = A 案 + 两条重访条件，随翻转批入表）
