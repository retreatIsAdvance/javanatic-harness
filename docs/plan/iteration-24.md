# 迭代 24 — Linux 交付与 Landlock（状态：S-a / S-b / S-c 三段全部落盘并放行（2026-09-24）；S-a/S-b 已按「放行 S-b，按两提交收口」裁决收口（`8353733` / `ef41e63`，未推送），S-c（CI 腿 + 归档冒烟 + 文档）随收口提交一并落盘；全量 package 544/0/0/7 绿（本机 darwin）；首次 CI 跑（landlock 硬门 / 容器冒烟 / upload-artifact 三项）待 push 放行后回填——S-c 证据第 8 节已如实标注边界。**S-c 补验（2026-09-24，本机 docker 可用后）：容器内构建出 linux-aarch64 归档并真跑六项——landlock 硬门负支路（exit 10 / ENOSYS）、干净容器归档冒烟（WARNING 支路）、INFO 支路（bwrap 可用档）、交付归档自带助手、聚焦测试容器内真跑（含 bwrap 两条真强制 e2e 本机首次于真实 Linux 内核跑通）、CI 第 7 步断言 vs 真实 surefire 报告；「首次 CI 跑待回填」项据此收缩为 artifact 上传 + landlock 正支路 + amd64 产物三项（`docs/plan/evidence/iteration-24/S-c-local-container-runs.txt`）**）

模块：`sandbox/local`（linux 候选链 + landlock 后端 + FFM 自限制助手）· `sandbox/sandbox`（`BackendStatus` 能力诊断契约扩展）· `bundle/base`（`AppBoot` verify 沙箱行）· `.github/workflows/ci.yml`（landlock 腿 + 归档冒烟 + artifact）· `docs/design/{05,README}` · `docs/release.md` · `README{,.zh-CN}.md` · `bundle/base/src/main/resources/META-INF/harness/bundle.yml`（注释）· `docs/design/{06,07}`（漂移订正）

路线来源：路线 README it24 行「**Linux 交付与 Landlock**：可直接使用的 Linux 归档；Landlock fallback 与能力诊断」——验收「干净 Linux 环境可安装运行；bwrap 不可用时按能力选择；不满足约束时 fail-closed；真实 OS 验证，不以编译通过代替」（`docs/design/README.md:146`）。平台承诺（`:131`）：「Linux Landlock fallback 保留在 0.2.0，不静默顺延；无法满足限制语义时 fail-closed，不用裸跑冒充支持」。

## 四确认

