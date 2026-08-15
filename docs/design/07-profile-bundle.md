# 07 · Profile / Bundle / Patch — 配置即组合

一个运行中的 JH 是一棵**有序分层叠加**的插件树。每一层可以替换、插入、禁用上一层的行。这让"换一个 provider 就换一个产品形态"成为配置决策，而非代码修改。

## 1. 三层组合模型

```
空 plugin 列表
  ↓ 各 Bundle 按 Profile 列出的顺序叠加（每个 Bundle 贡献若干 plugin 行）
  ↓ Profile 的 cordis.patch.yml（用户级 patch）
  ↓ Home 级 cordis.patch.yml（~/.harness/patch.yml）
  ↓ --patch 命令行 overlay（一次性）
= 最终 plugin 树（可 dump，可替换任意一行）
```

### Profile

一个**命名组合**，存储在 Harness home（`~/.harness/profiles/<name>/`）：
- 列出它 stack 的 bundles
- 持有 out-of-tree 插件
- 用户的 `cordis.patch.yml`

```yaml
# ~/.harness/profiles/web/profile.yml
name: web
description: Browser-based agent
bundles:
  - io.deepseek:harness-bundle-base:0.1.0
  - io.deepseek:harness-bundle-webapp:0.1.0    # 留接口，MVP 不实现
plugins: []                                      # out-of-tree 插件（classpath jar）
```

### Bundle

一个**发行格式**：一组 cordis.yml 配置行 + 它们挂载的代码（JPMS 模块 jar）。一个 Bundle 声明自己的 patch 文件：

```yaml
# harness-bundle-base 的 META-INF/harness/bundle.yml
name: base
description: First layer of every profile
patches: base-patch.yml                          # 同 jar 内的 patch 文件
```

```yaml
# harness-bundle-base 的 META-INF/harness/base-patch.yml
# 这是 base bundle 贡献的 plugin 行
rows:
  - id: llm-deepseek
    plugin: io.dsh.llm.deepseek.DeepSeekPlugin
    config:
      provider: deepseek
      baseUrl: ${env:DEEPSEEK_BASE_URL}
  - id: llm-replay
    plugin: io.dsh.llm.replay.ReplayPlugin
    disabled: ${env:DEEPSEEK_API_KEY} == null    # 有 key 时禁用 replay
  - id: fs-local
    plugin: io.dsh.fs.local.LocalFsPlugin
  - id: shell-bash-local
    plugin: io.dsh.shell.bash.local.BashLocalPlugin
  - id: persistence-jsonl
    plugin: io.dsh.session.persistence.jsonl.JsonlPlugin
    config:
      baseDir: ~/.harness/sessions
  - id: agent-loop
    plugin: io.dsh.core.agentloop.AgentLoopPlugin
```

### Patch

一个 **overlay**：按 id 替换整行，或插入新行。每一层 patch 作用于上一层的结果：

```yaml
# ~/.harness/profiles/web/cordis.patch.yml（用户级）
# 把 shell provider 换成 sandbox 版本
rows:
  - id: shell-bash-local           # 替换 base 的同名行
    replace: true
    plugin: io.dsh.shell.bash.sandbox.BashSandboxPlugin
    config:
      sandboxMode: landlock
      workspaceRoot: ${cwd}

  # 插入新行
  - id: my-custom-tool
    plugin: com.example.MyCustomToolPlugin
    after: agent-loop              # 插在 agent-loop 后
```

## 2. 行的合并语义

| Patch 字段 | 语义 |
|---|---|
| `id: X`（无 `replace`）| 若 id 已存在，**替换整行**；否则**追加** |
| `id: X, replace: true` | 强制替换；id 不存在则 fail loud |
| `id: X, remove: true` | 移除该行；id 不存在则 fail loud |
| `id: X, disabled: true` | 保留行但禁用（不 apply）|
| `after: Y` / `before: Y` | 插入位置锚点 |

**fail loud 原则**（移植 dsh）：patch 目标 id 不存在 → 报错，不静默跳过。这让 typo 立即暴露。

## 3. 配置行结构

```yaml
- id: <唯一字符串>           # 必填，patch 锚点
  plugin: <全限定 Plugin 类名>  # 必填
  config:                     # 可选，传给 Plugin.apply 的配置
    <key>: <value>
  disabled: <表达式>          # 可选，true 则跳过此行
  isolate: <scope-realm>      # 可选，preset 内隔离域
  priority: <int>             # 可选，同插件排序（默认 0）
```

