# 迭代 25.1 — 0.2.0 发布工程（S-d 甩出）（状态：**进行中**（放行 2026-09-29——四确认核可；aarch64 裁决 = 选 2「双 arch CI job 事实源」，降级选 3 预授权；停点 A：首跑红 → 裁决 F1（arm 腿改 microsoft）、二跑红 → 裁决 R1（侦察 delta 加单引号）→ 侦察三跑全绿（run 36548135323）→ 摘旗标并回 master、真跑全绿（run 36549993644，四 job 全绿 + 三件归档在案）→ **停点 A 闭合、首裁项闭合**；2026-09-29））

模块：发布面文档（`README.md` / `README.zh-CN.md` / `docs/release.md` / `docs/design/12-api-stability.md` / `docs/design/README.md`）· 根 POM 与全仓 POM（版本翻转）· `.github/workflows/`（tag→Release 附件工作流定义）· aarch64 交付面（裁决后落地）

路线来源：it25 验收末项「发布工件与稳定面核对齐全」（`docs/design/README.md:147`）——it25 收口裁决（2026-09-29）：「S-d 甩成 it25.1」（D2 预授权行使：S-d 天然可分离、仍属 0.2.0、零损失——见 `docs/plan/iteration-25.md` 裁决记录）。it24 点名推迟项「tag→GitHub Release 附件自动化」（`docs/release.md` §5 注 / `.github/workflows/ci.yml:112` 注释 / it25 设计增量）归本迭代回收；it25 台账验收末行与证据表 S-d 行已随收口移交（`docs/plan/iteration-25.md:147/:136`）。

## 四确认

- **内容**（七项工作清单——2026-09-29 收口裁决逐字定；**aarch64 交付三选一列首个待裁**）：
  1. **aarch64 交付（首个待裁）**：官方 Windows 交付的架构面三选一——
     - **A｜CI 腿出 aarch64**：实探 GitHub arm64 Windows runner（标签/可用性/额度/预置工具面）——可用则加 job 腿真构建 `windows-aarch64` 归档并上传（Release 附件取自 CI artifact；纯 CI 脚本面）；
     - **B｜发布时 VM 手工构建**：ARM64 VM 真构建（已有真先例：`windows-aarch64` zip/tar.gz，sha `49a0c578…` / `98ea576c…`，解压冒烟 A–H 腿过），归档纳入 Release 附件（jlink 镜像与平台绑定——本机 macOS 不可跨架构出件，故构建必在 Windows 宿主；真执行用户侧）；
     - **C｜维持 x64 官方面**：官方交付 = CI runner 产出的 `windows-amd64`（六跑已真构建 + 冒烟 + upload）；aarch64 不进 Release，README 记「按需构建」。
     - 现状事实（判据）：CI 六跑（run 36534900915）产出 `javanatic-harness-0.2.0-SNAPSHOT-windows-amd64.{tar.gz,zip}`；真 VM（ARM64）产出 `windows-aarch64` 同名两归档——两归档同构、仅架构面不同。x64 归档在 ARM64 宿主经仿真可跑与否**未实测**，不作为 C 的替代论证。
  2. **版本翻转**：`0.2.0-SNAPSHOT` → `0.2.0`（`mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false`，全仓 POM 各一处；翻转后 `rm -rf dist/jh/target` 防 jlink「Two versions of module」；提交 `chore(release): 0.2.0`——tag 必须指向承载该 commit 的同一提交）+ 发布前预检（§2：全量 `mvn -B package` 绿 + `-P release -Dgpg.skip=true package` 干跑；每个发布 POM 自带 `<name>` 硬校验项在案）。真 deploy / tag / Portal Publish 按 `docs/release.md` §0 归用户侧。
  3. **12 全量扫描**：`docs/design/12-api-stability.md` §2 导出面与现状逐条核对（it17–it25 破坏面与迁移注记逐条在案、无未登记破坏面——预期以 shell 坐标/工具名/插件 id 迁移、入口与 CLI 面变化为主；扫描结论随证据档落盘）。
  4. **release notes（含已知残余清单）**：it17–it25 破坏性变更与迁移路径汇总（以 12 在案条目为准——0.1.0 → 0.2.0 迁移对照：[接入指南](../embedding.md) 已有表行）+ **已知残余如实登记**（windows-acl PARTIAL 两洞：可写面 = 主机一切 Low 标签对象 / NTFS 硬链接别名越界；landlock `/dev/null` 写入按设计拒绝——D4 终裁 A；Windows 容器不支持（POSIX 宿主门）；locale 面已修、边界如实）。落点提议：独立 `docs/release-notes-0.2.0.md`（GitHub Release 采用稿；评审可改）。
  5. **README×2 平台反转 + pwsh 安装前提**：`README.md:159` / `README.zh-CN.md:149` 平台段按 0.2.0 实况反转（现文案「Windows 暂不在支持面……随 0.2.0 发布进入支持面」⇒ 实况化：支持矩阵 macOS seatbelt / Linux bwrap→landlock / Windows windows-acl（PARTIAL 如实））；**pwsh 安装前提明写**（PowerShell 7+ 必需、Windows PowerShell 5.1 不支持；pwsh 缺席 fail-loud 点名——S-0 真机事实）；双语文案同步。
  6. **tag→Release 附件工作流（定义落盘；真执行用户侧）**：it24 点名回收——工作流定义（tag 触发：构建归档并按第 1 项裁决面附 GitHub Release；产出命名与现有 `windows-${os.arch}` / `linux-amd64` 归档口径对齐）；真 tag / Release 上传执行 = 用户侧。
  7. **S-b·S-c 挂账记 post-0.2 backlog（只记不动）**：清单现落两处——it25 台账「后续」节（协议词表共享抽取；`WindowsAcl.Invocation` sealed 化；`wide()` 改名；`isReparsePoint` 死参清理；洞 2 消除）与 S-c 证据档 §7.3（`matchesDialect` / `StreamDrain` 去重抽取；`ShellPlatform` enum/sealed 化；`ShellToolPlugin` `tool` 改名；windows-acl 两洞消除），另有 CI 建链特权面环境口径（S-c 档 §9：runner 用户 `runneradmin` 语境，不出「非提权用户」事实）——0.2 窗口外，记 post-0.2 backlog，本迭代不修。

