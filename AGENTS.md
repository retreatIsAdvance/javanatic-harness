# AGENTS.md

Javanatic Harness（JH）是把 [DeepSeek Harness (dsh)](docs/dsh-reference.md) 的工程思想移植到 JVM 的插件化 Agent Harness：**思想照搬，形状不照搬**。Java 25 LTS / JPMS / Maven 多模块，Maven groupId `io.github.retreatisadvance`（中央仓命名空间 = GitHub 身份；与包名不同源属有意为之）。

改 `kernel/` 前必读 [docs/design/01-kernel.md](docs/design/01-kernel.md)；全部设计的导航与 R1–R4 总表在 [docs/design/README.md](docs/design/README.md)。

## 行为指令（AI 执行的最高优先级）

- **迭代仪式**：每个迭代开始前，与用户确认四件事——**内容 / 目标 / 为什么这样设计 / 明确不做什么**，确认结果落盘 [docs/plan/iteration-N.md](docs/plan/README.md)；未确认落盘不得开始写迭代代码。迭代结束勾完全部验收项、在文件内附证据（实际执行的命令与结果），并如实报告，不夸大、不跳过。
- **审查停点（关键类 review，自动审批下的主动停）**：迭代开工时按锚点表设 2-3 处必停点（承载契约 / 跨模块效应 / 引入新词表的类；纯机械实现不设），写进迭代文档。到点必须主动停下——不 commit、不进下一锚点——产出 review packet 后发起正式询问等用户放行。packet 含：承载类最终全文（或契约相关段）、三行映射（设计增量条目 → 实现 → 测试）、偏离清单（出现什么 / 为什么 / 待裁决）、"为什么是这个形状 / 若错靠什么发现"的论证。用户可随时打断先看某处；停点密度按迭代反馈调整。
- **讨论阶段产出解释，不产出代码**。用户在理解/审查阶段时，交付的是讲解与评估；讨论中发现的代码缺陷，指出并说明修法，经确认（或用户明确要求）再动代码。
- **文档即事实源**：代码与设计文档同一提交同步更新；文档陈述当前状态，不保留演进史叙事。一个事实只有一个家。
- **犯错回填**：AI 犯错且根因是本文件或设计文档未覆盖 → 同一提交把规则写回（防再犯）。「已知坑」与「编码规范」就是这么长出来的；只修代码不回填规则 = 同一个坑会再踩。pre-push 对 fix 类提交自动提醒：改了代码但 AGENTS.md 与 docs/ 零变更时给出 warn（advisory，不拦截）。
- **语言政策**：设计文档中文正文 + 英文标题；Javadoc 中文简洁；提交信息英文小写；标识符与 API 名英文。
- **证据匹配改动面**：行为改动配聚焦测试（`mvn -B -q -pl <module> test`）；架构/组合改动配全量 `mvn -B -q package`。不为提交重复跑已绿的检查；不声称没跑过的验证。
- **fail loud**：错配、缺依赖、半装配状态必须在发生点大声失败；禁止静默兜底、默认值吞错、空 catch 吞异常。
- **不确定就问，不猜**；有冲突停下来说明，不擅自扩大范围。

## 现状

- **已实现**：`kernel/brand`（`Id<T>`）+ `kernel/core`（统一 Scope 内核，预算见 01）；`core/session`（事件溯源）；`llm/llm` + `llm/replay`（seam + keyless 回放）；`core/tools` + `fs` 三模块（R2 pipeline）；`core/agent` + `core/system-prompt` + `core/agent-loop`（Turn/Step 状态机）；`examples/agent-spine`（可运行竖切 + ArchUnit R2 架构测试）；`shell` 三模块（bash 真执行）；`llm/deepseek`（真实 Provider）；`session/persistence(-jsonl)`（R1 闭环）；`llm/openai-compat`（通用 OpenAI 兼容适配器 + VendorProfile，deepseek 为薄壳）；fs 根目录策略；审批三模式（interaction/approval）+ 命令面（interaction/commands：registry/slash 解析/事件对，it14）；`examples/headless`（CLI runner：--verify/policy/--workspace=/--approval=/--budget=，经 AppBoot 数据化组合；裸 `jh` 进 REPL——命令面 + 流式渲染 + typed 失败渲染（按 `FailureKind`），it14；生产模拟场景测试（replay 驱动、keyless：压缩 + resume + 预算 + R1 全比对），it15；dist/jh 打成 jlink 镜像，`bin/jh` 直接运行）；kernel/config + bundle/base（ConfigService/AppBoot/YAML 三层/CompositionManifest）；core/preset（per-session 能力集）；scoped 工具注册表 + setup window（06 落地）；compaction 生产者 + budget 档 + durable resume + request-context（长跑能力）；core/todo + core/plan（todo_write 整表快照 + 计划模式；ExtensionEvent + ServiceLoader codec 三实例——含 it14 的 assistant/chunk）；sandbox 三模块（同机进程约束：darwin=seatbelt / linux=bwrap 均已实测落地，fail-closed + plan 压只读；windows 后端入 0.2.0）。
- **占位**：其余叶子模块只有 `module-info.java` + 标记类——依赖图从第一天起由 JPMS 编译器强制，不是待办清单，而是模块契约。
- **Pre-release**：无外部消费者。正确地基 > 兼容包袱：可自由重命名/重排包并同步全部引用，不写兼容垫片。

