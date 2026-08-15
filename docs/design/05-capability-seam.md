# 05 · Capability Seam — 三角色范式

Capability Seam 是 dsh 最独特的工程范式：**一个可替换能力由三个角色组成**，通常分属三个模块。换一个 Provider，整个产品形态就变了。

```
Definition（声明接口 + ctx.<key>）
       ▲
       │ implements / registers into
       │
  Provider(s) ─── 提供具体实现（local / sandbox / e2b / replay）
       │
       │ consumed by
       ▼
   Consumer ──── 使用能力（通常是模型工具）
```

## 1. 三角色纪律（移植自 dsh）

| 角色 | 职责 | 模块命名 | JPMS 特征 |
|---|---|---|---|
| **Definition** | 声明 Service 接口、`ServiceKey`、词汇类型 | `harness.<cap>.<cap>` | `exports` 接口 + 类型 |
| **Provider** | 实现 Service 接口 | `harness.<cap>.<impl>` | `provides Plugin`，`requires Definition` |
| **Consumer** | 使用 Service（通常是 `ToolDefinition`）| `harness.<cap>.tool` | `requires Definition` + `tools` |

**三规则（移植自 dsh glossary）**：
1. 一个 seam 是完整的三角色，**绝不是单一角色**。
2. 只有当角色独立演进时才拆模块；一个包可拥有多角色（如 `llm.llm` 同时是 Definition 和 Consumer）。
3. Consumer 通过 `ctx.get(KEY)` 拿到 Service，**从不 import Provider 具体类**。

## 2. 通用模式：一个 seam 的 Java 骨架

### Definition

```java
// io.dsh.<cap>.<Cap>Service —— Definition 模块
package io.dsh.example;

import io.dsh.kernel.context.*;

public interface ExampleService {

    /** 全局服务键。Provider 注册到此键，Consumer 从此键查找。 */
    ServiceKey<ExampleService> KEY = new ServiceKey<>("example");

    /** 业务方法。 */
    String doSomething(String input);
}
```

### Provider

```java
// io.dsh.<cap>.local —— Provider 模块
package io.dsh.example.local;

public final class LocalExamplePlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        ctx.provide(ExampleService.KEY, new LocalExampleImpl());
    }

    static final class LocalExampleImpl implements ExampleService {
        @Override public String doSomething(String input) {
            return "local:" + input;
        }
    }
}
// META-INF/services/io.dsh.kernel.plugin.Plugin → io.dsh.example.local.LocalExamplePlugin
```

### Consumer

```java
// io.dsh.<cap>.tool —— Consumer 模块
public final class ExampleToolPlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        ExampleService svc = ctx.get(ExampleService.KEY);  // 从 Definition 查找，不 import Provider
        ToolRegistry tools = ctx.get(ToolRegistry.KEY);
        tools.register(ToolDefinition.builder("example")
            .description("Calls example service")
            .parameters(schema)
            .execute((args, exec) -> {
                String result = svc.doSomething(args.readString("input"));
                return ToolExecutionResult.success(result);
            })
            .build());
    }
}
```

**关键不变式**：Consumer 模块的 `module-info.java` **不 `requires` Provider 模块**，只 `requires Definition`。这样换 Provider 时 Consumer 完全不感知。组合时由 Bundle 决定 classpath 上放哪个 Provider。

---

## 3. 完整 Seam：LLM（模型适配器）

### Definition（`harness.llm.llm`）

```java
// io.dsh.llm.LlmService
package io.dsh.llm;

public interface LlmService {

    ServiceKey<LlmService> KEY = new ServiceKey<>("llm");

    /**
     * 流式调用模型。
     * @param config provider + model + 采样参数
     * @param request 消息历史 + system prompt + tool schemas
     * @param signal 取消信号
     * @return 流式 chunk 的 Flow.Publisher（Reactive Streams 标准）
     */
    Flow.Publisher<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal);

    /** 注册一个 provider adapter。 */
    Subscription registerAdapter(String provider, LlmAdapter adapter);
}
```

### 词汇类型（同模块）

