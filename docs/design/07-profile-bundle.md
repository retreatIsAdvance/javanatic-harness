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

### Profile

一个**命名组合**，存储在 Harness home（`~/.harness/profiles/<name>/`）：

```yaml
# ~/.harness/profiles/prod/profile.yml
name: prod
description: Production composition
policy: production          # 治理档位（见 §6；缺省 standard）
bundles:
  - io.javanatic:harness-bundle-base:0.1.0
plugins: []                 # out-of-tree 插件（classpath/module-path 追加）
```

### Bundle

一个**发行格式**：一组行 + 挂载的代码（JPMS 模块 jar）。Bundle 声明自己的 patch 文件：

```yaml
# harness-bundle-base 的 META-INF/harness/bundle.yml
name: base
description: First layer of every profile
patches: base-patch.yml
```

```yaml
# harness-bundle-base 的 META-INF/harness/base-patch.yml
# 行引用 plugin id（不是类名——JPMS 下类名不可跨模块反射访问）。
# id 由 Plugin.id() 声明，ServiceLoader 发现后按 id 匹配。
rows:
  - plugin: agent-loop
  - plugin: session-store
  - plugin: llm
  - plugin: llm-deepseek
    config:
      baseUrl: ${env:DEEPSEEK_BASE_URL:-https://api.deepseek.com}
  - plugin: llm-replay
    disabled: ${env:DEEPSEEK_API_KEY} != null   # 有 key 时禁用 replay（走真实 provider）
  - plugin: fs-local
  - plugin: shell-bash-local
  - plugin: persistence-jsonl
    config:
      baseDir: ~/.harness/sessions
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

        // 7. dump-config：打印组合结果后退出（不加载任何插件）
        if (opts.dumpConfig()) { printConfig(rows); return null; }

        // 8. 双向显式组合校验（ServiceLoader 发现 ↔ 行引用，两侧都 fail loud）：
        //    a) 行引用的 id 必须存在——typo/缺 jar 立即暴露；
        //    b) 被发现的插件必须被某行引用——不做隐式挂载（发现≠组合）。
        PluginLoader loader = new PluginLoader();
        Map<String, Plugin> discovered = loader.discover();
        verifyComposition(discovered, rows);          // 双向，见上

        // 9. Runtime + ConfigService + 组合清单
        Runtime runtime = new Runtime();
        Scope root = runtime.root();
        root.provide(ConfigService.KEY, configServiceFrom(rows));
        root.provide(CompositionManifest.KEY, manifestFrom(rows));   // R1：进 SessionHeader

        // 10. 按 rows 顺序加载（每插件子 scope，失败原子回滚——01 §7 R3）
        loader.loadAll(root, rows.stream().map(r -> discovered.get(r.plugin())).toList());

        // 11. 治理验证（R4）：构造期已强制（loop/executor 构造器），此处断言档位
        if (opts.verify()) { verifyGovernance(root, policyOf(opts)); }

        return runtime;
    }
}
```

**为什么双向校验**：只查 (a) 会留下"jar 在 classpath 上就悄悄挂载"的隐式行为——这正是 dsh "never silently skip a missing referent" 的反面（never silently mount an unreferenced plugin）。显式组合 = 行之外无挂载，行之内无缺失。

**CompositionManifest**（R1，03 §8）：boot 从行序 + 插件版本（module descriptor）+ 影响模型可见输出的 config 值生成清单，注册为服务；`session-store` 创建 SessionHeader 时消费它。清单 + 日志 = 可重建性的全部持久化事实。

## 6. `--verify` 与 policy 档位（R4）

治理挂载的证明分两层（04 §4 已述类型层）：**类型层**让"没挂治理"组装不出系统；**配置层**回答"挂的是哪个实现、够不够这个档位"。

```sh
jh --profile prod --verify
```

```text
Profile: prod   policy: production
  composition:  8 rows, 8 discovered, 0 unreferenced        ✓
  approval:     HUMAN_GATE (approval-ask)                  ✓ policy 禁止 AUTO
  audit:        jsonl ~/.harness/sessions (durable)        ✓
  stop:         max-turns=50 max-steps=200 budget=2000000  ✓
exit 0
```

