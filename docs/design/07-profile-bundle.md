# 07 · Profile / Bundle / Patch — 配置即组合

一个运行中的 JH 是一棵**有序分层叠加**的插件树。每一层可以替换、插入、禁用上一层的行。这让"换一个 provider 就换一个产品形态"成为配置决策，而非代码修改。

本篇是不变式 **R4（治理完备性）** 的配置层载体：组合可验证（`--verify`），治理挂载可证明（见 §6）。

## 1. 三层组合模型

```
空 plugin 行列表
  ↓ 各 Bundle 按 Profile 列出的顺序叠加（每个 Bundle 贡献若干行）
  ↓ Profile 的 patch.yml（用户级）
  ↓ Home 级 patch.yml（~/.harness/patch.yml）
  ↓ --patch 命令行 overlay（一次性，可多个）
= 最终 plugin 行序（可 dump，可替换任意一行）
```

**键名白名单在装载期执法**（`YamlRows`，it23 起）：profile 顶层 `name`/`policy`/`bundles`/`rows`、bundle 顶层 `name`/`description`/`rows`、行 `plugin`/`config`/`remove`/`replace`/`after`/`before`/`disabled`——未列名键（含拼写错误）**fail loud**，不静默忽略。

### Profile

一个**命名组合**，存储在 Harness home（`~/.harness/profiles/<name>/`）：

```yaml
# ~/.harness/profiles/prod/profile.yml
name: prod
policy: production          # 治理档位（见 §6；缺省 standard）
bundles:
  - base                    # bundle 名（非 GAV）——classpath 上 META-INF/harness/bundle.yml 的 name
rows:                       # 可选：profile 级 patch 行（replace/remove/insert/include）
  - plugin: approval-ask
    replace: true
    config:
      idleTimeoutSeconds: 120
```

### Bundle

一个**发行格式**：一组行 + 挂载的代码（JPMS 模块 jar）。bundle 自述文件（classpath 资源，按 `name` 被 profile 引用）：

```yaml
# harness-bundle-base 的 META-INF/harness/bundle.yml（节选；全文见该文件）
name: base
description: First layer of every profile
rows:
  # 行引用 plugin id（不是类名——JPMS 下类名不可跨模块反射访问）。
  # id 由 Plugin.id() 声明，ServiceLoader 发现后按 id 匹配。
  - plugin: session-store
  - plugin: llm
  - plugin: llm-openai-compat
    config:
      name: deepseek
      baseUrl: ${env:DEEPSEEK_BASE_URL:-https://api.deepseek.com}
    disabled: ${env:DEEPSEEK_API_KEY} == null   # 无 key 时禁用（走 replay/自检）
  - plugin: fs-local
    config:
      root: ${cwd}
  - plugin: shell-bash-local
  - plugin: persistence-jsonl
    config:
      root: ${home}/.harness/sessions
```

### Patch

一个 **overlay**：按 plugin id 替换整行、插入新行、或移除。每一层 patch 作用于上一层的结果：

```yaml
# ~/.harness/profiles/prod/patch.yml
rows:
  - plugin: shell-bash-local     # 替换 base 的同名行（换 provider 不改代码）
    replace: true
    config:
      sandboxMode: landlock
      workspaceRoot: ${cwd}

  - plugin: approval-ask         # 插入新行（生产档要求 human-gate，见 §6）
    after: session-store
```

## 2. 行结构与合并语义

行 = **plugin id + 配置**。行的 patch 锚点就是 plugin id（`Plugin.id()` 全局唯一，01 §7），不需要第二个 id 字段：

```yaml
- plugin: <plugin-id>        # 必填；必须能在 ServiceLoader 发现结果中找到
  config: {<key>: <value>}   # 可选，boot 解析后经 ConfigService 供给插件
  disabled: <表达式>          # 可选，true 则该行不参与组合
```

