# Javanatic Harness

> [English](README.md)

基于 JVM 的插件化 Agent Harness —— **Java 25 LTS / JPMS / Maven**。把 [DeepSeek Harness (dsh)](docs/dsh-reference.md) 的工程思想移植到 Java 体系：**思想照搬，形状不照搬**。

> **状态**：kernel、core 全主干（session/tools/todo/plan/agent/agent-loop/system-prompt）、capability（llm + fs + shell 三角色）、llm/deepseek 真实 Provider、JSONL 持久化（R1 闭环）均已实现并测试——**真实模型已可驱动完整竖切**（模型 tool_use → 工具真执行 → 日志落盘 → R1 哈希可证）。迭代 7-16 与 12.6 硬化回填（openai-compat、治理上线、AppBoot 组合数据化、scope/preset、长跑能力 compaction/budget/resume、todo_write + 计划模式、sandbox 同机进程约束（darwin/linux）、shell-docker 环境级隔离、可运行产物 dist/jlink + CLI 完备、JSONL 耐久 / typed LLM 失败 / fs realpath 围栏 / 平台预警、REPL 交互面（命令面 + 流式渲染 + typed 失败渲染）、生产模拟场景（replay 驱动、keyless、确定性——多步任务 + compaction + 中途 kill/resume + budget 停 + R1 全比对）、发布工程 → 0.1.0 已发布（Central + GitHub Release + jlink 归档））已完成——组合是数据、R1–R4 治理不变式就位、真实任务经 CLI 跑通；依赖图从第一天起由编译器强制执行。
>
> 命名：JPMS 根名 / 包名 `io.javanatic.harness.*`；Maven `io.github.retreatisadvance:harness-*`（groupId = 中央仓命名空间，与包名不同源属有意为之）。

## 设计思想（五大基石 + 四条不变式）

1. **一切皆插件** —— 稳定插件 id + 静态组合，换 Provider 即换产品形态
2. **Session 是事件日志** —— `LoggedEvent(seq, event)` 信封；模型历史是 derived 投影（Event Sourcing）
3. **Capability Seam 三角色** —— Definition / Provider / Consumer，JPMS 编译期隔离
4. **配置即组合** —— Profile / Bundle / Patch 三层叠加，受限插值（无任意代码）
5. **显式与纪律** —— sealed 穷尽、ScopedValue、fail loud、teardown 顺序

四条治理不变式贯穿全部设计：

| # | 不变式 | 一句话 | 当前机制 |
|---|---|---|---|
| R1 | 可重建性 | 模型某一轮的完整请求可从持久化事实逐字节重建 | `llm/request` 双哈希锚点 + JSONL 回放闭环测试 |
| R2 | 执行一致性 | 模型发起的副作用有且仅有一条受控路径 | ToolExecutor 五段 pipeline + ArchUnit 唯一分发点断言 |
| R3 | 副作用消除 | 插件失败/作用域关闭即清理一切注册副作用 | Scope effect 栈 LIFO + 插件装载原子回滚 |
| R4 | 治理完备 | 生产配置能证明权限、审计、停止条件已挂载 | 构造器强制（已落地）+ `--verify`/policy 档位（it7） |

完整设计文档：[docs/design/README.md](docs/design/README.md)（13 篇，含导航索引与 R1–R4 总表）。

## 获取

**Maven Central** —— `io.github.retreatisadvance:harness-*`（0.1.0）：

```xml
<dependency>
  <groupId>io.github.retreatisadvance</groupId>
  <artifactId>harness-kernel-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

预构建归档见 [v0.1.0 release 页](https://github.com/retreatisadvance/javanatic-harness/releases/tag/v0.1.0)：`javanatic-harness-0.1.0-<平台>.tar.gz` / `.zip`（运行时已内置，解压即用 `bin/jh`）；自源码构建见下文。

## 环境要求

- **JDK 25**（LTS）+ Maven 3.8+
- 本仓库用 [jenv](https://www.jenv.be/) 管理局部 JDK：根目录的 `.java-version`（`25.0`）随仓库提交，进入目录即自动切换；Maven 经 jenv shim 解析到同一 JDK，无需手动设置 `JAVA_HOME`：

```sh
jenv local 25.0        # 仅当首次新增 JDK 或改版本时执行
mvn -B package         # 直接构建
```

无 jenv 的环境（如 CI）退回显式指定：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
```