| 档位 | 校验内容 |
|---|---|
| `standard`（缺省）| 组合双向校验通过；治理服务在装配期已强制（构造器注入） |
| `production` | 另加三条：`ApprovalService.mode() != AUTO`；持久化实现存在且 durable；`LoopGuard.limits()` 的 max-turns / max-steps / budget 全部非零 |

- `--verify` **不创建 agent、不需要 API key**，组合 + 断言后以 exit 0/1 退出——进 CI，机器可检查。
- 治理摘要来自实现自述：`ApprovalService.mode()`（`AUTO` / `HUMAN_GATE` / `DENY_ALL`）、`SessionPersistence.durable()`、`LoopGuard.limits()`。实现类如实报告，档位校验负责拒绝不合格组合（fail loud at boot）。
- `--dump-config` 回答"组成了什么"；`--verify` 回答"治理够不够"。两个都是纯组合期操作。

## 7. Headless Profile（MVP 默认）

```yaml
# ~/.harness/profiles/headless/profile.yml（模板）
name: headless
description: One-shot runner, no server
bundles:
  - io.javanatic:harness-bundle-base:0.1.0
```

### Headless Runner 入口

```java
// io.javanatic.harness.headless.HeadlessMain —— examples/headless 模块
public final class HeadlessMain {

    public static void main(String[] args) throws Exception {
        Options opts = parseArgs(args);   // [--profile X] [--dump-config] [--verify] [--patch f.yml] "<task>"

        try (Runtime runtime = AppBoot.boot(opts)) {
            if (runtime == null) return;  // dump-config / verify-only 已处理完退出

            Scope root = runtime.root();
            AgentRegistry agents = root.require(AgentRegistry.KEY);

            SessionId sid = SessionId.of("headless-" + System.currentTimeMillis());
            AgentHandle handle = agents.create(root, CreateAgentOptions.builder()
                .sessionId(sid)
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
java -jar headless.jar --profile headless --dump-config          # 组合结果
java -jar headless.jar --profile headless --verify               # 治理断言（无 key 可跑）
DEEPSEEK_API_KEY=sk-... java -jar headless.jar --profile headless "List files"
```

## 8. dump-config 输出示例

```text
Profile: headless   policy: standard
Bundles: harness-bundle-base@0.1.0
Patches: ~/.harness/patch.yml (absent)

Effective plugin rows:
  #0  agent-loop         [core]
  #1  session-store      [core]
  #2  llm                [core]
  #3  llm-deepseek       baseUrl=${DEEPSEEK_BASE_URL:-https://api.deepseek.com}
  #4  llm-replay         disabled (DEEPSEEK_API_KEY present)
  #5  fs-local           [core]
  #6  shell-bash-local   [core]
  #7  persistence-jsonl  baseDir=~/.harness/sessions

# Any row above can be replaced by a patch of your own.
```

## 9. 自定义示例：换 sandbox provider

```yaml
# ~/.harness/profiles/headless/patch.yml
rows:
  - plugin: shell-bash-local
    replace: true
    config:
      sandboxMode: landlock
      workspaceRoot: ${cwd}
```

注意：替换的是**配置**（同一插件 id 的 config 覆盖），不需要换类名。真正换 provider 时，patch 引用另一个已发现的 plugin id（如 `shell-bash-sandbox`），jar 经 profile `plugins` 或 `--module-path` 进入发现面——但**只有被行引用才挂载**（§5 双向校验）。

## 10. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Profile（named composition） | `~/.harness/profiles/<name>/profile.yml` | 同 |
| Bundle（dsh.bundle patch file） | `META-INF/harness/<name>-patch.yml` | 同语义 |
| row 引用插件（path/类名） | **row 引用 plugin id**，ServiceLoader 发现 | JPMS 下类名不可反射 |
| 隐式发现即挂载 | **双向显式组合**（发现 ⊇ 引用且引用 ⊇ 发现的已启用集）| 收紧：发现≠组合 |
| row `id` patch 锚点 | 锚点 = plugin id | 少一个字段 |
| `replace` / `disabled` / `remove` | 同 | |
| `!!js` 表达式 | `${env:...}` 插值 + `==`/`!=` 比较 | 收紧（无任意代码） |
| `dsh --profile X --dump-config` | 同 + **`--verify` + policy 档位** | R4 新增 |
| `dsh-base` 首层 | `harness-bundle-base` | 同 |
