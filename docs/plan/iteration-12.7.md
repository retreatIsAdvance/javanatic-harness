# 迭代 12.7 — 平台链落地：Linux 同机约束（bwrap）（状态：进行中）

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

## 风险与缓解（本迭代最大不确定性：CI 上 bwrap 能否跑）

Ubuntu 24.04 起 AppArmor 默认限制非特权 user namespace，bwrap 在 GH runner 上可能起不来。
缓解顺序：CI 内先功能探针 → 不行则调 `kernel.apparmor_restrict_unprivileged_userns=0` 重试 →
再不行则该验收项**降级为「如实记录 + fail-closed 行为」并拉用户重新排 Landlock 优先级**（不静默吞掉）。

## 验收（证据 = 实际执行的命令与结果）

- [ ] **全 reactor `mvn -B -ntp package` 绿**（本机 darwin），测试数按逐类行口径记账（见 it12.5 口径说明）
- [ ] **bwrap argv 形状单测平台无关**（darwin 可跑）：READ_ONLY / WORKSPACE_WRITE 两种策略的 argv 断言（`--ro-bind / /`、各可写根 `--bind`、`--dev`、`--die-with-parent`）+ 方言清单
- [ ] **fail-closed**：bwrap 二进制缺失/不可用 → `SandboxUnavailableException`（注伪路径，darwin 可跑）；win32 空链同语义
- [ ] **平台链结构断言**：三平台各自候选集；空链报错文案点名平台与缺失后端
- [ ] **CI ubuntu job：bwrap 真强制 e2e**——READ_ONLY 写拒（EROFS + `sandboxDenied`）、WORKSPACE_WRITE 区内写通且宿主可见、区外写拒
- [ ] **CI macos job：seatbelt e2e 常绿**（把开发机验证搬进 CI）
- [ ] **文档**：05 §6 / bundle 注释 / README 路线表 + 状态段 / 02（若有变）

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| | | |
