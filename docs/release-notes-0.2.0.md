# javanatic-harness 0.2.0

> 本文件 = `v0.2.0` GitHub Release 正文**采用稿**（评审可改）；发布执行（版本翻转 / 签名 / 上传 / tag / Publish）属用户侧，见 [release.md](release.md) §0。

Plugin-based agent harness on the JVM — **Java 25 LTS / JPMS / Maven**. Ports the engineering ideas of DeepSeek Harness (dsh) to the Java ecosystem: **ideas carry over, shapes do not**.

0.2.0 的主题是**可靠的单机 CLI**：任务结果契约与退出码、取消与崩溃恢复、资源边界、工作区理解、会话操作与人工协作、外部 Java 接入闭环、三平台沙箱（macOS / Linux / Windows）与归档交付。含**破坏性变更**（shell 坐标族、审批 seam 签名、一次性任务出口语义）——0.1.0 用户升级前先读「升级必读」。

## 升级必读（0.1.0 → 0.2.0）

### 破坏性变更

| 面 | 0.1.0 | 0.2.0 | 动作 |
|---|---|---|---|
| 一次性任务出口 | 任务失败 / 取消一律 exit 0（无 stdout 结果契约；事件清单走 stderr） | stdout = 成功答案 / 等待答复的提问、失败恒空；**exit 3 失败 / 4 取消 / 5 等待答复**（0/1/2 语义不变） | 升级脚本判读（0.2.0 起为稳定承诺，[12 §6](design/12-api-stability.md)） |
| shell provider 坐标 | `harness-shell-bash-local` | `harness-shell-local` | 改 POM `artifactId`（it25 S-b 泛化：POSIX 语义不变，Windows 走 pwsh） |
| shell plugin id | `shell-bash-local` | `shell-local` | 改 profile/bundle 行的 `plugin` 值 |
| shell 工具名 | `bash` | `shell` | 提示词 / 工具白名单 / 回放夹具里的 `tool_use` 名随动 |
| shell 包名 | `io.javanatic.harness.shell.bash.local` | `io.javanatic.harness.shell.local` | `import` / `requires` 随动 |
| 审批 seam 签名 | `ApprovalService.require(request)`；`ApprovalPrompt.ask(request)` | 各加取消信号参数：`require(request, signal)` / `ask(request, signal)` | 自定义实现与调用点随动；等待中取消抛 `AbortedException`（走取消收敛，不落 error result） |
| 人闸等待面 | `ApprovalPrompt.stdin()` | `stdin(Duration idleTimeout)` | 自定义 prompt 随动；0/负 = 不设限，正 = 空闲上限（超时**按拒绝**，fail-closed）；非交互缺省 300s 后按拒绝，CLI `--approval-timeout=<秒>` 覆盖 |
| 沙箱诊断 | `BackendStatus.Ready(String backend)` | `Ready(String backend, SandboxEnforcement enforcement, String detail)` | 读取点随动；Linux 平台链由 `[bwrap]` 变 `[bwrap, landlock]`（选择期 fallback，进程生命周期内不切换） |
| 配置键名白名单 | 未列名键（含 typo）**静默忽略** | profile / bundle / 行三层**装载期 fail loud**（`YamlRows`） | 升级前检查 profile 里有无 typo 键——0.1.0 容忍的拼写错误会显形（有意为之的迁移面，[12 §5](design/12-api-stability.md)） |
| workspace 同源 | 无断言 | `agent-loop.cwd` / `fs-local.root` / `shell-tool.workspace` / `sandbox-policy.workspace` 四处漂移**装配期拒绝** | 四处对齐到同一工作区根（[07 §5](design/07-profile-bundle.md)） |
| 工具面 | 8 个（fs 5 + `bash` + `todo_write` + `exit_plan_mode`） | 10 个（fs 5 + `fs_search` + `shell` + `todo_write` + `exit_plan_mode` + `ask_user`） | 提示词 / 审批配置随动 |