| Patch 字段 | 语义 |
|---|---|
| `plugin: X`（无标记）| 若 X 已存在，**替换整行**；否则**追加** |
| `plugin: X, replace: true` | 强制替换；X 不存在则 fail loud |
| `plugin: X, remove: true` | 移除该行；X 不存在则 fail loud |
| `plugin: X, disabled: true` | 保留行但禁用（不加载）|
| `after: Y` / `before: Y` | 插入位置锚点 |

**fail loud**（移植 dsh）：patch 目标不存在 → 报错不静默跳过，typo 立即暴露。**同一 plugin id 出现两行 → fail loud**（一个插件一个实例；per-agent 副本是 scope 层的事，06）。

## 3. 受限表达式插值

`config` 值与 `disabled` 支持 `!!js` 等价的**受限表达式**——只插值与比较，不执行代码（dsh 的 `!!js` 在 loader context 执行任意 JS，JH 收紧）：

```yaml
disabled: ${env:DEEPSEEK_API_KEY} != null
config:
  baseUrl: ${env:DEEPSEEK_BASE_URL:-https://api.deepseek.com}
  timeout: ${props:llm.timeout:-30000}
```

变量源：`env:VAR`（环境变量）、`props:KEY`（system property）、`cwd`、`home`。运算仅 `:-`（默认值）、`==` / `!=`（与 null 或字面量比较）。实现在 `kernel.config` 的 `ExpressionResolver`（§4）。

**求值时机**：`disabled` 在 boot（行合并完成后、加载前）求值；`config` 值在行合并时求值。禁止"延迟到插件激活"的隐式求值——组合结果在任何时刻都是完整的（dump 出什么就加载什么）。

## 4. ConfigService — 配置供给

```java
// io.javanatic.harness.config.ConfigService（kernel.config 模块，kernel 零依赖）
/**
 * 插件配置供给。boot 把解析后的行配置注册为实现；插件按自己的 id 取配置。
 * 默认值不在 ConfigService 里——可调参数的默认值是插件的 resolve 职责（08 §7），
 * config 只携带"组合层明确给出的值"。
 */
public interface ConfigService {

    ServiceKey<ConfigService> KEY = new ServiceKey<>("kernel.config");

    /** 该插件的已解析配置；行未写 config 则返回空 Map（不是 null）。 */
    Map<String, Object> configFor(String pluginId);
}
```

boot 在 `loadAll` 之前把实现注册进 root scope；插件在 `apply(scope)` 里 `scope.require(ConfigService.KEY).configFor(id())` 取值。配置错位（key 拼错、类型不符）在插件**构造配置对象时** fail loud（record 构造器校验，08 §6）。

## 5. Boot 流程 — 双向显式组合

```java
// io.javanatic.harness.boot.AppBoot
public final class AppBoot {

    public static Runtime boot(BootOptions opts) throws Exception {
        // 1–5. 组合行：profile bundles → profile patch → home patch → --patch overlays
        List<ConfigRow> rows = composeRows(opts);

        // 6. 表达式求值（disabled / config 值）
        rows = resolveExpressions(rows);

        // 7. 双向显式组合校验（ServiceLoader 发现 ↔ 行引用，两侧都 fail loud）：
        //    a) 行引用的 id 必须存在——typo/缺 jar 立即暴露；
        //    b) 被发现的插件必须被某行引用——不做隐式挂载（发现≠组合）。
        PluginLoader loader = new PluginLoader();
        Map<String, Plugin> discovered = loader.discover();
        verifyComposition(discovered, rows);          // 双向，见上

        // 8. Runtime + ConfigService + 组合清单
        Runtime runtime = new Runtime();
        Scope root = runtime.root();
        root.provide(ConfigService.KEY, configServiceFrom(rows));
        root.provide(CompositionManifest.KEY, manifestFrom(rows));   // R1：进 SessionHeader

        // 9. 按 rows 顺序加载（每插件子 scope，失败原子回滚——01 §7 R3）
        loader.loadAll(root, rows.stream().map(r -> discovered.get(r.plugin())).toList());

        // 10. 治理验证（R4）：构造期已强制（loop/executor 构造器），此处断言档位
        if (opts.verify()) { verifyGovernance(root, policyOf(opts)); }

        return runtime;
    }
}
```

