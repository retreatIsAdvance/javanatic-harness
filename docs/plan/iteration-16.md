# 迭代 16 — 发布工程 → 0.1.0（状态：进行中）

模块：全仓 POM 坐标层（46 个 POM）、根 POM（元数据 + release profile）、`docs/design`（00/02 + 新增 12）、README（双语重构）、`dist/jh`（压缩 + 归档）、`docs/release.md`（新）、AGENTS

四确认日期：2026-09-17（用户确认草案；五项裁决照办：**命名空间走 GitHub → groupId 全仓改 `io.github.retreatisadvance`、不买域名**；GPG 走生成步骤文档（用户执行）；首发本地手动（不做 tag 触发 workflow）；README 英文主 + 中文从；版本流 0.1.0 → tag `v0.1.0` → main bump 0.2.0-SNAPSHOT）

## 四确认

- **内容**：
  1. **Maven 坐标改名**（发布前置，Central 命名空间验证走 GitHub 身份）：`io.javanatic` → `io.github.retreatisadvance`——46 个 POM 共 253 处 `<groupId>` + 当前态文档 4 处 + 02 的 Maven 片段。**Java 侧零改动**：包名 / JPMS 模块名 `io.javanatic.harness.*`、`dist/jh` moditect 模块清单 9 处均不动（Central 只看 Maven 坐标；文档加注「groupId 与包名不同源属有意为之」）。改名后跑一次 `mvn install` 回填本地仓库新坐标（旧坐标 SNAPSHOT 作废）。
  2. **Central 发布工装**：根 POM 补硬性元数据（url / scm / licenses=Apache-2.0 / developers）；`release` profile：maven-gpg-plugin 3.2.8（签名）+ maven-source-plugin 3.4.0（sources jar）+ maven-javadoc-plugin 3.12.0（javadoc jar）+ central-publishing-maven-plugin 0.11.0（`autoPublish=false`——首发上传为草稿，用户在 Portal 审核后手动 Publish）；`examples/`、`dist/` 子树不进发布面——实闸 = 本 profile 的 `excludeArtifacts`（5 个 artifactId；central-publishing 0.11.0 **不读** `maven.deploy.skip`，字节码实核）；另置 `maven.deploy.skip=true` 兜标准 deploy 路径（2 个 POM）。
  3. **门面冻结**：新增设计文档 `12-api-stability.md`——0.1.0 稳定面清单（seam 契约 / 事件 schema / 配置键 / 包级 API）+ 0.x 语义（0.1.x 修补不破 API；破坏性变更走 0.2.0 并声明）；README 摘要段。
  4. **双语 README**：`README.md` → 英文（国际主读者）；`README.zh-CN.md` 中文全本（现内容刷新，计数订正：45 个 reactor 模块 / 362 用例 / 4 跳）；置顶互链；设计文档维持中文（语言政策不动，README 说明）。
  5. **归档 + 镜像压缩**（it13 挂账收口）：`dist/jh` 产 tar.gz/zip（解压即用）；moditect `<compression>` 落地（86M 基线，记录前后体积）；jdk.httpserver 收窄评估（若需动测试工程结构则挂账）。
  6. **发布文档 + 首发流程**：`docs/release.md`——GPG 生成步骤（用户执行）、Central Portal 账号/命名空间验证步骤（用户执行）、发布命令序列（0.1.0 → `mvn -P release deploy` → Portal 审核 Publish → tag `v0.1.0` → GitHub Release（归档 + notes）→ main bump 0.2.0-SNAPSHOT）。
  7. **推送收口**：积压提交 + tag 一并推（放行后），CI 双 job 复验收口 it14 #29 / it15 #25 挂账行。

- **目标**：0.1.0 可被 Central 依赖（`io.github.retreatisadvance:harness-*`）、GitHub Release 可下载归档；稳定面与发布流程有书面契约；证据 = 本地全链路验证（改名后全量绿 + release 剖面工件完整性 + 隔离测试密钥真签名）+ 发布执行回执（用户侧，Portal 草稿审核放行）。

