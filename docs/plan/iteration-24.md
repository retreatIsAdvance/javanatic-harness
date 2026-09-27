# 迭代 24 — Linux 交付与 Landlock（状态：S-a / S-b / S-c 三段全部落盘并放行（2026-09-24）；S-a/S-b 已按「放行 S-b，按两提交收口」裁决收口（`8353733` / `ef41e63`，未推送），S-c（CI 腿 + 归档冒烟 + 文档）随收口提交一并落盘；全量 package 544/0/0/7 绿（本机 darwin）；首次 CI 跑（landlock 硬门 / 容器冒烟 / upload-artifact 三项）待 push 放行后回填——S-c 证据第 8 节已如实标注边界。**S-c 补验（2026-09-24，本机 docker 可用后）：容器内构建出 linux-aarch64 归档并真跑六项——landlock 硬门负支路（exit 10 / ENOSYS）、干净容器归档冒烟（WARNING 支路）、INFO 支路（bwrap 可用档）、交付归档自带助手、聚焦测试容器内真跑（含 bwrap 两条真强制 e2e 本机首次于真实 Linux 内核跑通）、CI 第 7 步断言 vs 真实 surefire 报告；「首次 CI 跑待回填」项据此收缩为 artifact 上传 + landlock 正支路 + amd64 产物三项（`docs/plan/evidence/iteration-24/S-c-local-container-runs.txt`）**。**首跑实况与修正（2026-09-27）：五提交已推送并触发首次 CI 跑（run `36297109220` / headSha `b02528c`）——macos job 全绿（bwrap 两条真强制 e2e 于真实 runner 内核跑通；前置门走了既有的 `sysctl kernel.apparmor_restrict_unprivileged_userns=0` 处置），build(ubuntu) job 止步步骤 7「Pre-flight sandbox probe (landlock)」：`WrongMethodTypeException: handle's method type (…,long×6)long but found (…,int,…)long` → exit 13——FFM 蹦床句柄全 long 而 `restrictSelf`/`addPathBeneathRule` 以 int 喂 fd，`invokeExact` 无隐式加宽，调用未到内核；此为该 apply 路径在任何环境上的**首次真实执行**（darwin 无 landlock；本机容器内核未编译 landlock；CI 步骤 6 的 e2e 因探针失败静默跳过，而专治静默跳过的步骤 8 恰在失败步之后未执行）。修法（已放行「参数改 `long` + 每绑定一条真跑用例 + 台账回填」）：两处 fd 形参改 `long` + 蹦床声明处钉纪律注释 + 新增 Linux 真跑用例（非法 fd 的裁决须出自内核而非 FFM 层）；容器内红/绿对（`Errors: 1 → 0`）+ 本机 545/0/0/8 全绿。首跑同时确认 **runner 内核 landlock ABI ≥ 3**（接受 WRITE_EFFECTS_V1|REFER|TRUNCATE 全集）——正支路环境能力成立，待第二轮 CI 真跑。**第二轮 CI（2026-09-27，run `36308418756` / headSha `9d8281f`）：macos job 连续两轮全绿；build job 止步步骤 6——本轮新增的 Linux 真跑用例 errno 期望写窄（只认 EBADF/ENOSYS，runner 真 landlock 内核按内核查序给 EPERM：`restrict_self` 先查 no_new_privs/CAP_SYS_ADMIN、之后才验 fd），与产品代码无关；同一轮取得关键正证据——`SandboxLocalTest` 跳过数 6→4（少掉的 2 条正是 landlock e2e）⇒ **两条 landlock 真强制 e2e 于真实 runner 内核真跑并通过**，迭代核验目标「bwrap 不可用时按能力选择、真拒写真放行」首次拿到真实 CI 证据。修正＝errno 按内核查序分档（`restrictSelf` 认 EPERM/EBADF/ENOSYS/EOPNOTSUPP，`add_rule` 认 EBADF/ENOSYS/EOPNOTSUPP）+ 四个具名常量 + 查序注释（引上游 `security/landlock/syscalls.c`）+ AGENTS.md 犯错回填（「errno 断言按查序分档」）；本机复验 validate 0 / package 545/0/0/8 / 容器 ENOSYS 档 0（EPERM 档唯 CI 可证）**（`docs/plan/evidence/iteration-24/S-c-first-ci-run-and-ffm-fix.txt` §8–§11；该修正已提交、**待推送**触发第三轮 CI 回填：步骤 6 全绿（含 EPERM 档）/ 步骤 7/8 硬门与断言真执行 / artifact 与 amd64 产物 / 双 job 全绿）**）

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
| `.github/workflows/ci.yml` ubuntu job | landlock 腿 + 归档冒烟（解压/`--help`/`--verify`/PATH 下 bwrap 不可见诊断行）+ upload-artifact | ✓（S-c：前置硬门 2 真跑助手 `--probe` + surefire XML 断言「跑了且没跳过」+ verify 落点点名 bwrap 断言 + `ubuntu:24.04` 容器解压冒烟 + `upload-artifact@v4`；定义期静态校验与断言三分支演习见 S-c 证据。**首跑（run `36297109220`）落点：步骤 4 bwrap 门真跑通过（sysctl 处置后），步骤 7 landlock 硬门真跑并暴露 FFM 调用点类型缺陷（exit 13 → 修正表），步骤 8–12 未执行；第二轮（run `36308418756`）落点：步骤 6 内两条 landlock 真强制 e2e 真跑通过（正支路首次取得 CI 证据），同步新增用例 errno 期望写窄转红（→ 修正表第二行），步骤 7–12 未执行；修正后第三轮 CI 待复验**） |
| `docs/design/05` §6、`README{,.zh-CN}.md`、`docs/release.md`、bundle.yml 注释 | 链/形状/洞/进程树/归档获取同步 | ✓（S-c 补齐：05 §6 退出码协议 + `/dev/null` 与 seatbelt 差异 + NixOS 宿主形态登记；README×2 平台段与归档索取；release.md §5/§8；05 §6 / 12 §3 / bundle.yml 注释 S-b 已在案） |
| `06-scope.md:198`、`07-profile-bundle.md:74` | 漂移订正（限定词汇表回落实况） | ✓（S-c：06 示例回收 `description`+`rows` 实况并补「档位不在 preset 里配」（含 preset 行 `config` 只解析不装配的实况）；07 patch 示例改 `sandbox-policy` 行 `mode`/`workspace`） |
| `sandbox/local` · `SandboxLocalTest`（+ landlock 形状/仲裁/负向 e2e `@EnabledOnOs(OS.LINUX)`） | 聚焦测试 + 突变 | ✓（S-a 形状/负向 e2e；S-b 仲裁/诊断/包装） |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行，全机械迭代写「无」）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S-a：**自限制助手与 FFM 绑定**（安全不变量承载类：allow-list 口径、no_new_privs 顺序、架构 fail loud、双形启动、负向自检） | `Landlock.java`、`LandlockExecMain.java` | **停点已达并已放行（2026-09-24；裁决「放行 S-a，继续 S-b」）**：`Landlock.java`（FFM 五形状 + 三形态启动 + 严格解析 + 负向探针 + 退出码协议）与 `LandlockExecMain.java` 落盘；`LandlockTest` 17 测试（1 条 Linux 腿在 darwin 跳过）；双形启动真命令各 exit 10 点名平台、用法拒绝 exit 14；五突变必红→还原复绿（M1/M2/M3/M4/M5）；**M4 首轮自曝测试缺陷已修**（平台分区曾借被测代码判断 → 改 `@EnabledOnOs`）；本机不可验边界如实标注（darwin 无 landlock 内核、容器内 Linux JDK 17 低于 FFM 定稿线 22——瓶颈是缺席的 Linux 宿主/内核，非 JDK 版本线 → 正路径归 S-c CI） |
| S-b：**链仲裁与诊断契约**（跨模块契约变更：链序、探针、`BackendStatus` 扩展、`--verify` 输出） | `SandboxLocalPlugin` 链、`BackendStatus`、`AppBoot.sandboxWarning` | **停点已达并已放行（2026-09-24；裁决「放行 S-b，按两提交收口」）**：链 `linux=[bwrap, landlock]`（选择期 fallback：探针一次、缓存、进程生命周期内不切换）+ `LandlockBackend`（helper 命令双形 + 负向探针 + 结论行回收）；`BackendStatus.Ready(backend, enforcement, detail)` 三部件（fail-loud 构造）；`AppBoot` 双级观测——`sandboxWarning` 字节不变、新增 `sandboxLine` + 可注入 `emitSandboxObservations`（WARNING/INFO 两级，exit 码不变）；聚焦三模块全绿（LandlockTest 17 / SandboxLocalTest 23 / AppBootTest 21，跳过项为平台分区）；真 `jh --verify` 出 INFO 沙箱行（证据第 3 节）；突变 5 条必红→还原复绿；**JUL 一次性流绑定缺陷自曝并修**（修正表）；随 S-b 落盘两台账小项（EFAULT 归属措辞对齐见 `S-a-landlock-helper.txt` §4；JDK 口径统一见本节三处） |
| S-c：CI 腿 + 归档冒烟 + 文档 + 收口 | （不设停点：机械为主） | **已完成（2026-09-24，随收口提交）**——待办逐条落地：① CI landlock 腿＝前置硬门 2（jlink 镜像模块形态真跑助手 `--probe`，非 0 即红）+ 配套 surefire XML 断言（两条 landlock e2e「跑了且没跳过」，跳/缺/正常三分支本地演习过）；② 归档冒烟＝`ubuntu:24.04` 容器内解压重跑（`--help`/`--verify`，断言 bwrap 不可见点名行 + 沙箱行如实两分支）+ `upload-artifact@v4`（`javanatic-harness-linux-amd64`，`if-no-files-found: error`）；③ README×2/release.md 按 S-b 输出契约与归档实况同步；④ 05 §6 补退出码协议一句带过与 `/dev/null`/seatbelt 差异一句，并按「NixOS 探针尾步登记」把「bwrap 定位不到」宿主形态写进已知残余（连 CI 容器冒烟作真跑登记——**此处为解释性落点，若原意是代码级探针步骤新增请指出，回填订正**）；⑤ 06/07 两处漂移订正（示例回落实况词表）。本地校验：YAML 可解析（11 步）、8 个 `run` 块 `bash -n` 全过、容器内层脚本 `sh -n` 过、断言脚本三分支演习齐、全量 package 544/0/0/7 绿；**边界（已由容器补验订正）**：原登记的三项首次 CI 跑事实中，(a) landlock 硬门真跑、(b) 容器 seccomp 行为已在本机容器取得可判别事实——硬门走到 `EXIT_UNAVAILABLE` 负支路（exit 10，结论行 `errno ENOSYS`）；ENOSYS 而非 EPERM ⇒ syscall 444 抵达内核，即**默认 seccomp 未拦 landlock 系统调用**；(c) artifact 上传仍属 CI 首跑，另 amd64 产物与 landlock **正**支路（ABI≥3 真拒写）亦仍属 CI 首跑（`S-c-local-container-runs.txt` §8）。**发现残余（候选挂账）**：landlock 腿写授权只到可写根，`/dev/null` 不在 `WritableRoots` 内 ⇒ 该腿下 `cmd > /dev/null` 被拒（EACCES），与 seatbelt/bwrap 行为不同——已在 05 §6 如实记账（**标为 allow-list 语义推论、本机无 landlock 内核未实测**），未修（修法涉及 `WritableRoots` 单一来源语义，宜与 sandbox 策略同议）。**两条未处置观察（容器补验新出，待裁）**：非 UTF-8 locale 下 CLI 输出整体降级——`--help` 中文变 `?`、结论行 em-dash 变单字节 `0x3f`（`LANG=C.UTF-8` 下两处均正常，字节级实证见补验档 §8）；出路候选：启动器注入 UTF-8 出口编码（`dist/jh` 的 launcher 目前无 options 位）或文档注明「非 UTF-8 locale 请设 LANG」。**首跑实况（2026-09-27，run `36297109220`）**：landlock 硬门真跑即该 apply 路径的**首次真实执行**——步骤 7 暴露 FFM 调用点类型缺陷（`WrongMethodTypeException` → exit 13；修正表 + 容器红/绿对 + 新增 Linux 真跑用例）；macos job 全绿（bwrap 两条真强制 e2e 于真实 runner 内核跑通）；步骤 8–12 未执行。**第二轮实况（2026-09-27，run `36308418756`）**：macos 全绿；build 止步步骤 6——修正提交新增的用例 errno 期望只认 EBADF/ENOSYS，而真 landlock 内核按查序给 EPERM（修正表第二行）；同轮 `SandboxLocalTest` 跳过 6→4 ⇒ 两条 landlock 真强制 e2e 于真实 runner 内核**真跑并通过**（步骤 6 内的正支路证据先行取得，步骤 7 的 `--probe` 硬门与步骤 8 断言因 tests 先红仍未执行）。修正后待第三轮 CI（`S-c-first-ci-run-and-ffm-fix.txt` §8–§11）。 |

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
| `S-c-first-ci-run-and-ffm-fix.txt` | **首跑回填 + it24 两轮修正（2026-09-27）**：首跑 run `36297109220` / `b02528c` 的双 job 步骤表（macos success；build 步骤 7 失败、8–12 skipped）与测试计数（build 侧 LandlockTest 17/0/0/2、SandboxLocalTest 23/0/0/6，含两条 landlock e2e 静默跳过之判读；步骤 4 bwrap 前置门 sysctl 处置）· 缺陷决定性输出行（WrongMethodTypeException + exit 13）· 根因链（蹦床全 long 句柄 vs int 形参；apply 路径首次真跑的三重原因）与同蹦床其余调用点真跑成功之旁证（⇒ runner 内核 ABI ≥ 3）· 修法 diff + 新增 Linux 真跑用例（非法 fd 裁决须出自内核）· 容器内红/绿对（`Errors: 1 → 0`，栈顶帧与 CI 逐字同构）· 本机 validate/package（545/0/0/8）· 业界方案核对（glibc 2.39 无 `landlock_*` 符号 ⇒ 维持 FFM + 钉调用点纪律）· **§8–§11 第二轮（run `36308418756` / `9d8281f`）：macos 全绿、build 止步步骤 6（新增用例 errno 期望写窄 → `Expecting actual: 1 to be in: [9, 38]`）；`SandboxLocalTest` 跳过 6→4 ⇒ 两条 landlock 真强制 e2e 于真实 runner 内核真跑并通过（正支路首次取得 CI 证据）；内核查序逐字引用（`syscalls.c`）；修正与本地复验（validate 0 / package 545/0/0/8 / 容器 ENOSYS 档 0，EPERM 档唯 CI 可证）；边界（步骤 6 全绿 / 7/8 / artifact 与 amd64 / 双 job 全绿待第三轮；静默跳过护栏仍未在 CI 上执行过）** |

