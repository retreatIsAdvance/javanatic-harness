# 迭代 25 — Windows 与 0.2 发布验收（状态：四确认与裁决已落盘（2026-09-28）；首步实探 S-0 待真 Windows 环境首跑）

模块：`sandbox/local`（win32 链 + windows-acl 后端 + FFM Win32 绑定/助手）· `shell/bash-local` → `shell/local`（平台分派 + 工具名/坐标迁移）· `examples/headless`（入口 UTF-8 wrap）· `dist/jh`（windows 归档真构建）· `.github/workflows/ci.yml`（windows job）· 测试平台分区四类（增强 1）· `integration/consumer-sample` + `integration/verify-consumer.sh` + `docs/embedding.md`（3a 改名涟漪）· `bundle/base`（bundle.yml 行）· `docs/design/{02,05,07,10,12,README}` · `README{,.zh-CN}.md` · `docs/release.md`

路线来源：路线 it25 行「**Windows 与 0.2 发布验收**：pwsh、本机隔离、进程树清理、路径/编码适配及发行包」——验收「真 Windows 环境完成安装、任务、拒批、取消和恢复；已支持平台回归；不是只装 pwsh 即算支持；发布工件与稳定面核对齐全」（`docs/design/README.md:147`）。平台承诺（`docs/design/README.md:131`）：「Windows 本机隔离 + pwsh、Linux Landlock fallback 保留在 0.2.0，不静默顺延」。

## 四确认

- **内容**（四件）：
  1. **win32 本机隔离（windows-acl 后端）**：`PLATFORM_CHAINS` `win32=[]` → `[windows-acl]`；按 `05 §6:474-478` 既定设计——WRITE_RESTRICTED 受限令牌 + per-workspace SID 常设授予 + per-session 随机临时目录/SID；`enforcement=PARTIAL` + 两洞（Everyone-可写外部对象仍可写、NTFS 硬链接别名越界）如实登记，stderr 签名 + exit 127 fail-closed。探针强度对齐 it24/bwrap：真建 + 真限 + 真跑 + 验真拒写（含限前正对照，防 DAC 误绿）。实现 = FFM 直呼 Win32（`CreateRestrictedToken` / `AllocateAndInitializeSid` / DACL 修改 / `CreateProcessAsUser`），无原生二进制、无 C 工具链。**拓扑与 landlock 不同**（增强 2）：助手（JVM）自身不受限，受限的是它创建的子进程；不变量 = 「子进程唯一生成路径是带受限令牌的 `CreateProcessAsUser`；令牌构造任一步失败即无子进程」；kill-tree 是真父子链（`descendants()` 可达）。
  2. **pwsh 执行面**（D3 3a）：shell 提供者平台化——`shell/bash-local` → `shell/local`：包 `shell.local`、插件 id `shell-local`、工具名 `bash` → `shell`；POSIX=bash（现状语义）/ Windows=pwsh（`-NoProfile -NonInteractive`）；拒绝方言、退出码与引号差异按平台登记；进程树清理在 Windows 上真验（`descendants()` 语义是否足 / Job Objects / `taskkill /T /F`，实探后定形）。**迁移涟漪**：0.1.0 已发布坐标 `harness-shell-bash-local` 被替换（0.2.0 迁移注记）；consumer-sample 16 依赖闭包、`docs/embedding.md`、`integration/verify-consumer.sh` 随动（candidate 腿用 0.2.0-SNAPSHOT 新坐标；release 腿钉 0.1.0 不动）。
  3. **Windows 交付与 CI**：windows 归档真构建 + 解压冒烟（`jh.bat --help`/`--verify` + 一条受限命令真拒写）；CI 新增 windows job（**平台分区完成后**才开——增强 1）；路径/编码适配——locale 修复随本段早落（形状已裁：`HeadlessMain` 入口 UTF-8 wrap + 助手 JVM `-Dstderr.encoding` 旗标）、盘符与 `%TEMP%`、CRLF。
  4. **0.2 发布工程（S-d，可分离）**：Release notes（it17–it25 破坏性变更与迁移路径汇总——`12` 已逐条在案）、稳定面全量核对（`12 §2` 导出面扫描）、`docs/release.md` 与 README×2 更新、**tag→GitHub Release 附件自动化**（it24 点名回收；本迭代出工作流定义，真 tag/Release/Publish 执行 = 用户侧）。