`config` 和 `disabled` 支持 `!!js` 等价的**表达式插值**：

```yaml
disabled: ${env:DEEPSEEK_API_KEY} == null
config:
  baseUrl: ${env:DEEPSEEK_BASE_URL:-https://api.deepseek.com}  # 默认值
  timeout: ${props:llm.timeout:-30000}
```

**插值变量源**（优先级高→低）：
1. `env:VAR` — 环境变量
2. `props:KEY` — Java system property（`-D`）
3. `cwd` — 当前工作目录
4. `home` — `~/.harness`

> **安全**：表达式只支持插值和相等比较，**不支持任意代码执行**（dsh 的 `!!js` 在 loader context 下执行，JH 收紧为纯插值 + 比较）。

## 4. Boot 流程

```java
// io.dsh.boot.AppBoot
public final class AppBoot {

    public static FiberRuntime boot(BootOptions opts) throws Exception {
        // 1. 解析 profile（读取 ~/.harness/profiles/<name>/profile.yml）
        Profile profile = Profile.load(opts.profile());

        // 2. 加载所有 bundle 的 patch 行（按 profile 列出的顺序）
        List<ConfigRow> rows = new ArrayList<>();
        for (BundleRef bundle : profile.bundles()) {
            rows.addAll(BundleLoader.loadRows(bundle));
        }

        // 3. 应用 profile patch
        rows = applyPatch(rows, profile.patch());

        // 4. 应用 home patch（~/.harness/patch.yml）
        Path homePatch = Paths.get(System.getProperty("user.home"), ".harness", "patch.yml");
        if (Files.exists(homePatch)) {
            rows = applyPatch(rows, Patch.load(homePatch));
        }

        // 5. 应用 --patch overlay（可多个，按顺序）
        for (Path p : opts.patches()) {
            rows = applyPatch(rows, Patch.load(p));
        }

        // 6. 插值变量 + disabled 求值
        rows = resolveExpressions(rows);

        // 7. dump-config（若请求）
        if (opts.dumpConfig()) {
            printConfig(rows);
            return null;
        }

        // 8. 创建 runtime + 加载 plugin
        FiberRuntime runtime = new FiberRuntime();
        Context rootCtx = runtime.rootContext();

        // 确保 kernel 核心服务（SessionStore、Events 等已由 FiberRuntime 装好）
        // 按 rows 顺序实例化 + apply 每个 plugin
        for (ConfigRow row : rows) {
            if (row.disabled()) continue;
            Class<?> cls = Class.forName(row.plugin());
            Plugin plugin = (Plugin) cls.getDeclaredConstructor().newInstance();
            Context child = rootCtx.child();
            if (row.config() != null) {
                child.provide(ConfigService.KEY.forPlugin(row.id()), row.config());
            }
            plugin.apply(child);
        }

        return runtime;
    }
}
```

## 5. Headless Profile（MVP 默认）

最简的可用 profile——一次性 runner，无 server：

```yaml
# ~/.harness/profiles/headless/profile.yml（模板）
name: headless
description: One-shot runner, no server
bundles:
  - io.deepseek:harness-bundle-base:0.1.0
```

`harness-bundle-base` 的行已经在 §1 给出。headless 只 stack 这一个 bundle。

### Headless Runner 入口

```java
// io.dsh.headless.HeadlessMain —— examples/headless 模块
public final class HeadlessMain {

    public static void main(String[] args) throws Exception {
        Options opts = parseArgs(args);
        // args: [--profile headless] [--dump-config] [--patch x.yml] "<task>"

        try (FiberRuntime runtime = AppBoot.boot(opts)) {
            if (runtime == null) return;  // dump-config 已打印后退出

            Context ctx = runtime.rootContext();
            AgentRegistry agents = ctx.get(AgentRegistry.KEY);
            SessionStore sessions = ctx.get(SessionStore.KEY);

            // 创建一个 agent
            SessionId sid = SessionId.of("headless-" + System.currentTimeMillis());
            AgentHandle handle = agents.create(ctx, CreateAgentOptions.builder()
                .sessionId(sid)
                .sessionOptions(CreateSessionOptions.builder()
                    .cwd(Path.of(System.getProperty("user.dir")))
                    .build())
                .agentOptions(AgentOptions.builder()
                    .provider("deepseek")
                    .model(opts.model().orElse("deepseek-chat"))
                    .build())
                .setup(agentCtx -> { /* 可挂 preset */ })
                .build()).join();

            Agent agent = handle.agent();

            // 发送任务
            agent.followup(UserMessage.of(opts.task(), MessageSource.human()));

            // 等完成
            agent.whenIdle().join();

            // 打印最终 assistant 消息
            List<Message> history = agent.session().deriveMessages();
            history.stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> ((AssistantMessage) m).content())
                .reduce((a, b) -> b)  // 最后一条
                .ifPresent(System.out::println);

            handle.dispose().join();
        }
    }
}
```