## Repository layout

```
kernel/      brand（Id 品牌类型）、core（Scope/Events/Plugin 内核）、config（YAML + ConfigService）
core/        session（事件溯源）、system-prompt（组装注册表）、tools（R2 pipeline）、todo（todo_write）、plan（计划模式）、agent（公开契约）、agent-loop（Turn/Step 状态机）
llm/         llm（seam）、openai-compat（通用适配器）、deepseek（真实 provider）、replay（录制回放，keyless 测试依赖）
fs/ shell/   capability 三角色：seam / provider / tool consumer（均已实现；shell 有本机 bash 与 docker 两个互斥 provider）
session/     persistence seam + jsonl 后端（R1 闭环）
sandbox/ interaction/   沙箱（seatbelt/bwrap 平台链 + 策略解析）与审批（三模式）+ 命令面（it14）
dist/        jh：jlink 运行时镜像编排（无代码；产出 target/jlink-image/bin/jh 与 tar.gz/zip 归档）
bundle/ examples/       base 组合（AppBoot 数据化装配）；agent-spine 与 headless（CLI runner）
docs/        design/ 12 篇设计文档 + dsh-reference.md；plan/ 逐迭代验收清单（[README](docs/plan/README.md)）
```

## Commands

```sh
java -version           # 25（.java-version=25.0 已提交，jenv 管理，是项目契约）
mvn -B -q -pl kernel/core test        # 聚焦单模块测试（含 jqwik 属性测试）
mvn -B -q -pl <group>/<pkg> -am test  # 连依赖一起构建测试
mvn -B -q validate                     # 快速门禁：spotless:check + checkstyle:check（全 reactor）
mvn -B -q spotless:apply               # 自动修复白空格/结尾换行/无用 import
mvn -B -q package                     # 全 reactor 构建+测试（推送/收尾前）
mvn -B -q -pl <module> -Dtest=XTest test   # 单测试类
git config core.hooksPath .githooks   # 一次性：启用 pre-commit/pre-push 钩子
```

无 mvnw wrapper。CI 用系统 Maven 3.8+（`actions/setup-java` 提供）；本机开发环境无系统 mvn——用 IDE 自带 Maven + temurin-25 执行上述命令。测试栈：JUnit 5 + AssertJ + jqwik（根 POM 统一注入，叶子 POM 不写测试依赖）。

### 跑一个 task（dist/jh jlink 镜像，it13 起）

```sh
mvn -B -q -pl dist/jh -am package                 # 或全量 mvn -B package
dist/jh/target/jlink-image/bin/jh --help          # 全部 flag 与示例（exit 0）
dist/jh/target/jlink-image/bin/jh --verify        # 组合 + 治理断言（无 key，exit 0/1）

dist/jh/target/jlink-image/bin/jh                 # 裸启动进 REPL（it14）：非 / 行成轮送模型并流式渲染；
                                                  # /help 列命令、/exit（或 EOF/Ctrl-D）退出；进行中轮以 aborted 落账

DEEPSEEK_API_KEY=sk-... dist/jh/target/jlink-image/bin/jh --workspace=<已存在目录> "任务文本"
# 每次运行打印独立会话 id（headless-<时间戳>-<短随机>）；--resume=<id> 按打印 id 续跑
# --resume=<id> 不带任务文本 → 在该既有会话上进 REPL 续聊
# 任务结果契约（it17）：stdout = 成功给最终答案 / 失败为空（成功无文本合法 → stdout 空 + exit 0）；
#   退出码 0 完成 · 1 --verify 违规 · 2 用法/缺 key · 3 任务失败 · 4 任务被取消；
#   诊断（失败文案、模型遗言）走 stderr——`out=$(bin/jh "任务")` 取答案、按退出码判成败
# 任意 OpenAI 兼容厂商:
#   … bin/jh "任务" --api-key-env=MOONSHOT_KEY --base-url=https://api.moonshot.cn/v1 \
#                --model=kimi-k2 --provider=kimi
# 容器级隔离（本机须有 docker 与镜像在场，不自动拉取）:
#   … bin/jh "任务" --docker --image=ubuntu:24.04
# 审批与治理档:
#   … bin/jh --verify --policy=PRODUCTION --approval=ask --budget=100000   # PRODUCTION 组合可达(exit 0);
#                                       # 缺 --budget / AUTO 审批 / 非耐久持久化仍逐项拒(exit 1)
```

