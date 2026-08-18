# 05 · Capability Seam — 三角色范式

Capability Seam 是 dsh 最独特的工程范式：**一个可替换能力由三个角色组成**，通常分属三个模块。换一个 Provider，整个产品形态就变了。

```
Definition（声明接口 + ServiceKey）
       ▲
       │ implements / registers into
       │
  Provider(s) ─── 提供具体实现（local / sandbox / replay）
       │
       │ consumed by
       ▼
   Consumer ──── 使用能力（通常是模型工具）
```

本篇是不变式 **R2（执行一致性）** 的主要载体（§8）。

## 1. 三角色纪律（移植自 dsh）

| 角色 | 职责 | 模块命名 | JPMS 特征 |
|---|---|---|---|
| **Definition** | 声明 Service 接口、`ServiceKey`、词汇类型 | `harness.<cap>.<cap>` | `exports` 接口 + 类型 |
| **Provider** | 实现 Service 接口 | `harness.<cap>.<impl>` | `provides Plugin`，`requires Definition` |
| **Consumer** | 使用 Service（通常是 `ToolDefinition`）| `harness.<cap>.tool` | `requires Definition` + tools |

**三规则**：一个 seam 是完整的三角色，绝不是单一角色；只有角色独立演进时才拆模块（如 llm 模块同时是 Definition 和默认 Provider）；Consumer 通过 `scope.require(KEY)` 拿到 Service，**从不 import Provider 具体类**。

## 2. 通用模式：一个 seam 的 Java 骨架

### Definition

```java
// io.javanatic.harness.example.ExampleService —— Definition 模块
package io.javanatic.harness.example;

public interface ExampleService {
    /** 全局服务键。Provider 注册到此键，Consumer 从此键查找。 */
    ServiceKey<ExampleService> KEY = new ServiceKey<>("example");

    /** 业务方法。阻塞签名（跑在虚拟线程上，见 §3 风格约定）。 */
    String doSomething(String input);
}
```

### Provider

```java
// io.javanatic.harness.example.local —— Provider 模块
public final class LocalExamplePlugin implements Plugin {
    @Override
    public String id() { return "example-local"; }

    @Override
    public void apply(Scope scope) {
        scope.provide(ExampleService.KEY, new LocalExampleImpl());
    }
}
// module-info: provides io.javanatic.harness.kernel.plugin.Plugin with LocalExamplePlugin;
```

### Consumer

```java
// io.javanatic.harness.example.tool —— Consumer 模块
public final class ExampleToolPlugin implements Plugin {
    @Override
    public String id() { return "example-tool"; }

    @Override
    public void apply(Scope scope) {
        ExampleService svc = scope.require(ExampleService.KEY);   // 从 Definition 查找，不 import Provider
        ToolRegistry tools = scope.require(ToolRegistry.KEY);
        tools.register(ToolDefinition.builder("example")
            .description("Calls example service")
            .parameters(schema)
            .execute((args, exec) ->
                ToolExecutionResult.success(svc.doSomething(args.readString("input"))))
            .build());
    }
}
```

**关键不变式**：Consumer 模块的 `module-info.java` **不 `requires` Provider 模块**。换 Provider 时 Consumer 完全不感知；组合时由 Bundle 行决定挂载谁（07）。

### 风格约定：阻塞签名，不要 CompletableFuture 包装

服务方法直接返回值/抛异常（阻塞语义），调用方跑在虚拟线程上（09）。`CompletableFuture` 只出现在确有 barrier 语义的地方（`whenIdle`、flush）。前版 `CompletableFuture<String> read(...)` 的包装没有收益——虚拟线程下 `String read(...)` 等价且少了组合噪音。

---

## 3. 完整 Seam：LLM（模型适配器）

### Definition + 默认 Provider（`harness.llm.llm`，plugin id `llm`）