## 构建与验证

```sh
mvn -B package              # 全量编译打包（46 个 reactor 模块，513 项测试；环境门控用例自跳过）
mvn -B -pl :harness-kernel-core -am package   # 单模块及其依赖
```

`mvn -B package` 顺带产出 jlink 运行时镜像（it13）与 `javanatic-harness-<版本>-<平台>.tar.gz` / `.zip` 归档（it16，位于 `dist/jh/target/`）：解压即用，无需手拼 module-path。

```sh
dist/jh/target/jlink-image/bin/jh --help     # 全部 flag 与示例（exit 0）
dist/jh/target/jlink-image/bin/jh --verify   # 组合 + 治理断言（无 key，exit 0/1）

dist/jh/target/jlink-image/bin/jh            # 裸启动进 REPL（it14）：非 / 行成轮送模型并流式渲染，
                                             # /help 列命令、/exit（或 EOF/Ctrl-D）退出、/cancel 取消在途轮（it22）；
                                             # 进行中轮以 aborted 落账；模型提问（← 提问: 行）后的下一行即答复
                                             # Ctrl-C（it18）取消进行中轮（以 aborted 落账）并留在 REPL；
                                             # 空闲 Ctrl-C 同 /exit 退出；取消未收敛时再按一次 → 强制退出 130

DEEPSEEK_API_KEY=sk-... dist/jh/target/jlink-image/bin/jh --workspace=<已存在目录> "任务文本"
# 每次运行打印独立会话 id（headless-<时间戳>-<短随机>）；--resume=<id> 续跑同一会话
# --sessions[=<N>] 列会话（id/状态/cwd/最后活动/事件数；keyless 只读，缺省 20，与任务文本/--resume/--verify 互斥）
# --resume=<id> 不带任务文本 → 在该既有会话上进 REPL 续聊
# --resume 命中占用（另一进程/实例持写者锁）fail loud：exit 3、stdout 为空
# 任务结果契约（it17；it22 增「等待答复」）：
#   stdout = 成功给最终答案 / 等待答复给提问文本（exit 5）/ 失败为空（成功但无文本合法，exit 0）
#   退出码 0 完成 · 1 --verify 违规 · 2 用法/缺 key · 3 任务失败（含 --resume 写者锁冲突） · 4 任务被取消 · 5 等待人工答复
#   诊断（失败文案、模型遗言）走 stderr；`out=$(bin/jh "任务")` 取答案、按退出码判成败
#   一问一答（it22）：exit 5 的答复 = 下一轮 user message（bin/jh --resume=<id> "答复文本"）
# --approval=ask 的等待界（it22）：非交互终端缺省 300 秒后按拒绝（fail-closed，绝不无限挂起）；
#   --approval-timeout=<秒> 仅在 --approval=ask 下有效（0 = 不设限）
# Ctrl-C（it18）：协作取消 → turn/end aborted("user")、exit 4、stdout 为空；
#   取消未收敛时再按一次 = 强制退出（130）。取消只对协作面生效——
#   整批工具无视取消并正常返回时仍以 Completed 收口（exit 0）
# 任意 OpenAI 兼容厂商：
#   … bin/jh "任务" --api-key-env=MOONSHOT_KEY --base-url=https://api.moonshot.cn/v1 --model=kimi-k2 --provider=kimi
# 容器级隔离（须本机 docker 与镜像在场，不自动拉取）：
#   … bin/jh "任务" --docker --image=ubuntu:24.04
```

keyless 竖切（replay 模型 + fs 工具 + 完整落账）见 `examples/agent-spine`；真实模型 e2e 见其 `RealModelAgentE2ETest`。

