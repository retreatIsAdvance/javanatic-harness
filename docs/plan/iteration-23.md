# 迭代 23 — 外部 Java 接入闭环（状态：已完成——四确认与 A–F 裁决 2026-09-24 落盘；S-a / S-b / S-c 三段全部放行（S-c 于 2026-09-24「裁决:放行,it23 关闭」）；三段随**单提交**收口——三停点改动交织（`embedding.md` / `iteration-23.md` / 脚本跨停点）无法回拆，偏离 it18–22 分段提交节奏；全量 package 46/46 绿 + 候选/发布两腿复验 + 文档从零走查；待 push 授权）

模块：`integration/consumer-sample`（新：仓内**非 reactor** 独立 Maven 工程）· `bundle/base`（`YamlRows` 三层未知键 fail loud 实修）· **删除** `bundle/headless` 孤岛 · `docs/embedding.md`（新）· `docs/design/{02,05,07,12}` 漂移订正 · `AGENTS.md` · `.github/workflows/ci.yml`（候选腿）

路线来源：路线 README it23 行「**外部 Java 接入闭环**：仓库外嵌入与插件示例；显式组合方式和排错指引；区分标准发行包与二开发行方式」——验收「独立 Maven 工程完成嵌入 Agent、注册工具和治理验证；不依赖主仓源码或 reactor 偶然提供的依赖；候选工件与发布工件分别复验」。路线 §Validation 自认此项**待建立**：`docs/design/README.md:184`「独立工程验证 Java 嵌入与插件接入；**候选版本验证候选工件，发布后复验 Central 工件**」；`:127`「内部 reactor 示例通过不等于外部消费者路径可用」。

## 四确认

- **内容**（四件）：
  1. **仓库外独立示例工程**（落 `integration/consumer-sample/`，**不进任何 `<modules>`**，根 POM 零引用）——自有坐标、版本单点属性 `<harness.version>`（不继承 `harness-parent`：继承会把 junit/assertj/jqwik 测试依赖一起拖进来，`pom.xml:439-455`），演示三件事：
     - **嵌入 Agent**：`AppBoot.compose/boot`（`AppBoot.java:75/:174`；`bundle/base` 已导出 `io.javanatic.harness.boot`，`bundle/base/src/main/java/module-info.java:19`）
     - **自注册插件与工具**：实现 `Plugin`（`kernel/core/.../plugin/Plugin.java:11`）+ `ToolDefinition.of(...)`（`ToolDefinition.java:38-46`）+ `registry.register(scope, def)`（范式 `FsToolPlugin.java:36,78-83`）；随包带 `META-INF/harness/bundle.yml`，使该插件可作为 profile 的一个 bundle 行被引用（发现路径 = ServiceLoader 声明 or bundle 行，**S-a 首步实探定形**并落证）
     - **治理自证（keyless）**：`compose` → `dump`（`:135`）→ `boot()` fail-loud（`VerifyFailedException` `:55`、组合期双向校验 `:300-320`）；`bootReported`（`:188`）为 **0.2.0 新增**，作为升级项标注而非两腿共用面
  2. **候选工件 / 发布工件分腿复验**（本迭代验收骨头）：
     - 候选腿：工作树 `mvn -B install` 进**临时本地仓**（`-Dmaven.repo.local=/tmp/...`）→ 示例工程编译 + 运行 exit 0
     - 发布腿：**Central 0.1.0** 坐标、本地仓为空（只走公网解析、无任何本地安装）→ 同源复验；按**交集面**（`boot`/`compose`/`dump`/`Plugin`/`of`/`register`）——`bootReported` 不在 0.1.0（6f92598 > v0.1.0 tag），发布腿的治理自证用 `boot()` fail-loud + `dump()`
     - 同源优先：一份源码两腿共用；若 0.1.0 面另有缺口 → 发布腿降级为「boot + 注册工具 + dump」，治理自证腿只留候选腿，**差异如实入文档**
     - 坐标可用性**已实探**（2026-09-24：`harness-parent` / `harness-bundle-base` / `harness-kernel-core` / `harness-core-tools` 的 0.1.0 POM 全 HTTP 200）
  3. **接入文档**（新增仓根 `docs/embedding.md`，与 `docs/release.md` 同级、面向外部开发者）：两种发行方式界线（**标准发行包** = jlink 镜像/归档，只跑 CLI，**不承诺丢 JAR 启插件**，`docs/design/README.md:151`；**二开发行方式** = 坐标嵌入 + 自注册插件）· 最小可照抄 pom（显式版本；无 BOM 可用——`pom.xml:64-275` 的 dependencyManagement 不是可依赖工件）· 消费两条路（classpath 默认 / module-path + 已导出模块表 `12-api-stability.md:17-55`）· 组合与引用口径（profile + bundle 行；**bundle 引用 = bundle name**（`AppBoot.java:86-90`）而非 GAV——`07:28/:219` 现文写 GAV，属订正面）· 治理自证（`compose/dump/boot/bootReported` 与摘要各行含义）· **排错指引**（Unknown bundle / unreferenced rows / policy 违规 / 缺 key / 模块路径冲突 / 版本不匹配的**真实文案**与处置）· 0.2.0 新增面标注（`ofExempt`、`ask_user`、`bootReported`）
  4. **漂移与承诺收敛**（含 D 升级为**实修**）：
     - **`YamlRows` 三层未知键 fail loud**：profile 顶层（`:74-88` 只取 name/policy/bundles/rows）、bundle（`:64-70` 只取 name/description/rows）、行级（`:109-130` 只取 plugin/config/remove/replace/after/before/disabled）——三层现为**静默忽略**（点取无检查）；实修为逐层 allowlist + 未知键 fail loud，配三腿单测（`YamlRowsTest`）+ 突变 + `07`/`12` 各一行；07:29 的 `plugins:` 承诺因此**缺席即执法**（解析器直接拒绝）
     - `07` 示例照实：`:22-30` profile 示例（`description` 与 `plugins` 均会被新校验拒绝；bundle 引用写 name）；`:36-41` bundle 示例 `patches:` → `rows:`（实况 `bundle/base/.../bundle.yml:3-5`）
     - `05` 三处 `ToolDefinition.builder` 幻 DSL（`:74`、`:259`、`:752`）→ `of()`/`ofExempt()` 实况
     - `02` 孤岛面（`:104`、`:171` 宣传 `harness-bundle-headless`；根 POM depMgmt `:259-263`）+ **删除 `bundle/headless/**`**（`pom.xml:10,13` 版本停在 0.1.0-SNAPSHOT、不在 `bundle/pom.xml:17-19` modules → **从未发布**，删除零破坏；`docs/release.md:75` 已记为已知状态）
     - `02` 其余过期（`:538` `-Werror` 与实况 `pom.xml:285-287` 仅 `-Xlint:all` 不符；`:750` 过期 `examples/headless/target/jh.jar` 路径）
     - `AGENTS.md:23`「**Pre-release**：无外部消费者」**已假**（0.1.0 已发布 Central）→ 软化：保留「正确地基 > 兼容包袱」的**非稳定面**自由，稳定面策略以 12 为准