- **为什么**：路线既定首发（docs/design/README §实现路线：发布工程 → 0.1.0）；it15 已收口全部功能性判据；签名 / 元数据 / 源码包 / 命名空间是 2026 年 Central（Portal）硬性网关；「稳定面 + 发布流程」写出来本身就是对 0.1.0 质量的最后一道审。

- **不做**：
  - 域名购买（裁决：GitHub 命名空间）；Java 侧改名（包名 / JPMS 名 / 模块清单）
  - Windows 平台面 / Landlock / pwsh（0.2.0）；设计文档翻译、官网、CONTRIBUTING/安全政策等社区文档（除非点名）
  - 运行时行为改动（只动构建与文档；版本号除外）；新增运行时依赖；跨模块重命名
  - tag 触发自动发布 workflow（首发本地手动；自动化留后续迭代）
  - `docs/plan/` 历史迭代文档回改（历史事实）

### 落盘钉（确认要点）

1. **命名空间拼写**：Portal 按 GitHub 身份自动创建并验证命名空间，实回显为全小写 `io.github.retreatisadvance`（GitHub 用户名大小写不敏感，Portal 侧小写规范化；api.github.com 实核规范展示为 camelCase，Web 访问任一大小写均归一）。POM 坐标已按回显在终局前 sed 校正为小写；GitHub URL / developers 身份同取小写（大小写不敏感，功能等价；如需展示形可再改）。（归属：Portal 账号注册 + 命名空间验证为用户侧动作；agent 只提供步骤文档，不触碰凭据）
2. **签名验证两层**：本地验证 = `-Dgpg.skip=true` 跑通非签名链路（sources/javadoc/元数据/工件完整性）+ **隔离 GNUPGHOME 生成一次性测试密钥**做真签名验证（不触用户密钥库；若工具策略拦截则降级为 gpg.skip 并在验收注明）；真实签名 + 上传 = 用户首次 `mvn -P release deploy`（autoPublish=false，上传不公开）。
3. **developers 条目**：先填 GitHub 身份（id/name = `retreatisadvance`，暂无邮箱）；发布前用户可补真实署名。
4. **版本翻转时机**：`0.1.0-SNAPSHOT → 0.1.0`、tag、bump `0.2.0-SNAPSHOT` 都在发布执行时做（S3 文档化命令序列）；平时保留 SNAPSHOT。（归属：版本翻转 + tag 为用户侧动作 · 切片 = 终局发布执行；agent 只文档化命令序列并给校验点）
5. **javadoc 跨模块**：JPMS javadoc 需全依赖在场（全量构建）；doclint 放宽（中文注释 + 模块化降噪）在 S1 落地时定，控噪 `-quiet`。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - Maven 坐标 `io.github.retreatisadvance:harness-*`（Central 命名空间 = GitHub 身份）
  - `release` profile（根 POM：gpg/source/javadoc/central-publishing 四插件 + 元数据）
  - `README.zh-CN.md`（中文全本）、`docs/design/12-api-stability.md`（稳定面 + 0.x 语义）、`docs/release.md`（发布流程）
  - `dist/jh` tar.gz/zip 归档产物