- **内容**（四件）：
  1. **Landlock 后端落地（linux 链 `[bwrap, landlock]`）**：
     - 链仲裁按序——bwrap 探针可用则用 bwrap；不可用（未安装 / AppArmor 收紧非特权 userns 等）落到 landlock；全不可用仍 **fail-closed**（`SandboxLocalPlugin.java:42-45/:287-308` 的链结构即为此预留）。
     - **术语（钉死）**：链内探针仲裁 = **选择期 fallback（按能力选择）**，非 per-call 降级——一次探针、缓存、进程生命周期内不再切换；「降级」一词不用于此（保留给显式策略降档语义）。
     - 形状 = **自限制后 exec**（it12.7 台账原文：「零依赖的 Landlock 需要『自限制后 exec』的 helper **或 FFM**」（`docs/plan/iteration-12.7.md:19`）——本迭代选 **FFM 分支**，非推翻任何 C/musl 承诺，台账无该记录）：`confine` 返回「助手 argv」——助手 `prctl(PR_SET_NO_NEW_PRIVS)` + `landlock_create_ruleset` + `landlock_add_rule`（可写根逐根 `PATH_BENEATH`）+ `landlock_restrict_self`（syscall 444/445/446，经 libc `syscall()` 的 FFM downcall），随后 **exec 目标 argv**（限制跨 execve 继承；助手被目标镜像替换，无监督进程残留）。
     - **兜底随产物自带**：助手即镜像内 JVM（`java.home/bin/java`）——**不引入 C 工具链与原生二进制**。这是 dsh「兜底随产物自带」的 Java 版形态（其 `native/landlock-run` 为 C + 独立 CI；JH 是 Java 仓，JVM 自身即现成助手宿主）。
     - **allow-list 口径**：只 handle 写效果 rights（`WRITE_FILE` / `REMOVE_FILE` / `REMOVE_DIR` / `MAKE_*` / `REFER` / `TRUNCATE`），可写根从 `WritableRoots.of(policy)` **单一来源**取（与 seatbelt / bwrap / fs 围栏同源，含 `/tmp` 与平台临时区）；读可见性与网络**不在约束面**（对齐 bwrap `--ro-bind / /` 的读可见残余，`05:472-476`）。
     - **ABI 分级**：ABI ≥ 3（`REFER`/`TRUNCATE` 齐备）→ 入选、`enforcement=FULL`；ABI 1–2 → **不入选**，探针失败并在诊断中点名——理由是 ABI<3 的两洞直接破坏 READ_ONLY 承诺（O_TRUNC 可清空只读文件、跨目录 rename/link 不受控），「不满足约束时 fail-closed」优先；`SandboxEnforcement.PARTIAL`（`SandboxEnforcement.java:12` 预留位）保持预留不动。能力判断**以内核事实为准**（见裁决增强 4），不按版本号推断。生态互补：无 ABI≥3 的老内核（如 5.15 系）默认不禁非特权 userns → bwrap 可用；而 AppArmor 收紧 userns 的 24.04+ 内核 ≥6.2 → landlock 可用——两条腿覆盖彼此缺口。
  2. **能力诊断（`--verify` 面 + 契约扩展）**：
     - `BackendStatus` 扩展：`Ready` 携带所选后端 + 完备度 + 细节（bwrap 探针结论 / landlock ABI 与 rights 子集）；非 Ready 维持点名预警。
     - `--verify` 在 Ready 时**也打印沙箱行**（当前静默，`AppBoot.java:240-253`）；链上有候选失败时点名「前一候选为何未用」（如 `bwrap probe failed (…); landlock: ABI v4 (REFER+TRUNCATE)`）。exit 码不变（预警非违规，`05:470` 口径）；诊断落点在 `--verify`（治理摘要的家），**不扩冻结 CLI 面**。
     - 契约变更触发设计同步规则②：`05 §6` 同步（含 `ConfinedArgv.enforcement` 消费面）；`12` 稳定面核对随 S-b。
  3. **可直接使用的 Linux 归档**：
     - CI ubuntu job 已产出 `dist/jh/target/javanatic-harness-*-linux-amd64.{tar.gz,zip}`（`dist/jh/pom.xml:134-138` 档名就绪）——本迭代补上**验证与交付**：解压 → `bin/jh --help` / `--verify`（含 bwrap 不可见时诊断行）→ `actions/upload-artifact` 供下载。
     - 干净环境真跑：docker 容器（`ubuntu:24.04`，CI 已有 provision 步骤，`.github/workflows/ci.yml:34-39`）内解压归档跑——「干净 Linux 环境可安装运行」（无 Maven、无 JDK，只凭归档自带运行时）。
     - `docs/release.md` §5/§8 更新：Linux 归档获取方式（0.1.0 首发只附 macOS 归档，`:112` 明说「CI 上传自动化留后续迭代」——本迭代即该后续）。tag→GitHub Release 附件自动化**明确推迟**到 it25（0.2 发布工程一并做，动作用户侧）——**推迟去向点名，不默然消失**。
  4. **真实 OS 验证（验收骨头）+ 文档同步**：
     - CI ubuntu：现有 bwrap 前置门与真强制 e2e 保持（`:16-29`，负向探针硬门为形状先例）；新增 **landlock 腿**——bwrap 注伪（构造器 seam 已在 `SandboxLocalPlugin.java:71-80`）→ 链落到 landlock → 真拒写（workspace 外 denied / workspace 内 allowed / READ_ONLY 全拒）。
     - 容器冒烟与 CI landlock 腿同时兑现「bwrap 不可用时按能力选择」；**开工首步实探** runner 内核 ABI 与 docker 默认 seccomp 对 landlock 系统调用的放行情况（不预设，取证落盘）。
     - darwin 本机不能验 landlock 真强制：形状/协议单测平台无关 + 如实标注「CI/容器为准」。
     - 文档同步：`05 §6`（链 / 形状 / ABI / 洞 / **真进程树优势** / 选择期 fallback 术语）、README 双语平台段、`release.md`、`bundle.yml` 沙箱注释；**漂移订正两处**——`07-profile-bundle.md:74`（it12.5 修正表**台账挂账**：「待随 it13 订正」实测至今未订）与 `06-scope.md:198`（**台账外新发现**，it12.5 只挂了 07；实况词表是 `sandbox-policy` 行的 `mode: workspace-write`，见 `bundle/base/.../bundle.yml:42-46`）。

