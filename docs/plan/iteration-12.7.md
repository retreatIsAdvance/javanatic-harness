# 迭代 12.7 — 平台链落地：Linux 同机约束（bwrap）（状态：已完成）

模块：`sandbox/local`（平台链 + bwrap 后端）、`.github/workflows/ci.yml`（双 job）、文档（05/02/README/bundle 注释）

四确认日期：2026-09-13（用户确认：以 it12.7 开工；Linux 后端 bwrap 先行；CI 加 macos job）

## 四确认

- **内容**：
  1. **平台链落地**：`SandboxLocalPlugin` 从「单一无条件 SeatbeltBackend」改为**平台→候选链**结构——`darwin=[seatbelt]`、`linux=[bwrap]`、`win32=[]`；单候选时不需探针仲裁，只做该候选的可用性探针；空链平台受限 confine 一律 fail-closed（沿用现状语义，报错文案改为点名平台与缺失后端）。新增 `bwrapBinary` 构造器注伪 seam（与 `seatbeltBinary` 同款惯例）。
  2. **bwrap 后端（argv 包装，零新依赖）**：`--unshare-user`（非特权 userns）+ `--ro-bind / /`（整个根只读）+ `--dev /dev`（新 devtmpfs，`/dev/null` 可写——seatbelt profile 里那个 literal 的对应物）+ `--die-with-parent`（R3）；WORKSPACE_WRITE 追加 `WritableRoots.of(policy)` 各根的 `--bind`；`--chdir <cwd>`。DANGER 不进 provider（`confining()` 为假，与 seatbelt 同）。
  3. **形状与强制拆开测**：argv 构造抽成**平台无关纯函数**，darwin 上可单测两种策略的 argv 形状与方言清单；真强制 e2e 挂 `@EnabledOnOs(OS.LINUX)` 由 CI 验。这是 it12 的教训——seatbelt 的 argv 断言也挂 `@EnabledOnOs(OS.MAC)`，导致本机之外一行验不到。
  4. **拒绝方言按后端声明**：bwrap = `Read-only file system` / `Permission denied`（只读挂载面给 EROFS，与 docker 同族；非 seatbelt 的 EPERM）。`ConfinedArgv.denialSignatures()` 契约本已逐后端声明，不动 seam。
  5. **CI 双 job 真验**：ubuntu job 安装 bubblewrap 跑 bwrap 强制 e2e；新增 macos job 把 seatbelt e2e 搬进 CI（今日 seatbelt 只在开发机验过，CI 完全盲）。
  6. **文档同步**：`05 §6`（bwrap 决策、方言、残余）、`bundle.yml` 注释（「Linux/Windows 部署在 it13 前需显式 overlay danger」→ Linux 用 bwrap、Windows 待 0.2.0）、README 路线表与状态段、`02`（若有变）。

- **目标**：Linux 上 `workspace-write`（base bundle 默认档）**开箱可用且真受内核约束**，不再要求用户「显式弃权」降级到 `danger-full-access`。这是 0.1.0「敢让人跑」在多数用户平台上的落点。

- **为什么**：base bundle 钉 `sandbox-policy.mode: workspace-write`，而今日 Linux 上首条 bash 命令必被 fail-closed 挡掉（`SandboxLocalPlugin.java:73` 的 `!DARWIN` 分支抛 `SandboxUnavailableException`）。GitHub 受众多数在 Linux——0.1.0 若如此发布，第一个 issue 就是「跑不起来」。bwrap 与 seatbelt **形状同构**（外部二进制 + argv 包装，现有 `ConfinedArgv` 契约直接套用），是候选链里成本最低的一格；零依赖的 Landlock 需要「自限制后 exec」的 helper 或 FFM，另作 0.2.0 第二候选（候选链结构就是为多候选探针仲裁留的）。

- **不做**：
  - windows-acl 与 pwsh（整体入 0.2.0；0.1.0 支持面明写 macOS/Linux）
  - Landlock 实现（0.2.0 第二候选；05 §6 记录）
  - bwrap 的 setuid 部署形态（只走非特权 userns 路径；不可用即 fail-closed）
  - 网络与进程可见性（词表外，与 seatbelt 语义对齐——只约束文件效果）
  - 提权流（`sandbox_permissions` + justification + 一次性更宽重试）
  - 资源限额（docker 侧 `--cpus/--memory` 挂账照旧）
  - 容器内叠 Landlock（挂账照旧）

- **需用户确认项（已确认 2026-09-13）**：
  - CI 新增 `apt-get install bubblewrap`——一个新的 CI 环境依赖，非 Maven 依赖
  - CI 新增 macos job（公开仓 macos runner 免费；CI 耗时约翻倍）

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：`sandbox-local` 平台链的 linux 档 `[bwrap]`（外部二进制 + argv 包装，与 seatbelt 同构）；`bwrapBinary` 构造器注伪 seam
- MODIFIED：`PLATFORM_CHAINS.linux` 空链 → `[bwrap]`（05 §6）；受限档拒绝方言新增 bwrap 族（`Read-only file system` / `Permission denied`）；`bundle.yml` 注释与 README 平台支持面（显式 overlay 弃权 → bwrap 就绪即开箱可用）
- REMOVED：无（win32 空链语义保留）

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `sandbox/local` · `SandboxLocalPlugin.java`：`SeatbeltBackend`、`!DARWIN` 分支（:73）、`profile()`/`sbplString()` | 拆平台链结构；候选按平台分发；bwrap argv 构造抽平台无关纯函数 | ✓ |
| `sandbox/local` · `SandboxLocalTest.java`：3 个 `@EnabledOnOs(OS.MAC)` 真强制测试 | 形状断言改平台无关；真强制 e2e 挂 `OS.LINUX` 由 CI 验 | ✓ |
| `sandbox/sandbox` · `ConfinedArgv` / `SandboxEnforcement` / `WritableRoots` | 只读参照，不动契约（动则触发设计同步规则②） | ✓（未动） |
| `.github/workflows/ci.yml`：单 ubuntu job | 双 job：ubuntu 装 bubblewrap + 新增 macos | ✓ |
| `bundle/base` · `bundle.yml` 的 `sandbox-policy` 注释 | Linux 说法更新 | ✓ |
| `docs/design/05-capability-seam.md` §6 | 平台链 / 方言 / 残余同步 | ✓ |