- **目标**：真 Windows 上完成「安装 → 任务 → 拒批 → 取消 → 恢复」全链；已支持平台（macos/linux）回归不破；不是只装 pwsh 即算支持——受限档由 windows-acl 真强制（PARTIAL 两洞如实，不冒充 FULL）、不满足约束 fail-closed；0.2.0 发布工件与稳定面核对齐全、迁移路径成文。

- **验收问题**（每轮一问）：*Windows 用户在未装任何额外工具的主机上让 Agent 受控地改工作区文件*——今天两条路都断（受限档 win32 空链 fail-closed；命令执行无 pwsh 通道）；it25 后：归档解压即跑、命令走 pwsh、受限档真强制（PARTIAL 如实）、拒批/取消/恢复在真 Windows 可验。

- **为什么**（现状证据）：
  - win32 链空（`SandboxLocalPlugin.java:48-51`），空链文案已点名「windows-acl planned — 0.2.0」（`:130-133`）⇒ Windows 受限档首条命令必 fail-closed。
  - 平台承诺落盘（`docs/design/README.md:131`）；路线 it25 行（`:147`）是本兑现窗口。
  - `05 §6:474-478` 设计已就位（受限令牌/SID/双洞/PARTIAL/exit 127 逐字在案），实现挂 0.2.0。
  - shell 只有 bash 一路：`LocalBashExecutor.java:53` 硬编码 `bash -c`；工具名 `bash`（`ShellToolPlugin.java:92`）；`LocalBashExecutorTest` 6 条无平台门、`ShellToolEndToEndTest` 前两用例无门（第三用例有 MAC 门）⇒ Windows 上必红。
  - `dist/jh`：`archive-windows` profile 已在（`dist/jh/pom.xml:139-143`）但从未真构建；CI 仅 ubuntu/macos 两 job。
  - it24 两项挂账归位：locale 修复 → 本迭代 S-c（形状已裁）；`/dev/null` allow-list 推论 → S-a 顺手实测出选项再裁（runner 已真 landlock 宿主 ABI v7）。
  - it24 点名推迟项：tag→GitHub Release 附件自动化 → 本迭代 S-d。

- **不做**：WSL/git-bash 通道（Windows 承诺的解释器是 pwsh）；Windows 容器（shell-docker 维持 POSIX 主机面）；windows-acl 两洞的消除（属 AppContainer/低完整性等更大改造，另行排期）；MSI/安装器/服务化（解压即用形态不变）；真 tag/Release 上传/Central Publish 的执行（用户侧，it16 先例）；网络与进程可见性、DANGER 档、提权流、资源限额照旧；0.1.x 已发布面语义回改（0.2.0 破坏面走迁移注记）；非 windows 平台新特性。

## 裁决记录（2026-09-28）

用户裁决：**草案成立；四项裁决全部可定，另附三处事实订正与七条增强。**

| 裁决 | 内容 |
|---|---|
| D1 | **验收主体 = keyless CI windows job（无需 secret）**：五个验收面全部可 keyless——任务 = 假服务端 SSE 驱动真 CLI 子进程 + 真 pwsh 工具调用（`HeadlessFakeServerE2ETest` 先例）；拒批 = EOF/审批超时（先例在案）；恢复 = `HeadlessResumeTest` 先例；取消 = `Signal.raise("INT")` 机制（**Windows 无 POSIX SIGINT，行为未知数 ⇒ 首步实探**）。真模型 Windows 跑 = 可选补充证据（it21/it22 real-run 先例），场所 = 本地 Windows VM（Win11 25H2 ARM64 ISO + UTM 已下载）|
| D2 | **一轮四面**；S-d 天然可分离——S-c 出口带证据决定是否甩成 it25.1（仍属 0.2.0），零损失 |
| D3 | **3a 改名泛化**（`shell/local` 平台分派；sandbox-local 的 `PLATFORM_CHAINS` 即先例；3b 双行并行会逼出「同 OS 双 provider 谁生效」的组合语义，违背单写者纹理）；涟漪见内容 2 |
| D4 | **`/dev/null` 顺手实测出选项**（runner 真 landlock 宿主 ABI v7，一条 e2e 出事实再裁，不带预判）|