- **目标**：干净 Linux 机器/容器上，解压归档即 ① 能跑（jlink 自带运行时，无需 Maven/JDK）；② 受限档**开箱可用**——有 bubblewrap 用 bwrap，没有/不可用（AppArmor 收紧 userns 等）自动落到 Landlock（ABI≥3），仍受内核强制；③ 两条都不可用时 fail-closed，且 `--verify`/诊断在事前就说清原因与出路。Windows 不在本迭代（it25）。

- **验收问题**（路线要求每轮补一问）：*Linux 用户在 Ubuntu 24.04 这类默认收紧非特权 userns 的主机上想跑受限任务*——今天首条 bash 命令必 fail-closed（bwrap 单候选，`SandboxLocalPlugin.java:42-45`；`05:458` 记录该 fail-closed 语义），且只附了 macOS 归档（`release.md:112`），Linux 用户无处下载；it24 后：从 CI/Release 拿归档解压即用，bwrap 不可用时按能力落到 Landlock 仍被约束，两条都不满足时启动期点名原因。

- **为什么**（现状证据）：
  - **平台承诺已落盘**：`README.md:131`「Linux Landlock fallback 保留在 0.2.0，不静默顺延；无法满足限制语义时 fail-closed」——it24 是其兑现窗口。
  - **bwrap 单候选的缺口已成常态**：Ubuntu 24.04 起 AppArmor 默认限制非特权 user namespace，bwrap 起不来；it12.7 的 CI 前置门当场实走了 sysctl 降档才通过（`.github/workflows/ci.yml:16-29`），dsh 亦把它列为「装不上 bwrap 的主机是常态」。
  - **参考实现明确拒绝「无兜底」**：dsh 把「bwrap or fail closed」判为 "concentrates failure on the hosts a sandbox matters most"，其兜底 `native/landlock-run` **随产物自带**（见 memory `reference-dsh-agent-notes`）；JH 缺的正是「随产物自带」这一层——本迭代补上（Java 形态）。
  - **契约位置已留**：`SandboxEnforcement.PARTIAL` 预留语（`SandboxEnforcement.java:12`）、`ConfinedArgv.enforcement/denialSignatures` 契约在位（`ConfinedArgv.java:17`）、`BackendStatus` 三态查询面在位、平台链结构即多候选探针仲裁（it12.7 四确认原文：「候选链结构就是为多候选探针仲裁留的」）。
  - **归档面缺口**：`release.md:109-112`（0.1.0 只附 macOS 归档；Linux 归档需在 Linux 机器手工 `mvn -B package`，「CI 上传自动化留后续迭代」）；CI ubuntu 目前已经在产 Linux 归档但**无验证、无取件**（`.github/workflows/ci.yml:41-47` 只冒烟 jlink 目录，不碰归档）。

- **不做**：
  - Windows / pwsh（it25）；**tag→GitHub Release 附件自动化**（点名推迟至 it25 0.2 发布工程，发布动作用户侧）
  - 网络与进程可见性（landlock 网络面、`--unshare-net/pid` 均不做——词表外照旧，`05:472-476`）
  - 读可见性约束（只 handle 写面 rights，与 bwrap 读可见残余对齐）
  - C 原生 helper / musl 静态二进制 / 自建原生构建链（台账从未记录该路线；FFM 分支已裁决为选中路线，此处记为不做以钉死边界）
  - 容器内叠 Landlock（it12.5 挂账照旧）；DANGER 档不进 provider（照旧）；提权流（照旧）；资源限额（照旧）
  - 不回改 0.1.0 已发布面；不动 fs-tool 进程内围栏与 `WritableRoots` 单一来源语义

## 裁决记录（2026-09-24）

评审结论：**草案成立，①–⑤ 全按建议**；附三处事实订正（落盘时已改）与七条锚点增强。事实基座核实（用户侧复核）：路线逐词对应属实；`firstUsable` 仲裁机制就位（`linux=[bwrap]` 单候选未激活即机制已在）；`BackendStatus` 三分支、Ready 静默属实；`WritableRoots` 可复用；FFM 零先例；kill-tree 语义在位；`release.md:112` 属实；it23 已推送 + CI 真跑在案（run `35961710129`），仅 `e21bd6f` 待推。