- **目标**：0.2.0 发布面就绪——发布工件与稳定面核对齐全（12 全量扫描）、迁移路径与已知残余成文、README×2 / 发布手册与实况一致、附件自动化工作流定义在案、aarch64 交付面裁定落地；真发布动作（deploy / tag / Release / Central Publish）留给用户侧凭据与授权。

- **验收问题**（每轮一问）：*0.1.0 的既有用户与新用户，能否只凭 README + release notes + 迁移对照完成 0.2.0 升级与首个任务？*——发布面文档从零走查，逐条对照实际发布工件口径（含平台矩阵与 pwsh 前提）。

- **为什么**：
  - it25 末项验收是 0.2.0 版本承诺的兑现窗口；S-d 天然可分离（D2 裁决：S-c 出口带证据决定甩出，零损失），六跑三 job 全绿即出口（2026-09-29 收口裁决）；
  - 0.2.0 含破坏面（shell 坐标/工具名/插件 id 迁移已落仓），消费方凭 0.1.0 口径升级必踩坑——「破坏稳定面的调整随 0.2.0，附 release notes 与迁移路径」是设计落定的硬要件（`docs/design/README.md:130`、`12-api-stability.md:10`）；
  - Windows 交付实况（pwsh 前提、windows-acl PARTIAL 两洞、x64/aarch64 架构面）必须与 README 口径一致，不能以 macOS/Linux 视角发布；
  - it24/it25 双重点名推迟的 tag→Release 附件自动化（`docs/release.md` §5 注、`ci.yml:112`、it25 设计增量）在本迭代回收——发布动作仍属用户侧。

- **不做**：
  - 真 tag / GitHub Release 上传 / Central Publish / GPG 签署部署的执行（用户侧——凭据与不可逆公开授权；`docs/release.md` §0 归属与 it16 先例）；
  - S-b·S-c 挂账内容本身（记 post-0.2 backlog，只记不动，不顺手修）；
  - 0.1.x 已发布面语义回改（0.2.0 破坏面走迁移注记）；
  - windows-acl 两洞消除、Windows 容器、WSL/git-bash 通道、MSI/安装器/服务化（it25 既定不做面延续）；
  - 产品行为变更与新功能（本迭代 = 发布工程面，零产品代码行为变更目标）；
  - 0.3 能力面（skills / MCP Tools——版本窗口外）。

## 裁决记录

**放行（2026-09-29）**：四确认（内容 / 目标 / 为什么 / 不做）核可；aarch64 交付面按四条裁决落地（「选 2」= 首项 A｜CI 腿出 aarch64；「选 3」= 首项 C｜维持 x64 官方面）：