**三处事实订正**（已并入正文）：

1. `ShellToolEndToEndTest` 并非全无门——第三用例有 MAC 门，前两确无（Windows 必红）；
2. it24 locale 修复未执行（台账口径「未改码、待裁」），it25 路线行「编码适配」自然归位；修复形状已裁（入口 wrap + helper `-Dstderr.encoding`），不再悬置；
3. `LocalFsTest` 大概率在 Windows 绿（围栏校验先于磁盘访问）——不动。

**七条增强**（并入锚点与实探清单）：

1. **测试平台分区 = CI windows job 的前置**：`LocalBashExecutorTest`（6 条）、`ShellToolEndToEndTest` 前两用例、`HeadlessCrashResumeE2ETest`（真 bash 子进程 + sleep 后代探测）、`DockerShellTest`（`@BeforeAll` 的 `sh -c` 在 daemonUp 门之前爆，Windows 上整类打成错误）——四类不分区，windows job「全量 package」到达即红。**顺序依赖显式化：S-b 分区完成，S-c 才开 windows job。**
2. **windows-acl 助手拓扑与 landlock 不同**（见内容 1）；landlock（自限制后 exec）/ windows-acl（helper 受限生子）/ docker（容器内 setsid）三种拓扑在 05 §6 各自写明，不混说。
3. **FFM 纪律从出生就上**（it24 血泪正向应用）：每函数一个类型化包装 + 绑定层测试；Windows 侧无 raw syscall 蹦床——直接绑 advapi32/kernel32 符号，类型来自真原型（it24 评审「libc 符号优先」的理想形态在 Windows 免费成立）；代价 = 符号在 macOS 不存在 ⇒ 绑定测试只在 Windows 跑。
4. **首步实探清单（S-0）**：`Signal.raise("INT")` 在 Windows 的行为（取消验收地基）· pwsh 存在性与版本 · `jh.bat` 真由 jlink 产出且归档解压可跑 · **FFM API 全谱**（`CreateRestrictedToken` + `AllocateAndInitializeSid` + DACL/ACL 修改 + `CreateProcessAsUser`）——任一符号/结构布局不可用都改变实现形状。
5. **locale 修复随 S-c 早落**（形状已裁：入口 wrap + helper 旗标），不第三次顺延。
6. **consumer-sample / embedding / verify-consumer 随 3a 改名联动**（D3）。
7. **`Ready.detail` 按 it24 契约携带 PARTIAL + 两洞**——不冒充 FULL，「PARTIAL 如实」由结构保证。

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：
  - `sandbox/local`：windows-acl 后端（FFM advapi32/kernel32 类型化绑定 + 负向/正向探针 + 受限令牌建子进程）
  - `shell/local`（由 `shell/bash-local` 改名泛化）：平台分派（POSIX=bash / Windows=pwsh）+ 工具名 `shell`
  - CI windows job（`.github/workflows/ci.yml`）+ 四类测试平台分区
  - tag→GitHub Release 附件工作流（tag 触发；真执行用户侧）
  - `examples/headless`：入口 UTF-8 wrap（locale 修复）
- MODIFIED：
  - `PLATFORM_CHAINS`：`win32` `[]` → `[windows-acl]`
  - 坐标迁移（0.2.0 迁移注记）：`harness-shell-bash-local` → `harness-shell-local`；插件 id `shell-bash-local` → `shell-local`；包 `shell.bash.local` → `shell.local`；工具名 `bash` → `shell`——同步面：`pom.xml:201`、`bundle/base/pom.xml:78`、`shell/tool/pom.xml:35`、`shell/bash-local/pom.xml:13-14`、`integration/consumer-sample/pom.xml:97`、`bundle.yml:52-53`、`dist/jh` 模块表、`12 §2/:40/:108`、`05:238/:318`、`07:58/:295`、`10:376`、`02:70`、`integration/verify-consumer.sh`、`docs/embedding.md`
  - `Ready.detail`：windows-acl 行携带 PARTIAL + 两洞（结构保证，不冒充 FULL）
  - 测试平台分区四类（增强 1）
  - 文档同步：05 §6（三拓扑分别写明 + win32 链实况）、02 模块表、07/bundle 行、12 稳定面（迁移面）、README×2 平台段与归档指引、`docs/release.md`（windows 归档、附件自动化）、`docs/design/README.md`（it25 状态随收口）