组合是数据（it8）：内置 headless profile → base bundle 行资源 → CLI flag overlay 经 AppBoot 装配；dist 镜像即 `examples/headless` 的 jlink 打包（无手拼 module-path）。

### 发布（0.1.0，执行侧 = 用户）

```sh
mvn -B -P release -Dgpg.skip=true package   # 干跑：release 剖面工件完整（sources/javadoc/元数据；无 .asc 属预期）
mvn -B -P release deploy                    # 真签名 + 上传 Central 草稿（autoPublish=false；server id `central`）
```

完整序列（GPG 生成 / Portal 命名空间验证 / 版本翻转 / 草稿审核 Publish / tag / bump）见 [docs/release.md](docs/release.md)。**签名硬门槛**：真 key 签名后复查 bundle 内 `.asc` 齐全——干跑「无 .asc」不得当作达标证据。

### 命令权限分级

| 可自主执行 | 需用户确认 | 禁止 |
|---|---|---|
| 全部 `mvn` 验证/构建命令、`spotless:apply`、新建与修改源码/测试/文档、`git add`/`git commit`（规范信息） | `git push`、跨模块重命名或移动、新增第三方依赖、推翻设计文档既有决策、删除非本次创建的文件 | 修改 `.java-version`、提交凭据/`.env`、force push、削弱或跳过 checkstyle/spotless 规则（放宽须 PR 说明理由并改 `config/checkstyle/checkstyle.xml`） |

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