```java
// io.dsh.llm —— 消息与流式词汇
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    List<ContentBlock> content();
}

public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock, ImageBlock {
    String type();
}
public record TextBlock(String text) implements ContentBlock { public String type() { return "text"; } }
public record ToolUseBlock(CallId id, String name, String arguments) implements ContentBlock { ... }
public record ToolResultBlock(CallId toolUseId, String content, boolean isError) implements ContentBlock { ... }
public record ImageBlock(String mediaType, byte[] data) implements ContentBlock { ... }

// 流式 chunk（sealed，对应 dsh StreamChunk）
public sealed interface StreamChunk permits StreamChunk.Delta, StreamChunk.Usage, StreamChunk.Finish {
    record Delta(String text) implements StreamChunk {}        // 增量文本
    record DeltaToolUse(CallId id, String name, String argumentsDelta) implements StreamChunk {}  // 增量工具调用
    record Usage(TokenUsage usage) implements StreamChunk {}   // token 计费
    record Finish(FinishReason reason) implements StreamChunk {} // 结束原因
}

public enum FinishReason { STOP, LENGTH, TOOL_USE, CONTENT_FILTER }
```

### Provider（`harness.llm.deepseek`）

```java
// io.dsh.llm.deepseek.DeepSeekPlugin
package io.dsh.llm.deepseek;

public final class DeepSeekPlugin implements Plugin {

    @Override
    public void apply(Context ctx) {
        LlmService llm = ctx.get(LlmService.KEY);
        CredentialsService creds = ctx.get(CredentialsService.KEY);

        ctx.provide(DeepSeekConfig.KEY, new DeepSeekConfig());  // 从 cordis.yml 读
        Subscription sub = llm.registerAdapter("deepseek", new DeepSeekAdapter(creds));
        ctx.addCloseable(sub::close);
    }
}

final class DeepSeekAdapter implements LlmAdapter {

    @Override
    public Flow.Publisher<StreamChunk> stream(
            LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        return subscriber -> {
            // 在虚拟线程上发 SSE 请求
            Thread.startVirtualThread(() -> {
                try {
                    var httpRequest = buildSseRequest(config, request);
                    var response = httpClient.send(httpRequest, BodyHandlers.ofLines());
                    response.body().forEachRemaining(line -> {
                        signal.checkAbort();
                        StreamChunk chunk = parseSseLine(line);
                        if (chunk != null) subscriber.onNext(chunk);
                    });
                    subscriber.onComplete();
                } catch (AbortedException e) {
                    subscriber.onComplete();  // 取消 = 正常结束
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            });
        };
    }
}
```

### Consumer（agent-loop 内部）

agent-loop 通过 `ctx.get(LlmService.KEY)` 拿到 LLM，调 `stream()`，订阅 chunk 流，边收边 append `assistant/chunk` 到 session log。**agent-loop 不 import 任何 Provider**。

---

## 4. 完整 Seam：FS（文件系统）

### Definition（`harness.fs.fs`）

```java
// io.dsh.fs.FsService
package io.dsh.fs;

public interface FsService {

    ServiceKey<FsService> KEY = new ServiceKey<>("fs");

    /** 读文件。 */
    CompletableFuture<String> read(Path path);

    /** 写文件（覆盖）。 */
    CompletableFuture<Void> write(Path path, String content);

    /** 编辑文件（字符串替换，唯一匹配）。 */
    CompletableFuture<String> edit(Path path, String oldString, String newString);

    /** 删除文件。 */
    CompletableFuture<Void> delete(Path path);

    /** 列目录。 */
    CompletableFuture<List<DirEntry>> list(Path path);
}
```

### Provider（`harness.fs.local`）

```java
public final class LocalFsPlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        ctx.provide(FsService.KEY, new LocalFs());
    }
}

final class LocalFs implements FsService {
    @Override public CompletableFuture<String> read(Path p) {
        return CompletableFuture.supplyAsync(() -> {
            try { return Files.readString(p); }
            catch (IOException e) { throw new CompletionException(e); }
        });
    }
    // write/edit/delete/list 类似，Files.* API
}
```

### Consumer（`harness.fs.tool`）

```java
public final class FsToolPlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        FsService fs = ctx.get(FsService.KEY);
        ToolRegistry tools = ctx.get(ToolRegistry.KEY);

        tools.register(defineRead(fs));
        tools.register(defineWrite(fs));
        tools.register(defineEdit(fs));
    }

    private ToolDefinition defineRead(FsService fs) {
        return ToolDefinition.builder("fs_read")
            .description("Read a file from the filesystem")
            .parameters(ValueSchema.object(
                "path", ValueSchema.string().description("Absolute file path")
            ))
            .execute((args, exec) -> fs.read(Path.of(args.readString("path")))
                .thenApply(ToolExecutionResult::success))
            .render(RenderIntent.text())   // UI 渲染意图：纯文本
            .build();
    }
}
```

---

## 5. 完整 Seam：Shell（命令执行）

### Definition（`harness.shell.shell`）