| 裁决 | 内容 |
|---|---|
| ① | **Java helper**：零 C 泳道、JVM 随镜像自带、接受约 100ms 级启动噪声 |
| ② | **ABI≥3 才入选**：1–2 两洞毁 READ_ONLY 承诺 → fail-closed + 诊断点名 |
| ③ | **扩 `--verify`**（治理摘要的家），不扩冻结 CLI |
| ④ | **CI artifact + 归档冒烟**；Release 附件推迟去向点名 it25，不默认 |
| ⑤ | **CI landlock 腿 + 容器冒烟**（bwrap 负向探针硬门为形状先例） |

**三处事实订正**（落盘时已并入正文）：

1. 「musl / C helper 为 it12.7 台账记录」**无据**——台账原文是「helper **或 FFM**」（`iteration-12.7.md:19`）；Java-helper 是**选中 FFM 分支**，非推翻 C 假设。不做清单已按此改写。
2. 术语钉死：链内探针 = **选择期 fallback（按能力选择）**，非 per-call 降级；「降级」保留给显式策略降档。
3. `06-scope.md:198` 系**台账外新发现失真**（it12.5 只挂了 07）；两处漂移的出处标注已区分。

**七条锚点增强**（并入锚点表与实现口径）：

1. 助手 JVM 必带 `-XX:-UsePerfData`（否则 READ_ONLY 下 /tmp 落 perfdata = 违规写）与 `--enable-native-access=<module|ALL-UNNAMED>`。
2. 启动双形：named 模块（镜像态）→ `-m <module>/<main>`；unnamed（开发/测试态）→ `-cp <助手类 code source> <main>`——按 `Class.getModule().isNamed()` 探测，非按 classpath 空否猜测（**S-b 订正**：原落盘为 `-cp <java.class.path>`；实证 surefire 下 java.class.path 可能是 booter jar，助手类真实位置只在 code source——见偏离表）。
3. syscall 号**按架构 fail loud**：444/445/446 仅 x86_64/aarch64 白名单，他者探针失败并点名架构。
4. ABI/能力检测走 `create_ruleset` **内核接受的最大 rights 子集**（事实），不按版本号推断。
5. 探针强度对齐 bwrap：**真建 + 真限 + 真跑 + 验真拒写**（负向探针；含限制前写正对照，防 DAC 误绿）。
6. **真进程树**优势写进 05：助手 exec 后即目标进程，与调用方同树——既有 killTree 零新增机制收敛（与 docker 的结构性不同：容器边界内进程不在同机树）。
7. `no_new_privs` **先于** `restrict_self`（内核前置条件）。

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED（均在 `sandbox/local`，不新增模块——`dist` 镜像模块表已含 `io.javanatic.harness.sandbox.local`，`java.lang.foreign` 在 `java.base`）：
  - `Landlock.java`：FFM 绑定（`prctl` / libc `syscall` 经 `Linker.Option.firstVariadicArg`）；架构→syscall 号表（x86_64/aarch64 白名单，他者 fail loud）；写效果 rights 集；`create_ruleset` rights 子集降档探测（内核事实）；`add_rule`（`PATH_BENEATH`）；`restrict_self`。
  - `LandlockExecMain.java`：助手入口——`--probe`（负向自检：限制前写正对照 → 自限制 → 验写被拒）与 `--mode read-only|workspace-write [--root <path>]… -- argv…`（自限制后 exec 目标）。
  - `LandlockBackend`（`SandboxLocalPlugin` 内）：链第二候选；helper 命令双形构造（增强 1+2）；负向探针 + 首探缓存；`ConfinedArgv` = helper argv + `enforcement` + landlock 方言。
- MODIFIED（旧 → 新）：
  - `PLATFORM_CHAINS.linux`：`[bwrap]` → `[bwrap, landlock]`（选择期 fallback）
  - `BackendStatus.Ready(String)` → `Ready(String backend, SandboxEnforcement enforcement, String detail)`（能力诊断；触发同步规则②，12 稳定面核对随 S-b）
  - `AppBoot.sandboxWarning`（字节不变）+ 新增 `sandboxLine`/`emitSandboxObservations` → Ready 出沙箱行（INFO）、非 Ready 点名预警（WARNING）不变；exit 码不变
  - `.github/workflows/ci.yml` ubuntu job → + landlock 腿（bwrap 注伪 → 真拒写/真放行）+ 归档冒烟 + `upload-artifact`
  - `docs/design/05 §6` → linux 链、形状、ABI、洞、真进程树、选择期 fallback 术语
  - `docs/release.md §5/§8` → Linux 归档获取（CI artifact；Release 附件推迟 it25 已点名）
  - `README.md` / `README.zh-CN.md` → 平台段与归档指引
  - `bundle/base/.../bundle.yml` → 沙箱注释（linux 链更新）
  - `07-profile-bundle.md:74`、`06-scope.md:198` → 漂移订正（示例回落实况词表）
