# 08 · 类型纪律

dsh 有一套非常严格的类型纪律（贯穿 AGENTS.md）。JH 用 Java 25 的语言设施完整复刻，部分场景比 TS 更强（编译期 vs 运行时）。

## 1. Sealed Interface + Record — Discriminated Union

dsh 用 TypeScript discriminated union（`{ kind: 'a'; ... } | { kind: 'b'; ... }`）。JH 用 Java 25 `sealed interface` + `record`：

```java
// 对应 dsh: type TurnEndReason =
//   | { kind: 'completed' }
//   | { kind: 'aborted'; reason: TurnEndCancelCause }
//   | { kind: 'error'; error: LlmFailure }
//   | { kind: 'max-tokens' }
//   | { kind: 'interrupted' }
//   | { kind: 'blocked' }
public sealed interface TurnEndReason permits
    TurnEndReason.Completed,
    TurnEndReason.Aborted,
    TurnEndReason.Error,
    TurnEndReason.MaxTokens,
    TurnEndReason.Interrupted,
    TurnEndReason.Blocked {

    record Completed() implements TurnEndReason {}
    record Aborted(AgentCancelCause cause) implements TurnEndReason {}
    record Error(LlmFailure failure) implements TurnEndReason {}
    record MaxTokens() implements TurnEndReason {}
    record Interrupted() implements TurnEndReason {}  // 仅持久化 crash recovery 产生
    record Blocked() implements TurnEndReason {}
}
```

### switch pattern matching 穷尽检查

Java 25 `switch` 对 sealed 类型做穷尽检查（此特性自 21 final，25 无变化）：

```java
String describe(TurnEndReason r) {
    return switch (r) {
        case Completed c -> "completed";
        case Aborted a -> "aborted: " + a.cause();
        case Error e -> "error: " + e.failure();
        case MaxTokens m -> "max-tokens";
        case Interrupted i -> "interrupted";
        case Blocked b -> "blocked";
        // 无需 default：sealed 已穷尽
        // 漏掉任一分支 → 编译错误
    };
}
```

**比 TS 更强**：TS 的 `switch` 不强制穷尽，需要手动写 `assertNever(x)`。Java 的 sealed switch 在编译期保证。

### 扩展出口：non-sealed

dsh 的某些 union 是 merge-extensible（`TurnEndReasonMap` 可加插件变体）。JH 对应 `non-sealed` 子接口：

```java
// merge-extensible：插件可加新变体（走 ExtensionEvent 同款机制）
public sealed interface TurnEndReason permits
    TurnEndReason.Completed, /* ... 核心变体 ... */, TurnEndReason.Extension {
    // 核心变体（record）...
    non-sealed interface Extension extends TurnEndReason {}
    // 插件实现 Extension，注册到 TurnEndReasonRegistry
}
```

**switch 处理扩展**（对应 dsh "fall through documented default"）：
```java
String describe(TurnEndReason r) {
    return switch (r) {
        case Completed c -> "completed";
        // ... 核心分支 ...
        case TurnEndReason.Extension ext -> "extension: " + ext.getClass().getSimpleName();
        // Extension 是 non-sealed，必须有 case 或 default
    };
}
```

## 2. Map → Derived Union 模式（替代 declaration merging）

dsh 用 `interface XxxMap { 'a': ...; 'b': ... }` + `keyof` 派生 union，插件 `declare module` 扩展。Java 没有编译期 declaration merging。

### 核心用 sealed（穷尽）

核心变体用 `sealed interface`，编译期穷尽检查（§1）。

### 扩展用运行时注册表（可扩展）

插件扩展变体实现 `non-sealed Extension` 子接口，运行时注册：

```java
// 插件扩展 TurnEndReason
public final class ScheduleBlocked implements TurnEndReason.Extension {
    // ...
}
// 在 Plugin.apply 里
TurnEndReasonRegistry.register("schedule-blocked", ScheduleBlocked.class);
```

持久化/反序列化时查 registry（见 [03 §7](03-session-event-sourcing.md)）。

### 何时用 sealed vs non-sealed

| 情况 | 选择 | 理由 |
|---|---|---|
| 核心不变集（turn/step/tool 事件、cancel cause、surface op） | `sealed`（穷尽） | 编译期保证无遗漏 |
| 用户可扩展（session 事件、turn end reason、tool render intent） | `sealed` 核心 + `non-sealed Extension` | 平衡穷尽与扩展 |

