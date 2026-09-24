# 外部接入指南 —— 在自家 Java 服务里嵌入 Agent

面向**不读本仓源码**的 Java 开发者：只凭发布坐标，在一个独立 Maven 工程里完成三件事——

1. **嵌入 Agent**：用 `AppBoot` 起一个 `Runtime`；
2. **自注册插件与工具**：实现 `Plugin`、注册自有 `ToolDefinition`，并让它可被 profile 组合引用；
3. **治理自证（keyless）**：组合 → dump → `boot()` 失败即抛，机器可检查。

本指南的每条命令口径都对着一个**可复跑的验证工件**：`integration/consumer-sample`（独立 Maven 工程，不在主仓 reactor 内）+ `integration/verify-consumer.sh`（两条工件腿 + 四负例，见 §7）。稳定面承诺与冻结清单见 [docs/design/12-api-stability.md](design/12-api-stability.md)。

## 1. 两条发行线：标准发行包 vs 二开发行

| | 标准发行包 | 二开发行（本指南） |
|---|---|---|
| 形态 | jlink 运行时镜像 / 归档（[v0.1.0 release](https://github.com/retreatisadvance/javanatic-harness/releases/tag/v0.1.0) 的 `javanatic-harness-0.1.0-<platform>.tar.gz` / `.zip`，解压用 `bin/jh`） | Maven 坐标 `io.github.retreatisadvance:harness-*` |
| 能力面 | 只跑 CLI（任务 / REPL / `--verify`） | 编译期嵌入：组合、插件、工具、治理断言 |
| 插件 | **不承诺**「往镜像里丢 JAR 即启用插件」——运行时热插拔不在 0.2.0 面内（[docs/design/README.md](design/README.md) 0.2 阶段「本阶段不做」） | 插件随你的构建产物进 classpath / module-path，由组合（profile）显式引用 |

两条线共享同一套组合与治理语义：**插件面只对编译期坐标开放**——想让自有工具参与组合，就走二开。

## 2. 最小可照抄 pom

前提：JDK 25 + Maven 3.8+。没有 BOM 可用：主仓 `pom.xml` 的 `dependencyManagement` 不是可依赖工件；也**不要**继承 `harness-parent`——它会把主仓测试依赖（junit/assertj/jqwik 等）一起拖进你的构建。版本用一处属性自持：

```xml
<properties>
    <maven.compiler.release>25</maven.compiler.release>
    <!-- 0.1.0 = Maven Central 发布件；0.2.0-SNAPSHOT = 主仓工作树 install 的候选件 -->
    <harness.version>0.1.0</harness.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.retreatisadvance</groupId>
        <artifactId>harness-bundle-base</artifactId>
        <version>${harness.version}</version>
    </dependency>
    <!-- 其余 15 条见 integration/consumer-sample/pom.xml：kernel-core / kernel-config /
         core-tools / core-session / llm-llm / core-agent / core-system-prompt /
         llm-openai-compat / fs-local / fs-tool / shell-bash-local / shell-tool /
         session-persistence-jsonl / interaction-approval / core-preset -->
</dependencies>
```

**为什么是 16 条显式依赖而不是一两条**：组合装配期做**双向校验**——每个行引用的插件都必须被发现，每个被发现的插件都必须被某个行引用。`harness-bundle-base` 的发布 POM 只声明编译面依赖，行引用的插件实现（fs/shell/persistence/approval…）要由**消费方自持**，否则 `boot()` 会点名缺失项（文案见 §6）。这正是「不靠 reactor 偶然提供的依赖」的验收点：独立工程写全闭包。

## 3. 消费两条路：classpath（默认）与 module-path

两条路都只在**构建期**编译你的代码；运行期的差别是 jar 放在哪。

**classpath（默认，示例工程走这条，已端到端复跑）**

```sh
mvn -f pom.xml -Dharness.version=0.1.0 dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" your.Main profile.yml
```

**module-path（JPMS 消费方）**：注意 **JPMS 模块名 ≠ 包名**——`harness-bundle-base` 的模块名是 `io.javanatic.harness.bundle.base`，它导出包 `io.javanatic.harness.boot`（`AppBoot`/`Policy` 在此）。你的 `module-info.java`：

```java
module your.service {
    requires io.javanatic.harness.bundle.base;   // AppBoot / Policy
    requires io.javanatic.harness.kernel;        // Plugin / Scope / Runtime
    requires io.javanatic.harness.core.tools;    // ToolRegistry / ToolDefinition
    provides io.javanatic.harness.kernel.plugin.Plugin with your.service.YourPlugin;
}
```

运行时三件事，缺一即撞真实报错（§6）：

1. **jar 全量上 module-path**——含第三方闭包（`com.fasterxml.jackson.*`、`org.yaml.snakeyaml`）；classpath 路线由 Maven 自动带齐，module-path 路线须自己列。
2. **解析你的插件模块**：`--add-modules your.service`（或提供者在 `--add-modules ALL-MODULE-PATH` 内）。打包成 jar 的模块，服务提供者随 `provides` 声明；**自动模块**（无 `module-info.class` 的 jar）的 `provides` 由 `META-INF/services` 派生，同样有效。
3. 你需要的导出模块按 §12 §2 的导出面表逐个 `requires`（`io.javanatic.harness.boot` 的包在 `bundle.base` 模块里是常见踩点）。

模块名与导出包全量对照表：[docs/design/12-api-stability.md §2](design/12-api-stability.md)。

## 4. 组合与引用口径（profile / bundle / 行）

- **profile 是显式文件路径**（没有隐式命名发现）：`AppBoot.compose(new AppBoot.BootOptions(Path.of("profile.yml"), List.of(), true, Policy.STANDARD))`。`BootOptions` = `(profilePath, overlays, verify, verifyPolicy)`，缺省档 `STANDARD`。
- **bundle 按 name 引用，不是 GAV**。发现渠道 = classpath 资源 `META-INF/harness/bundle.yml`（每 bundle 一份，资源名固定）；profile 写 `bundles: [base, your-bundle]`。
- **行引用 plugin id**（kebab-case），发现渠道 = `ServiceLoader.load(Plugin.class)` → 你的插件必须声明 `META-INF/services/io.javanatic.harness.kernel.plugin.Plugin`（module-path 路线等价写法是 `provides`）。
- **两条通路缺一不可**：bundle 资源负责「这个 bundle 有哪些行」，ServiceLoader 负责「这个插件 id 对应哪个实现类」。只做其一，装配期必报 `profile references unknown bundle:` 或 `行引用的插件未发现:`。
- **三层叠加**：bundle 行 → profile `rows:`（patch：`replace` / `remove` / `insert` / `disabled`）→ 程序化 `overlays`。表达式的插值白名单 `${env:...}` / `${props:...}` / `${cwd}` / `${home}` / `:-默认值`，比较式 `==` / `!=`（无任意代码）。
- **键名白名单（fail loud）**：profile 顶层 `name`/`policy`/`bundles`/`rows`；bundle 顶层 `name`/`description`/`rows`；行 `plugin`/`config`/`remove`/`replace`/`after`/`before`/`disabled`——未列名键（含拼写错误）装载期直接拒绝。**0.1.0 为静默忽略**，升级到 0.2.0 时原有 typo 会显形（这是有意的迁移面，见 [12 §5](design/12-api-stability.md)）。
- 一个最小 bundle 与 profile（示例工程实况）：

```yaml
# src/main/resources/META-INF/harness/bundle.yml
name: your-bundle
description: your tools
rows:
  - plugin: your-plugin
```

```yaml
# profile.yml
name: your-service
policy: STANDARD
bundles:
  - base          # 首层：治理与工具面（模型/审批/fs/shell/persistence/...）
  - your-bundle   # bundle 名，不是 GAV
```

## 5. 治理自证（keyless，进 CI 的机器可检查面）

```java
var options = new AppBoot.BootOptions(Path.of("profile.yml"), List.of(), true, Policy.STANDARD);
List<ConfigRowSpec> composed = AppBoot.compose(options);     // 三层叠加后的行序（表达式未求值）
List<ConfigRowSpec> enabled  = AppBoot.resolve(composed);    // 求值后的有效行
System.out.print(AppBoot.dump(enabled));                     // 人读视图：一行一插件，config 展开 {k=v}，凭据键脱敏 ****
try (Runtime runtime = AppBoot.boot(options)) {              // 装配 + 治理断言；违规抛 VerifyFailedException
    ...
}
```

- `dump(enabled)` 输出一行一插件（`persistence-jsonl {root=/home/you/.harness/sessions}`，凭据键脱敏）；`(disabled: <expr>)` 注记只在 dump **未 resolve** 的 `compose(...)` 结果时出现——`resolve` 后的列表已滤掉禁用行。**CLI 无 `--dump-config` flag**，dump 是 API 面（[07 §8](design/07-profile-bundle.md)）。
- `boot` 的失败面是 fail-loud：组合双向校验不过、workspace 四处不同源、policy 违规——都抛异常，绝不降级继续。
- `Policy.STANDARD`：治理件在场即可（审批任意模式、内存会话可接受）。`Policy.PRODUCTION`：另加禁 AUTO 审批、要求耐久持久化、`LoopGuard` limits 与 budget 非零、沙箱非 `danger-full-access`——违规逐项进 `VerifyFailedException.violations()`，消息形如 `policy violations: [...]`。
- `--verify`（CLI）与上等价：不创建 agent、不需要 API key，exit 0/1；嵌入场景自行决定异常处置（示例 `SelfCheck` 直接让它冒泡 → 进程非 0）。
- 装配成功后 `Runtime.root()` 可 `require(ConfigService.KEY)` / `ToolRegistry.KEY` 等：`registry.schemas(root)` 看模型面工具清单、`registry.resolve(root, id)` 取定义直接执行——自证「我的工具真的挂上了」。

## 6. 排错指引（真实文案 → 处置）

| 真实文案（节选） | 含义 | 处置 |
|---|---|---|
| `profile references unknown bundle: <name>` | profile 引用的 bundle 名没被任何 classpath 资源发现 | 检查该 bundle 的 `META-INF/harness/bundle.yml` 是否打进你的产物、`name:` 拼写是否与 `bundles:` 一致；**不要写 GAV** |
| `行引用的插件未发现: <id>` | 组合行引用了 classpath/module-path 上没有的插件 | 补对应坐标（组合闭包自持，见 §2）；module-path 路线检查 `--add-modules` 与 jar 是否在场 |
| `发现的插件未被任何行引用: <id>` | 你的 classpath 多带了插件（传递依赖或多打 jar） | 从依赖里去掉，或给它在 profile `rows:` 显式加行——发现 ≠ 组合 |
| `bundle '<name>': unknown key '<k>' (allowed: …)` / `profile '<name>': …` / `行 '<plugin>': …` | profile/bundle/行出现未列名键（含 typo） | 按允许键集改名；0.1.0 静默忽略、0.2.0 fail loud |
| `policy violations: [治理缺失：无 ApprovalService（先装载 approval-* 插件）]` | 组合缺治理件（审批/持久化/LoopGuard/沙箱） | 用 `base` 首层或按 12 §5 的插件 id 补行 |
| `policy=PRODUCTION 但审批为 AUTO（换 approval-ask / approval-deny）` | 生产档禁 AUTO 审批 | 换审批行，或退回 `STANDARD` |
| `workspace drift: {…} —— 提示词 cwd 与 fs/shell/sandbox 围栏必须同源(it21)` | `agent-loop.cwd` / `fs-local.root` / `shell-tool.workspace` / `sandbox-policy.workspace` 不同值 | 四处同源（同一工作区根）；**0.2.0 起**装配期拒绝，0.1.0 无此断言 |
| `llm-openai-compat: no api key (config 'apiKey' or env DEEPSEEK_API_KEY); keyless compositions should disable this row` | 行启用但 key 缺失 | 设环境变量 / 行 `config.apiKey`；或给该行 `disabled: ${env:DEEPSEEK_API_KEY} == null`；只做组合自证时用 `--verify`（不需要 key） |
| `Could not resolve dependencies … Could not find artifact …:jar:<version>` | 坐标/版本在解析源不存在 | 版本与仓库对齐（0.1.0 = Central；`0.2.0-SNAPSHOT` 只在本机 `install` 过的仓里） |
| `'dependencies.dependency.version' for io.github.retreatisadvance:harness-kernel-core:jar is missing` | 抄了主仓 reactor 写法（依赖不写版本） | 独立工程必须写显式版本（`<harness.version>`） |
| javac：`错误: 找不到模块: io.javanatic.harness.bundle.base` | module-path 上缺该 jar | jar 放上 `--module-path`；核对**模块名**而非包名 |
| java：`java.lang.module.FindException: Module com.fasterxml.jackson.databind not found, required by io.javanatic.harness.llm.openai.compat` | module-path 缺第三方闭包 | 把 `jackson-*`、`snakeyaml` 也列上 module-path（classpath 路线无此问题） |
| `Two versions of module … found` | 同一模块两个版本同时在路径上（常见：旧 `target/` 残留） | 清掉旧版本产物（[docs/release.md](release.md) 注2 实撞记录） |

## 7. 验证：两条工件腿 + 四负例

```sh
<repo>/integration/verify-consumer.sh candidate   # 候选腿：主仓工作树 install 进隔离仓 → 示例编译 + 自证运行
<repo>/integration/verify-consumer.sh release     # 发布腿：隔离仓为空、只从 Central 解析 0.1.0 → 同源复验
```

- **候选腿**证明「工作树今天的坐标」可用；**发布腿**证明「Central 上的发布件」可用；两腿跑同一份示例源码，按**交集面**（`compose` / `dump` / `boot` / `Plugin` / `of` / `register`）复验，发布腿的治理自证用 `boot()` fail-loud + `dump()`。
- **四负例**（候选仓 + 候选构建产物，控制组）：坏版本必解析失败、去显式版本必构建失败、坏治理配置必 boot 抛错、`PRODUCTION` 档撞 AUTO 审批必抛 `VerifyFailedException`。
- 发布腿**不进 CI**（公网依赖不作构建门禁）；候选腿进 CI 作漂移警报。

### 版本面：0.1.0 vs 0.2.0-SNAPSHOT

| 面 | 0.1.0（Central） | 0.2.0-SNAPSHOT（工作树） |
|---|---|---|
| `compose` / `resolve` / `dump` / `boot` / `Policy` / `VerifyFailedException` / `ToolRegistry.schemas` / `resolve` | ✅ | ✅ |
| 键名三层白名单 fail loud（§4） | ❌ 静默忽略 | ✅ |
| workspace 四处同源装配期断言（§6） | ❌ | ✅ |
| `AppBoot.bootReported`（组合自述计数：行/发现/未引用） | ❌ | ✅ |
| `ToolDefinition.ofExempt(...)`（免审批声明） | ❌ | ✅ |
| `ask_user` 工具（`interaction.ask` 模块 + `ask-user` 行）与 `fs_search`（有界搜索，it21） | ❌（组合面 9 个工具） | ✅（11 个工具） |

升级到 0.2.0 时按上表逐项核对；发布件升级路径与 release notes 见 [docs/release.md](release.md)。