1. **选 2**：`.github/workflows/ci.yml` 追加 `windows-11-arm` job，镜像现有 windows job 八步（build+test → windows-acl 探针硬门 → e2e 点名断言 → jlink 冒烟 → 归档冒烟 → upload）；两个架构一个事实源，Release 附件从双 job 收件。VM 手工产物（`windows-aarch64` zip/tar.gz，sha `49a0c578…` / `98ea576c…`）降级为证据引用、**永不作发布件**（不可 CI 复现的产物不进发布面）。
2. **三个 arch 特异点预先在案**：① `setup-java` 需 `architecture: aarch64`（temurin 有 windows-aarch64 JDK 25——ARM64 VM 已证产品面）；② 归档名走 `windows-${os.arch}`（dist/jh pom profile）自动得 `windows-aarch64`，与 VM 命名一致；③ 容器 e2e 已 POSIX 门、docker 面零涉，pwsh 7 为镜像自带（ARM64 原生）。
3. **首跑即首验面**：该 job 首轮按既有纪律**停-取证-再议**；首次用 scratch 分支侦察跑（`-Dmaven.test.failure.ignore=true` 一次收全清单）压缩轮次。
4. **预授权降级**：若 arm runner 出现可用性/排队问题致不可靠，降级到选 3（0.2 只承诺 amd64；aarch64 以「实验性」文档化并引 VM + arm job 最好一轮的证据）——**无需再开一轮裁决**。

**缺口标注（随停点 A 提请裁决）**：裁决第 3 条所称「此前回填的侦察跑规则」在仓内（AGENTS.md / docs / .github，`侦察|recon|failure.ignore` 全词检索）与记忆中均未见原文——本次按其表述内容执行；该规则是否回填、落点（AGENTS.md CI 段 / 10-testing）待裁决。

**停点 A 裁决（2026-09-29，首跑红后；裁决文本四条）**：

1. **归因核可**：首跑红（run `36543615310`）根因为 temurin 无 windows-aarch64 的 JDK 25（Adoptium API 独立复核为空集）——证伪源头为上游「放行裁决」的输入事实，非实现缺陷。**采 F1**：arm job `distribution: temurin → microsoft`；`ci.yml` 注释同步校正为 microsoft 清单实况（setup-java 运行时读其 main 清单，25.0.x win32/aarch64 在案）。F2（zulu，引入第三家 JDK 族）弃；F3（raw 下载）/F4（降级选 3）维持不采。
2. **规则回填**：AGENTS.md 已知坑补「新平台/新 CI job 首落地先侦察跑」条目（含 it25 六轮与本轮实证出处）——书面缺口补齐。
3. **收口序列**：F1 落 scratch → 侦察二跑（`-Dmaven.test.failure.ignore=true` 本轮生效，收全测试面）→ 干净则摘侦察旗标、scratch 并回 master、单次 push → CI 真跑。master 全绿即 aarch64 选 2 落证、首裁项闭合。
4. **纪律不变**：侦察/真跑任一轮红即停取证；master 在收口前零合并。

首跑证据档：`docs/plan/evidence/iteration-25.1/S-a-arm-job-first-run.txt`（run/失败全文/三特异点对账/厂商探针/源码线索/F 选项）。

**侦察二跑裁决（R1）（2026-09-29，二跑红后；裁决文本四条）**：

1. **归因核可**：二跑红（run `36546502989`）隔离在 scratch-only 侦察 delta 的 pwsh 点号截断（F1 已证生效；landing 形态零裸 `-D` token；与 it25 五跑同族、被 AGENTS.md 自家条目预言——配方与兄长条目未交叉核对属回填教训）。**采 R1**：`ci.yml` 侦察 delta 的 token 加单引号 → 侦察三跑（收全 ARM 测试面清单）；R2（直接摘旗标省一轮）不采。
2. **AGENTS.md 同提交两处**：侦察跑配方补「pwsh 步必须单引号（见上条 pwsh 实参坑）；bash 步无须」并与 pwsh 条目互指；按「先删后加」压缩回 ≤200 行（201 → 200）。
3. **收口序列不变**：侦察三跑干净 → 摘旗标 → scratch 并回 master → 单次 push → 真跑；master 三+一全绿即 aarch64 交付（选 2）落证、首裁项闭合。
4. **纪律不变**：任一轮红即停取证；master 收口前零合并。

二/三/真跑证据：`docs/plan/evidence/iteration-25.1/S-a-arm-job-first-run.txt` §10（二跑实况与 R1/R2 待裁）· §11（三跑全绿实况与判定）· §12（真跑全绿、选 2 落证、停点 A 闭合）。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - `.github/workflows/ci.yml` 新 job `windows-arm`（`runs-on: windows-11-arm`）——aarch64 发布件事实源，与 `windows` 腿构成双 arch 收件面（裁决·选 2）。
  - `docs/release-notes-0.2.0.md`（计划落点，评审可改）——0.2.0 迁移路径 + 已知残余清单。
  - tag→Release 附件工作流定义（第 6 项工作时定落点：ci.yml 增补或新 workflow）。