## 验收（证据 = 实际执行的命令与结果）

- [ ] 干净 Linux 环境可安装运行（归档在干净容器/主机解压 → `--help`/`--verify` 全绿）——**本机容器 ✓ 补验**（本机容器内构建的 linux-aarch64 归档在裸 `ubuntu:24.04` 解压即跑：`--help` 正常、`--verify` exit 0、两处 grep 命中；`S-c-local-container-runs.txt` §3/§5）；**CI 侧定义 ✓ S-c**（冒烟步骤 + artifact 取件，S-c 档 §7）；**两轮 CI 均未达成**（首跑止步步骤 7、第二轮止步步骤 6 ⇒ 步骤 10/11 均被跳过），第二次修正后待第三轮 CI 回填
- [ ] bwrap 不可用时按能力选择（链仲裁落到 landlock，真拒写真放行，CI linux 腿 + 本机容器）——**本机容器 ✓ 部分**（两候选全败时的链点名与 fail-closed 真跑 + bwrap 两条真强制 e2e 于真实 Linux 内核跑通，补验档 §3/§6）；**landlock 真拒写真放行 ✓ 已取 CI 证据**——第二轮 CI 步骤 6 内两条 landlock 真强制 e2e 于真实 runner 内核真跑并通过（`SandboxLocalTest` 跳过 6→4，修正档 §8）；**硬门与断言步骤仍未执行**——首轮止步于 apply 路径类型缺陷、第二轮止步于新增用例的 errno 期望（均已修），步骤 7 的 `--probe` 硬门与步骤 8 真执行待第三轮 CI
- [x] 不满足约束时 fail-closed（ABI<3 / LSM 缺失 / 两候选全败 → 点名原因，不裸跑）——助手退出码 10/11/12/13/14/127 全 fail-closed + 结论行回收（`S-a-local-kernel-probe.txt` / `S-a-landlock-helper.txt`）；链仲裁与全败点名由 `SandboxLocalTest` 注入式 e2e 覆盖（S-b，平台无关，本机真跑 23/0/0/4）；**容器内 LSM 缺失档真跑**（exit 10 点名 `errno ENOSYS`，补验档 §2）
- [ ] 真实 OS 验证，不以编译通过代替（darwin 不可验项如实标注；CI/容器实跑证据在案）——darwin 侧真跑在案（seatbelt 真强制 e2e + 真 `jh --verify` INFO 行，S-b）；linux 侧**本机容器真跑在案**（landlock 硬门负支路 + 干净容器归档冒烟 + INFO 支路 + bwrap 真强制 e2e，补验档 §2–§6）+ **CI 侧**（macos job 两轮全绿、bwrap 两条真强制 e2e 于真实 runner 内核跑通，修正档 §1/§8）；landlock 正支路**已在第二轮 CI 真实执行**（两条真强制 e2e 真跑通过，修正档 §8；步骤 7 硬门与步骤 8 断言待第三轮）；**EPERM 档 errno 在 CI 上实测为 1**（旧期望因此转红，修正档 §9–§10）——修正后的分档期望尚待第三轮 CI 复验
- [ ] 全 reactor `mvn -B -ntp package` 绿（本机 darwin + CI 双 job）——**本机 ✓**（544/0/0/7，跳过 7 条逐条枚举见 S-c 档 §6；两轮修正后 545/0/0/8，30 个含测试模块）；**CI 首跑 run `36297109220`**：macos 绿 / build 红（步骤 7）；**第二轮 run `36308418756`**：macos 绿 / build 红（步骤 6，新增用例 errno 期望，修正档 §8–§10）——第二次修正后待第三轮 CI 回填双 job 全绿
- [x] 文档同步（05/README×2/release.md/bundle.yml 注释 + 06/07 两处漂移订正）——S-b 落 05 §6/12 §3/bundle.yml 注释；S-c 补齐 05 §6 退出码协议与 `/dev/null`/seatbelt 差异、README×2 平台段与归档索取、release.md §5/§8、06/07 漂移订正