- **目标**：一个**不读主仓源码**的外部 Java 开发者，只按 `docs/embedding.md` + 发布坐标即可完成：嵌入 agent、注册自有工具、治理自证；且这条路有**可重跑的独立证据**（脚本 + 两条工件腿 + 三负例），而不是「仓内 examples 跑得通」。
- **验收问题**（路线要求每轮补一问）：*团队要在自家 Java 服务里嵌入 agent 并注册自有工具*——今天只能照抄仓内 examples（**reactor 形态**：`examples/agent-spine/pom.xml:7-11` 父指向聚合器、`:17-20` 依赖不写版本 → 抄出来必跑不通）；it23 后：按文档起独立工程 → 注册工具 → 治理自证，且**换 Central 坐标同样能跑**（交集面）。
- **为什么**（现状证据）：
  - **零外部接入文档**：`README.md:30-42`「Get it」仅一条 `harness-kernel-core` 依赖 + 归档页；全仓 `--add-modules` 的消费方说明零覆盖（唯一命中在 `docs/plan/iteration-13.md:10` 的仓内构建记录）
  - **零独立工程验证**：无 consumer fixture、无 `maven-invoker-plugin`、无独立 pom；CI 只跑仓内 `mvn -B -ntp package` + 镜像冒烟（`.github/workflows/ci.yml:41-47`）
  - **文档不可照抄**：`05:74` 的 `builder` 在实现里不存在（`ToolDefinition.java:38-46` 只有 `of`/`ofExempt`）；`02:750` 路径过期、`02:538` 与 POM 实际不符
  - **承诺与实现不符**：`07:29` profile `plugins:` 键在 `YamlRows` 无读取路径（三层静默忽略）；`07:28/:219` 说 bundle 引用是 GAV，实现按 bundle name 匹配
  - **依赖面缺 BOM**：`pom.xml:64-275` dependencyManagement 非可依赖工件；外部唯一等价物是继承 `harness-parent`（会拖入测试依赖）
  - **孤岛坐标**：`bundle/headless` 从未发布却被 `pom.xml:259-263` 挂名、`02:104/:171` 宣传 → 外部按文档依赖必失败
- **不做**：
  - 不做服务端/网络化嵌入（Spring Boot starter、HTTP API、ACP）——留 0.3+
  - 不做 jlink 镜像丢 jar 加载插件（`docs/design/README.md:151` 明示不承诺）；不做外部插件的安全隔离/版本协商（JPMS 隔离 ≠ 恶意插件沙箱，`:129`）
  - 不新增 BOM artifact（示例显式版本；留待 0.2.0 发布前再评估）
  - 不回改 0.1.0 已发布坐标（不可逆；差异以文档如实说明）
  - 不做平台面（Windows/Linux 归档属 it24/it25）；不动 `preset` 的 `plugins` 引用校验（`PresetService.java:78`，另一语义面）

## 裁决记录（2026-09-24）