- REMOVED：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `sandbox/local` · `SandboxLocalPlugin.java`：`PLATFORM_CHAINS`(:42-45)、`Backend` 接口(:194-205)、`ChainedBackend.firstUsable`(:312-326) | 链加 landlock；helper 命令构造（双形 + 两 JVM 开关）；landlock 负向探针 | ✓（S-b） |
| `sandbox/local` · 新增 `Landlock.java` / `LandlockExecMain.java` | FFM 自限制后 exec；架构白名单 fail loud；rights 子集探测；no_new_privs 前置；负向自检 | ✓（S-a） |
| `sandbox/sandbox` · `BackendStatus.Ready` | 扩 enforcement + detail；消费方（AppBoot/测试）随动；设计同步② | ✓（S-b） |
| `bundle/base` · `AppBoot.sandboxWarning`(:228-254) | Ready 沙箱行 + 级联失败点名；exit 码/预警语义不变 | ✓（S-b） |
| `.github/workflows/ci.yml` ubuntu job | landlock 腿 + 归档冒烟（解压/`--help`/`--verify`/PATH 下 bwrap 不可见诊断行）+ upload-artifact | ✓（S-c：前置硬门 2 真跑助手 `--probe` + surefire XML 断言「跑了且没跳过」+ verify 落点点名 bwrap 断言 + `ubuntu:24.04` 容器解压冒烟 + `upload-artifact@v4`；定义期静态校验与断言三分支演习见 S-c 证据，真 runner 落点待首次 CI 跑回填） |
| `docs/design/05` §6、`README{,.zh-CN}.md`、`docs/release.md`、bundle.yml 注释 | 链/形状/洞/进程树/归档获取同步 | ✓（S-c 补齐：05 §6 退出码协议 + `/dev/null` 与 seatbelt 差异 + NixOS 宿主形态登记；README×2 平台段与归档索取；release.md §5/§8；05 §6 / 12 §3 / bundle.yml 注释 S-b 已在案） |
| `06-scope.md:198`、`07-profile-bundle.md:74` | 漂移订正（限定词汇表回落实况） | ✓（S-c：06 示例回收 `description`+`rows` 实况并补「档位不在 preset 里配」（含 preset 行 `config` 只解析不装配的实况）；07 patch 示例改 `sandbox-policy` 行 `mode`/`workspace`） |
| `sandbox/local` · `SandboxLocalTest`（+ landlock 形状/仲裁/负向 e2e `@EnabledOnOs(OS.LINUX)`） | 聚焦测试 + 突变 | ✓（S-a 形状/负向 e2e；S-b 仲裁/诊断/包装） |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S-a：**自限制助手与 FFM 绑定**（安全不变量承载类：allow-list 口径、no_new_privs 顺序、架构 fail loud、双形启动、负向自检） | `Landlock.java`、`LandlockExecMain.java` | **停点已达并已放行（2026-09-24；裁决「放行 S-a，继续 S-b」）**：`Landlock.java`（FFM 五形状 + 三形态启动 + 严格解析 + 负向探针 + 退出码协议）与 `LandlockExecMain.java` 落盘；`LandlockTest` 17 测试（1 条 Linux 腿在 darwin 跳过）；双形启动真命令各 exit 10 点名平台、用法拒绝 exit 14；五突变必红→还原复绿（M1/M2/M3/M4/M5）；**M4 首轮自曝测试缺陷已修**（平台分区曾借被测代码判断 → 改 `@EnabledOnOs`）；本机不可验边界如实标注（darwin 无 landlock 内核、容器内 Linux JDK 17 低于 FFM 定稿线 22——瓶颈是缺席的 Linux 宿主/内核，非 JDK 版本线 → 正路径归 S-c CI） |
| S-b：**链仲裁与诊断契约**（跨模块契约变更：链序、探针、`BackendStatus` 扩展、`--verify` 输出） | `SandboxLocalPlugin` 链、`BackendStatus`、`AppBoot.sandboxWarning` | **停点已达并已放行（2026-09-24；裁决「放行 S-b，按两提交收口」）**：链 `linux=[bwrap, landlock]`（选择期 fallback：探针一次、缓存、进程生命周期内不切换）+ `LandlockBackend`（helper 命令双形 + 负向探针 + 结论行回收）；`BackendStatus.Ready(backend, enforcement, detail)` 三部件（fail-loud 构造）；`AppBoot` 双级观测——`sandboxWarning` 字节不变、新增 `sandboxLine` + 可注入 `emitSandboxObservations`（WARNING/INFO 两级，exit 码不变）；聚焦三模块全绿（LandlockTest 17 / SandboxLocalTest 23 / AppBootTest 21，跳过项为平台分区）；真 `jh --verify` 出 INFO 沙箱行（证据第 3 节）；突变 5 条必红→还原复绿；**JUL 一次性流绑定缺陷自曝并修**（修正表）；随 S-b 落盘两台账小项（EFAULT 归属措辞对齐见 `S-a-landlock-helper.txt` §4；JDK 口径统一见本节三处） |
| S-c：CI 腿 + 归档冒烟 + 文档 + 收口 | （不设停点：机械为主） | **已完成（2026-09-24，随收口提交）**——待办逐条落地：① CI landlock 腿＝前置硬门 2（jlink 镜像模块形态真跑助手 `--probe`，非 0 即红）+ 配套 surefire XML 断言（两条 landlock e2e「跑了且没跳过」，跳/缺/正常三分支本地演习过）；② 归档冒烟＝`ubuntu:24.04` 容器内解压重跑（`--help`/`--verify`，断言 bwrap 不可见点名行 + 沙箱行如实两分支）+ `upload-artifact@v4`（`javanatic-harness-linux-amd64`，`if-no-files-found: error`）；③ README×2/release.md 按 S-b 输出契约与归档实况同步；④ 05 §6 补退出码协议一句带过与 `/dev/null`/seatbelt 差异一句，并按「NixOS 探针尾步登记」把「bwrap 定位不到」宿主形态写进已知残余（连 CI 容器冒烟作真跑登记——**此处为解释性落点，若原意是代码级探针步骤新增请指出，回填订正**）；⑤ 06/07 两处漂移订正（示例回落实况词表）。本地校验：YAML 可解析（11 步）、8 个 `run` 块 `bash -n` 全过、容器内层脚本 `sh -n` 过、断言脚本三分支演习齐、全量 package 544/0/0/7 绿；**边界（已由容器补验订正）**：原登记的三项首次 CI 跑事实中，(a) landlock 硬门真跑、(b) 容器 seccomp 行为已在本机容器取得可判别事实——硬门走到 `EXIT_UNAVAILABLE` 负支路（exit 10，结论行 `errno ENOSYS`）；ENOSYS 而非 EPERM ⇒ syscall 444 抵达内核，即**默认 seccomp 未拦 landlock 系统调用**；(c) artifact 上传仍属 CI 首跑，另 amd64 产物与 landlock **正**支路（ABI≥3 真拒写）亦仍属 CI 首跑（`S-c-local-container-runs.txt` §8）。**发现残余（候选挂账）**：landlock 腿写授权只到可写根，`/dev/null` 不在 `WritableRoots` 内 ⇒ 该腿下 `cmd > /dev/null` 被拒（EACCES），与 seatbelt/bwrap 行为不同——已在 05 §6 如实记账（**标为 allow-list 语义推论、本机无 landlock 内核未实测**），未修（修法涉及 `WritableRoots` 单一来源语义，宜与 sandbox 策略同议）。**两条未处置观察（容器补验新出，待裁）**：非 UTF-8 locale 下 CLI 输出整体降级——`--help` 中文变 `?`、结论行 em-dash 变单字节 `0x3f`（`LANG=C.UTF-8` 下两处均正常，字节级实证见补验档 §8）；出路候选：启动器注入 UTF-8 出口编码（`dist/jh` 的 launcher 目前无 options 位）或文档注明「非 UTF-8 locale 请设 LANG」。 |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-24/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| `S-a-local-kernel-probe.txt` | 开工首步实探（锚点前置）：本机无 landlock 内核（docker 6.4.16-linuxkit 未编译 `CONFIG_SECURITY_LANDLOCK`）、容器内 Linux JDK 17 低于 FFM 定稿线 22（瓶颈是缺席的 Linux 宿主/内核，非 JDK 版本线）→ S-a 正路径不可本地验，权威验证归 S-c CI 腿（覆盖边界依据） |
| `S-a-landlock-helper.txt` | S-a 停点：编译/门禁 EXIT=0 · 聚焦测试 33 跑 3 跳（含 `SandboxLocalTest` 回归）· 双形启动真命令（exit 10 点名平台 / exit 14 用法拒绝）· FFM 五形状探针 · 调用点×描述符 arity 逐字节审计 · 五突变必红→还原复绿 · M4 自曝测试缺陷留档；S-b 补录 §4 的 (f) 错形状静默错参实测（EFAULT 归属措辞对齐） |
| `S-a-ffm-shape-probe.java` | FFM 绑定形状的本机实跑探针源码（上文件第 4 节命令的执行体，未参与构建；含「形状错=静默传错参」实证 (f)，六次实跑 errno 14×3 / 22×3 摆动） |
| `S-b-chain-and-diagnostics.txt` | S-b 停点：链仲裁（`[bwrap, landlock]` 选择期 fallback、探针缓存与结论行回收）· `BackendStatus.Ready` 三部件 · `--verify` 双级观测（真 `jh --verify` 出 INFO 沙箱行）· JUL 一次性流绑定探针 · 突变 M1–M5 必红→还原复绿 |
| `S-b-jul-err-binding-probe.java` | 「JUL 后端在首条记录发布时一次性绑定 `System.err`」的独立复现源（上文件第 4 节命令的执行体；修正表缺陷的实证，未参与构建） |
| `S-c-ci-leg-and-docs.txt` | S-c 收口：CI 增量逐条（硬门 2 / 断言 / verify 点名 / 容器冒烟 / artifact）· YAML 可解析与 11 步清单 · 8 个 `run` 块 `bash -n` 全过 · 容器内层脚本 `sh -n` · 断言脚本三分支演习（跳/缺/正常）· 全量 package 544/0/0/7 + 7 条跳过逐条枚举 · 归档名与 glob/profile 核对 + 本机 `--verify` 同构行 · **第 8 节：三项首次 CI 跑事实的边界登记（待 push 后回填）** |
| `S-c-local-container-runs.txt` | **S-c 补验（本机容器真跑，2026-09-24）**：容器内构建 Linux 归档（首撞裸镜像缺 binutils → jlink 失败；补后 BUILD-EXIT=0，产 aarch64 tar.gz/zip + jlink 镜像）· landlock 硬门负支路（`--probe` exit 10 / `errno ENOSYS` ⇒ seccomp 放行 syscall 444）· 干净容器归档冒烟（CI 第 9 步逐字形状，SMOKE-EXIT=0、两条 grep 命中、`--verify` exit 0）· INFO 支路（bwrap 可用档，逐字节命中 CI 第 8 步 grep 串）· 交付归档自带助手真跑（`bin/java -m …LandlockExecMain`）· 聚焦测试容器内真跑（SandboxLocalTest 23/0/0/6、LandlockTest 17/0/0/2；**bwrap 两条真强制 e2e 本机首次于真实 Linux 内核跑通**）· CI 第 7 步断言 vs 真实 surefire 报告（如实转红并点名）· §8 诚实边界（artifact 上传 / landlock 正支路 / amd64 产物仍属 CI 首跑）与两条待裁观察（非 UTF-8 locale 降级，含字节级实证） |

