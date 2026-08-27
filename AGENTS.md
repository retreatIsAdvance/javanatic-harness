# AGENTS.md

Javanatic Harness（JH）是把 [DeepSeek Harness (dsh)](docs/dsh-reference.md) 的工程思想移植到 JVM 的插件化 Agent Harness：**思想照搬，形状不照搬**。Java 25 LTS / JPMS / Maven 多模块，groupId `io.javanatic`。

改 `kernel/` 前必读 [docs/design/01-kernel.md](docs/design/01-kernel.md)；全部设计的导航与 R1–R4 总表在 [docs/design/README.md](docs/design/README.md)。

## 行为指令（AI 执行的最高优先级）

- **迭代仪式**：每个迭代开始前，与用户确认四件事——**内容 / 目标 / 为什么这样设计 / 明确不做什么**；未确认不得开始写迭代代码。迭代结束报告实际跑过的命令与结果，不夸大、不跳过。
- **讨论阶段产出解释，不产出代码**。用户在理解/审查阶段时，交付的是讲解与评估；讨论中发现的代码缺陷，指出并说明修法，经确认（或用户明确要求）再动代码。
- **文档即事实源**：代码与设计文档同一提交同步更新；文档陈述当前状态，不保留演进史叙事。一个事实只有一个家。
- **证据匹配改动面**：行为改动配聚焦测试（`mvn -B -q -pl <module> test`）；架构/组合改动配全量 `mvn -B -q package`。不为提交重复跑已绿的检查；不声称没跑过的验证。
- **fail loud**：错配、缺依赖、半装配状态必须在发生点大声失败；禁止静默兜底、默认值吞错、空 catch 吞异常。
- **不确定就问，不猜**；有冲突停下来说明，不擅自扩大范围。

## 现状

- **已实现**：`kernel/brand`（`Id<T>` 品牌类型）+ `kernel/core`（统一 Scope 内核：.scope / .events / .plugin 三包，33 测试全绿，主代码 ≈1.2k 行，预算见 01）。
- **占位**：其余 25 个叶子模块只有 `module-info.java` + 标记类——依赖图从第一天起由 JPMS 编译器强制，不是待办清单，而是模块契约。
- **Pre-release**：无外部消费者。正确地基 > 兼容包袱：可自由重命名/重排包并同步全部引用，不写兼容垫片。

## Repository layout

```
kernel/      brand（Id 品牌类型）、core（Scope/Events/Plugin 内核）、config（规划中）
core/        session（事件溯源）、system-prompt、tools、agent、agent-loop（占位）
llm/         llm（seam）、deepseek（真实 provider）、replay（录制回放，keyless 测试依赖）
fs/ shell/   capability 三角色：seam / local provider / tool consumer（占位）
session/     persistence seam + jsonl 后端（占位）
sandbox/ interaction/   沙箱与审批（approval 三模式，executor 固定 stage）
bundle/ examples/       base/headless 组合与可运行示例（占位）
docs/        design/ 12 篇设计文档 + dsh-reference.md
```

## Commands

```sh
java -version           # 25（.java-version=25.0 已提交，jenv 管理，是项目契约）
mvn -B -q -pl kernel/core test        # 聚焦单模块测试（含 jqwik 属性测试）
mvn -B -q -pl <group>/<pkg> -am test  # 连依赖一起构建测试
mvn -B -q package                     # 全 reactor 构建+测试（推送/收尾前）
mvn -B -q -pl <module> -Dtest=XTest test   # 单测试类
```

无 mvnw wrapper，用系统 Maven 3.8+。测试栈：JUnit 5 + AssertJ + jqwik（根 POM 统一注入，叶子 POM 不写测试依赖）。

## 技术栈（定死，引入新依赖前自问归属）