- **MODIFIED**：
  - 46 个 POM：253 处 `<groupId>`（根 / parent 引用 / dependencyManagement 内部依赖）
  - 根 POM：url / scm / licenses / developers + pluginManagement 补四插件版本
  - `examples/pom.xml`、`dist/pom.xml`：`maven.deploy.skip=true`
  - `dist/jh/pom.xml`：moditect `<compression>`（JPMS 模块清单不动）
  - `README.md`：中文 → 英文（主）+ 计数刷新；`docs/design/02-module-layout.md`（坐标口径 + Maven 片段）、`00-overview.md`、`AGENTS.md`（groupId 说明 + 发布命令）
  - `docs/design/README.md` 路线表：it16 行状态
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| 46 个 `pom.xml`（253 处 `<groupId>`） | → `io.github.retreatisadvance`（exact 元素匹配，JPMS 名不动） | ✅ 253 处 + 02 片段 21 处；残留 0 |
| `pom.xml`（根）：project 元数据 | url / scm / licenses / developers | ✅ 15–37 行 |
| `pom.xml`（根）：`<profiles>` + pluginManagement | `release` profile（四插件 + 版本） | ✅ 298–319 / 365–432 行 |
| `examples/pom.xml`、`dist/pom.xml` | `maven.deploy.skip=true` | ✅ 落盘为兜底；实闸 = 根 profile `excludeArtifacts`（见设计偏离①） |
| `docs/design/02-module-layout.md:14+397/471-511/590-703` | 坐标口径 + Maven 片段；加「不同源」注 | ✅ |
| `docs/design/00-overview.md:115`、`README.md:7`、`AGENTS.md:3` | 坐标口径同步 | ✅ |
| `README.md`（整体）+ `README.zh-CN.md`（新） | 英文主 + 中文从 + 互链 + 计数 | ✅ 英文主 129 行 / 中文从 124 行；互链双向；计数 45/362 四处一致 |
| `docs/design/12-api-stability.md`（新） | 稳定面清单 + 0.x 语义 | ✅ 117 行；导出面 32 模块 36 包与 module-info 扫描 diff IDENTICAL；配置键 12+12 实核 |
| `dist/jh/pom.xml`：moditect `<compression>` + 归档 | 压缩 + tar.gz/zip | ✅ `zip-6`（86M → 53M）；assembly 3.7.1 产 tar.gz/zip，平台 profile ×3，`tarLongFileMode=posix` |
| `docs/release.md`（新） | GPG / Portal / 发布命令序列 | ✅ 8 节：前置（GPG/命名空间/settings.xml）/ 预检 / 序列 / 校验点（含签名硬门槛）/ tag+Release / bump / 回滚边界 / 归档说明 |
| `docs/design/README.md:108-117` | 路线表 it16 | ✅ it16 行补归档 + release.md 链接 +「发布执行为用户侧动作」 |
| `AGENTS.md`（发布节）+ `README.md`/`README.zh-CN.md`（构建节）+ `02-module-layout.md`（dist 行/树） | 发布命令 + 归档产物描述 | ✅ AGENTS 新增「发布（0.1.0，执行侧=用户）」；README×2 各 1 行；02 两处 |
| `.github/workflows/ci.yml` | 不动（发布 workflow 不做） | — |

## 审查停点（到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S1 发布工装 + 坐标改名 | 46 POM + 根 POM 元数据/release profile + 排除面 + 文档 4 处 | ✅ 2026-09-17 用户放行 |
| S2 双语 README + 门面冻结文档 | `README.md` / `README.zh-CN.md` / `docs/design/12-api-stability.md` | ✅ 2026-09-17 用户放行 |
| S3 归档 + 压缩 + 发布文档 | `dist/jh` + `docs/release.md` + 路线表 | ✅ 2026-09-17 用户放行 |
| 终局：发布执行（用户动作；Central 公开不可逆） | 版本翻转 + `mvn -P release deploy` + Portal Publish + tag + GitHub Release | ☐ |

## 验收（证据 = 实际执行的命令与结果）