## 验收（证据 = 实际执行的命令与结果）

- [ ] 干净 Linux 环境可安装运行（归档在干净容器/主机解压 → `--help`/`--verify` 全绿）——**本机容器 ✓ 补验**（本机容器内构建的 linux-aarch64 归档在裸 `ubuntu:24.04` 解压即跑：`--help` 正常、`--verify` exit 0、两处 grep 命中；`S-c-local-container-runs.txt` §3/§5）；**CI 侧定义 ✓ S-c**（冒烟步骤 + artifact 取件，S-c 档 §7）；**amd64 产物与 artifact 取件待首次 CI 跑回填**
- [ ] bwrap 不可用时按能力选择（链仲裁落到 landlock，真拒写真放行，CI linux 腿 + 本机容器）——**本机容器 ✓ 部分**（两候选全败时的链点名与 fail-closed 真跑 + bwrap 两条真强制 e2e 于真实 Linux 内核跑通，补验档 §3/§6）；**landlock 真拒写真放行待首次 CI 跑回填**（本机内核无 landlock，只到负支路）
- [x] 不满足约束时 fail-closed（ABI<3 / LSM 缺失 / 两候选全败 → 点名原因，不裸跑）——助手退出码 10/11/12/13/14/127 全 fail-closed + 结论行回收（`S-a-local-kernel-probe.txt` / `S-a-landlock-helper.txt`）；链仲裁与全败点名由 `SandboxLocalTest` 注入式 e2e 覆盖（S-b，平台无关，本机真跑 23/0/0/4）；**容器内 LSM 缺失档真跑**（exit 10 点名 `errno ENOSYS`，补验档 §2）
- [ ] 真实 OS 验证，不以编译通过代替（darwin 不可验项如实标注；CI/容器实跑证据在案）——darwin 侧真跑在案（seatbelt 真强制 e2e + 真 `jh --verify` INFO 行，S-b）；linux 侧**本机容器真跑在案**（landlock 硬门负支路 + 干净容器归档冒烟 + INFO 支路 + bwrap 真强制 e2e，补验档 §2–§6）；landlock 正支路待首次 CI 跑
- [ ] 全 reactor `mvn -B -ntp package` 绿（本机 darwin + CI 双 job）——**本机 ✓ S-c**（544/0/0/7，跳过 7 条逐条枚举见 §6；30 个含测试模块）；**CI 双 job 待首次 CI 跑回填**
- [x] 文档同步（05/README×2/release.md/bundle.yml 注释 + 06/07 两处漂移订正）——S-b 落 05 §6/12 §3/bundle.yml 注释；S-c 补齐 05 §6 退出码协议与 `/dev/null`/seatbelt 差异、README×2 平台段与归档索取、release.md §5/§8、06/07 漂移订正