**首跑回填（照 it23 先例）**：已回填——run `36297109220` / headSha `b02528c`（2026-09-27）：macos job 全绿（incl. bwrap 两条真强制 e2e 于真实 runner 内核跑通），build job 止步步骤 7（landlock 硬门，`WrongMethodTypeException` → exit 13），步骤 8–12 全跳过 ⇒ 上列 1/2/5 的 CI 侧本轮**未达成**；4 的 CI 侧部分达成（darwin 无法验的 linux 侧在 runner 上另有 bwrap 真跑在案）。偏差原因＝apply 路径的类型缺陷（上列 2/4 的 landlock 正支路因此从未真跑），按惯例入修正表并已修（`S-c-first-ci-run-and-ffm-fix.txt`）。

**第二轮回填（2026-09-27）**：run `36308418756` / headSha `9d8281f`（首跑修正提交）——macos job 全绿；build job 止步步骤 6（本轮新增用例的 errno 期望只认 EBADF/ENOSYS，真 landlock 内核按查序给 EPERM），步骤 7–12 全跳过。同轮取得迭代关键正证据：`SandboxLocalTest` 跳过 6→4（少掉的 2 条正是 landlock e2e）⇒ 两条 landlock 真强制 e2e 于真实 runner 内核真跑并通过，上列 2/4 的 landlock 正支路**首次拿到真实 CI 证据**（经步骤 6 而非步骤 7 硬门——后者仍未执行）。本轮红与产品代码无关，是本轮新增用例自身的期望写窄，按惯例入修正表并已修（`S-c-first-ci-run-and-ffm-fix.txt` §8–§11）；待回填四项（步骤 6 全绿含 EPERM 档 / 步骤 7/8 硬门与断言真执行 / artifact 与 amd64 产物 / 双 job 全绿）顺延至第三次修正后第三轮 CI。

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （it24 S-b 提交） | `--verify` 双级观测（WARNING + INFO）初版以 **stderr 捕获式断言**验证——JUL 后端在**首条记录发布时**一次性绑定彼时的 `System.err`，同进程内后设的捕获流不再生效；与既有捕获式用例成对运行时必有一红（独立探针 `S-b-jul-err-binding-probe.java` 实测 first-captured=true / second-captured=false） | 生产出口收成包私有可注入 sink：`AppBoot.emitSandboxObservations(Scope, BiConsumer<Level, String>)`（`boot()` 以 `LOG::log` 装配）；两用例改断言 sink 记录（WARNING/INFO 前缀）；真实 stderr 端到端面归 S-c CLI 冒烟 |
| （首跑修正提交） | **CI 首跑暴露（`36297109220`，build 步骤 7）**：`Landlock.restrictSelf`（:566）与 `addPathBeneathRule`（:557）以 `int` fd 直喂 FFM 蹦床 ⇒ `WrongMethodTypeException: handle's method type (…,long×6)long but found (…,int,…)long` → `EXIT_APPLY_FAILED`(13)。蹦床描述符全 `JAVA_LONG` 且 `invokeExact` **不做隐式加宽**；调用在 FFM 层即失败，未到内核。为何长期未现：apply 路径此前**在任何环境都未真跑过**（darwin 无 landlock / 本机容器内核未编译 landlock / CI e2e 因探针失败静默跳过，而专治静默跳过的步骤 8 在失败步之后） | 两处 fd 形参 `int → long`（纪律写进类型；上层 `int rulesetFd` 的加宽在 Java 调用处合法，无需散落 cast）+ 蹦床声明处钉注释「全 long 形参…」+ 新增 Linux 真跑用例 `bogusFdReachesTheKernelInsteadOfFailingAtInvokeExact`（非法 fd 的裁决须出自内核 EBADF/ENOSYS，类型纪律再破即红）；可见性同步放宽为包私有。容器内红/绿对（`Errors: 1 → 0`，栈顶帧与 CI 逐字同构）+ 本机 545/0/0/8 全绿（`S-c-first-ci-run-and-ffm-fix.txt`） |
| （第二轮修正提交） | **CI 第二轮暴露（`36308418756`，build 步骤 6）**：首轮修正新增的真跑用例把 `restrictSelf(-1)` 的 errno 期望写成 `{EBADF, ENOSYS}`——`Expecting actual: 1 to be in: [9, 38]`。上游内核查序（`security/landlock/syscalls.c`）：`landlock_restrict_self` **先**查 `no_new_privs`/`CAP_SYS_ADMIN`（普通 JVM 两者皆无 → EPERM）、**之后**才 `get_ruleset_from_fd`（非法 fd → EBADF）；`landlock_add_rule` 则先验 fd（EBADF）。故在真有 landlock 的内核上该调用恒给 EPERM，EBADF 分支到不了；本机两环境（darwin / 容器内核未编译 landlock）只会给 ENOSYS ⇒ 该期望**只有 CI 能证伪**，本地红绿对覆盖不到 | 期望按查序分档：`restrictSelf` 认 `isIn(EPERM, EBADF, ENOSYS, EOPNOTSUPP)`、`add_rule` 认 `isIn(EBADF, ENOSYS, EOPNOTSUPP)`（EOPNOTSUPP＝LSM 未启用档）+ 四个具名常量 + 查序 Javadoc（引 `syscalls.c`）；守的性质不变（裁决来自内核而非 FFM 层，类型纪律再破即 WrongMethodTypeException，与 errno 取值无关）。**规则回填**：`AGENTS.md` 已知坑新增「errno 断言按查序分档」一条。本机复验 validate 0 / package 545/0/0/8 / 容器 ENOSYS 档 0（EPERM 档唯 CI 可证，如实标注；`S-c-first-ci-run-and-ffm-fix.txt` §8–§11） |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 锚点增强 2 的 unnamed 形态写法 `-cp <java.class.path> <main>`（plan:71） | `-cp <助手类 code source>`——`Landlock.hostHelperCommand` 取 `Landlock.class` 的 code source 所在路径 | surefire/JPMS 下 `java.class.path` 可能是 booter jar（实测该形态下为空壳），助手类真实位置只在 code source；镜像态走 named 形态，不受影响 | 迭代内已同步（plan:71 已订正 + 本表登记；S-a packet 四项偏离裁决 ①） |
| `06-scope.md` preset 示例含顶层 `config:`（缩进块给 `workspaceRoot`/`sandbox.mode`） | preset 行只承载 `plugin`；行上 `config` 被解析但**不参与装配**——`PresetService.mount` 走 `PluginLoader.loadAllUnder(agentScope, List<Plugin>)`（无 config 入参），`ConfigRowSpec.Include` 的 config 字段装载时被弃 | S-c 订正 06:198 漂移时实读源码发现：示例给的是能力，实现只有 Include 面（preset 的定位本就是「agent 组合的增量能力集」） | 迭代内已同步（06 示例回落实况 + 新增「档位不在 preset 里配」段并点名该限制）；**行 config 生效与否是 preset 面自身的设计题**，不在 it24 范围——若后续要做，随 preset 演进立项 |
| landlock 腿写授权只到 `WritableRoots`（设计口径原文，05 §6 allow-list） | 该腿下 `/dev/null` 的写被拒（EACCES）——与 seatbelt 的 `(literal "/dev/null")` 放行、bwrap 的新 devtmpfs 行为不同（**allow-list 语义的直接推论；本机无 landlock 内核，未实测**） | `WritableRoots` 单一来源不含设备节点；把 `/dev/null` 加进去会改 fs 围栏共用的可写根语义（牵动 fs-tool/seatbelt/bwrap 三处消费面） | 迭代内已同步（05 §6 已知残余如实记账，不假装同构）；**挂账候选**：与 sandbox 策略一并议（是否引入「设备节点例外」而不动 `WritableRoots`）；Linux 腿真跑后可顺手复核此推论 |