- [x] S1 改名后全量绿：`mvn -B -q validate` + 全量 `mvn -B -q install` → BUILD SUCCESS 2:52；65 套件 / 362 用例 / 0 败 0 错 4 跳（2 keyless e2e + 2 linux-only 沙箱）；jlink 镜像重建后 `--help`/`--verify` exit 0（"verify 通过"）
- [x] S1 release 剖面工件完整：全量 `package` SUCCESS（45 模块，1:12）；sources 32/32、javadoc 32/32（JPMS 包结构在场）；effective-pom 抽查 kernel/brand + core/tools 两条父链：url/licenses/developers/scm 继承生效
- [x] S1 签名链路（隔离测试密钥）：一次性 GNUPGHOME 真签名 6 件 `.asc` → jar/sources/pom 三件 `gpg --verify` = 完好的签名；干跑 deploy（/tmp 0.1.0 副本 + 假凭据 + `-Dgpg.skip=true`）→ bundle zip 650 项 = 130 主件 × (1+4 校验和)；40 坐标（1 根 + 9 聚合器 + 30 叶）；排除项零泄漏；唯一红点 = 上传 401（预期）
- [x] S2 双语 README：英文主本（129 行）用户审阅放行 + 中文从本（124 行）完整 + 双向互链 + 计数订正（45 模块 / 362 用例，四处一致）
- [x] S2 稳定面文档：`12-api-stability.md` 导出面 32 模块 36 包与 `module-info` 扫描 diff IDENTICAL（36/36）；bundle.yml 24 行实核（12 可配 + 12 无配）；CLI 面与 `jh --help` 逐 flag 比对一致；4 文件链接核查 0 缺失
- [x] S3 归档：tar.gz/zip 解压可用——双格式独立解压后 `bin/jh --help`/`--verify` 均 exit 0（"verify 通过"）；`gzip -t`/`unzip -t` 无错；126 项；权限实核（bin//lib/ 0755，含 jspawnhelper；其余 0644）。镜像体积 86M → 53M（zip-6 + jdk.httpserver 出链；模块 41 → 40，`bin/jwebserver` 消失）；全量 `package` SUCCESS（45 模块，1:21）
- [x] 终局前坐标小写校正复审：全量 `mvn -B install` exit 0；65 套件 / 362 用例 / 0 败 0 错 4 跳（与 S1 基线一致）；46 POM camelCase 残留 = 0（sed 297 处 = 46 POM + 9 当前态文档）
- [x] 发布前 README 复审（终局前）：双语三处同步（状态段 7-15 + 生产模拟场景项 / 路线表 15 ✅ / ZH 统一「R1–R4 治理不变式就位」）+ 新增「Get it / 获取」段；归档命名与 `dist/jh` profile 实配核一致（`javanatic-harness-<版本>-<平台>`，如 `macos-aarch64`）；计数复核 45 模块 / 362 用例不变；it15 计划头状态订正「已完成——CI 待 push 放行后复验」；追击过时文案：状态段删除「剩余叶模块为 module-info + 标记类」句；module-info 头注释 5 处同类措辞一并订正（shell/tool、shell/shell、shell/bash-local、kernel/brand、examples/agent-spine，纯注释；会进 javadoc 制品，javadoc jar 实核；唯 `bundle/headless` 仍为骨架、保留）
- [ ] 终局发布执行（用户放行 + 用户侧凭据）：Portal deployment 草稿审核 Publish 回执 + tag `v0.1.0` + GitHub Release
- [ ] **签名硬门槛（用户拍板附加）**：真 key 签名后复查 bundle 内 `.asc` 齐全——干跑的「无 .asc」仅是 `gpg.skip` 预期，不得当作达标证据
- [ ] 推送收口：积压 + tag push 后 CI 双 job 绿（it14 #29 / it15 #25 挂账行一并勾）
- [ ] 文档同步：路线表 / AGENTS / 设计文档（00/02/12）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| S1 | reactor 计数误记：46 个 POM 文件 ≠ 46 项目；审查包一度写 44（漏计 examples 聚合器与 dist/jh 口径） | 订正为 45 个 reactor 模块（根 1 + 聚合器 11 + 叶 33）；发布面 40 + 排除 5 = 45 自洽；计划文本与 S2 README 计数均按此 |
| S2 | `docs/design/README.md` 索引滞后 3 处：快速导航无 12 行；技术栈「27 个叶子模块 / 38 个 reactor 项目」为旧口径；MVP 清单 Commands 仍标 stub（it14 已落地） | 补 12 行导航；口径改「33 个叶模块 / 45 个 reactor 模块」；Commands 行改「命令面 registry/slash 解析，it14」 |
| S3 | 归档初版两处构建缺陷：① 平台 profile 在 macOS 双命中（plexus-utils 3.3.1 `isFamily` mac 与 unix 同真，探针实核）→ 归档名错标 `linux-aarch64`；② `conf/security/policy/...` 深路径超 ustar 100 字符 → tar 长名告警 | ① linux 档加 `<name>Linux</name>` 收窄保证互斥（pom 注释记实测依据）；② `tarLongFileMode=posix`（pax 扩展头），告警消除、双格式解压复验 |
| 终局前 | Portal 登录实回显：命名空间为全小写 `io.github.retreatisadvance`（S1 改名后全仓为 camelCase `retreatIsAdvance`；GitHub 用户名大小写不敏感，Portal 侧小写规范化） | groupId / 坐标全仓二次 sed 校正为小写：46 POM + 9 个当前态文档共 297 处（含 02 片段、release.md、README×2、AGENTS、00/07/12）；GitHub URL / developers 身份随行小写（功能等价）；全量 `install` 复审绿（见验收） |
| 终局前 | README 发布前复审滞后：状态段止于 it14（it15 未覆盖）；路线表 15 未勾（it15 全部停点已放行、功能性判据已收口）；中文从本「R1 三规则齐备」与英文主本「R1–R4 invariants」表述不同源 | 双语同步：状态段 7-15 + 生产模拟场景项；路线表 15 ✅；ZH 统一「R1–R4 治理不变式就位」；新增「Get it / 获取」段（Central 坐标 0.1.0 + kernel-core 依赖片段 + v0.1.0 归档指引）；it15 计划头状态订正（见验收） |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 四确认 §2「`examples/`、`dist/` 子树 `maven.deploy.skip=true`（properties 继承，2 个 POM 覆盖 5 个）」 | 实闸 = 根 release profile 的 `excludeArtifacts`（5 个 artifactId）；`deploy.skip` 仅兜标准 deploy 路径 | central-publishing 0.11.0 不读 `maven.deploy.skip`（javap 常量池实核）；干跑 bundle 零泄漏验证 | 迭代内已同步（计划措辞 + 根 POM 注释）；用户已裁决同意 |
| `docs/design/07-profile-bundle.md:28,216` 示例 bundles 写 GAV 形式 | `AppBoot.compose` 按 **name** 解析（`bundle.yml` 的 `name`；未知名 fail loud） | 07:204 自注「GAV 钉扎随发布切片」；钉扎属运行时行为变更，被四确认「不做」排除 | 用户裁决①：维持 07 原样（GAV 形式 + 注记存活）；钉扎推迟超一个发布周期（0.2.0 未落地）则重访；坐标拼写本次已同步 |
| 落盘钉 #5「doclint 放宽在 S1 落地时定」 | `doclint=none` + `quiet`（release profile） | 中文 Javadoc + JPMS 降噪；文档质量靠写作纪律 | 用户裁决接受；javadoc 质量门禁（doclint 子集 / 独立检查）可选挂账，未列本迭代 |
| （观察·非偏离）`bundle/headless/` 骨架 | it8（c1270ad）有意摘除 modules 后留 marker 类 + stale target + 根 dependencyManagement 幻影 GAV；it16 仅机械改名 | 非 it16 引入 | 用户裁决知悉不动；清理 / 注册留后续迭代 |
| 四确认「不做」列「新增运行时依赖」 | S3 新增 **build 插件** maven-assembly-plugin 3.7.1（版本内联，与 dependency/moditect 同惯例） | 归档工装 = 四确认 §5 明示范围（"产 tar.gz/zip"）；约束面为运行时依赖，未触碰 | 用户放行（packet §5-S3 裁决①）；归档为 dist 本地产物，不进 Central 面 |
| （观察·非偏离）四确认 §5「jdk.httpserver 收窄评估（若需动测试工程结构则挂账）」 | 评估结论：**无需动测试结构**——三个模块仅测试引用，`requires static` 一步闭环（镜像 41 → 40 模块） | 静态 requires = 编译期必需/运行期可选，jlink 不再随链 | 迭代内落地并实核（假服务端测试 20/1/1 全绿）；无挂账 |
| （观察·非偏离）平台 profile 含 `archive-windows` 档 | Windows 不在 0.1.0 支持面 | 保留仅为归档名 fail-loud 兜底（三分之外暴露未解析占位符） | 用户放行（packet §5-S3 裁决③） |