## 仓库结构

```
docs/design/        13 篇设计文档（00-overview … 12-api-stability）+ docs/plan/ 逐迭代验收
docs/dsh-reference.md   设计参照系说明（dsh 仓库路径约定）
kernel/             Cordis 等价物：core（统一 Scope/Events/Plugin）+ brand + config（YAML + ConfigService）
core/               Agent 主干：session/tools/todo/plan/agent/agent-loop/system-prompt/preset（全部已实现）
sandbox/            同机进程约束：Definition + seatbelt/bwrap Provider + 策略解析（darwin/linux 实测；windows 设计先行）
llm/                seam + replay（keyless 测试地基）+ openai-compat（通用适配器）+ deepseek（真实 Provider）
fs/ shell/          capability 三角色（均已实现；shell 有两个互斥 Provider——本机 bash 与 docker 容器，见 it12.5）
session/            持久化 seam（JsonValue 树 + codec SPI）+ JSONL 后端（R1 闭环）
interaction/        审批三模式（auto/ask/deny，it7）+ 命令面（registry/slash 解析/事件对，it14）
dist/               jlink 运行时镜像编排：产出 bin/jh（解出即用，无需手拼 module-path，it13）
bundle/ examples/   base 组合（AppBoot/ConfigService 数据化装配）+ 可运行示例（agent-spine / headless）
```

## 实现路线（垂直切片）