- **MODIFIED**：
  - `README.md` / `README.zh-CN.md` 平台段：Windows 由「随 0.2.0 进入支持面」反转为实况支持矩阵（seatbelt / bwrap→landlock / windows-acl PARTIAL 如实）+ pwsh 7+ 安装前提（PS 5.1 不支持）。
  - `docs/design/12-api-stability.md`：全量扫描核对为主；仅当发现缺登记项时补。
  - `docs/release.md`：附件自动化由「点名推迟」改为在案（第 6 项落地时）。
  - 全仓 POM：`0.2.0-SNAPSHOT` → `0.2.0`（版本翻转，机械；tag 指向该提交）。
- **REMOVED**：产品面零移除；README×2 旧措辞「Windows 暂不在支持面」属语义移除——须全局 grep 旧措辞同步（口径反转纪律，AGENTS.md 行为指令）。

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `.github/workflows/ci.yml:142` windows job（镜像源） | 追加 `windows-arm` job：八步镜像 + 三 arch 特异点 + 归档/上传通配钉 `windows-aarch64`（已落地，真跑全绿） | ☑ |
| `README.md:159` / `README.zh-CN.md:149` 平台段 | 平台反转 + pwsh 前提（双语同步） | ☐ |
| `docs/design/12-api-stability.md` §2 导出面 | 全量扫描逐条核对（缺口随扫随补） | ☐ |
| `docs/release.md` §5 注（tag→Release 点名推迟） | 附件自动化在案化（定义落盘） | ☐ |
| 根 POM `version` + 全仓 POM | `0.2.0-SNAPSHOT` → `0.2.0`（+ 干跑预检） | ☐ |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| A｜aarch64 job 首跑（停-取证-再议；含降级选 3 的建议权） | `ci.yml` windows-arm job 全文 + 首跑证据 | **通过**（2026-09-29——真跑 run `36549993644` 四 job 全绿、windows-arm 12 步全过、归档三件在案；选 2 落证、首裁项闭合；S-a §12） |
| B｜发布面成文（对外承诺面：12 扫描结论 + release notes + README×2 反转稿） | `12-api-stability.md` §2 / `release-notes-0.2.0.md` / README×2 | 未到 |
| C｜收口前（版本翻转 + 干跑 + 附件工作流定义落盘后，发布执行交接前） | 全仓 POM / `release.md` / 附件工作流 / 全量证据 | 未到 |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-25.1/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `S-a-arm-job-first-run.txt`（停点 A） | 选 2 落地形态 + 侦察三跑 + 真跑（run id / 各 job 结论 / arm job 逐步状态 / 归档三件 / 关键输出行 / EXIT 回显；真跑证据走 jobs/artifacts API 逐字） |
| `S-d-release-engineering.txt`（随包落盘） | 12 全量扫描结论 + release notes/迁移路径 + release.md + 附件工作流 + aarch64 落地 |

## 验收（证据 = 实际执行的命令与结果）

（放行 2026-09-29 启用；勾选与证据随工作推进落此。）

- [ ] 12 全量扫描：导出面逐条核对、无未登记破坏面
- [ ] release notes 成文（含已知残余清单）
- [ ] README×2 平台段与 pwsh 前提与实况一致
- [x] aarch64 交付裁决落地（选 2：双 arch CI job 事实源；降级预授权 = 选 3）——真跑 run `36549993644` 四 job 全绿、windows-arm 12 步全过、`javanatic-harness-windows-aarch64` 归档 68,512,445B 在案（S-a §12）；降级选 3 未行使（授权条件未触发）
- [ ] tag→Release 附件工作流定义落盘（真执行用户侧）
- [ ] 版本翻转与发布前预检（干跑绿；真 deploy/tag/Publish 用户侧）
- [ ] S-b·S-c 挂账记 post-0.2 backlog（只记不动）
- [ ] 全量 package 绿（本机 + CI）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| `a8cdafa` | arm 首跑红：temurin 无 windows-aarch64 JDK 25（run `36543615310`） | `distribution: temurin → microsoft` + `ci.yml` 注释校正（裁决 F1） |
| `1b0fbdd` | 二跑红：侦察 delta 的 `-D` token 被 pwsh 点号截断（run `36546502989`） | token 加单引号 + AGENTS.md 配方互指与压缩（裁决 R1） |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| （随工作填写） | | | |