```java
// io.javanatic.harness.llm.LlmService
public interface LlmService {

    ServiceKey<LlmService> KEY = new ServiceKey<>("llm");

    /**
     * 流式调用模型。阻塞 Stream：provider 在自己的虚拟线程上生产，
     * chunk 经有界阻塞队列传递（背压 = 队列容量，09 §9）；
     * consumer 的 forEach 阻塞读。try-with-resources 关闭即取消生产侧。
     */
    Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal);

    /** 注册一个 provider adapter（deepseek/replay 插件在 apply 里调用）。 */
    Disposable registerAdapter(String provider, LlmAdapter adapter);
}
```

`LlmPlugin`（id `llm`）提供 LlmService 默认实现（路由到已注册 adapter）；`llm-deepseek` / `llm-replay` 声明 `requires() = Set.of("llm")`，在 apply 里 `registerAdapter`——一个 Definition 常驻，多个 Provider 挂适配器，加载顺序由 requires 保证（01 §7）。

### 词汇类型（同模块）

```java
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    List<ContentBlock> content();
}

public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock, ImageBlock {
    String type();
}
public record TextBlock(String text) implements ContentBlock { public String type() { return "text"; } }
public record ToolUseBlock(CallId id, String name, String arguments) implements ContentBlock { /* ... */ }
public record ToolResultBlock(CallId toolUseId, String content, boolean isError) implements ContentBlock { /* ... */ }
public record ImageBlock(String mediaType, byte[] data) implements ContentBlock { /* ... */ }

/** 流式 chunk。permits 四个变体齐全（含增量工具调用——组装 tool_use 需要它）。 */
public sealed interface StreamChunk
    permits StreamChunk.Delta, StreamChunk.DeltaToolUse, StreamChunk.Usage, StreamChunk.Finish {
    record Delta(String text) implements StreamChunk {}
    record DeltaToolUse(CallId id, String name, String argumentsDelta) implements StreamChunk {}
    record Usage(TokenUsage usage) implements StreamChunk {}
    record Finish(FinishReason reason) implements StreamChunk {}
}

public enum FinishReason { STOP, LENGTH, TOOL_USE, CONTENT_FILTER }
```

### Provider（`harness.llm.deepseek`，plugin id `llm-deepseek`）

```java
public final class DeepSeekPlugin implements Plugin {
    @Override public String id() { return "llm-deepseek"; }
    @Override public Set<String> requires() { return Set.of("llm"); }

    @Override
    public void apply(Scope scope) {
        Map<String, Object> config = scope.require(ConfigService.KEY).configFor(id());
        DeepSeekOptions opts = DeepSeekOptions.from(config);   // record 构造器校验，fail loud
        CredentialsService creds = scope.require(CredentialsService.KEY);
        LlmService llm = scope.require(LlmService.KEY);
        scope.onClose(llm.registerAdapter("deepseek", new DeepSeekAdapter(opts, creds)));
    }
}
```

```java
final class DeepSeekAdapter implements LlmAdapter {
    /** producer 跑在专用虚拟线程上：SSE 逐行 → queue.put（满则挂起=背压）。 */
    @Override
    public Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        BlockingQueue<StreamChunk> queue = new ArrayBlockingQueue<>(64);
        CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
            try {
                var response = httpClient.send(buildSseRequest(config, request), BodyHandlers.ofLines());
                response.body().forEachRemaining(line -> {
                    signal.checkAbort();
                    StreamChunk chunk = parseSseLine(line);
                    if (chunk != null) queue.put(chunk);
                });
                queue.put(FIN);                       // 哨兵：正常结束
            } catch (AbortedException e) {
                queue.put(FIN);                       // 取消 = 正常结束（consumer 侧自查）
            } catch (Exception e) {
                queue.put(new ErrorMarker(e));        // 错误经流传递，consumer 侧抛出
            }
        }, virtualThreads);
        return StreamSupport.stream(new QueueSpliterator(queue, signal), false)
            .onClose(() -> producer.cancel(true));
    }
}
```

### Consumer（agent-loop 内部）

agent-loop 通过 `scope.require(LlmService.KEY)` 拿到 LLM，try-with-resources 消费阻塞流，边收边落账（04 §7）。**agent-loop 不 import 任何 Provider**。

---