**首跑回填（照 it23 先例）**：push 放行后 CI 首次跑，上列 1/2/4/5 的 CI 侧与 CI run id 一次回填——经容器补验后待回填项已收缩为三项：artifact 上传与 amd64 产物、landlock **正**支路、CI 双 job 全绿；若首跑与定义期判断有偏差，按「回填／修正表」惯例订正，不改本节已 ✓ 的本地部分。

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （it24 S-b 提交） | `--verify` 双级观测（WARNING + INFO）初版以 **stderr 捕获式断言**验证——JUL 后端在**首条记录发布时**一次性绑定彼时的 `System.err`，同进程内后设的捕获流不再生效；与既有捕获式用例成对运行时必有一红（独立探针 `S-b-jul-err-binding-probe.java` 实测 first-captured=true / second-captured=false） | 生产出口收成包私有可注入 sink：`AppBoot.emitSandboxObservations(Scope, BiConsumer<Level, String>)`（`boot()` 以 `LOG::log` 装配）；两用例改断言 sink 记录（WARNING/INFO 前缀）；真实 stderr 端到端面归 S-c CLI 冒烟 |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 锚点增强 2 的 unnamed 形态写法 `-cp <java.class.path> <main>`（plan:71） | `-cp <助手类 code source>`——`Landlock.hostHelperCommand` 取 `Landlock.class` 的 code source 所在路径 | surefire/JPMS 下 `java.class.path` 可能是 booter jar（实测该形态下为空壳），助手类真实位置只在 code source；镜像态走 named 形态，不受影响 | 迭代内已同步（plan:71 已订正 + 本表登记；S-a packet 四项偏离裁决 ①） |
| `06-scope.md` preset 示例含顶层 `config:`（缩进块给 `workspaceRoot`/`sandbox.mode`） | preset 行只承载 `plugin`；行上 `config` 被解析但**不参与装配**——`PresetService.mount` 走 `PluginLoader.loadAllUnder(agentScope, List<Plugin>)`（无 config 入参），`ConfigRowSpec.Include` 的 config 字段装载时被弃 | S-c 订正 06:198 漂移时实读源码发现：示例给的是能力，实现只有 Include 面（preset 的定位本就是「agent 组合的增量能力集」） | 迭代内已同步（06 示例回落实况 + 新增「档位不在 preset 里配」段并点名该限制）；**行 config 生效与否是 preset 面自身的设计题**，不在 it24 范围——若后续要做，随 preset 演进立项 |
| landlock 腿写授权只到 `WritableRoots`（设计口径原文，05 §6 allow-list） | 该腿下 `/dev/null` 的写被拒（EACCES）——与 seatbelt 的 `(literal "/dev/null")` 放行、bwrap 的新 devtmpfs 行为不同（**allow-list 语义的直接推论；本机无 landlock 内核，未实测**） | `WritableRoots` 单一来源不含设备节点；把 `/dev/null` 加进去会改 fs 围栏共用的可写根语义（牵动 fs-tool/seatbelt/bwrap 三处消费面） | 迭代内已同步（05 §6 已知残余如实记账，不假装同构）；**挂账候选**：与 sandbox 策略一并议（是否引入「设备节点例外」而不动 `WritableRoots`）；Linux 腿真跑后可顺手复核此推论 |