- REMOVED：无（改名属 MODIFIED；0.1.0 坐标保留在已发布历史）

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `sandbox/local` · `PLATFORM_CHAINS`(:48-51) + 新增 WindowsAcl 绑定/助手 | win32 链 + 受限令牌生子（拓扑见内容 1）+ 探针 | 待 |
| `sandbox/local` · `SandboxLocalTest`（WINDOWS 腿 + `/dev/null` 实测项，D4） | 平台 e2e + it24 挂账实测 | 待 |
| `shell/bash-local` → `shell/local`（pom / 包 / 模块名 / plugin id / 工具名 / 方言） | 平台分派 + 迁移涟漪 | 待 |
| 四类测试平台分区（增强 1） | POSIX 门 | 待 |
| `examples/headless` · `HeadlessMain` 入口 + 助手 JVM 旗标 | UTF-8 wrap | 待 |
| `dist/jh` · pom 模块表 + `archive-windows` + assembly | windows 归档真构建 | 待 |
| `.github/workflows/ci.yml` | + windows job（分区完成后） | 待 |
| `integration/consumer-sample` + `verify-consumer.sh` + `docs/embedding.md` | 3a 改名随动 | 待 |
| 文档同步面（05/02/07/10/12/README×2/release.md/design README/bundle.yml） | 迁移注记与平台段 | 待 |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S-a：**windows-acl 后端与 FFM 绑定**（安全不变量承载：受限令牌构造、子进程唯一生成路径、探针正负对照、PARTIAL 两洞登记） | `sandbox/local` 新绑定 + 探针 + `PLATFORM_CHAINS` | 待 |
| S-b：**shell 平台化与契约迁移面**（跨模块：工具名 / 插件 id / 坐标 / 分区 / 文档同步面） | `shell/local` + 分区 + 迁移面 | 待 |
| （S-c / S-d 不设停点：机械为主；S-d 可分离见 D2） | | |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-25/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `S-0-windows-probe.java` + `S-0-windows-probe.txt` | 首步实探（增强 4）：Signal INT / pwsh / encoding / FFM API 全谱（符号 + 令牌 + 生子 + 正负写腿）；**源已备（本机 javac 25 编译通过 + macOS 冒烟空跑 failures=0，FFM 腿按设计 SKIP），待真 Windows 首跑** |
| `S-a-windows-acl.txt` | S-a 停点：探针真跑（真建真限真跑 + 正对照 + 真拒写）/ 突变 / 两洞如实 / `/dev/null` 实测事实（D4） |
| `S-b-shell-platform.txt` | S-b 停点：pwsh 执行真跑 + 方言 + 进程树 + 分区 + 迁移面核对 |
| `S-c-windows-delivery.txt` | windows 归档 + CI windows job + 归档冒烟 + locale 修复实证 |
| `S-d-release-engineering.txt` | 发布工程核对（12 全量扫描 + notes/迁移路径 + release.md + 附件工作流） |

## 验收（证据 = 实际执行的命令与结果）

- [ ] 真 Windows 环境完成安装（归档解压 → `jh.bat --help`/`--verify` 全绿）
- [ ] 真 Windows 完成任务（keyless：假服务端 SSE 驱动真 CLI 子进程 + 真 pwsh 工具调用；可选补充：真模型 Windows 跑）
- [ ] 真 Windows 拒批（EOF/审批超时先例）
- [ ] 真 Windows 取消（机制随 S-0 实探定形）
- [ ] 真 Windows 恢复（`--resume` keyless 先例）
- [ ] 不是只装 pwsh 即算支持（受限档真强制 + PARTIAL 两洞如实 + fail-closed 支路）
- [ ] 已支持平台回归（ubuntu/macos job 双绿 + 归档冒烟不回归）
- [ ] 发布工件与稳定面核对齐全（12 全量扫描 + release notes/迁移路径 + release.md + 附件自动化）
- [ ] 全量 package 绿（本机 + CI 三 job）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| | | |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| | | | |