## 4. 完整 Seam：FS（文件系统）

### Definition（`harness.fs.fs`）

```java
public interface FsService {
    ServiceKey<FsService> KEY = new ServiceKey<>("fs");

    String read(Path path) throws IOException;
    void write(Path path, String content) throws IOException;
    String edit(Path path, String oldString, String newString) throws IOException;
    void delete(Path path) throws IOException;
    List<DirEntry> list(Path path) throws IOException;
}
```

### Provider（`harness.fs.local`，plugin id `fs-local`）

`Files.*` 的直接包装，无并发包装（阻塞语义，虚拟线程下安全）。

### Consumer（`harness.fs.tool`，plugin id `fs-tool`）

```java
tools.register(ToolDefinition.builder("fs_read")
    .description("Read a file from the filesystem")
    .parameters(ValueSchema.object(
        "path", ValueSchema.string().description("Absolute file path")))
    .execute((args, exec) -> ToolExecutionResult.success(
        fs.read(Path.of(args.readString("path")))))
    .render(RenderIntent.text())
    .build());
```

注意 Consumer **不做审批**：审批是 ToolExecutor 的固定 stage（§8、R4），不是各工具的自觉。

---

## 5. 完整 Seam：Shell（命令执行）

### Definition（`harness.shell.shell`）

```java
public interface ShellExecutor {
    ServiceKey<ShellExecutor> KEY = new ServiceKey<>("shell");

    /** 执行一条 shell 命令。取消 → kill 子进程。 */
    ShellResult execute(ShellRequest request, AbortSignal signal) throws Exception;
}

public record ShellRequest(String command, Path cwd, Duration timeout, Map<String, String> env) {
    public ShellRequest {   // 构造器校验：fail loud at construction（08 §6）
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) throw new IllegalArgumentException("command empty");
        if (!cwd.isAbsolute()) throw new IllegalArgumentException("cwd must be absolute");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}

public record ShellResult(int exitCode, String stdout, String stderr, Duration duration) {}
```

### Provider（`harness.shell.bash-local`，plugin id `shell-bash-local`）

```java
final class LocalBashExecutor implements ShellExecutor {
    @Override
    public ShellResult execute(ShellRequest req, AbortSignal signal) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", req.command())
            .directory(req.cwd().toFile())
            .start();
        signal.controller().addListener(() -> p.destroyForcibly());   // 取消 → kill
        boolean finished = p.waitFor(req.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) { p.destroyForcibly(); throw new TimeoutException("bash timeout"); }
        return new ShellResult(p.exitValue(),
            new String(p.getInputStream().readAllBytes()),
            new String(p.getErrorStream().readAllBytes()), Duration.ZERO);
    }
}
```

### Consumer（`harness.shell.tool`，plugin id `shell-tool`）

工具 execute 里只有**显式 resolve**（默认值集中一处，08 §7）+ 委托 provider：

```java
.execute((args, exec) -> {
    ShellRequest sr = resolve(args, exec.cwd());   // timeout 默认 30s 在 resolve 里
    ShellResult r = shell.execute(sr, exec.signal());
    return ToolExecutionResult.success(
        "exit: " + r.exitCode() + "\n" + r.stdout(), r.exitCode() != 0);
})
.render(RenderIntent.terminal())
```

---

## 6. 完整 Seam：Sandbox（进程沙箱）与 Approval（审批）

### Sandbox Definition（`harness.sandbox.sandbox`）

```java
/** 策略服务（Definition 模块自己声明，不依赖外部）。 */
public interface SandboxPolicyService {
    ServiceKey<SandboxPolicyService> KEY = new ServiceKey<>("sandboxPolicy");
    SandboxPolicy current();
}

public interface SandboxProvider {
    ServiceKey<SandboxProvider> KEY = new ServiceKey<>("sandbox");
    /** Consumer spawn 前调此：拿到包装 argv + 沙箱句柄。 */
    SandboxedProcess wrap(ProcessArgv argv, SandboxPolicy policy);
}

public record SandboxPolicy(SandboxMode mode, Path workspaceRoot) {}
public enum SandboxMode { OFF, LANDLOCK, CONTAINER }
```