一图速览全部类的关系 + 最小运行示例见 [01-kernel 附录](docs/design/01-kernel.md#附一图速览与最小运行示例)。

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
- **类引用走 import**：可执行代码禁行内全限定类名；跨包同名冲突按 pre-release 立场重命名解决，万不得已用 FQN 须同处注释说明。Javadoc `{@link}` 的全限定名不受限（目标类未 import 时它是唯一写法）。
- **类型纪律**（[08](docs/design/08-type-discipline.md)）：不可变数据用 `record`；判别联合用 `sealed interface` + `record` + 穷尽 `switch`，封闭联合的 default 分支抛 `IllegalStateException`（assertNever 等价）；**禁止判别式 if 链**、**互斥动作/模式不落字段**（replace/remove/insert 这类互斥动作用 sealed 联合变体——多个互斥布尔参数就是被摊平的判别式 if 链）；构造器内 fail-loud 校验（JEP 513 灵活构造器体）；跨边界 id 用 `Id<T>` 品牌，不裸传 `String`。
- **泛型**：`@SuppressWarnings("unchecked")` 必须附注释说明为何收窄不可行；unchecked cast 仅限内核登记处的同构点。
- **异常**：不捕获 `Throwable`；catch 要么处理要么翻译上抛，空 catch 必须注释吞了什么、为何无他物可达；不用异常做流程控制；try-with-resources 优先；语义性 `RuntimeException` 原样上抛不裹第二层皮（丢失消息）。
- **并发**：共享可变表用 `ConcurrentHashMap`；双检锁字段必须 `volatile`；虚拟线程 executor 用 `Executors.newVirtualThreadPerTaskExecutor()`；异步边界（worker/进程/HTTP）才做运行时校验，同进程类型化边界信任编译器。
- **集合与 null**：不返回 null 集合（返回空集合或 `Optional`）；不可变优先 `List.of`/`Set.of`；遍历中删除用 `removeIf`。
- **注释与 Javadoc**：每个模块/导出的非显然契约有简洁中文 Javadoc，函数式导出带 `@param`/`@return`；注释写契约与不变量，不写代码复述、不写"我改了什么"；TODO 按 `FIXME`/`TODO`/`XXX` 紧急度分级；文件恰好一个结尾换行。
- **方法体量**：单方法显著超过 ~80 行先想拆分（阿里手册）；kernel/core 主代码预算 ≤1200 行，超了先删而不是挪。

以上白空格/结尾换行/无用 import/星号导入/行内全限定名/命名/制表符/参数上限（max 6；数据束、依赖注入构造器等合法超限经 `CHECKSTYLE:OFF ParameterNumber` 注释豁免并写明理由）等规则**已执法化**：`mvn -B -q validate` 即 spotless + checkstyle 门禁，CI 同口径；规则集在 [config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml)，先绿后严，收紧或放宽须在 PR 说明理由。

## 模块与构建规则（[02](docs/design/02-module-layout.md)）

- 叶子 POM 内部依赖**不写版本**（根 POM dependencyManagement 唯一版本源）；聚合 POM 只有 `<modules>`。
- 新增第三方依赖：先查 BOM；再问"它删掉了多少自有代码与测试"；边界模块才允许。
- `opens` 仅给 codec/adapter 的反射门面与 JUnit 运行时反射（各叶子模块开自身主包，测试与主类同包；只放开运行时反射，不改编译期可见性）；`requires transitive` 不用（无聚合 jar）。

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
- JDK 25 终版 ScopedValue（JEP 506）：跨线程共享仅限 `StructuredTaskScope.fork`（preview，项目禁用）；普通 `Thread.ofVirtual().start()` **不继承**绑定。执行方法是 `Carrier.call/run`（预览期 `get(Supplier)` 已删）。
- VS Code Java（jdt.ls）与 Maven 并发写 `target/` 会互相污染：症状是 surefire 跑出带 `Unresolved compilation problems` 的 ECJ 假类、或 mvn 对已修代码报陈旧错误。处置：干净失败先删可疑模块 `target/` 再重建；`.vscode` 已关 autobuild 并忽略 APILeak 告警（非 transitive 政策的预期 IDE 噪音，javac 不报）。
- ArchUnit 扫描：1.3.0 的 ASM 不认 Java 25 字节码（major 69）且静默丢类（警告走无 provider 的 slf4j 被吞）——用 1.5.0+；surefire 的 manifest-boot jar 使 `java.class.path` 无真实条目，类导入走锚类 `CodeSource` 定位（见 `ToolDispatchArchitectureTest`）。
- JDK HttpClient：响应流远端关闭时阻塞 read 返回 -1、本地 close() 会唤醒阻塞 read 抛 IOException(看门狗据此掐断);`Thread.sleep` 无纳秒重载,传错会静默睡走数十小时——用 `sleep(Duration)`。
- surefire 下 `System.in` 不是 EOF 而是挂起流:stdin 相关测试必须 `System.setIn(空流)` 注入,否则测试类永久挂死。
- ECJ/JDT 对「导出 API 引用他模块类型」报 *missing requires transitive*（IDE 告警 8390067）——非 transitive 政策（02 §748）的预期代价，不要用 `requires transitive` 消音；IDE 侧经 `java.settings.url` 忽略 APILeak。
- javac 25：泛型推断下零参隐式 lambda 对 varargs 抽象方法编译失败（`() -> null` ✗）；用单参 lambda（`overrideArgs -> null` ✓，[Next 的 Javadoc](kernel/core/src/main/java/io/javanatic/harness/kernel/events/Next.java)）。
- `.jqwik-database`（jqwik 模糊缓存）不入库，已在 .gitignore。
- 事件订阅表遍历用 `CopyOnWriteArrayList`；waterfall 的 next 守卫包在 rest 上（invokeOnce），不在最外层。
- checkstyle 不解析 `module-info.java`（已排除在门禁外）；首次使用 `import module`（JEP 511）前先升级 checkstyle 依赖，否则解析报错。

## 修改本文件

规则自包含、链接到权威文档；内容膨胀时先删后加；与设计文档冲突时以设计文档为准并立刻修此处。

- **拆分阈值**：单文件 ≤200 行；超过、或某模块的规则只关己时，拆子目录 AGENTS.md（如 `kernel/AGENTS.md`），AI 按需读取，根文件只留全局规则。
- **决策记录阈值**：设计文档只承载目标态；当「为什么这么做」的决策史开始挤占目标态时，引入 `.agents/notes/` 决策记录目录（带归档冻结策略），不要把决策史写进设计文档。