**为什么双向校验**：只查 (a) 会留下"jar 在 classpath 上就悄悄挂载"的隐式行为——这正是 dsh "never silently skip a missing referent" 的反面（never silently mount an unreferenced plugin）。显式组合 = 行之外无挂载，行之内无缺失。

**工作区单源（it21）**：工作区是四个键的同一事实——`agent-loop.cwd`（提示词上下文）、`fs-local.root`（文件围栏）、`shell-tool.workspace`（shell 起点）、`sandbox-policy.workspace`（沙箱授予面）。装配在 `resolve` 之后、加载之前逐键取 enabled 行的 config：声明值归一化（`Path.normalize`，尾斜杠不算漂移）后多于一个不同值即 `IllegalStateException`（点名每个声明键与值），非字符串值同拒。缺键视为未声明——不参与比对，由各插件自己的文档化缺省兜底（如 `agent-loop` 缺省 `user.dir`）。去掉的是"漂移 = 交集生效"的静默语义：不写断言时四处可以各指一处目录而无人报错。

**CompositionManifest**（R1，03 §8）：boot 从行序 + 插件版本（module descriptor）+ 影响模型可见输出的 config 值生成清单，注册为服务；`session-store` 创建 SessionHeader 时消费它。清单 + 日志 = 可重建性的全部持久化事实。

## 6. `--verify` 与 policy 档位（R4）

治理挂载的证明分两层（04 §4 已述类型层）：**类型层**让"没挂治理"组装不出系统；**配置层**回答"挂的是哪个实现、够不够这个档位"。

```sh
jh --verify --policy=PRODUCTION --approval=ask --budget=2000000
```

```text
Profile: headless   policy: PRODUCTION
  composition: 20 rows, 25 discovered, 0 unreferenced
  approval: HUMAN_GATE (approval-ask; exempt=ask_user)
  audit: jsonl ~/.harness/sessions (durable)
  stop: max-turns=50 max-steps=40 budget=2000000
```

（it20 起实况：摘要五行打 stdout、exit 0；`policy` 沿用 CLI 词表大写；rows = 加载行数、discovered = ServiceLoader 发现总数、unreferenced 校验通过后恒 0（非零即 boot 失败——计数与校验同一口径）；预算 0 显示 `unlimited`；违规路径逐项打 stderr 且 stdout 为空。it22 起 `approval:` 行尾点名**免审批工具**（`exempt=`；读 `ToolRegistry.definitions(Scope)` 的 `approvalExempt` 声明面，名称序逗号分隔、无声明印 `-`）——审批面与它的例外在同一行自述；示例计数随 it22 的 `ask-user` 行 +1（20/25）。）

| 档位 | 校验内容 |
|---|---|
| `standard`（缺省）| 组合双向校验通过；治理服务在装配期已强制（构造器注入） |
| `production` | 另加三条：`ApprovalService.mode() != AUTO`；持久化实现存在且 durable；`LoopGuard.limits()` 的 max-turns / max-steps / budget 全部非零 |

- `--verify` **不创建 agent、不需要 API key**，组合 + 断言后以 exit 0/1 退出——进 CI，机器可检查；通过时治理摘要五行打 stdout。
- 治理摘要来自实现自述：`ApprovalService.mode()`（`AUTO` / `HUMAN_GATE` / `DENY_ALL`）、免审批工具名单（`ToolRegistry.definitions(Scope)` 的 `approvalExempt` 声明面，it22）、`SessionPersistence.durable()`、`LoopGuard.limits()`、`AppBoot.bootReported` 的组合计数（行/发现/未引用）——实现类如实报告，档位校验负责拒绝不合格组合（fail loud at boot）。