## 风险与缓解（本迭代最大不确定性：CI 上 bwrap 能否跑）

Ubuntu 24.04 起 AppArmor 默认限制非特权 user namespace，bwrap 在 GH runner 上可能起不来。
缓解顺序：CI 内先功能探针 → 不行则调 `kernel.apparmor_restrict_unprivileged_userns=0` 重试 →
再不行则该验收项**降级为「如实记录 + fail-closed 行为」并拉用户重新排 Landlock 优先级**（不静默吞掉）。

## 验收（证据 = 实际执行的命令与结果）

- [x] **全 reactor `mvn -B -ntp package` 绿**（本机 darwin）：`BUILD SUCCESS`（2026-09-13，1:54）；逐类记账 57 类 296 跑 0 败 0 错 4 跳（4 跳 = DeepSeek/real-model E2E 无 key 2 + SandboxLocalTest 的 LINUX 用例在 darwin 自跳 2）
- [x] **bwrap argv 形状单测平台无关**（darwin 可跑）：`bwrapWrapIsWholeTreeReadOnlyPlusWritableRootsWithBwrapDialect` 以 `containsExactly` 断言整 argv——READ_ONLY 精确形状；WORKSPACE_WRITE 逐根 `--bind` 且计数 == `WritableRoots.of(policy).size()`；方言 `Read-only file system`/`Permission denied`。darwin 全跑过（12 跑 10 过 2 跳，跳的是 LINUX e2e）
- [x] **fail-closed**：`linuxChainFailsClosedWhenBwrapUnusable`（注伪 `/nonexistent/bwrap` → `bwrap probe failed (binary: ...)`）、`darwinChainFailsClosedWhenSeatbeltUnusable`、`emptyChainFailsClosedNamingPlatformAndPlannedBackend`（win32 文案点名平台与 0.2.0）——darwin 全跑过
- [x] **平台链结构断言**：`platformChainsAreDarwinSeatbeltLinuxBwrapWin32Empty` 过
- [x] **CI ubuntu job：bwrap 真强制 e2e**——run `34789197722` ubuntu job 绿（1:52）：前置探针硬门实走 AppArmor 阶梯（首探被 userns 限制挡下 → `kernel.apparmor_restrict_unprivileged_userns=0` → 重试过）；`SandboxLocalTest` 12 跑 0 败 3 跳（跳的是 MAC 用例；2 个 LINUX e2e 真跑过）、`ShellToolEndToEndTest` 3 跑 1 跳（2 个 WORKSPACE_WRITE 在真 bwrap 下过）、`DockerShellTest` 11/11（预置修复后首跑）、`BUILD SUCCESS`
- [x] **CI macos job：seatbelt e2e 常绿**——同 run `34789197722` macos job 绿（52s）：全 reactor `BUILD SUCCESS`；`SandboxLocalTest` 12 跑 0 败 2 跳（跳的是 LINUX 用例；3 个 seatbelt e2e 真跑过）、`ShellToolEndToEndTest` 3/3（含 MAC 标记用例）
- [x] **文档**：05 §6（linux=[bwrap] 落定 + 方言 + 残余）/ bundle 注释 / README 平台支持面（commit `1806305`）+ 状态段 / 02 模块表 / sandbox-local module-info javadoc

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| `9d233d0` | it12.5 的 docker e2e 依赖本机镜像 `agent-runner:latest`；CI 从未跑过该模块，该环境依赖也未被管线预置 → ubuntu job 首跑 6/11 用例 fail-loud 报错（插件探针行为正确，缺的是预置） | ubuntu job 增 `Provision docker test image` 步骤：docker.io 的 `ubuntu:24.04` 打同名 tag（同一能力面 bash + setsid）；插件「镜像缺失 fail loud」契约不动 |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 四确认·内容 2：`--unshare-user`（非特权 userns） | 未加该 flag；非 setuid bwrap 隐式建立 userns | dsh 生产 argv（`sandbox/sandbox-local/src/profiles.ts`）不含它；其 CI 需放行 `kernel.apparmor_restrict_unprivileged_userns` 恰证明 userns 已被隐式建立——显式 flag 对约束面是空操作，且与证据形态保持最小差 | 迭代内已同步（05 §6 未列该 flag；单测按实现断言） |
| 四确认·内容 2：`--chdir <cwd>` | 未加；cwd 由调用方 `ProcessBuilder.directory(request.cwd())` 让子进程继承（与无沙箱路径一致） | `confine(argv, policy)` 契约不收 cwd，`policy` 只有 workspaceRoot；dsh 无此 flag；验收 argv 清单不含 | 迭代内已同步（05 §6 未列） |