```java
// io.dsh.shell.ShellExecutor
package io.dsh.shell;

public interface ShellExecutor {

    ServiceKey<ShellExecutor> KEY = new ServiceKey<>("shell");

    /**
     * 执行一条 shell 命令。
     * @param request 命令 + cwd + 超时 + 环境变量
     * @param signal 取消信号（kill 子进程）
     */
    CompletableFuture<ShellResult> execute(ShellRequest request, AbortSignal signal);
}

public record ShellRequest(
    String command,
    Path cwd,
    Duration timeout,
    Map<String, String> env
) {}

public record ShellResult(
    int exitCode,
    String stdout,
    String stderr,
    Duration duration
) {}
```

### Provider（`harness.shell.bash-local`）

```java
public final class BashLocalPlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        ctx.provide(ShellExecutor.KEY, new LocalBashExecutor());
    }
}

final class LocalBashExecutor implements ShellExecutor {
    @Override
    public CompletableFuture<ShellResult> execute(ShellRequest req, AbortSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", req.command())
                    .directory(req.cwd().toFile())
                    .redirectErrorStream(false);
                pb.environment().putAll(req.env());
                Process p = pb.start();

                // 注册取消 → kill
                signal.controller().addListener(() -> p.destroyForcibly());

                boolean finished = p.waitFor(req.timeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) { p.destroyForcibly(); throw new TimeoutException(); }

                String out = new String(p.getInputStream().readAllBytes());
                String err = new String(p.getErrorStream().readAllBytes());
                return new ShellResult(p.exitValue(), out, err, Duration.ZERO);
            } catch (Exception e) { throw new CompletionException(e); }
        }, Thread.ofVirtual().factory());
    }
}
```

### Consumer（`harness.shell.tool`）

```java
public final class BashToolPlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        ShellExecutor shell = ctx.get(ShellExecutor.KEY);
        ToolRegistry tools = ctx.get(ToolRegistry.KEY);
        // 可选：审批
        ApprovalService approval = ctx.getIfPresent(ApprovalService.KEY).orElse(null);

        tools.register(ToolDefinition.builder("bash")
            .description("Execute a bash command")
            .parameters(ValueSchema.object(
                "command", ValueSchema.string().description("The bash command"),
                "timeout_ms", ValueSchema.integer().optional().description("Max execution time")
            ))
            .execute((args, exec) -> {
                // 审批（若配置）
                if (approval != null) {
                    approval.require(exec.initiator(), "bash: " + args.readString("command"));
                }
                ShellRequest sr = new ShellRequest(
                    args.readString("command"),
                    exec.cwd(),
                    Duration.ofMillis(args.readInt("timeout_ms", 30000)),
                    Map.of()
                );
                return shell.execute(sr, exec.signal())
                    .thenApply(r -> ToolExecutionResult.success(
                        "exit: " + r.exitCode() + "\n" + r.stdout(),
                        r.exitCode() != 0
                    ));
            })
            .render(RenderIntent.terminal())  // UI：终端样式
            .build());
    }
}
```

---

## 6. 完整 Seam：Sandbox（进程沙箱）

### Definition

```java
// io.dsh.sandbox.SandboxProvider
package io.dsh.sandbox;

public interface SandboxProvider {

    ServiceKey<SandboxProvider> KEY = new ServiceKey<>("sandbox");

    /**
     * 包装 argv 在 per-call 沙箱策略下。
     * Consumer（bash/terminal/fs）spawn 前调此方法，拿到包装后的 argv + 沙箱句柄。
     */
    SandboxedProcess wrap(ProcessArgv argv, SandboxPolicy policy);

    record SandboxedProcess(
        List<String> wrappedArgv,
        Map<String, String> envOverrides,
        AutoCloseable handle  // spawn 后 close 释放沙箱资源
    ) {}
}

public record SandboxPolicy(
    SandboxMode mode,    // OFF / LANDLOCK / CONTAINER
    Path workspaceRoot   // 限制访问到此目录
) {}

public enum SandboxMode { OFF, LANDLOCK, CONTAINER }
```

### Provider（`harness.sandbox.local`，MVP stub）

```java
// MVP：mode=OFF 直接透传，mode=LANDLOCK 留接口不实现
public final class LocalSandboxPlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        SandboxPolicy policy = ctx.get(SandboxPolicyService.KEY).current();
        ctx.provide(SandboxProvider.KEY, new LocalSandbox(policy));
    }
}

final class LocalSandbox implements SandboxProvider {
    private final SandboxPolicy policy;
    @Override
    public SandboxedProcess wrap(ProcessArgv argv, SandboxPolicy p) {
        if (p.mode() == SandboxMode.OFF) {
            return new SandboxedProcess(argv.argv(), Map.of(), () -> {});
        }
        throw new UnsupportedOperationException("Sandbox mode " + p.mode() + " not implemented in MVP");
    }
}
```