评审结论：**草案成立，A–F 全按建议**；附三项增强（其中一项升级为实修）。载荷核实（用户侧复核）：`boot` 导出面属实（`compose`/`dump`/`bootReported` public；`verifyComposition` 包私有、经 `bootReported` 间接）；孤岛**从未发布**确认；`builder` 幻 DSL **三处**；consumer fixture **零存在**。引用路径小误订正：`ToolRegistry` 在 `core/tools`、`YamlRows` 在 `bundle/base`。

| 裁决 | 内容 |
|---|---|
| A | 示例工程落点 = **仓内非 reactor**（`integration/consumer-sample/`，自有坐标，版本一处属性） |
| B | **发布腿不进 CI**；**候选腿进 CI**（作漂移警报）——公网依赖不进构建门禁 |
| C | `bundle/headless` **删除**（连 `module-info` 一起删）+ depMgmt + `02` 两处同步 |
| D | `plugins:` 键：**删文档承诺** + 实修为 `YamlRows` 三层未知键 **fail loud**（见增强 2） |
| E | 治理自证**不新增公开 API**：复用交集面；`bootReported` 作 **0.2.0 升级项标注** |
| F | 文档落点 = 新增 **`docs/embedding.md`** + README 双语各加一行指引 |

**三项增强**：

1. **版本探针收口**：`bootReported` 为 **0.2.0 新增**（6f92598 > v0.1.0 tag）→「同源」= **交集面**（`boot`/`compose`/`dump`/`Plugin`/`of`/`register`）；0.1.0 治理自证 = `boot()` fail-loud + `dump()`；`bootReported` 在文档与代码注释中标注 0.2.0 升级项；**锚点直接落此**（不再有「实施时再看」的悬空）。
2. **（D 升级）`YamlRows` 三层静默忽略实修**：profile 顶层 / bundle / 行级均点取无检查 → 三层 **fail loud**（小改 + 突变 + `07`/`12` 各一行）；`plugins:` 的收敛形态随之变成**缺席即执法**。备选最小面 = 仅文档化容忍（不推荐，与 fail loud 契约冲突）。
3. **`AGENTS.md:23` 软化**：「Pre-release 无外部消费者」已假，并入漂移收敛清单。

**备选与代价（留档，不重开）**：

| # | 事项 | 建议 | 备选与代价 |
|---|---|---|---|
| A | 示例工程落点 | 仓内非 reactor：可入库、可复验、CI 可跑 | 仓外另建 repo：最贴近真实用户但证据链断（无法入库复跑）；仓内 reactor 模块：违背验收（靠 reactor 解析） |
| B | 发布腿进 CI | 不进：CI 不承担公网脆弱点与时长 | 进：多一个网络闪烁风险，且「Central 已发布件」非本仓可控 |
| C | 孤岛处置 | 删除：从未发布 → 零破坏、少一个误导坐标 | 纳入 reactor：多养活一个空模块，仍留「宣传了但用不上」风险 |
| D | `plugins:` 键 | 删承诺 + 三层 fail loud（缺席即执法） | 实装 out-of-tree module-path 追加：与「不承诺丢 jar」冲突、引入任意 jar 装载面（属 0.3 生态议题） |
| E | 治理自证 API | 不新增：`bundle/base` 已导出 `compose/dump/boot/bootReported` | 下沉 headless 五行摘要为公开 API：动 12 稳定面，收益未证明 |
| F | 文档落点 | `docs/embedding.md`（教程与消费指南不与稳定面/模块布局混住） | 并入 12（稳定面事实源）或 02（模块布局）：都不是消费指南的家 |

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - `integration/consumer-sample/**`（新，**非 reactor**）：独立 POM（自有坐标 + `<harness.version>` 单点属性 + 显式依赖）、插件与工具实现、`META-INF/harness/bundle.yml`、治理自证主类、build/run 说明
  - `integration/verify-consumer.sh`（两腿驱动：候选腿 / 发布腿 + **三负例**控制）
  - `docs/embedding.md`（外部接入指南：发行方式界线 / 最小 pom / classpath 与 module-path / 组合与引用口径 / 治理自证 / 排错指引）
  - `YamlRows` 三层未知键校验（行为面）+ `YamlRowsTest` 三腿新用例
  - `.github/workflows/ci.yml` ubuntu job 的候选腿步骤（B 裁决）
- **MODIFIED**：
  - `YamlRows.parseProfile` / `parseBundle` / `parseRow`：逐层 allowlist + 未知键 fail loud（消息含层与键名，指向文档）
  - `docs/design/07-profile-bundle.md`：profile 示例照实（去 `description`/`plugins`、bundle 引用写 name）、bundle 示例 `patches:` → `rows:`、`§1` 增「未知键 fail loud」一行、`:219` GAV 措辞订正
  - `docs/design/12-api-stability.md`：§5 配置键面增「未知键 fail loud」一行（0.2.0 迁移注记）
  - `docs/design/05-capability-seam.md`：三处 `ToolDefinition.builder` → `of()`/`ofExempt()` 实况
  - `docs/design/02-module-layout.md`：`:104`/`:171` 孤岛去除、`:538` `-Werror` 订正、`:750` 路径订正
  - `docs/design/README.md`：it23 行状态随实现推进更新（收口时）
  - `README.md` / `README.zh-CN.md`：Get it 块加 `docs/embedding.md` 指引一行
  - `AGENTS.md`：`:23` 软化（有发布面）；「已知坑」按需回填（本轮若实撞）