实现落定（it8）：`ConfigService`/`ExpressionResolver`/行模型（sealed 动作联合 Include/Replace/Remove/Insert——互斥动作不落布尔字段）在 kernel/config（零第三方）；`AppBoot` + SnakeYAML 装载在 bundle/base（第三第三方，仅此模块）。it7 的程序化口径已迁移：headless 经内置 profile + CLI flag overlay 走同一 boot 路径；Policy 断言进 boot（VerifyFailedException 携带违规清单）。显式差异：profile 为显式文件路径（home 命名发现随 it9）；bundle 按 classpath 资源名解析（GAV 钉扎随发布切片）；CompositionManifest 含行序+已解析 config、进 SessionHeader（插件版本摘要随发布切片）；插件配置化——构造器注入（程序化组合）与无参 + `configFor(id())`（数据组合）两条显式路径等价，安全边界值（fs/persistence root）在 config 路径缺失即 fail loud。dump 对含 `apikey` 的键脱敏。

实现落定（it7）：`Policy`（STANDARD/PRODUCTION）与治理断言在 `examples/headless` 落地——程序化组合口径（直装插件清单，非 YAML rows；YAML/ConfigService/bundle 层随 it8）。`--verify` 无 key 可跑（不装配 provider）；审批三模式齐备（auto 留 core/tools 作 executor 锚，ask/deny 在 interaction/approval；ask 缺省即拒绝语义）。PRODUCTION 拒 AUTO / 非 durable / 零 limits，违规逐项指出、exit 1。it8 bundle 落地时 Policy 校验上移到 boot。
- 组合结果的人读视图 = `AppBoot.dump(rows)`（API 面纯函数，§8）：一行一插件、`config` 展开为 `{k=v}`、凭据键脱敏、禁用行带 `(disabled: <expr>)`。**CLI 无 `--dump-config` flag**——`jh` 的组合期出口只有 `--verify`，CLI 面以 [12 §6](12-api-stability.md) 冻结清单为准。
- `--verify` 回答"治理够不够"（§6）：组合 + 断言后 exit 0/1，不创建 agent、不需要 key。

## 7. Headless Profile（MVP 默认）

```yaml
# ~/.harness/profiles/headless/profile.yml（模板）
name: headless
bundles:
  - base                    # bundle 名（0.1.0 起；旧文档的 GAV 写法不成立）
```

### Headless Runner 入口

```java
// io.javanatic.harness.headless.HeadlessMain —— examples/headless 模块
public final class HeadlessMain {

    public static void main(String[] args) throws Exception {
        Options opts = parseArgs(args);   // [--profile X] [--workspace=<dir>] [--approval=auto|ask|deny] [--verify] [--resume=<id>] "<task>"

        try (Runtime runtime = AppBoot.boot(opts)) {
            if (opts.verify()) return;    // verify-only：组合 + 断言后按 0/1 退出

            Scope root = runtime.root();
            AgentRegistry agents = root.require(AgentRegistry.KEY);

            // 每次运行独立会话 id（headless-<时间戳>-<短随机>），运行时打印；--resume 按打印 id 续跑
            String sessionId = opts.resume().orElse(newRunSessionId());
            System.getLogger("jh").log(Level.INFO, "session={0}", sessionId);
            AgentHandle handle = agents.create(root, CreateAgentOptions.builder()
                .sessionId(SessionId.of(sessionId))
                .agentOptions(AgentOptions.builder()
                    .provider("deepseek")
                    .model(opts.model().orElse("deepseek-chat"))
                    .build())
                .build());
            Agent agent = handle.agent();

            agent.followup(UserMessage.of(opts.task(), MessageSource.human()));
            agent.whenIdle().join();

            agent.session().deriveMessages().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .map(AssistantMessage::content)
                .reduce((a, b) -> b)      // 最后一条
                .ifPresent(System.out::println);

            handle.disposeAndAwait();
        }
    }
}
```