| 维度 | 选型 | 边界 |
|---|---|---|
| 语言 | Java 25 LTS，**无 preview**（`--release 25`） | 禁 `--enable-preview`（[11 §5](docs/design/11-java25-upgrade.md)） |
| 并发 | 虚拟线程（"同步写法、异步调度"）+ `ScopedValue` | 禁 `ThreadLocal`；不用 Structured Concurrency（preview） |
| 模块 | JPMS `module-info.java` | `exports` 只导出 API 包；实现类 package-private |
| 构建 | Maven 多模块 + BOM | 版本唯一声明处在根 POM `dependencyManagement` |
| JSON/YAML | Jackson / SnakeYAML **只在边界模块**（codec、HTTP、config） | domain record 零注解（[03 §6](docs/design/03-session-event-sourcing.md)） |
| 日志 | `System.Logger` 独占 | 禁 log4j/slf4j/logback；生产代码禁 `println`（examples 演示除外） |
| 服务发现 | `ServiceLoader` | 禁反射框架；禁 Spring / Lombok（[00 §0](docs/design/00-overview.md)） |

kernel 三模块**零第三方依赖**（`kernel/core` 仅 `requires java.base`），永久保持。

## 架构速记

1. **一切皆插件**：`Plugin(id/requires/apply)` → `PluginLoader`（发现/查重/拓扑排序/逐个装，apply 抛异常即整插件回滚）→ `Runtime.mountScope()` 发 `PluginScope` 挂载视图（provide 落共享 root，effect/子 scope 落插件私有房，订阅过滤绑共享层、注销登记私有栈）。
2. **统一 Scope = 生命周期 + 可见性 + 服务 overlay**：服务沿父链逐层解析、**每次访问重查不缓存**；effect 栈 LIFO 回收（后装的先卸）；close 用 CAS 保证单线程排水。
3. **注册皆 effect**：`provide`/`effect`/`onClose`/订阅统一返回 `Disposable`（幂等撤销凭据）。
4. **两模式 Events**：NOTIFY（`notify`/`notifyOrdered`/`notifyAndWait`）与 WATERFALL（`waterfall`/`firstOf`）；`EventKey` 模式与派发方法错配 fail loud；订阅经 `ScopedEvents`（bind 过滤 / owner 注销分离，[01 §5](docs/design/01-kernel.md)）。
5. **R1–R4 治理不变式**（机制与测试贯穿全部设计文档，[00 §0.5](docs/design/00-overview.md)）：
   - **R1 可重建性**：模型任一轮请求可从持久化事实逐字节重建（哈希锚点 + replay 测试）
   - **R2 执行一致性**：模型副作用有且仅有一条受控路径（ToolExecutor pipeline + 架构测试）
   - **R3 副作用消除**：插件失败/作用域关闭即清理一切注册副作用（原子回滚 + 无缓存解析）
   - **R4 治理完备**：生产配置能证明权限/审计/停止条件已挂载（构造器强制 + `--verify` + policy 档位）
6. **Capability Seam 三角色**（后续模块）：Definition / Provider / Consumer，JPMS 编译期隔离（[05](docs/design/05-capability-seam.md)）。

## 编码规范

命名遵循《阿里巴巴 Java 开发手册》中适用于本项目的子集，叠加 JH 自有约定：