- **REMOVED**：
  - `bundle/headless/**`（`pom.xml` + `module-info.java` + `BundleHeadlessModule.java`）
  - 根 POM `dependencyManagement` 的 `harness-bundle-headless` 块（`:259-263`）

## 锚点（开工前填写：本次将改动的既有代码位置；行号基线 ed8b1c8，开工时复核）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `bundle/base/src/main/java/io/javanatic/harness/boot/YamlRows.java:64-70`（bundle）/`:74-88`（profile 顶层）/`:109-130`（行级） | 三层 allowlist + 未知键 fail loud（键集常量在 `YamlRows` 内单点声明） | ✓ S-b |
| `bundle/base/src/test/java/io/javanatic/harness/boot/YamlRowsTest.java` | 三腿未知键用例（顶层/bundle/行级各一，含「合法键不误伤」对照） | ✓ S-b（一法三断言 + 一法对照，突变 A/B/C 逐层钉红） |
| `integration/consumer-sample/pom.xml`（新） | 独立坐标 + `<harness.version>` + 显式依赖（无 parent 继承） | ✓ S-a |
| `integration/consumer-sample/**`（新） | 实际落盘：`WordCountPlugin`（`Plugin` + `ToolDefinition.of` + `registry.register`）、`SelfCheck`（`compose`/`dump`/`boot`；`bootReported` 未用，交集面）、`META-INF/harness/bundle.yml` + `META-INF/services/…Plugin`、`profile/consumer-sample.yml`、`negatives/{bad-governance.yml,no-version/pom.xml}`、`README.md`（build/run 说明） | ✓ S-a |
| `integration/verify-consumer.sh`（新） | 候选腿（临时本地仓 install → 编译 + 运行）+ 发布腿（本地仓为空 + `harness.version=0.1.0`）+ 三负例 | ✓ S-a；S-b 增④（生产档撞 AUTO 审批，钉 `VerifyFailedException`）并订正 0.1.0 Policy 口径 |
| `docs/embedding.md`（新） | 六节指南（含排错指引表） | ✓ S-b（实为七节：六节 + §7 两条腿与四负例）；S-c 走查修 §5 一处（`(disabled: <expr>)` 注记的出现条件与示例对齐，见修正表） |
| `docs/design/07-profile-bundle.md:22-30`（示例）/`:36-41`（bundle 示例）/`:219`（GAV 措辞） | 照实订正 + fail loud 一行 | ✓ S-b（另收敛 `--dump-config` 幻 flag 两处：§6 步序与 §8 标题；见 packet 披露） |
| `docs/design/12-api-stability.md`（§5 配置键） | 未知键 fail loud 一行 + 0.2.0 迁移注记 | ✓ S-b（另：§1「pre-release 立场」措辞订正、§2 表头计数 32/36→33/37） |
| `docs/design/05-capability-seam.md:74/:259/:752` | `builder` 幻 DSL → `of()`/`ofExempt()` | ✓ S-b（另订 `RenderIntent` 行为 enum 枚举值） |
| `docs/design/02-module-layout.md:104/:171`（孤岛）/`:538`（`-Werror`）/`:750`（路径） | 四处订正 | ✓ S-b（另删 `:782` dsh 对照表的孤岛行） |
| `bundle/headless/**`（删）+ `pom.xml:259-263` | 孤岛删除 + depMgmt 同步 | ✓ S-b |
| `README.md:30-42` + `README.zh-CN.md` 同构块 | 加 `docs/embedding.md` 指引一行 | ✓ S-b |
| `AGENTS.md:23` | Pre-release 措辞软化（有发布面；稳定面以 12 为准） | ✓ S-b |
| `.github/workflows/ci.yml:41-47`（ubuntu job） | 候选腿步骤（`mvn … install` 进隔离仓 → `integration/verify-consumer.sh candidate`；脚本为位置参数，非 `--candidate-only`） | ✓ S-c（落形为**一条脚本调用**：install 由脚本 `leg candidate` 自持，避免二次全量构建；锚点文字的「mvn install → 脚本」即其内部序列，见 S-c 实探发现 3） |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a 候选腿闭环**：示例工程（嵌入 + 自注册工具 + 治理自证）+ 候选腿（临时本地仓 `install` → 编译 + 运行 exit 0）+ 脚本 + **三负例**（坏版本 / 去版本 / 坏治理配置）与还原复绿 | 锚点 3–5 | **已放行（2026-09-24「裁决:放行,it23 关闭」）→ 随 it23 单提交收口**：四份证据落盘（S-a-candidate / S-a-negatives / S-a-focus / S-a-regreen），候选腿 92s 绿 + 三负例全「已拒」+ 复跑 12s 绿 |
| **S-b 发布腿与文档**：Central 0.1.0 交集面复验（`boot` fail-loud + `dump`）+ `docs/embedding.md` + **D 实修**（`YamlRows` 三层 fail loud + 突变）+ `07/05/02/12` 漂移收敛 + 孤岛删除 + `AGENTS.md:23` | 锚点 1–2、6–11 | **已放行（2026-09-24「裁决:放行,it23 关闭」）→ 随 it23 单提交收口**：证据八份落盘（S-b-package / S-b-candidate / S-b-release-leg / S-b-mutation-A·B·C / S-b-version-surface / S-b-module-path-probe）；全量 `mvn -B package` 46 模块全绿 515 测试 0 失败（EXIT=0）；候选腿 76s `失败项：0` + 四负例全「已拒」；发布腿 42s `失败项：0`（隔离仓 66 条 `_remote.repositories` 全 `public-central`）；突变 A/B/C 逐层红→还原复绿；`embedding.md` 七节 + 两腿差异（行 25/20 vs 26/21、工具 9 vs 11）逐项入文档；module-path 消费路实探（三真实负例 + 正例） |
| **S-c 收口**：CI 候选腿（B 裁决：发布腿不进）+ 全量 `mvn -B package` + 验收勾选与证据落盘 | 锚点 12 | **已放行（2026-09-24「裁决:放行,it23 关闭」）→ 随 it23 单提交收口**：证据三份落盘（S-c-package / S-c-ci-dryrun / it23-real-run）；ubuntu job 末位新增候选腿步骤（YAML 校验 + 步骤命令本机干跑 `失败项：0` EXIT=0、腿 79s、四负例全「已拒」）；全量 `mvn -B package` 46 模块绿 515 测试 0 失败（EXIT=0，冻结校验内容级两命：构建窗口 `find -newermt` 无输出 + 构建后复核仅 5 份文档落笔、构建输入零改动）；**从零走查**（仓外新工程 + 自写插件，0.1.0）：`FRESH WALKTHROUGH OK`、rows 25/20、工具 9、EXIT=0，并发现修掉 1 处文档缺陷（S-c 实探发现 1）；验收 ⑦⑨ 已勾选 |