> 未来可加 `harness.sandbox.landlock`（JNI 调 Linux Landlock）或 `harness.sandbox.docker`，不改任何 Consumer。

---

## 7. Session Persistence Seam

### Definition

```java
// io.dsh.session.persistence.SessionPersistence
public interface SessionPersistence {

    ServiceKey<SessionPersistence> KEY = new ServiceKey<>("sessionPersistence");

    /** 持久化一个 session 的 header + events。 */
    CompletableFuture<Void> save(Session session);

    /** 加载持久化的 session。 */
    CompletableFuture<Session> load(SessionId id);

    /** 列出所有已持久化的 session id。 */
    CompletableFuture<List<SessionId>> list();
}
```

### Provider（`harness.session.persistence-jsonl`）

见 [03-session-event-sourcing.md §6](03-session-event-sourcing.md)。JSONL 实现。

### Consumer

agent-loop 不直接消费 persistence。而是：
1. 一个 `PersistencePlugin` 订阅 `session/event`（emit），buffer 后台 batch 写盘。
2. 订阅 `session/flush`（parallel），await 写盘完成（同步 barrier）。
3. agent-loop resume 时通过 `ctx.get(SessionPersistence.KEY).load(id)` 加载。

```java
public final class PersistencePlugin implements Plugin {
    @Override
    public void apply(Context ctx) {
        SessionPersistence backend = ctx.get(SessionPersistence.KEY);
        SessionStore store = ctx.get(SessionStore.KEY);

        // 订阅 session/event，buffer 写盘
        ctx.onGlobal(SessionEvents.EVENT, (carrier, payload) -> {
            Session session = (Session) carrier;
            SessionEvent event = (SessionEvent) payload;
            if (event.seq() >= session.firstLiveSeq()) {  // 只持久化 live 事件
                buffer(session.id(), event);
            }
        });

        // flush barrier
        ctx.onGlobal(SessionEvents.FLUSH, (carrier, payload) -> {
            Session session = (Session) carrier;
            return flushBuffer(session.id()).thenCompose(v -> backend.save(session));
        });
    }
}
```

---

## 8. ToolRegistry — 工具注册与执行 pipeline

### Definition（`harness.core.tools`）

```java
// io.dsh.core.tools.ToolRegistry
public interface ToolRegistry {

    ServiceKey<ToolRegistry> KEY = new ServiceKey<>("tools");

    /** 注册一个工具。返回 Subscription，close 即注销。 */
    Subscription register(ToolDefinition tool);

    /** 当前可见的工具 schema 列表（给 system prompt 组装用）。 */
    List<ToolSchema> schemas(ScopeKey scope);

    /** 执行一个工具调用（走完整 pipeline）。 */
    CompletableFuture<ToolExecutionResult> execute(ToolCall call, ToolExecutionContext ctx);
}
```

### ToolDefinition

```java
// io.dsh.core.tools.ToolDefinition
public record ToolDefinition(
    String name,
    String description,
    ValueSchema parameters,
    ToolExecutor executor,
    RenderIntent renderIntent,         // generic / terminal / diff / locations
    java.util.function.Function<ToolExecutionResult, Optional<UiNode>> presenter  // 纯函数
) {
    public static Builder builder(String name) { return new Builder(name); }
    public ToolSchema toSchema() { return new ToolSchema(name, description, parameters); }
}

@FunctionalInterface
public interface ToolExecutor {
    CompletableFuture<ToolExecutionResult> execute(ToolArgs args, ToolExecutionContext ctx)
        throws Exception;
}
```

### 执行 pipeline（对应 dsh tools/pre-execute → execute → post-execute）