Java 接入面的逐项对照（含纯增量项）另见[接入指南 §7 版本面](embedding.md#版本面010-vs-020-snapshot)；稳定面语义以 [12-api-stability.md](design/12-api-stability.md) 为准（§1 只随次版本破坏 + 本文声明变更与迁移路径）。

### 纯增量（不破坏 0.1.0 面）

- **CLI 新旗标** `--version`（打印版本串，如 `0.2.0`；keyless、exit 0）、`--approval-timeout=<秒>`（人闸等待界）与 `--sessions[=<N>]`（只读会话旁路，keyless；与任务文本 / `--resume` / `--verify` 互斥）；REPL 新命令 `/cancel`。
- **等待答复闭环**：模型经 `ask_user` 提问停轮（exit 5），答复 = 下一轮 user message（`jh --resume=<id> "答复文本"`；REPL 下提问行后的下一行即答复）——无常驻等待、无第二输入通道（[12 §6](design/12-api-stability.md)）。
- **工具契约**：`ToolDefinition.ofExempt(...)`（免审批声明）与 `ToolExecutionResult.concluding(content)`（正常成功但终结本 turn）。
- **组合自述**：`AppBoot.bootReported`（行/发现/未引用计数）与 `AppBoot.sandboxLine(...)` / `emitSandboxObservations(...)`（沙箱落点观测；`--verify` 输出行，exit 码不变）。

## Highlights

- **任务结果契约（it17 起）**：stdout = 成功答案 / 等待答复的提问；失败恒为空、诊断全走 stderr——脚本判读无歧义。退出码：0 完成 / 3 失败 / 4 取消 / 5 等待答复。
- **取消收敛（it18）**：Ctrl-C → 取消 → 收敛落账后 exit 4（不硬退）；未收敛时再按 = 逃生门（halt 130）；REPL 内 Ctrl-C 取消当前轮不退出。
- **崩溃恢复与单写者（it19）**：JSONL fsync 屏障 + 撕裂尾修复；`--resume` 占用即拒绝；崩溃 / SIGKILL 后写者锁自释，可直接续跑、无需人工清理。
- **资源边界（it20）**：fs 读取 / 编辑 / 列举 / 搜索全部有界（超限截断标记或 fail loud）；每轮末一行 `stats: turn=… steps=… tokens_in=… tokens_out=… elapsed=…s`（verify / REPL / one-shot 同形）。
- **工作区理解与可靠编辑（it21）**：workspace 四处同源断言；`AGENTS.md` 项目说明装载为 `project/instructions` 事件；`fs_search` 有界搜索；fs 编辑唯一匹配 + log-fold 读后保护。
- **会话操作与人工协作（it22）**：`--sessions` 只读旁路看历史会话；`ask_user` + exit 5 一问一答闭环；审批等待界（非交互缺省 300s 按拒绝）；REPL `/cancel`。
- **外部 Java 接入闭环（it23）**：示例工程 + 候选腿（工作树 install 进隔离仓）+ 发布腿（只从 Central 解析）+ 四负例；键名白名单 fail loud。
- **Linux 沙箱与归档（it24）**：平台链 bwrap → landlock（无 bubblewrap 的宿主零安装——归档自带 landlock 助手，内核 ABI ≥ 3 即用）；`--verify` 打印沙箱落点行；Linux 归档在干净 `ubuntu:24.04` 容器内解压冒烟。
- **Windows 支持与三平台交付（it25 / it25.1）**：windows-acl 低完整性沙箱（PARTIAL 如实）、shell 平台化（POSIX bash / Windows pwsh，无 pwsh 则执行期 fail-closed）、locale 修复；CI 四 job 出四件归档（macos-aarch64 / linux-amd64 / windows-amd64 / windows-aarch64），双架构一个事实源。

## 平台支持（0.2.0）

| 平台 | 沙箱后端（链） | 强制强度 | 前提 |
|---|---|---|---|
| macOS | seatbelt | **FULL** | Apple Silicon（aarch64 归档；Intel Mac 暂无发行面） |
| Linux | bwrap → landlock | **FULL** | 链取第一个可用后端：bwrap 在 PATH 优先；否则归档自带 landlock 助手（内核 ABI ≥ 3）；都不可用 fail closed（`--verify` 点名原因与出路） |
| Windows | windows-acl | **PARTIAL**（覆盖见「已知残余」） | **PowerShell 7+（`pwsh`）必需**——Windows PowerShell 5.1 不支持；pwsh 缺席时 shell 执行期 fail-closed 点名 |

## Get it

Maven Central — `io.github.retreatisadvance:harness-*:0.2.0`（索引收录可能滞后于本 Release）：

```xml
<dependency>
  <groupId>io.github.retreatisadvance</groupId>
  <artifactId>harness-kernel-core</artifactId>
  <version>0.2.0</version>
</dependency>
```

预构建归档见下方 Assets（运行时装好，解压即用；全平台归档由 CI 产出）：

- `javanatic-harness-0.2.0-macos-aarch64.tar.gz` / `.zip`
- `javanatic-harness-0.2.0-linux-amd64.tar.gz` / `.zip`
- `javanatic-harness-0.2.0-windows-amd64.tar.gz` / `.zip`
- `javanatic-harness-0.2.0-windows-aarch64.tar.gz` / `.zip`

验收：`bin/jh --help` 与 `bin/jh --verify` 均 exit 0（无 key 可跑；Windows 归档启动脚本为 `bin/jh.bat`，且需 `pwsh` 在场——`--verify` 会如实打印沙箱落点）。

```sh
tar xzf javanatic-harness-0.2.0-linux-amd64.tar.gz
cd javanatic-harness-0.2.0-linux-amd64     # 顶层目录同名
bin/jh --help                              # 全部旗标与示例
bin/jh --verify                            # 组合 + 治理断言 + 沙箱落点行
DEEPSEEK_API_KEY=sk-... bin/jh --workspace=<existing-dir> "task text"
```

## 已知残余（如实登记）

1. **windows-acl 为 PARTIAL**，两洞在案（[05 §6](design/05-capability-seam.md)）：① 可写面 = 主机上一切 Low 完整性标签对象（不限于 workspace）；② 递归打标跟随 NTFS 硬链接——硬链接别名可越出预期面。
2. **landlock 下 `/dev/null` 写入按设计拒绝**（D4 终裁 A；真机实测 DENIED）——`> /dev/null` 之类重定向会被判拒。
3. **Windows 容器不支持**（容器 e2e 为 POSIX 宿主门）；WSL / git-bash 通道未承诺。
4. **网络策略不在沙箱词表内**：同机与容器面默认均有网络出口；docker Provider 无 `--cpus` / `--memory` 限额（挂账）。
5. **bwrap 不在 PATH 的宿主形态**（如 NixOS / 精简镜像）链落 landlock；两者都不可用则 fail closed。
6. **locale 边界**：jh 自身输出固定 UTF-8（it25 S-c 修复，Windows GBK 控制台与 Linux 非 UTF-8 locale 下不再降级）；外部工具自己写出的字节不随宿主编码转换（shell 执行面事实，[05 §6](design/05-capability-seam.md)）。
7. **运行时热插拔不在 0.2.0 面内**：插件随构建产物进 classpath / module-path，由组合显式引用（[接入指南 §1](embedding.md)）。
8. **macOS Gatekeeper 拦截**：浏览器下载的归档带隔离属性（quarantine），未签名二进制的首跑会被系统拦下；规避二选一——`curl | tar xz` 方式下载（无 quarantine），或 `xattr -dr com.apple.quarantine <解压目录>` 后再运行。
9. **musl / Alpine 不支持**：归档按 glibc 目标构建（jlink 平台绑定），Alpine 等 musl 发行版不在 0.2.0 发行面内。

## Docs

- [README](https://github.com/retreatIsAdvance/javanatic-harness#readme)（[中文](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/README.zh-CN.md)）
- [docs/embedding.md](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/docs/embedding.md) — 接入指南（Maven 坐标 / profile-bundle / 版本面对照）
- [docs/design/12-api-stability.md](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/docs/design/12-api-stability.md) — 稳定面冻结与 0.2.0 迁移注记
- [docs/release.md](https://github.com/retreatIsAdvance/javanatic-harness/blob/master/docs/release.md) — 发布流程与校验点