## S-a 实探发现（2026-09-24，落证在前、packet 引用）

1. **发现路径定形（计划书要求「S-a 首步实探定形并落证」）**：plugin 发现 = `ServiceLoader.load(Plugin.class)`（`PluginLoader.discover()`，`kernel/core/src/main/java/io/javanatic/harness/kernel/plugin/PluginLoader.java:32-36`）→ `META-INF/services/…Plugin` 声明是硬要求；bundle 发现 = classpath 资源全量扫描（`YamlRows.discoverBundles()` 遍历 classpath 上全部 `META-INF/harness/bundle.yml`）→ profile 的 `bundles:` 以该资源里的 `name` 引用。**两条通路各自载荷、缺一不可**，示例工程两个文件都在并已实证（`consumer-sample` 行由 bundle name 引入，插件经 ServiceLoader 装载）。
2. **依赖闭包要求**：`AppBoot.verifyComposition` 双向校验（每行→须发现、每发现→须有行）→ 消费方 classpath 必须恰好覆盖组合闭环；故示例 pom 自持 16 条显式依赖。`harness-interaction-ask` 经候选 `bundle/base` 的 compile 依赖传递进入（0.1.0 发布 POM 未声明 → 发布腿 base 组合无 `ask-user` 行，交集面差异已定案）。
3. **取件通路（S-b 复跑后订正口径）**：本机 `settings.xml` 配 `ane56-mirror (http://nexus.ane56-ins.com:8081/repository/public/)` 且对 0.1.0 的 16 个 jar 持续 404（pom 正常）——这正是负例① 首跑耗时 2:11（16 条坐标逐条往返）与候选仓取件走公司 mirror 的原因。**发布腿不走这条路**：脚本对它挂 `-s integration/settings-central.xml`（`mirrorOf *` → 公共代理），隔离仓 `_remote.repositories` 的 id 恒为 `public-central`（66 条全命中）——「只从 Central 解析」的准确口径 = **经公共 Central 代理解析，本机仓为空、公司 mirror 不参与**。
4. **订正计划书猜测**：负例③的异常是 `IllegalStateException: workspace drift: {…}`（`AppBoot.verifyWorkspaceAlignment`，it21 引入 = 0.2.0 面），非 `VerifyFailedException`；且失败解析结果会被隔离仓按 mirror 的 update interval 缓存（负例①复跑 0.094s、报「cached … previous attempt」变体），三项判据不受影响。
5. **示例工程自带 `README.md`**（计划书 ADDED 所列「build/run 说明」），仓根 `docs/embedding.md` 仍为 S-b 交付。

## S-b 实探发现（2026-09-24，落证在前、packet 引用）

