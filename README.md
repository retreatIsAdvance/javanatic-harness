# Javanatic Harness

基于 JVM 的插件化 Agent Harness —— **Java 25 LTS / JPMS / Maven**。把 [DeepSeek Harness (dsh)](docs/dsh-reference.md) 的工程思想移植到 Java 体系：**思想照搬，形状不照搬**。

> **状态**：设计完成 + 迭代 1（kernel）与迭代 2（core/session）、迭代 3（llm seam + replay）与迭代 4（core.tools + fs，R2 执行路径）已实现并测试。其余 20 个叶子模块为 `module-info.java` + 标记类 —— 依赖图从第一天起由编译器强制执行。
>
> 命名：JPMS 根名 / 包名 `io.javanatic.harness.*`，Maven `io.javanatic:harness-*`。

## 设计思想（五大基石 + 四条不变式）

1. **一切皆插件** —— 稳定插件 id + 静态组合，换 Provider 即换产品形态
2. **Session 是事件日志** —— `LoggedEvent(seq, event)` 信封；模型历史是 derived 投影（Event Sourcing）
3. **Capability Seam 三角色** —— Definition / Provider / Consumer，JPMS 编译期隔离
4. **配置即组合** —— Profile / Bundle / Patch 三层叠加，受限插值（无任意代码）
5. **显式与纪律** —— sealed 穷尽、ScopedValue、fail loud、teardown 顺序

四条治理不变式贯穿全部设计：

| # | 不变式 | 一句话 |
|---|---|---|
| R1 | 可重建性 | 模型某一轮的完整请求可从持久化事实逐字节重建（哈希锚点 + replay 测试）|
| R2 | 执行一致性 | 模型发起的副作用有且仅有一条受控路径（ToolExecutor pipeline + 架构测试）|
| R3 | 副作用消除 | 插件失败/作用域关闭即清理一切注册副作用（子 scope 原子回滚）|
| R4 | 治理完备 | 生产配置能证明权限、审计、停止条件已挂载（构造器强制 + `--verify` + policy 档位）|

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
mvn -B package              # 全量编译打包（38 个 reactor 项目）
mvn -B -pl :harness-kernel-core -am package   # 单模块及其依赖
jar --describe-module --file kernel/core/target/harness-kernel-core-0.1.0-SNAPSHOT.jar
```

## 仓库结构

```
docs/design/        12 篇设计文档（00-overview … 11-java25-upgrade）
docs/dsh-reference.md   设计参照系说明（dsh 仓库路径约定）
kernel/             Cordis 等价物：core（统一 Scope/Events/Plugin）+ brand + config
core/               Agent 主干：session/tools/agent/agent-loop/system-prompt
llm/ fs/ shell/     capability seam（Definition + Provider + Consumer）
session/            持久化 seam（Store + SessionEventCodec SPI + JSONL 后端）
sandbox/ interaction/   沙箱与审批（approval 三模式，executor 固定 stage）
bundle/ examples/   base/headless 组合与可运行示例
```

每个叶子模块当前只含 `module-info.java` + 标记类 —— **依赖图是真的，代码是占位**。例外：`kernel/brand`（`Id<T>`）与 `kernel/core`（`Scope`/`Runtime`/`Events`/`PluginLoader`，30+ 测试）已实现。

## 实现路线（垂直切片）

| 切片 | 模块 | 设计文档 |
|---|---|---|
| 1 ✅ | `kernel.core`（统一 Scope 内核）| [01-kernel.md](docs/design/01-kernel.md) |
| 2 ✅ | `core.session`（LoggedEvent 信封 + Surface）| [03-session-event-sourcing.md](docs/design/03-session-event-sourcing.md) |
| 3 ✅ | `llm.seam` + `llm.replay`（keyless 测试地基）| [10-testing.md](docs/design/10-testing.md) |
| 4 ✅ | `core.tools` + `fs.*`（R2 单一执行路径打通）| [05-capability-seam.md](docs/design/05-capability-seam.md) |
| 5 | `core.agent-loop`（Turn/Step 状态机）| [04-agent-loop.md](docs/design/04-agent-loop.md) |
| 6 | `examples.headless` 跑通第一轮 turn | [07-profile-bundle.md](docs/design/07-profile-bundle.md) |
| 7 | scope/preset 组合 | [06-scope.md](docs/design/06-scope.md) |
| 8 | 治理：`--verify` + policy 档位（R4）| [07-profile-bundle.md](docs/design/07-profile-bundle.md) |

R1–R4 对应测试随切片走，不做收尾补（[10-testing.md](docs/design/10-testing.md)）。

## 许可

架构设计源自对 dsh 的移植分析（见 [docs/dsh-reference.md](docs/dsh-reference.md)）；本仓库代码为原创实现。
