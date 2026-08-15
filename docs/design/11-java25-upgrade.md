# 11 · Java 25 升级专题

本文档集中说明 JH 选 **Java 25 LTS**（而非最初的 21 LTS）的决策依据、依赖的 JEP 状态、preview 特性的风险与 fallback，以及未来迁移路径。

## 1. 决策摘要

| 问题 | 结论 |
|---|---|
| Java 25 能否用？ | **能**。2025-09-16 GA，5 年 LTS（支持到 2030+）|
| 相比 21 的硬收益 | **`ScopedValue` final（JEP 506）** —— initiator 传递从 ThreadLocal 的"能用但有继承陷阱"升级为"不可变 + 虚拟线程自动继承" |
| 有没有 preview 风险 | 有：`StructuredTaskScope`（JEP 505）在 25 仍是第五 preview。本项目**隔离使用**并提供 CompletableFuture fallback，MVP 可选不碰 preview |
| 是否影响 JPMS/Maven/库生态 | 不影响。JPMS 自 9 稳定；Jackson/SnakeYAML/JUnit 5 均支持 Java 25；Maven compiler 3.13+ 支持 `release 25` |

## 2. 依赖的 JEP 与状态

下表只列**本项目实际依赖或受影响**的 JEP。完整 18 个 JEP 见 [OpenJDK JDK 25](https://openjdk.org/projects/jdk/25/)。

| JEP | 特性 | Java 25 状态 | 本项目用法 | 依赖强度 |
|---|---|---|---|---|
| 444 | Virtual Threads | Final（自 21） | agent driver、工具执行、流式 IO | **必需** |
| 441 | Pattern Matching for switch | Final（自 21） | sealed union 穷尽检查 | **必需** |
| 409 | Sealed Classes | Final（自 17） | SessionEvent/TurnEndReason/SurfaceOp 判别式 | **必需** |
| 395 | Records | Final（自 16） | 所有值对象 | **必需** |
| **506** | **Scoped Values** | **Final（25 新转 final）** | agent initiator 传递（[04 §8](04-agent-loop.md)）| **首选**（21 下退化为 ThreadLocal）|
| **511** | **Module Import Declarations** | **Final（25 新转 final）** | 跨模块消费者降噪（[08 §11](08-type-discipline.md)）| 可选（锦上添花）|
| **513** | **Flexible Constructor Bodies** | **Final（25 新转 final）** | record 构造器前置校验（[08 §10](08-type-discipline.md)）| 可选（更自然，非必需）|
| 505 | Structured Concurrency | **第五 Preview** | 工具并行执行（[09 §5](09-concurrency.md)）| 可选（有 fallback）|
| 507 | Primitive Patterns | Preview | — | 不用 |
| 491 | Synchronize Virtual Threads w/o Pinning | Final（自 24） | `synchronized` 下虚拟线程不再 pin | 被动受益（无需改代码）|
| 480/485 | Stream Gatherers | Final（自 23） | 可选用于 surface fold | 不依赖 |

**关键边界**：
- **生产代码只依赖 Final 特性**：444/441/409/395（自 ≤21 已 final）+ 506/511/513（25 新 final）。
- **Preview 特性（505）隔离使用**：仅 `ToolExecutor` 一个类用 `StructuredTaskScope`，需 `--enable-preview`。MVP 可完全跳过，用 [09 §12](09-concurrency.md) 的 CompletableFuture fallback。

## 3. 为什么 ScopedValue（JEP 506）是首要理由

这是选 25 不选 21 的**决定性**理由。其余（511/513）是锦上添花。

### 问题：ThreadLocal 在虚拟线程下的陷阱

agent loop 的 initiator 传递需求：
- driver 虚拟线程设置 `currentInitiator = agent`
- driver fork 的工具执行子虚拟线程要读到同一个 initiator（日志归因）
- 退出时自动清理

用 ThreadLocal 的问题：
1. **子虚拟线程默认不继承**：`Thread.ofVirtual().start(...)` 创建的子虚拟线程**不**继承父的 ThreadLocal 值（除非父线程用 `Thread.Builder.inherit(InheritableThreadLocal)` 显式开启，且只继承 *Inheritable*ThreadLocal）。
2. **可变 + 必须手动 remove**：`set()` 后忘 `remove()` 在线程池里会串值；虚拟线程量大时这个风险更隐蔽。
3. **与 StructuredTaskScope 不协同**：StructuredTaskScope 的 fork 子任务无法天然继承 ThreadLocal。

### ScopedValue 的解法

```java
private static final ScopedValue<Agent> INITIATOR = ScopedValue.newInstance();

ScopedValue.where(INITIATOR, agent).run(() -> {
    // 当前虚拟线程 + 所有 fork 的子虚拟线程都自动继承 INITIATOR == agent
    Thread.startVirtualThread(() -> {
        log("initiator={}", INITIATOR.get());  // ✅ agent
    });
});
// 退出 run() 自动解绑，无需 remove
```

三个优势直接命中痛点：
1. **自动继承**子虚拟线程（含 StructuredTaskScope fork）。
2. **不可变**，绑定后无法被任意代码 `set()` 篡改。
3. **词法作用域绑定**，`run()` 退出自动解绑，零泄漏风险。

### 21 下的退化方案（若被迫用 21）

```java
// 21 退化：InheritableThreadLocal + 手动继承
private static final InheritableThreadLocal<Agent> INITIATOR = new InheritableThreadLocal<>();

// 但 fork 子虚拟线程时仍需手动传递（虚拟线程默认不触发 InheritableThreadLocal 的继承钩子）
// 实践中要配合 Thread.Builder.inherit() 或显式参数传递，复杂且易错
```

**结论**：initiator 是 agent loop 的核心横切关注点，值得为 JEP 506 上 25。详见 [09 §11](09-concurrency.md)。

## 4. import module（JEP 511）与 Flexible Constructor Bodies（JEP 513）的增量价值

这两个单独不值升级，但叠加 506 后收益凑齐：

- **JEP 511**：`import module io.deepseek.harness.kernel;` 一行替代 30+ 包级 import。本项目 agent-loop 跨 kernel/session/tools/llm 四模块，降噪明显。纪律：尊重 JPMS `exports`，不破坏隔离（[08 §11](08-type-discipline.md)）。
- **JEP 513**：record 构造器可在字段赋值前执行校验语句。本项目的值对象（ShellRequest、ConfigRow 等）大量做 fail-loud-at-construction 校验，此前要绕弯或用静态工厂，现在自然前置（[08 §10](08-type-discipline.md)）。**不依赖它也能工作**，只是代码更顺。

## 5. StructuredTaskScope（JEP 505）的 preview 风险管理

JEP 505 在 Java 25 仍是**第五次 preview**（API 形状趋稳，但尚未 final）。本项目策略：

### 隔离使用
- 仅 `ToolExecutor.executeTools(...)` 一个方法用 `StructuredTaskScope.ShutdownOnFailure`（并行执行工具调用）。
- 该文件单独标注 `--enable-preview`，Maven 配置见 [02 §5](02-module-layout.md)。

### 提供 fallback
- 若 MVP 不愿引入 `--enable-preview`，用 `CompletableFuture.allOf` + 手动 cancel 实现等价语义（[09 §12](09-concurrency.md) 的完整代码）。
- fallback 失去了"作用域退出自动 join + 取消传播"的词法保证，但本项目只在工具并行这一处用，复杂度可控。

### 迁移路径
- JEP 505 预计在 Java 26 或 27 转 final。届时：
  1. 移除该文件的 `--enable-preview` 标注。
  2. 若 API 有微调（preview 期间可能改名/调签名），更新 `ToolExecutor` 单文件。
  3. 删除 fallback 分支（或保留为测试用）。
- 因为隔离在单类，迁移面收敛。

## 6. 生态兼容性核对

| 依赖 | Java 25 支持 | 备注 |
|---|---|---|
| JPMS（module-info）| ✅ 自 9 稳定 | 25 无变化 |
| Maven compiler-plugin 3.13+ | ✅ `release 25` | 需 JDK 25 运行 Maven |
| Jackson 2.17+ | ✅ | 25 测试通过 |
| SnakeYAML 2.2+ | ✅ | |
| JUnit 5.10+ | ✅ | |
| AssertJ 3.25+ | ✅ | |
| java.net.http.HttpClient | ✅ 自 11 | 虚拟线程友好 |
| JaCoCo 0.8.12+ | ✅ | 支持 25 class file 版本 |
| IntelliJ IDEA | ✅ 2025.1+ | 原生支持 JDK 25 + ScopedValue |
| Eclipse | ✅ 2025-09+ | |
| VSCode + Java 扩展 | ✅ | |

**风险点**：少数冷门库可能未适配 Java 25 的 class file 版本（class major 69）。本项目依赖都是主流库，已核对。新增依赖时检查 `Automatic-Module-Name` 或 JPMS `module-info` 支持。

## 7. 若必须降级到 Java 21

如果部署环境强制 Java 21 LTS，可回退，代价：

| 特性 | 21 退化方案 | 代价 |
|---|---|---|
| ScopedValue（506）| `InheritableThreadLocal` + 手动继承 | 子虚拟线程继承需手工，易泄漏 |
| import module（511）| 罗列包级 import | 冗长，无功能损失 |
| Flexible Constructor Bodies（513）| 紧凑构造器内校验（部分场景用静态工厂）| 校验不能完全前置，可读性略降 |
| StructuredTaskScope（505）| CompletableFuture fallback（09 §12）| 无词法作用域保证 |
| synchronized 虚拟线程优化（491）| 21 下 synchronized 会 pin 虚拟线程 | 高并发 IO 场景吞吐降低；本项目用 `ReentrantLock` 规避 |

降级步骤：
1. `maven.compiler.release` 改回 `21`。
2. `04-agent-loop.md` 的 `ScopedValue<Agent>` 换回 `InheritableThreadLocal<Agent>`（注意 fork 子虚拟线程的手动传递）。
3. Session.append 的 `synchronized` 评估是否换 `ReentrantLock`（避免 pinning）。
4. 移除所有 `import module` 声明，展开为包级 import。
5. `ToolExecutor` 用 [09 §12](09-concurrency.md) fallback。

## 8. 前瞻：Java 26/27 预期

- **JEP 505 StructuredTaskScope 转 final**：届时移除 `--enable-preview`，`ToolExecutor` 单文件迁移。
- **JEP 507 Primitive Patterns**：若 final，可考虑给 `SessionEvent` 的 seq/time（long）加 primitive pattern 优化，非必需。
- **AOT 编译（Graal）**：若 `jlink` + Graal native image 成熟，headless runner 可产出原生可执行文件，启动毫秒级。本项目 JPMS 结构天然适配 native image（无复杂反射）。

## 9. 参考资料

- [OpenJDK JDK 25 项目页](https://openjdk.org/projects/jdk/25/) —— 官方 JEP 列表
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506)
- [JEP 511: Module Import Declarations](https://openjdk.org/jeps/511)
- [JEP 513: Flexible Constructor Bodies](https://openjdk.org/jeps/513)
- [JEP 505: Structured Concurrency](https://openjdk.org/jeps/505)
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [InfoQ: Java 25 released](https://www.infoq.com/news/2025/09/java25-released/)
- [Oracle: The Arrival of Java 25](https://blogs.oracle.com/java/the-arrival-of-java-25)