1. **0.1.0 的 PRODUCTION 档已含禁 AUTO 审批**：`javap -p` 反汇编 0.1.0 的 `Policy$2`（PRODUCTION）显示断言在发布件里就有——**推翻我此前「0.1.0 只查治理件在场」的误读**（该误读曾被我写进脚本注释，已订正）。故负例④（生产档撞 AUTO）对 0.1.0 同样成立，钉 `VerifyFailedException` 面不属 0.2.0 新增（`S-b-version-surface.txt`）。
2. **module-path 消费路实探**：三真实负例——javac `错误: 找不到模块: io.javanatic.harness.bundle.base`（jar 未上路径）、`FindException: Module com.fasterxml.jackson.databind not found`（第三方闭包未上路径）、`profile references unknown bundle`（把 **exploded classes 目录**当模块——它没有 `module-info`，不是模块）；打包成 jar（**自动模块**，`provides` 由 `META-INF/services` 派生）后正例 `module-path probe ok`（`S-b-module-path-probe.txt`）。
3. **两腿工具面差异需逐名探针**：只看 `tool.schemas=9/11` 无法归因，`ToolProbe`（仅用交集面）逐名对照才钉死差的是 `ask_user` 与 `fs_search`——文档「差异如实」要求逐项列名而非只报计数（`S-b-release-leg.txt`）。
4. **冻结校验与后记**：全量 package 前先跑 `find -newermt` 冻结校验，构建证据才对应确定状态；构建后若再有订正（本次为 `12 §2` 计数 32/36→33/37 的纯文档修正），须在同一证据文件「后记」里声明，否则复核者重跑校验会撞上无法解释的差异（`S-b-package.txt`）。

## S-c 实探发现（2026-09-24，落证在前、packet 引用）

1. **从零走查抓出 1 处指南缺陷（已修）**：`docs/embedding.md §5` 示例用 `dump(enabled)`（`resolve` 后已滤掉禁用行）却写「禁用行附 `(disabled: <expr>)`」——按示例照做**永不出现**该注记。实证：`dump(compose)` 5 行带注记（`llm-openai-compat` / `approval-ask` / `approval-deny` / `shell-docker` / `compaction`）vs `dump(resolve)` 0 行；改文后注记的出现条件（dump **未 resolve** 的 `compose(...)` 结果）与示例对齐（`it23-real-run.txt`）。**教训**：文档里「某调用能产出 X」的断言，须与「能产出 X 的那次调用」同处一个片段，并用真实运行复现一次——S-b 按 §5 片段逐字跑过示例工程，但没跑「说明句」本身。
2. **陌生视角的价值在「自写」而非「重跑示例」**：本次用**仓外新工程 + 自写插件/主类**（非示例拷贝）复跑 0.1.0 全链——若只重跑示例工程，§5 缺陷不会被看见（示例 `SelfCheck` 打印 dump 后接 boot，注意力在计数与 `SELF-CHECK OK`）。新工程另证「16 条显式依赖清单」对外部人可用（按 §2 指引取自示例 pom）。
3. **锚点 12 的落形偏离（形态，非语义）**：锚点文字写「`mvn … install` 进隔离仓 → `integration/verify-consumer.sh candidate`」两步；实落为**一条脚本调用**——install 由脚本 `leg candidate` 自持（`integration/verify-consumer.sh:85-88`），CI 若另跑一次 install 只会二次全量构建（隔离仓路径在脚本内部 `repo-candidate`）。CI 步骤与本地/停点证据**同一入口**，漂移警报口径一致。
4. **CI 步骤的代价与前置**：隔离仓每跑每新 ⇒ 该步骤真下载依赖闭包（本机 install 1:15 min；CI 冷速更慢），属 B 裁决接受的公网依赖代价；**执行位是硬前置**（`100755`——CI 直接以相对路径调用脚本），提交时勿丢（`git ls-files -s` 复核）。
5. **keyed 真跑腿跳过（如实）**：计划书标「可选」且不在验收 ①–⑨ 面内；it23 验证面为 keyless 嵌入与组合（`--verify`/`boot()` 均不需 key）。如需模型真跑，走 it17 基线口径（CLI 任务腿）更合适——本轮不做，非遗漏。

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-23/）