```sh
jh --help                                   # 全部 flag 与示例（exit 0）
jh --verify                                 # 组合 + 治理断言（无 key 可跑）
DEEPSEEK_API_KEY=sk-... jh "List files" --workspace=<已存在目录>
jh --resume=<上次打印的 session=…> "继续"    # 会话按打印 id 续跑（seq 续接）
```

`jh` = `dist/jh` jlink 运行时镜像的 launcher（it13，见 [02 §Distribution 层](02-module-layout.md)）：解出即用，无需手拼 module-path。

## 8. 组合结果的人读视图：`AppBoot.dump(rows)`

`dump` 是 API 面纯函数（CLI 无对应 flag，见 §6）：一行一插件；`config` 非空则展开为 `{k=v}`（值已求值、含 `apikey` 的键脱敏为 `****`）；禁用行附 `(disabled: <expr>)`。任何一行都能被自己的 patch 替换——dump 是替换前的对照面。

```text
# 节选（base 组合，行序 = 装载序）
session-store
persistence-jsonl {root=/home/you/.harness/sessions}
llm-openai-compat {name=deepseek, baseUrl=https://api.deepseek.com}
approval-ask  (disabled: true)
tools
shell-docker  (disabled: true)
agent-loop {cwd=/path/to/workspace}
```

外部嵌入的自证即用它打印"组成了什么"（`integration/consumer-sample` 的 `SelfCheck`：`compose` → `dump`），`--verify` 另印治理摘要五行（§6）。

## 9. 自定义示例：换 shell provider / 改沙箱档

```yaml
# ~/.harness/profiles/headless/patch.yml —— 环境级隔离：本机 bash → docker 容器
# （与 CLI `--docker [--image=…]` 的 overlay 等价，it12.5/it13）
rows:
  - plugin: shell-bash-local
    disabled: true                    # 同一 seam 只留一个 ShellExecutor
  - plugin: shell-docker
    config:
      image: ubuntu:24.04             # 挂载面即可写面：容器根恒只读
```

```yaml
# 改沙箱档：sandbox 配置在 sandbox-policy 行，不在 shell provider 行
rows:
  - plugin: sandbox-policy
    config:
      mode: danger-full-access        # 三档词表：read-only / workspace-write / danger-full-access
      workspace: ${cwd}               # 授予面：与 fs 围栏 / shell workspace 同一目录
```

注意：patch 锚点是 **plugin id**，不带标记即替换整行（新行接管启用态与 config），不需要换类名。换 provider 是行引用另一个已发现的 plugin id（jar 经 profile `plugins` 或 `--module-path` 进入发现面）——但**只有被行引用才挂载**（§5 双向校验）。Shell provider 管「在哪执行」，沙箱档管「能写哪」——分属两个 seam 的两行配置各归各行，不叠在 shell provider 的 config 里。

## 10. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Profile（named composition） | `~/.harness/profiles/<name>/profile.yml` | 同 |
| Bundle（dsh.bundle patch file） | `META-INF/harness/bundle.yml`（classpath 资源，资源名固定；`name:` 声明包名） | 同语义；引用写 name（§1） |
| row 引用插件（path/类名） | **row 引用 plugin id**，ServiceLoader 发现 | JPMS 下类名不可反射 |
| 隐式发现即挂载 | **双向显式组合**（发现 ⊇ 引用且引用 ⊇ 发现的已启用集）| 收紧：发现≠组合 |
| row `id` patch 锚点 | 锚点 = plugin id | 少一个字段 |
| `replace` / `disabled` / `remove` | 同 | |
| `!!js` 表达式 | `${env:...}` 插值 + `==`/`!=` 比较 | 收紧（无任意代码） |
| `dsh --profile X --dump-config` | **无对应 CLI flag**：dump 收在 API（`AppBoot.dump`，§8），CLI 面只有 `--verify` + policy 档位 | 收紧：CLI 面以 12 §6 冻结清单为准 |
| `dsh-base` 首层 | `harness-bundle-base` | 同 |