```java
// io.dsh.core.tools.ToolRegistryImpl —— execute 实现
public CompletableFuture<ToolExecutionResult> execute(ToolCall call, ToolExecutionContext ctx) {
    // tools/pre-execute (waterfall)：可否决/改写
    return events.waterfall(ToolEvents.PRE_EXECUTE, ctx.initiator(),
        List.of(call, ctx),
        () -> proceedExecute(call, ctx)
    ).thenCompose(result ->
        // tools/post-execute (waterfall)：观察/改写结果（如 spill 大输出）
        events.waterfall(ToolEvents.POST_EXECUTE, ctx.initiator(),
            List.of(call, result),
            () -> CompletableFuture.completedFuture(result))
    );
}

private CompletableFuture<ToolExecutionResult> proceedExecute(ToolCall call, ToolExecutionContext ctx) {
    ToolDefinition tool = toolsByName.get(call.name());
    if (tool == null) {
        return CompletableFuture.completedFuture(
            ToolExecutionResult.error("Unknown tool: " + call.name()));
    }
    // monotonic guard（防止重复执行同一 callId）
    if (!executedCallIds.add(call.callId())) {
        return CompletableFuture.completedFuture(
            ToolExecutionResult.error("Duplicate call: " + call.callId()));
    }
    return tool.executor().execute(call.parsedArgs(), ctx);
}
```

### RenderIntent — UI 渲染意图（移植 dsh）

```java
public sealed interface RenderIntent {
    record generic() implements RenderIntent {}        // 通用文本
    record terminal() implements RenderIntent {}       // 终端输出样式
    record diff() implements RenderIntent {}           // 文件 diff
    record locations(List<Path> paths) implements RenderIntent {}  // 文件位置列表
}
```

**这是设计的一部分**（移植 dsh 原则："A tool's UI render intent is part of its design, decided up front"）。工具作者在定义工具时就声明渲染意图，UI 消费者据此选择渲染器。

---

## 9. 完整 Seam 清单（MVP + 留接口）

| Seam | Definition 模块 | MVP Provider(s) | Consumer | 状态 |
|---|---|---|---|---|
| LLM | `llm.llm` | `deepseek`, `replay` | agent-loop | ✅ |
| FS | `fs.fs` | `local` | `fs.tool` | ✅ |
| Shell | `shell.shell` | `bash-local` | `shell.tool` | ✅ |
| Sandbox | `sandbox.sandbox` | `local`(stub) | bash/terminal/fs | ✅ stub |
| Session Persistence | `session.persistence` | `jsonl` | persistence plugin | ✅ |
| Approval | `interaction.approval` | `auto`(stub) | tools, bash tool | ✅ stub |
| Commands | `interaction.commands` | — | headless | ✅ stub |
| Subagent | `subagent.subagent` | —（留接口）| tool-subagent | 🔌 接口 |
| Web | `web.web` | —（留接口）| tool-web | 🔌 接口 |
| LSP | `lsp.lsp` | —（留接口）| tool-lsp | 🔌 接口 |
| Terminal | `terminal.terminal` | —（留接口）| tool-terminal | 🔌 接口 |
| Compaction | `compaction.compaction` | —（留接口）| — | 🔌 接口 |

留接口的 seam：Definition 模块完整定义接口和 `ServiceKey`，但**不提供任何 Provider**。组合时不挂载这些 seam 即可；未来加 Provider 不改任何 Consumer。

## 10. 三角色纪律的编译期保障

JPMS 让"Consumer 不 import Provider"成为**编译期约束**：

```java
// harness.fs.tool 的 module-info.java
module io.deepseek.harness.fs.tool {
    requires io.deepseek.harness.fs.fs;        // ✅ Definition
    requires io.deepseek.harness.core.tools;   // ✅ Consumer 依赖
    // 注意：没有 requires io.deepseek.harness.fs.local  ← Provider！
    provides io.dsh.kernel.plugin.Plugin with io.dsh.fs.tool.FsToolPlugin;
}
```

如果 FsToolPlugin 意外 `import io.dsh.fs.local.LocalFs`，**编译失败**（`fs.tool` 模块不 `requires fs.local`）。这是比 dsh（靠约定 + ESLint）更强的隔离。

## 11. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Service Definition (abstract class/registry) | Java interface + `ServiceKey<T>` | |
| Service Provider (concrete impl) | `implements` + `Plugin.apply` 注册 | |
| Consumer (inject service) | `ctx.get(KEY)` | 不 import Provider |
| 三角色纪律靠约定 | JPMS `requires` 编译期保障 | **更强** |
| `ToolDefinition` + `defineTool` DSL | `ToolDefinition.builder()` | |
| `tools/pre-execute` waterfall | `ToolEvents.PRE_EXECUTE` waterfall | |
| `tools/post-execute` waterfall | `ToolEvents.POST_EXECUTE` waterfall | |
| `RenderIntent`（generic/terminal/diff/locations） | `sealed interface RenderIntent` | 设计期声明 |
| capability graph（生成图）| Maven plugin 或脚本扫描 `module-info.java` 生成 | 可选 |