（S-a 四份 + S-b 八份 + S-c 三份已落盘（下表 ✓）；`S-c-package.txt`/`S-c-ci-dryrun.txt` 为 S-c 新增行——
计划书只列了 `it23-real-run.txt`，两份为取证必要补列，见 packet 披露）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `S-a-candidate.txt` ✓ | 候选腿：`mvn -B install -Dmaven.repo.local=<临时>`（工作树）→ 示例 `mvn -f integration/consumer-sample/pom.xml` 编译 + 运行（嵌入 / 自注册工具 / 治理自证），EXIT=0 —— 结果：92s 绿；`rows.composed=26 rows.enabled=21`、`tool.schemas=11 word_count.visible=true`、`content=words=3`、`SELF-CHECK OK`；示例行位列末位 |
| `S-a-negatives.txt` ✓ | 三负例控制（夹具/属性驱动，零源码改动）：① 坏版本 9.9.9 构建必败（16 坐标逐个 `is missing` + `ane56-mirror` 拒）② 去显式版本 `'dependencies.dependency.version' … is missing` 必败 ③ 坏治理配置必抛 —— **实际异常 = `IllegalStateException: workspace drift: {…}`**（订正：非 `VerifyFailedException`，见实探发现 4）；三项判定全「已拒」，复跑同绿 |
| `S-a-focus.txt` ✓ | 聚焦回归：`-pl bundle/base -am test`（40 工程；YamlRows 三腿未知键用例属 S-b）+ 示例腿复跑一致 —— 结果：27 模块级汇总行 / 56 类级行两法独立求和 tests=428 failures=0 errors=0 skipped=2（sandbox-local）/ BUILD SUCCESS 39.4s / EXIT=0 |
| `S-a-regreen.txt` ✓ | 负例之后同命令原地复跑：`失败项：0` EXIT=0（腿用时 12s，隔离仓热；计数与首跑逐项一致）；负例不改源码 → 「还原干净」= 无残留可还原，复跑即证据 |
| `S-b-candidate.txt` ✓ | 候选腿复跑（冻结树）：`CONSUMER_WORK=<新> verify-consumer.sh candidate` → `失败项：0` EXIT=0，腿 76s；`rows.composed=26 rows.enabled=21`、`tool.schemas=11 word_count.visible=true`、`content=words=3`、`SELF-CHECK OK`；四负例全「已拒」（④ 钉 `AppBoot$VerifyFailedException: policy violations: [policy=PRODUCTION 但审批为 AUTO…]`） |
| `S-b-release-leg.txt` ✓ | 发布腿复跑（冻结树、脚本驱动）：`verify-consumer.sh release`，隔离仓初始为空 + `-s settings-central.xml` → `失败项：0` EXIT=0，腿 42s；`rows.composed=25 rows.enabled=20`、`tool.schemas=9`、`SELF-CHECK OK`；隔离仓 66 条 `_remote.repositories` 全 `>public-central=`（无本地件）；两腿差异逐项列名（行 25/20 vs 26/21＝缺 `ask-user`；工具 9 vs 11＝缺 `ask_user`、`fs_search`；含 `ToolProbe.java` 逐名对照） |
| `S-b-version-surface.txt` ✓ | 0.1.0 面实核（`javap` 逐条）：交集面 = `compose`/`resolve`/`dump`/`boot`/`verifyComposition`/`sandboxWarning`/`Policy`/`VerifyFailedException`/`ToolDefinition.of`（5 参）/`ToolRegistry.{KEY,register,schemas,resolve}`；**订正我此前的误读**——0.1.0 的 `Policy$2`（PRODUCTION）**已含**禁 AUTO 审批断言；0.2.0 新增 = `bootReported`/`verifyWorkspaceAlignment`/`ofExempt`/`ask_user` 等 |
| `S-b-module-path-probe.txt` ✓ | module-path 消费路实探：三真实负例（javac `找不到模块: io.javanatic.harness.bundle.base`／`FindException: Module com.fasterxml.jackson.databind not found`／`unknown bundle`）+ 打包成 jar（自动模块，`provides` 由 `META-INF/services` 派生）后正例 `module-path probe ok`；含 `jar --describe-module` 输出 |
| `S-b-mutation-A.txt` ✓ | 突变：`YamlRows` 顶层未知键检查失效 → 顶层断言必红；EXIT=1（还原后类级 5 测试复绿 EXIT=0） |
| `S-b-mutation-B.txt` ✓ | 突变：bundle 级未知键检查失效 → bundle 断言必红；EXIT=1（还原复绿） |
| `S-b-mutation-C.txt` ✓ | 突变：行级未知键检查失效 → 行级断言必红；EXIT=1（还原复绿） |
| `S-b-package.txt` ✓ | 全量 `mvn -B package`（孤岛删除后）：46 模块全 SUCCESS、`BUILD SUCCESS` 55.6s、两法独立求和 **515 测试 / 0 失败 / 0 错误**（4 跳过＝环境面）、`dist/jh` jlink 镜像 + tar.gz/zip 产出、EXIT=0；含树冻结校验 |
| `it23-real-run.txt` ✓ | **文档从零走查（陌生视角）+ 真跑**：仓外新工程 `/tmp/it23-sc/fresh-consumer`（自写插件 + 自写主类，非示例拷贝）按 `docs/embedding.md` §2–§5 在 **0.1.0** 上跑通——compile 13.7s / 运行类路径 32 条 / `rows.composed=25 rows.enabled=20`、`tool.schemas=9 text_stats.visible=true`、`content=chars=31 words=5`、`FRESH WALKTHROUGH OK` EXIT=0；**发现 1 处文档缺陷并已修**（§5 `(disabled: <expr>)` 注记与示例代码矛盾：`dump(compose)` 5 行带注记 vs `dump(resolve)` 0 行）；keyed 腿**跳过**（可选面、不在验收 ①–⑨，理由见 S-c 实探发现 5）；环境适配两处（`-s settings-central.xml` + 隔离仓）已声明 |
| `S-c-package.txt` ✓ | 全量 `mvn -B package`（S-c 终态树：CI yml + `embedding.md §5` 修复后）：46 模块全 SUCCESS、`BUILD SUCCESS` 57.4s、两法独立求和 **515 测试 / 0 失败 / 0 错误**（4 跳过＝环境面）、EXIT=0；冻结校验**内容级两命**（构建窗口 `find -newermt` 无输出；构建后 `find` 复核：仅 5 份文档落笔、构建输入零改动）；含后记（构建后落笔均为文档，非构建输入） |
| `S-c-ci-dryrun.txt` ✓ | **CI 候选腿（锚点 12）**：ruby/psych 解析工作流（步骤名逐条列出；新步骤在 ubuntu job 末位，macos job 不动）+ 步骤命令本机干跑（`CONSUMER_WORK=<本机路径> integration/verify-consumer.sh candidate`）→ `失败项：0` EXIT=0、腿 79s、install `BUILD SUCCESS` 1:15 min、四负例逐条「已拒」；含执行位（`100755`）与 runner 前置核对、真实 CI 首跑需 push 的限制声明 |