MVP Provider（id `sandbox-local`）：`mode=OFF` 透传，其余 `UnsupportedOperationException`。未来加 `sandbox-landlock` / `sandbox-docker` 不改任何 Consumer。

### Approval Definition（`harness.interaction.approval`）—— 不是 stub

对一个执行 bash 的 harness，审批是安全边界，MVP 就有真实实现：

```java
public interface ApprovalService {
    ServiceKey<ApprovalService> KEY = new ServiceKey<>("approval");

    /** 治理摘要（R4：--verify 与 policy 档位消费）。 */
    Mode mode();

    /** 批准则返回；拒绝抛 ApprovalDeniedException（executor 转 error result，不炸 turn）。 */
    void require(ApprovalRequest request) throws ApprovalDeniedException;

    enum Mode { AUTO, HUMAN_GATE, DENY_ALL }
}

public record ApprovalRequest(
    Agent initiator, String toolName, String summary, JsonValue args) {}
```

三个 Provider 齐备：`approval-auto`（AUTO，开发/测试档）、`approval-ask`（HUMAN_GATE，CLI 交互或 ACP 上报）、`approval-deny`（DENY_ALL，最小权限档）。`policy: production` 禁止 AUTO（07 §6）。**审批在 ToolExecutor 的固定 stage 调用**（§8），单个工具无法绕过。

---

## 7. Session Persistence Seam

### Definition

```java
public interface SessionPersistence {
    ServiceKey<SessionPersistence> KEY = new ServiceKey<>("sessionPersistence");
    boolean durable();                 // R4：--verify 报告用
    void save(Session session) throws IOException;
    Session load(SessionId id) throws IOException;
    List<SessionId> list() throws IOException;
}
```

Provider（id `persistence-jsonl`）见 [03 §6](03-session-event-sourcing.md)。

### Consumer（persistence 插件）

```java
public final class PersistencePlugin implements Plugin {
    @Override public String id() { return "persistence-jsonl"; }

    @Override
    public void apply(Scope scope) {
        SessionPersistence backend = /* jsonl 实现（本插件自带） */;
        // 订阅 session/appended：buffer + 后台 batch 写盘
        scope.events().onGlobal(SessionEvents.APPENDED, (carrier, entry) -> {
            Session session = (Session) carrier;
            if (entry.seq() >= session.firstLiveSeq()) buffer(session.id(), entry);
        });
        // flush barrier（notifyAndWait 派发，本 listener 阻塞至写盘完成）
        scope.events().onGlobal(SessionEvents.FLUSH, (carrier, session) ->
            flushBuffer(session.id()));
        scope.provide(SessionPersistence.KEY, backend);
    }
}
```

---

## 8. ToolRegistry 与 ToolExecutor — R2 的落点

> **R2（执行一致性）**：模型发起的副作用有且仅有一条路径——ToolCall → ToolExecutor pipeline（校验 → 审批 → 超时 → 执行 → 审计落账）。插件代码直接调 capability 是代码发起（过 review），不在此约束内。

两个服务，职责分离：

```java
/** 注册与 schema 来源。 */
public interface ToolRegistry {
    ServiceKey<ToolRegistry> KEY = new ServiceKey<>("tools");
    Disposable register(ToolDefinition tool);
    /** 当前 scope 可见的 schema（agent-loop 组装请求的唯一来源）。 */
    List<ToolSchema> schemas(Scope scope);
}

/** 唯一执行路径（R2）。 */
public interface ToolExecutor {
    ServiceKey<ToolExecutor> KEY = new ServiceKey<>("toolExecutor");

    /**
     * 执行一批模型工具调用：落账 tool/call → 逐个执行（并行）→ 落账 tool/result。
     * 返回与输入同序的结果信封列表。
     */
    List<LoggedEvent<ToolResultEvent>> execute(List<ToolUseBlock> calls,
                                               int turn, int step, AbortSignal signal);
}
```

### 四道锁