| 切片 | 模块 | 设计文档 |
|---|---|---|
| 1 ✅ | `kernel.core`（统一 Scope 内核）| [01-kernel.md](docs/design/01-kernel.md) |
| 2 ✅ | `core.session`（LoggedEvent 信封 + Surface）| [03-session-event-sourcing.md](docs/design/03-session-event-sourcing.md) |
| 3 ✅ | `llm.seam` + `llm.replay`（keyless 测试地基）| [10-testing.md](docs/design/10-testing.md) |
| 4 ✅ | `core.tools` + `fs.*`（R2 单一执行路径打通）| [05-capability-seam.md](docs/design/05-capability-seam.md) |
| 5 ✅ | `core.agent` + `core.agent-loop` + `examples/agent-spine`（竖切闭环 + R2 架构测试）| [04-agent-loop.md](docs/design/04-agent-loop.md) |
| 6 ✅ | `shell.*` + `llm.deepseek` + 持久化 JSONL + R1 回放哈希闭环 + 真实模型全栈 e2e | [05](docs/design/05-capability-seam.md) / [03](docs/design/03-session-event-sourcing.md) |
| 7 ✅ | `llm.openai-compat` 重构 + fs 根目录 + 审批三模式 + `--verify`/policy + `examples.headless` | [07-profile-bundle.md](docs/design/07-profile-bundle.md) |
| 8 ✅ | AppBoot 组合数据化：kernel/config + bundle/base + CompositionManifest + headless 迁移 | [07-profile-bundle.md](docs/design/07-profile-bundle.md) |
| 9 ✅ | scope/preset：ScopedToolRegistry + setup window + preset 组合 + home profile 发现（最后的 API 破坏性迭代） | [06-scope.md](docs/design/06-scope.md) |
| 10 ✅ | 长跑能力：compaction 生产者 + budget 档 + --resume + request-context（生产模拟门槛前半） | [03](docs/design/03-session-event-sourcing.md) |
| 11 ✅ | 能干活：todo_write（整表替换快照）+ 计划模式（plan/mode 纯 fold + exit_plan_mode 直写翻转 + 动态提示段）；扩展事件 codec 走 ServiceLoader | — |
| 12 ✅ | 敢让人跑：sandbox（平台链 darwin=seatbelt 实测，linux/windows 设计先行）+ restriction（shell wrap + fs 围栏 + plan 压只读 + PRODUCTION 断言） | [05](docs/design/05-capability-seam.md) |
| 12.5 ✅ | 环境级隔离：`shell-docker` 第二个 `ShellExecutor` Provider——挂载面即可写面（整个容器根只读），沙箱三档词表在容器后端同义且更强；换 Provider 不动 seam，纯组合选择 | [05](docs/design/05-capability-seam.md) |
| 12.6 ✅ | 硬化回填（13 后插入）：JSONL 耐久（fsync 屏障 + 撕裂尾修复）、typed LLM 失败（`LlmCallException` + `Kind` 词表）、LocalFs realpath 围栏（symlink 出界封死）、`--verify` 平台预警；LoopGuard 注释 + 02 漂移订正 | [03](docs/design/03-session-event-sourcing.md) / [05](docs/design/05-capability-seam.md) |
| 12.7 ✅ | 平台链落地：Linux 同机约束（bwrap 后端）——darwin=seatbelt / linux=bwrap / win32=空链；CI 双 job 真验（ubuntu 装 bubblewrap、macos 补 seatbelt） | [05](docs/design/05-capability-seam.md) |
| 13 ✅ | 可运行产物：dist（jlink）+ CLI 完备（`--help` / `--workspace=` / `--approval=`；运行 id + CI 冒烟）| — |
| 14 ✅ | 交互面：REPL（`interaction/commands` 落地）+ 流式渲染（chunk 落账 + typed 失败渲染按 `FailureKind`）| — |
| 15 ✅ | 生产模拟进 CI：replay 驱动（keyless、确定性）+ PRODUCTION policy + 多步任务 + compaction + 中途 kill/resume + budget 停 + R1 全比对 | [03](docs/design/03-session-event-sourcing.md) |
| 16 ✅ | 发布工程 → **0.1.0** 已发布：Maven Central + GitHub Release（jlink 归档）、门面冻结、双语 README | — |
| 17–25（计划） | **0.2.0**：可靠单 Agent CLI、取消/恢复/资源边界、工作区与会话易用性、外部 Java 接入、Linux 归档 + Landlock、Windows 本机隔离 + pwsh | [总体计划](docs/design/README.md#phased-evolution-plan) |
| 0.3 系列（计划） | 可复用能力：先 skills + MCP Tools，随后网络资料获取与 LSP | [总体计划](docs/design/README.md#phased-evolution-plan) |
| 0.4（计划） | 先可管理的后台任务，再受控多 Agent 委派 | [总体计划](docs/design/README.md#phased-evolution-plan) |

**规划原则**：维护者场景驱动、真实任务验收、社区反馈校准，不等待社区反馈才推进。以上未来阶段均未开工；范围、依赖与验收闸门以总体计划为准，每轮仍须单独完成四确认和审查停点。

**0.1.0 平台支持面**：macOS 与 Linux 可用（含同机沙箱）。macOS 自带 seatbelt、开箱即用；Linux 走 bwrap——**需主机安装 bubblewrap**，就绪后开箱可用；无 userns 权限的主机受限档 fail-closed（Landlock 兜底入 0.2.0）。**Windows 不在 0.1.0 支持面**——缺同机沙箱后端，且 `shell-bash-local` 假设 bash 存在（pwsh provider 待做），两者随 windows-acl 一并排入 0.2.0。

R1–R4 对应测试随切片走，不做收尾补（[10-testing.md](docs/design/10-testing.md)）。

## API 稳定面

0.1.0 冻结其对外面——JPMS 导出包、seam 契约、事件 schema、插件配置键与 CLI。完整清单见 [docs/design/12-api-stability.md](docs/design/12-api-stability.md)：**0.1.x 修补不破上述任何面；破坏性变更随 0.2.0 并附 release notes 与迁移路径。**

## 许可

**Apache-2.0**（2026-09-08 确认）。架构思想源自对 dsh 的移植分析（见 [docs/dsh-reference.md](docs/dsh-reference.md)）；本仓库代码为原创实现。目标：开源供社区使用——JDK 25 LTS 单版本（ScopedValue 终版叙事优先），首发同时面向国际与中文社区。