## 验收（证据 = 实际执行的命令与结果）

- [x] ① 独立工程（非 reactor）仅凭坐标嵌入 agent：不引主仓源码、不靠 reactor 解析，能起 `Runtime` —— 两条腿同源复验（`S-b-candidate.txt` / `S-b-release-leg.txt`；示例 pom 无 parent、16 条显式依赖）
- [x] ② 注册自定义工具：`Plugin` + `ToolDefinition.of` + `registry.register` 生效，工具在声明面可枚举 —— 两腿 `word_count.visible=true` + 可执行 `content=words=3`；`ToolProbe` 逐名列出（发布腿 9 / 候选腿 11，含 `word_count`）
- [x] ③ 治理验证（keyless）：合法组合 `dump` 摘要 + `boot()` 通过；不合法组合 `boot()` **fail loud**（可读文案含违规项）—— 两腿 `SELF-CHECK OK` + 四负例（②③④）真实文案落证
- [x] ④ 候选工件腿：工作树 `install` 进临时本地仓 → 示例编译 + 运行 exit 0（命令/关键行/EXIT 落证）—— `S-a-candidate.txt` + 冻结树复跑 `S-b-candidate.txt`（`失败项：0`）
- [x] ⑤ 发布工件腿：Central 0.1.0（本地仓为空）→ 交集面复验；差异（含 `bootReported` 为 0.2.0 升级项）如实入文档 —— `S-b-release-leg.txt`（`失败项：0`，66 条取件全 `public-central`）；差异表入 [docs/embedding.md](../embedding.md) 版本面
- [x] ⑥ 负例控制：坏版本 / 去版本 / 坏治理配置 + **④生产档撞 AUTO 审批**（S-b 增强）四项各自必败或必抛，还原后复绿 —— `S-b-candidate.txt` 四「已拒」+ `S-a-regreen.txt` 复绿
- [x] ⑦ 文档：`docs/embedding.md` 六节齐（含排错指引）+ README 双语指引行；**按文档从零走一遍**可完成嵌入与自证 —— 文档面 ✓ S-b（七节，含模块路与排错表）；**走查 ✓ S-c**（`it23-real-run.txt`：仓外新工程 + 自写插件在 0.1.0 上从零跑通 EXIT=0；走查发现并修 1 处文档缺陷）
- [x] ⑧ 漂移收敛：`YamlRows` 三层 fail loud（含突变与 `07`/`12` 各一行）+ `07` 示例照实 + `05` 三处 builder + `02` 四处 + 孤岛删除 + `AGENTS.md:23` 软化 —— 突变 A/B/C 逐层红→复绿；全量 package 46 模块绿
- [x] ⑨ CI 候选腿 + 全量 `mvn -B package` 绿 —— 全量 package ✓ S-b（`S-b-package.txt`）+ S-c 终态复跑（`S-c-package.txt`：515/0/0，冻结校验内容级两命）；**CI 步骤 ✓ S-c**（`S-c-ci-dryrun.txt`：YAML 校验 + 步骤命令干跑 `失败项：0`）；真实 CI 首跑随下次 push（另行授权）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| S-c（随停点提交） | `docs/embedding.md §5`（S-b 交付）：`(disabled: <expr>)` 注记的说明与同段示例代码矛盾——示例 `dump(enabled)` 永不产出该注记（实况：`dump(compose)` 5 行带注记 vs `dump(resolve)` 0 行） | S-c 改文：注记出现条件写明「dump **未 resolve** 的 `compose(...)` 结果」；发现路径 = 文档从零走查（`it23-real-run.txt`）。**规则回填**：文档中「某调用产出 X」的断言，与「能产出 X 的那次调用」同处一个片段，并用真实运行复现一次 |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 计划书 §内容 2「同源优先：一份源码两腿共用…按交集面」（曾预期两面近似等同） | 发布腿组合行 25/20 vs 候选腿 26/21（差 `ask-user` 行）；工具面 9 vs 11（差 `ask_user`、`fs_search`） | 0.1.0 的 `harness-bundle-base` 发布 POM 未声明 `harness-interaction-ask`（compile 依赖传递差异，S-a 实探发现 2）；`fs_search`（it21）、`ask_user`（it22）本就不在 0.1.0 | 迭代内已同步：差异逐项入 [docs/embedding.md](../embedding.md) 版本面表 + `S-b-release-leg.txt`；**不回改 0.1.0 已发布坐标**（不可逆，裁决 §不做） |