## 3. Branded ID — 类型品牌

dsh 用 `Branded<B>` 让 `SessionId` 和 `CallId` 在类型层不可互换。JH 用泛型 phantom type：

```java
// io.dsh.kernel.brand.Id
package io.dsh.kernel.brand;

/**
 * 类型品牌的 ID。结构上是 String，类型上是 T 的载体。
 * 泛型 T 仅供编译器检查（erasure 后运行时不参与）。
 *
 * 对应 dsh Branded<B>。
 *
 * @param <T> 品牌标记（phantom type，如 SessionId.Brand）
 * @param value 底层字符串值
 */
public record Id<T>(String value) {

    public Id {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Id value must be non-empty");
        }
    }

    /** 品牌标记接口（phantom，无方法）。每个 ID 类型定义自己的 Brand。 */
    public interface Brand {}

    @Override
    public String toString() { return value; }
}
```

### 具体 ID 类型

```java
// io.dsh.core.session.SessionId
public class SessionId {
    public interface Brand extends Id.Brand {}
    public static Id<Brand> of(String value) { return new Id<>(value); }
    public static Id<Brand> generate() { return of("sid-" + UUID.randomUUID()); }
}

// io.dsh.llm.CallId
public class CallId {
    public interface Brand extends Id.Brand {}
    public static Id<Brand> of(String value) { return new Id<>(value); }
    public static Id<Brand> generate() { return of("call_" + Long.toHexString(System.nanoTime())); }
}

// io.dsh.jobs.JobId
public class JobId {
    public interface Brand extends Id.Brand {}
    public static Id<Brand> of(String value) { return new Id<>(value); }
}
```

### 编译期不可互换

```java
Id<SessionId.Brand> sid = SessionId.of("sid-1");
Id<CallId.Brand> cid = CallId.of("call_1");

void acceptSession(Id<SessionId.Brand> id) { ... }
acceptSession(sid);  // ✅
acceptSession(cid);  // ❌ 编译错误：Id<CallId.Brand> 不是 Id<SessionId.Brand>
```

**比 dsh 略弱**：dsh 的 `Branded<B>` 是 `string & { [BRAND]: B }`，品牌在类型层。JH 用 `Id<T>` 泛型，品牌是 phantom type parameter。但效果相同：编译器阻止把一个 ID 类型传给期望另一个的参数。运行时都是 `String`，序列化/JSON 无负担。

## 4. assertNever → default + IllegalStateException

dsh 用 `assertNever(x)` 保证 union 穷尽。JH 在 sealed switch 里**不需要**（编译器保证），但在处理 `non-sealed Extension` 时用等价物：

```java
// 工具方法
public final class AssertNever {
    private AssertNever() {}

    /**
     * 用于 sealed switch 之外的穷尽断言（如 if-else 链），
     * 或处理 non-sealed 扩展的默认分支。
     *
     * 对应 dsh assertNever(x)。
     */
    public static <T> RuntimeException unhandled(T value) {
        return new IllegalStateException(
            "Unhandled case: " + (value == null ? "null" : value.getClass().getName()));
    }
}

// 用法
if (x instanceof A a) { ... }
else if (x instanceof B b) { ... }
else throw AssertNever.unhandled(x);
```

**优先用 sealed switch**（编译期保证），`AssertNever` 仅用于无法 switch 的场景。

## 5. 判别式标签 switch（强制 switch，禁止 if 链）

dsh 约定 "switch on discriminant tags; don't chain ifs"。JH 同样：

```java
// ✅ 好：switch pattern matching
String type = switch (chunk) {
    case StreamChunk.Delta d -> "delta";
    case StreamChunk.DeltaToolUse dt -> "delta-tool-use";
    case StreamChunk.Usage u -> "usage";
    case StreamChunk.Finish f -> "finish: " + f.reason();
};

// ❌ 坏：if 链（不强制穷尽，易漏）
String type;
if (chunk instanceof StreamChunk.Delta d) type = "delta";
else if (chunk instanceof StreamChunk.Usage u) type = "usage";
// 漏了 DeltaToolUse 和 Finish → 静默 bug
```

