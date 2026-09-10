# Javanatic Harness

基于 JVM 的插件化 Agent Harness —— **Java 25 LTS / JPMS / Maven**。把 [DeepSeek Harness (dsh)](docs/dsh-reference.md) 的工程思想移植到 Java 体系：**思想照搬，形状不照搬**。

> **状态**：kernel、core 全主干（session/tools/agent/agent-loop/system-prompt）、capability（llm + fs + shell 三角色）、llm/deepseek 真实 Provider、JSONL 持久化（R1 闭环）均已实现并测试——**真实模型已可驱动完整竖切**（模型 tool_use → 工具真执行 → 日志落盘 → R1 哈希可证）。迭代 7/8（openai-compat、治理上线、AppBoot 组合数据化：ConfigService + YAML 三层 + CompositionManifest）已完成——组合是数据、R1 三规则齐备、真实任务经 CLI 跑通；其余 6 个叶子模块为 `module-info.java` + 标记类——依赖图从第一天起由编译器强制执行。
>
> 命名：JPMS 根名 / 包名 `io.javanatic.harness.*`，Maven `io.javanatic:harness-*`。

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

完整设计文档：[docs/design/README.md](docs/design/README.md)（12 篇，含导航索引与 R1–R4 总表）。

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
mvn -B package              # 全量编译打包（38 个 reactor 项目，169+ 测试；e2e 无 key 自跳过）
mvn -B -pl :harness-kernel-core -am package   # 单模块及其依赖
```

跑一个 keyless 竖切示例（replay 模型 + fs 工具 + 完整落账）：

```sh
mvn -B -q -pl examples/agent-spine -am package
java --module-path <各模块 target/classes 与 jackson jar> \
     -m io.javanatic.harness.examples.agent.spine/io.javanatic.harness.examples.agent.spine.SpineMain
```

真实模型驱动（需 `DEEPSEEK_API_KEY`）：见 `examples/agent-spine` 的 `RealModelAgentE2ETest`；it7 落地 `examples/headless` 后提供 `java … "task"` 命令行入口。

## 仓库结构

```
docs/design/        12 篇设计文档（00-overview … 11-java25-upgrade）+ docs/plan/ 逐迭代验收
docs/dsh-reference.md   设计参照系说明（dsh 仓库路径约定）
kernel/             Cordis 等价物：core（统一 Scope/Events/Plugin）+ brand；config（占位）
core/               Agent 主干：session/tools/agent/agent-loop/system-prompt（全部已实现）
llm/                seam + replay（keyless 测试地基）+ deepseek（真实 Provider）；openai-compat（it7）
fs/ shell/          capability 三角色（均已实现：root 限制见 it7）
session/            持久化 seam（JsonValue 树 + codec SPI）+ JSONL 后端（R1 闭环）
sandbox/ interaction/   沙箱（占位，挂账）与审批（it7：三模式）
bundle/ examples/   base/headless 组合（占位/it7）与可运行示例（agent-spine 已实现）
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
| 11 | 能干活：todo_write + 计划模式（PlanStart/PlanEnd 落账状态） | — |
| 12 | 敢让人跑：sandbox（Linux landlock/FFM 设计先行）+ restriction | — |
| 13 | 发布工程 → **0.1.0**：CI、Maven Central、门面 API、双语 README；生产模拟场景进 CI 常绿 | — |
| 14+ | subagent、skills、web、LSP——按社区声音排序 | — |

R1–R4 对应测试随切片走，不做收尾补（[10-testing.md](docs/design/10-testing.md)）。

## 许可

**Apache-2.0**（2026-09-08 确认）。架构思想源自对 dsh 的移植分析（见 [docs/dsh-reference.md](docs/dsh-reference.md)）；本仓库代码为原创实现。目标：开源供社区使用——JDK 25 LTS 单版本（ScopedValue 终版叙事优先），首发同时面向国际与中文社区。