1. **单一 schema 来源**：agent-loop 组装 LLM 请求的工具列表只从 `ToolRegistry.schemas(scope)` 取，别处无权注入 function-calling schema。
2. **单一分发点**：模型响应里的 toolCalls 只交 `ToolExecutor.execute`——全库唯一调用点，架构测试断言（10）。
3. **executor 拥有审计**：`tool/call` 与 `tool/result` 由 executor **无条件落账**。工具实现只返回 `ToolExecutionResult`，它没有写日志的入口——工具在结构上无法"执行了但不留痕"。
4. **审批与超时是 executor 的固定 stage**：不是工具的自觉（前版 bash 工具内嵌可选审批的写法已废弃）。`ToolExecutorImpl` 构造器强制 `ApprovalService`（R4）。

### 执行 pipeline

```java
public final class ToolExecutorImpl implements ToolExecutor {

    private final ToolRegistry registry;
    private final ApprovalService approval;      // R4：构造器强制，无 Optional
    private final Events events;
    private final Session session;
    private final ExecutorService virtualThreads;

    @Override
    public List<LoggedEvent<ToolResultEvent>> execute(
            List<ToolUseBlock> calls, int turn, int step, AbortSignal signal) {

        // 0. 重复执行守卫：本批内 callId 唯一（批结束即弃——有界，不随会话增长）
        Set<CallId> batchIds = new HashSet<>();

        return calls.stream()
            .map(call -> virtualThreads.submit(() -> executeOne(call, turn, step, signal, batchIds)))
            .toList().stream().map(this::await).toList();   // join 全部，按调用序
    }

    private LoggedEvent<ToolResultEvent> executeOne(
            ToolUseBlock call, int turn, int step, AbortSignal signal, Set<CallId> batchIds) {
        // 1. 审计落账：tool/call（executor 拥有）
        session.append(new ToolCallEvent(clock.millis(), turn, step,
            call.id(), call.name(), call.arguments()));
        if (!batchIds.add(call.id())) {
            return appendResult(call, ToolExecutionResult.error("Duplicate call"), turn, step);
        }

        try {
            // 2. tools/pre-execute（waterfall）：可否决/改写
            ToolExecutionPlan plan = events.waterfall(ToolEvents.PRE_EXECUTE, /* scope */ agentScope,
                /* carrier */ this, List.of(call, signal), () -> ToolExecutionPlan.proceed(call));

            if (plan.vetoed()) {
                return appendResult(call, ToolExecutionResult.error(plan.vetoReason()), turn, step);
            }

            // 3. 审批（固定 stage；拒绝 → error result，不炸 turn）
            approval.require(new ApprovalRequest(initiator, call.name(),
                summarize(call), call.arguments()));

            // 4. 执行（异常 → error result，即错误是数据；AbortedException 传播）
            ToolDefinition tool = registry.resolve(agentScope, call.name());
            if (tool == null) {
                return appendResult(call, ToolExecutionResult.error("Unknown tool"), turn, step);
            }
            ToolExecutionResult result = tool.executor()
                .execute(ToolArgs.parse(call.arguments(), tool.parameters()), execCtx(signal));

            // 5. tools/post-execute（waterfall）：观察/改写结果（spill 大输出等）
            ToolExecutionResult finalResult = events.waterfall(ToolEvents.POST_EXECUTE, agentScope,
                this, List.of(call, result), () -> result);

            return appendResult(call, finalResult, turn, step);
        } catch (AbortedException e) {
            throw e;                                   // 取消向上传播（turn 收敛）
        } catch (ApprovalDeniedException e) {
            return appendResult(call, ToolExecutionResult.error("denied: " + e.getMessage()), turn, step);
        } catch (Exception e) {
            return appendResult(call, ToolExecutionResult.error(e), turn, step);
        }
    }

    private LoggedEvent<ToolResultEvent> appendResult(
            ToolUseBlock call, ToolExecutionResult r, int turn, int step) {
        return session.append(new ToolResultEvent(clock.millis(), turn, step,
            r.toMessage(call.id()), r.error(), r.meta(), r.concludesTurn(),
            SurfaceOpAppend, List.of()));             // 无条件落账
    }
}
```