运行：
```sh
# 打印最终 plugin 树
java -jar headless.jar --profile headless --dump-config

# 跑一个任务
DEEPSEEK_API_KEY=sk-... java -jar headless.jar --profile headless "List files in the current directory"
```

## 6. dump-config 输出示例

```
Profile: headless
Bundles: harness-bundle-base@0.1.0
Patches: ~/.harness/patch.yml (absent)

Effective plugin tree:
  #0  llm-deepseek       io.dsh.llm.deepseek.DeepSeekPlugin        provider=deepseek
  #1  fs-local           io.dsh.fs.local.LocalFsPlugin
  #2  shell-bash-local   io.dsh.shell.bash.local.BashLocalPlugin
  #3  persistence-jsonl  io.dsh.session.persistence.jsonl.JsonlPlugin   baseDir=~/.harness/sessions
  #4  agent-loop         io.dsh.core.agentloop.AgentLoopPlugin

# Any row above can be replaced by a patch of your own.
```

## 7. 自定义示例：换 sandbox provider

用户想在 headless profile 下把 bash 换成 sandbox 版本，不写代码：

```yaml
# ~/.harness/profiles/headless/cordis.patch.yml
rows:
  - id: shell-bash-local
    replace: true
    plugin: io.dsh.shell.bash.sandbox.BashSandboxPlugin
    config:
      sandboxMode: landlock
      workspaceRoot: ${cwd}
```

确保 `harness-shell-bash-sandbox` jar 在 classpath（通过 profile 的 `plugins` 或命令行 `-cp`）。

## 8. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Profile（named composition） | `~/.harness/profiles/<name>/profile.yml` | 同 |
| Bundle（dsh.bundle patch file） | `META-INF/harness/<name>-patch.yml`（jar 内） | 同语义 |
| `cordis.patch.yml`（profile 级） | `~/.harness/profiles/<name>/cordis.patch.yml` | 同 |
| home 级 `cordis.patch.yml` | `~/.harness/patch.yml` | 同 |
| `--patch` overlay | `--patch x.yml`（可多个） | 同 |
| row `id` patch 锚点 | 同 | |
| `replace` / `disabled` / `remove` | 同 | |
| `!!js` 表达式 | `${env:...}` / `${props:...}` 插值 + 相等比较 | **收紧**（无任意代码） |
| `dsh --profile X --dump-config` | `jh --profile X --dump-config` | 同 |
| ServiceLoader 发现 Plugin | ServiceLoader + `provides Plugin with` | JPMS 原生 |
| `dsh-base` 首层 | `harness-bundle-base` | 同 |
| `dsh-headless` | `harness-bundle-headless` + `examples/headless` | 同 |

## 9. 表达式求值（受限）

```java
// io.dsh.kernel.config.ExpressionResolver
public final class ExpressionResolver {

    private static final Pattern EXPR = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * 解析 ${...} 插值。
     * 支持的变量源：env:, props:, 内置 cwd/home。
     * 支持的运算：:-（默认值）、==（相等比较，返回 boolean）。
     * 不支持任意代码（安全收紧）。
     */
    public static Object resolve(String expr, Map<String, String> env, Properties sysProps) {
        // ${env:VAR:-default}
        // ${props:key}
        // ${env:KEY} == null
        // ${cwd}
        // ...
    }
}
```

**`disabled` 字段**在 loader context（所有 plugin 已发现、配置已合并）求值；**`config`** 在 plugin 自己的 context（`ctx.serviceName` 已激活）求值。对应 dsh 的 "Include preserves nested row expressions until target activation"。
