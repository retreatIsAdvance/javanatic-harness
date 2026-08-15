# Javanatic Harness

基于 JVM 的插件化 Agent Harness —— **Java 25 LTS / JPMS / Maven**。把 [DeepSeek Harness (dsh)](docs/dsh-reference.md) 的工程思想移植到 Java 体系。

> **状态**：设计完成 + 骨架可编译。33 个 JPMS 模块的依赖图已从第一天起由编译器强制执行；实现尚未开始。

## 设计思想（五大基石）

1. **一切皆插件** —— 无特权内核，换 Provider 即换产品形态
2. **Session 是事件日志** —— 模型历史是 derived 投影（Event Sourcing）
3. **Capability Seam 三角色** —— Definition / Provider / Consumer，JPMS 编译期隔离
4. **配置即组合** —— Profile / Bundle / Patch 三层叠加
5. **显式与纪律** —— sealed 穷尽、ScopedValue、fail loud、teardown 顺序

完整设计文档：[docs/design/README.md](docs/design/README.md)（12 篇，含导航索引）。

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
mvn -B package              # 全量编译打包（44 个 reactor 项目）
mvn -B -pl :harness-kernel-context -am package   # 单模块及其依赖
jar --describe-module --file kernel/aggregate/target/harness-kernel-0.1.0-SNAPSHOT.jar  # 查看模块描述
```

## 仓库结构

```
docs/design/        12 篇设计文档（00-overview … 11-java25-upgrade）
docs/dsh-reference.md   设计参照系说明（dsh 仓库路径约定）
kernel/             Cordis 等价物：context/fiber/events/scope/plugin/config/brand
core/               Agent 主干：session/tools/agent/agent-loop/system-prompt
llm/ fs/ shell/     capability seam（Definition + Provider + Consumer）
session/            持久化 seam（JSONL）
sandbox/ interaction/  沙箱与审批（stub）
bundle/ examples/   base/headless 组合与可运行示例
```

每个叶子模块当前只含 `module-info.java` + 标记类 —— **依赖图是真的，代码是占位**。

## 实现路线（按设计文档顺序）

| 步骤 | 模块 | 设计文档 |
|---|---|---|
| 1 | `kernel.brand` + `kernel.context` | [01-kernel.md](docs/design/01-kernel.md) |
| 2 | `kernel.fiber` / `kernel.events`（waterfall） | [01-kernel.md](docs/design/01-kernel.md) |
| 3 | `core.session`（事件溯源 + Surface） | [03-session-event-sourcing.md](docs/design/03-session-event-sourcing.md) |
| 4 | `llm.replay`（先于 deepseek，keyless 测试依赖它） | [10-testing.md](docs/design/10-testing.md) |
| 5 | `core.tools` + `fs.*`（最简 seam 打通） | [05-capability-seam.md](docs/design/05-capability-seam.md) |
| 6 | `core.agent-loop`（Turn/Step 状态机） | [04-agent-loop.md](docs/design/04-agent-loop.md) |
| 7 | `examples.headless` 跑通第一轮 turn | [07-profile-bundle.md](docs/design/07-profile-bundle.md) |
| 8 | `kernel.scope` + preset | [06-scope.md](docs/design/06-scope.md) |

## 许可

架构设计源自对 dsh 的移植分析（见 [docs/dsh-reference.md](docs/dsh-reference.md)）；本仓库代码为原创实现。