**超时**作为固定 stage 由 resolve 携带（`ShellRequest.timeout` 等）+ provider 侧 waitFor/kill 兜底；wall-clock 上限是 ToolExecutor 的 ConfigService 配置项（可调参数在 config，07 §4），到点经 signal 取消。

### ToolDefinition 与 RenderIntent

```java
public record ToolDefinition(
    String name,
    String description,
    ValueSchema parameters,
    ToolExecutorFn executor,          // ToolExecutionResult execute(ToolArgs, ToolExecutionContext)
    RenderIntent renderIntent,        // generic / terminal / diff / locations —— 设计期声明
    Function<ToolExecutionResult, Optional<UiNode>> presenter   // args 的纯函数
) { /* builder */ }
```

渲染意图是工具设计的一部分（移植 dsh："a tool's UI render intent is part of its design, decided up front"）：作者定义时声明，UI 消费者据此选择渲染器。

---

## 9. 完整 Seam 清单（MVP + 留接口）

| Seam | Definition 模块 | MVP Provider(s) | Consumer | 状态 |
|---|---|---|---|---|
| LLM | `llm.llm` | `deepseek`, `replay` | agent-loop | ✅ |
| Tools | `core.tools` | （registry+executor 内建） | 各 tool 模块 | ✅ |
| FS | `fs.fs` | `local` | `fs.tool` | ✅ |
| Shell | `shell.shell` | `bash-local` | `shell.tool` | ✅ |
| Sandbox | `sandbox.sandbox` | `local`（OFF 透传）| bash/terminal/fs | ✅ stub |
| Session Persistence | `session.persistence` | `jsonl` | persistence 插件 | ✅ |
| Approval | `interaction.approval` | `auto` / `ask` / `deny` | ToolExecutor 固定 stage | ✅ **真实** |
| Commands | `interaction.commands` | — | headless | ✅ stub |
| Subagent / Web / LSP / Terminal / Compaction | 各 Definition | —（留接口）| — | 🔌 接口 |

留接口的 seam：Definition 模块完整定义接口和 `ServiceKey`，但不提供 Provider；组合时不挂载即可，未来加 Provider 不改任何 Consumer。

## 10. 三角色纪律的编译期保障

JPMS 让"Consumer 不 import Provider"成为**编译期约束**：

```java
// harness.fs.tool 的 module-info.java
module io.javanatic.harness.fs.tool {
    requires io.javanatic.harness.fs.fs;         // ✅ Definition
    requires io.javanatic.harness.core.tools;    // ✅ Consumer 依赖
    // 没有 requires io.javanatic.harness.fs.local   ← Provider！
    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.fs.tool.FsToolPlugin;
}
```

若 FsToolPlugin 意外 `import io.javanatic.harness.fs.local.LocalFs`，**编译失败**。这是比 dsh（约定 + ESLint）更强的隔离。

## 11. 与 dsh 对齐

| dsh | JH | 备注 |
|---|---|---|
| Service Definition | Java interface + `ServiceKey<T>` | |
| Service Provider | `implements` + `Plugin.apply(scope)` 注册 | |
| Consumer (inject service) | `scope.require(KEY)` | 不 import Provider |
| 三角色纪律靠约定 | JPMS `requires` 编译期保障 | **更强** |
| `ToolDefinition` + defineTool DSL | `ToolDefinition.builder()` | |
| `tools/pre-execute` / `post-execute` waterfall | `ToolEvents.PRE/POST_EXECUTE` waterfall | |
| 审批在工具内可选挂 | **ToolExecutor 固定 stage + 构造器强制** | R2/R4 收紧 |
| 工具自己写日志/结果 | **executor 拥有 tool/call + tool/result 落账** | R2 |
| `RenderIntent` | sealed interface | 设计期声明 |
| LLM 流式 AsyncIterable | 阻塞 `Stream<StreamChunk>` + 有界队列 | 无协议税 |
| capability graph | 扫描 module-info 生成 | 可选 |