**编译器 enforcement**：sealed switch 漏分支编译失败。这是 JH 相对 TS 的额外安全保障。

## 6. JSON 边界校验（信任 TypeScript 同进程边界 vs 校验外部输入）

dsh 原则："Trust TypeScript at typed same-process boundaries. Do not add runtime validation for values the static interface requires; validate at parser/config, queued, model/tool JSON, durable/file, worker, process, and wire boundaries."

JH 等价：

| 边界 | 校验 | 手段 |
|---|---|---|
| 同进程 typed 调用 | **不**重复校验 | Java 类型系统（sealed/generics/record）|
| YAML/JSON 解析 | 校验 | Jackson `@JsonCreator` + 构造器校验 |
| Model/tool JSON 输入 | 校验 | `ValueSchema` 解析 + 校验 |
| 持久化加载 | 校验 | `Session.fromRestore` + invariant companion |
| 虚拟线程边界 | 类型不变 | `Id<T>` phantom type |
| 网络边界 | 校验 | Jackson `@JsonDeserialize` 严格模式 |

**示例**：构造器校验（fail loud at construction）：
```java
public record ShellRequest(String command, Path cwd, Duration timeout, Map<String, String> env) {
    public ShellRequest {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cwd, "cwd");
        if (command.isEmpty()) throw new IllegalArgumentException("command empty");
        if (!cwd.isAbsolute()) throw new IllegalArgumentException("cwd must be absolute");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
```

构造器校验是 record 的天然 fail-loud 点。**所有跨边界的值对象在构造时校验**，同进程传递不再二次校验。

## 7. 显式 > 隐式（默认值是显式 resolve 步骤）

dsh："defaulting is an explicit `resolve(request): Spec` step in the owning implementation, never a hidden `?? default` inside `run()`."

JH 对应：

```java
// ❌ 坏：在 execute 里隐藏默认
.execute((args, ctx) -> {
    String provider = args.readString("provider", "deepseek");  // 隐藏默认
    ...
})

// ✅ 好：显式 resolve 步骤
class ShellRequestResolver {
    /** 显式把 ToolArgs 解析成 ShellRequest（Spec），默认值集中在此。 */
    public ShellRequest resolve(ToolArgs args, Path cwd) {
        return new ShellRequest(
            args.readString("command"),                          // 无默认（必填）
            cwd,                                                  // 来自 execution context
            Duration.ofMillis(args.readInt("timeout_ms", 30000)), // 显式默认
            Map.of()
        );
    }
}
```

**规则**：所有可调参数（超时、模型、provider）的默认值在**一个 `resolve` 方法里**，不散落在 `execute` 各处。这让"改默认值"只改一个地方。

## 8. 错配大声失败（Fail Loud）

dsh："Misconfiguration fails loud at load when self-contained, otherwise at earliest resolvable point; never silently skip a missing referent."

JH 落地：

```java
// 1. Plugin 依赖缺失 → 启动失败
ctx.get(SomeService.KEY);  // 若未注册抛 ServiceNotAvailableException

// 2. Patch 目标 id 不存在 → 报错
// 3. YAML 字段缺失/类型错 → 构造器抛 IllegalArgumentException
// 4. 持久化日志含未知事件（非 ignorable）→ load 抛异常
// 5. invariant companion 违规 → 抛 InvariantViolation

// 对应：空 catch 必须命名吞了什么
try {
    Files.delete(path);
} catch (NoSuchFileException e) {
    // 吞：文件已不存在，删除目标已达成。无其他 IOException 能到此处（path 已校验）。
}
```

## 9. 非空注解（jspecify）

用 `org.jspecify` 标注 nullness，在类型层表达可空性：

```java
import org.jspecify.nullness.Nullable;

public record AssistantMessageEvent(
    long seq, long time, int turn, int step,
    AssistantMessage message,
    @Nullable TokenUsage usage,          // 可空
    SurfaceOp surfaceOp,
    @Nullable List<Long> sourceEventSeqs  // 可空
) implements SessionEvent, SurfaceEvent { ... }
```

非 `@Nullable` 的参数/返回值默认非空。静态检查（Checker Framework 或 IDE）可在编译期捕获空指针。

## 10. Flexible Constructor Bodies（JEP 513，Java 25 Final）