- **命名**：类 `UpperCamelCase` 名词结尾；方法/变量 `lowerCamelCase` 动词开头；常量 `UPPER_SNAKE_CASE`；包全小写；抽象类 `Abstract*`；异常 `*Exception`；测试 `*Test` 与被测类同名；POJO 布尔**不用** `is` 前缀。JH 专有：插件 id kebab-case（`fs-local`）；`EventKey` 名小写斜杠（`session/created`）；`ServiceKey` 名短稳定名（`clock`）；JPMS 名 `io.javanatic.harness.<pkg>`；artifactId `harness-<group>-<pkg>`。
- **魔法值禁止**：字面量出现第二次前提成常量或 enum。
- **类型纪律**（[08](docs/design/08-type-discipline.md)）：不可变数据用 `record`；判别联合用 `sealed interface` + `record` + 穷尽 `switch`，封闭联合的 default 分支抛 `IllegalStateException`（assertNever 等价）；**禁止判别式 if 链**；构造器内 fail-loud 校验（JEP 513 灵活构造器体）；跨边界 id 用 `Id<T>` 品牌，不裸传 `String`。
- **泛型**：`@SuppressWarnings("unchecked")` 必须附注释说明为何收窄不可行；unchecked cast 仅限内核登记处的同构点。
- **异常**：不捕获 `Throwable`；catch 要么处理要么翻译上抛，空 catch 必须注释吞了什么、为何无他物可达；不用异常做流程控制；try-with-resources 优先；语义性 `RuntimeException` 原样上抛不裹第二层皮（丢失消息）。
- **并发**：共享可变表用 `ConcurrentHashMap`；双检锁字段必须 `volatile`；虚拟线程 executor 用 `Executors.newVirtualThreadPerTaskExecutor()`；异步边界（worker/进程/HTTP）才做运行时校验，同进程类型化边界信任编译器。
- **集合与 null**：不返回 null 集合（返回空集合或 `Optional`）；不可变优先 `List.of`/`Set.of`；遍历中删除用 `removeIf`。
- **注释与 Javadoc**：每个模块/导出的非显然契约有简洁中文 Javadoc，函数式导出带 `@param`/`@return`；注释写契约与不变量，不写代码复述、不写"我改了什么"；TODO 按 `FIXME`/`TODO`/`XXX` 紧急度分级；文件恰好一个结尾换行。
- **方法体量**：单方法显著超过 ~80 行先想拆分（阿里手册）；kernel/core 主代码预算 ≤1200 行，超了先删而不是挪。

## 模块与构建规则（[02](docs/design/02-module-layout.md)）

- 叶子 POM 内部依赖**不写版本**（根 POM dependencyManagement 唯一版本源）；聚合 POM 只有 `<modules>`。
- 新增第三方依赖：先查 BOM；再问"它删掉了多少自有代码与测试"；边界模块才允许。
- `opens` 仅给 codec/adapter 的反射门面；`requires transitive` 不用（无聚合 jar）。

## 测试策略（[10](docs/design/10-testing.md)）

- **测试描述行为**：行为变了改测试，PR 里说明为什么；不为实现细节写"正确性证明"。
- **不变式配属性测试**（jqwik）：LIFO 回收序、envelope seq 单调等结构性质用 `@Property`，不是单个例子。
- **R1–R4 测试随切片走**，不做收尾补；每个改动的接受路径都要有拒绝无效用例的证明。
- **keyless**：单元测试无网络、无 API key、可重复；未来真实 provider 测试无 key 自跳过。
- fixtures 在 macOS/Linux 可重放；修 fixture，不修 normalizer。

## Git 与安全

- 提交信息 Conventional Commits：`feat|fix|docs|refactor|test|chore(scope): 英文小写主题`（见 git log 先例）。
- **永不提交凭据**；未来 `DEEPSEEK_API_KEY` 走环境变量，测试无 key 自跳过；config 插值只允许 `${env:}`/`${props:}`/`:-`/`==`/`!=` 白名单，无任意代码执行。
- 未经要求不 push、不 force push。

## 已知坑（本项目环境实测）

- macOS BSD `sed` 不支持 `\b`（静默无效）：批量改名用 `perl -pi -e 's/\bOld\b/New/g'`。
- javac 25：泛型推断下零参隐式 lambda 对 varargs 抽象方法编译失败（`() -> null` ✗）；用单参 lambda（`overrideArgs -> null` ✓，[Next 的 Javadoc](kernel/core/src/main/java/io/javanatic/harness/kernel/events/Next.java)）。
- `.jqwik-database`（jqwik 模糊缓存）不入库，已在 .gitignore。
- 事件订阅表遍历用 `CopyOnWriteArrayList`；waterfall 的 next 守卫包在 rest 上（invokeOnce），不在最外层。

## 修改本文件

规则自包含、链接到权威文档；内容膨胀时先删后加；与设计文档冲突时以设计文档为准并立刻修此处。