Java 25 之前，record 的紧凑构造器或显式构造器里 `super(...)` / 字段赋值的**第一条语句**约束让"前置校验"必须绕弯（紧凑构造器里能校验但不能在 super 前）。JEP 513 解除了这个限制：

```java
// Java 25 Flexible Constructor Bodies：可在隐式/显式 super 前执行语句
public record ShellRequest(String command, Path cwd, Duration timeout, Map<String, String> env) {

    public ShellRequest {
        // 这些语句现在可以放在字段赋值之前（record 的隐式赋值等价于隐式 super）
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        Objects.requireNonNull(cwd, "cwd");
        if (!cwd.isAbsolute()) {
            throw new IllegalArgumentException("cwd must be absolute: " + cwd);
        }
        // 仅规范化，不改变校验语义
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
```

**对本项目的意义**：[05](05-capability-seam.md) 和本节的 record 构造器校验（fail loud at construction）是跨边界值对象的核心纪律。21 下这些校验必须在"字段已赋值后"才能引用 `this`，或用静态工厂绕开；25 下校验逻辑自然前置，符合"先校验后构造"的直觉。**不依赖此特性也能工作**，但有了它代码更清晰。

> 对继承类（`extends`）的情况，JEP 513 还允许在 `super(...)` 调用前执行语句——本项目极少用继承（以 interface + record 为主），此场景收益小。

## 11. Module Import Declarations（JEP 511，Java 25 Final）

Java 25 允许 `import module M;` 一次性导入模块 M 导出的所有包的顶层类。本项目模块多、跨包引用频繁，这是显著的降噪：

```java
// Java 25 之前：消费者要罗列几十个包级 import
import io.dsh.kernel.context.Context;
import io.dsh.kernel.context.ServiceKey;
import io.dsh.kernel.context.Subscription;
import io.dsh.kernel.fiber.Fiber;
import io.dsh.kernel.events.EventKey;
import io.dsh.kernel.events.EventListener;
import io.dsh.kernel.events.Events;
// ... 还有十几个

// Java 25：一行 import module
import module io.deepseek.harness.kernel;

// 然后直接用 Context / ServiceKey / Fiber / Events ...
```

**纪律**：`import module` 只导入 `exports` 的包（尊重 JPMS 边界，不破坏隔离）。建议在**跨模块消费者**（如 agent-loop 消费 kernel+session+tools+llm）用 `import module`，在**模块内部**仍用精确的包级 import 以保持可追溯性。

> 注意：`import module` 与包级 import 可共存；若同名冲突，包级 import 优先。静态分析工具（如 checkstyle）可配置"仅允许 import 某些 module"以防滥用。

## 12. 类型纪律总结表

| dsh 纪律 | JH 落地 | 强度 |
|---|---|---|
| Discriminated union | `sealed interface` + `record` | 编译期穷尽 ✅ |
| Map→Union declaration merging | `sealed` 核心 + `non-sealed Extension` + 运行时 registry | 核心 compile-time，扩展 runtime |
| `Branded<B>` | `Id<T>` phantom type | 编译期不可互换 ✅ |
| `assertNever(x)` | sealed switch（无需）+ `AssertNever.unhandled(x)`（扩展用）| 编译期为主 ✅ |
| switch on discriminant（禁止 if 链）| sealed switch 强制 | 编译期 ✅ |
| Trust typed same-process boundary | Java 类型系统 + 构造器校验 | 同 |
| 构造器前置校验（fail loud at construction）| Flexible Constructor Bodies（JEP 513）| 自然前置 ✅ |
| 显式 > 隐式（resolve step）| 显式 `Resolver` 类 | 同 |
| Fail loud | 构造器抛 + 启动检查 + invariant | 同 |
| 空命名 catch | Javadoc 注释说明 | 同 |
| 非空约束 | `@Nullable` (jspecify) + 构造器校验 | 同 |
| 跨模块导入降噪 | Module Import Declarations（JEP 511）| 编译期，尊重 JPMS 边界 ✅ |
| Source plane vs Artifact plane | Maven src/main vs target/*.jar + JPMS module-path | 编译期 ✅ |
| 一个模块一个 aggregate tsconfig | JPMS `module-info.java` | 编译期 ✅ |
