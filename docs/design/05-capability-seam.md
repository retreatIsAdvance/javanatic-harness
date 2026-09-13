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

**实现落定（迭代 3）**：`RoutingLlmService` 的注册表注销器经 kernel 新增的 `Disposable.of(AutoCloseable)` 公有工厂（无栈撤销凭据）；未知 provider 的 fail-loud 消息携带已注册清单（装配缺口就地诊断）。词表补充 `CallId` 品牌工厂、`AbortSignal`/`AbortedException`（checkAbort 协议）与 **`ChunkAssembly` 纯函数**——chunk 流折叠为 `Assembled(text, toolCalls, usage, finishReason)`，回放测试与 agent-loop 必须共用它（否则回放验证的不是生产行为）。包名 `io.javanatic.harness.llm`（模块名不变）。JPMS 注意：本仓不用 `requires transitive`，任何直接触碰 session Message 类型的模块（含测试）须自声明 `requires io.javanatic.harness.core.session`。

### 词汇类型（同模块；消息模型在 core/session，见 02 归属修正）

```java
/** 流式 chunk（迭代 3 已实现）。permits 四个变体齐全（含增量工具调用——组装 tool_use 需要它）。 */
public sealed interface StreamChunk
    permits StreamChunk.Delta, StreamChunk.DeltaToolUse, StreamChunk.Usage, StreamChunk.Finish {
    record Delta(String text) implements StreamChunk {}
    record DeltaToolUse(Id<CallId> id, String name, String argumentsDelta) implements StreamChunk {}
    record Usage(TokenUsage usage) implements StreamChunk {}
    record Finish(FinishReason reason) implements StreamChunk {}
}

public enum FinishReason { STOP, TOOL_USE, LENGTH }
```

```java
// 消息与内容块在 core/session（消息是被日志的事实）。tools 切片进 permits 时
// 由 session 侧扩展：ContentBlock permits TextBlock + （将进）ToolUseBlock /
// ToolResultBlock / ImageBlock —— tool 块形态见 04 §7 与 05 §5 的落账图。
public sealed interface ContentBlock permits TextBlock /* , ToolUseBlock, ToolResultBlock, ImageBlock */ { }
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

### 实现落定（it6）

- **取消钩子**:`llm.AbortSignal` 增加 `default onCancel(Runnable)`(不破坏函数式接口);`AbortController` 实现同步触发、已取消后注册立即执行、动作异常仅记录。轮询覆盖等待间隙,监听覆盖阻塞在不可中断 IO。
- **deepseek wire 实测**:结束分块把 `content:"" + finish_reason + usage` 合并在**同一事件**(与 OpenAI 分离事件不同)——解码器每事件产出 0..3 个分块;seam 契约「Finish 恒最后一块」由 adapter 持有 Finish 至流尾补发来维持。
- **JDK HttpClient 事实**(实证):响应流被远端关闭时阻塞 read 返回 -1;被本地 close() 时阻塞 read 抛 `IOException: closed`——空闲看门狗据此掐断挂死连接。`Thread.sleep` 只有毫秒重载,传纳秒会静默睡走数十小时(实测踩坑)。
- **传输韧性**:429/5xx/IOException 有界重试(指数退避+抖动,尊重 Retry-After 秒值);凭据脱敏(DeepSeekOptions 覆写 toString);配置经构造器注入(ConfigService/CredentialsService 随组合切片接手来源)。

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

public record ShellRequest(String command, Path cwd, Duration timeout, Map<String, String> env,
                           SandboxPolicy policy) {
    public ShellRequest {   // 构造器校验：fail loud at construction（08 §6）
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(policy, "policy");   // it12:逐调用携带,无隐藏默认
        if (command.isEmpty()) throw new IllegalArgumentException("command must be non-empty");
        if (!cwd.isAbsolute()) throw new IllegalArgumentException("cwd must be absolute");
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;   // 30s,唯一默认点
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}

/**
 * outputTruncated/sandboxDenied 是 it12 加的叙述位:模型据此分辨「输出被截断」
 * 与「被沙箱拒」和「命令自己失败」——denied 由 provider 判定(exit≠0 且 stderr
 * 命中**本后端**方言签名),消费方不跨后端求并集。
 */
public record ShellResult(int exitCode, String stdout, String stderr, Duration duration,
                          boolean outputTruncated, boolean sandboxDenied) {}
```

### Provider（`harness.shell.bash-local`，plugin id `shell-bash-local`）

```java
final class LocalBashExecutor implements ShellExecutor {
    // 生产语义(it6 落地,与初稿差异三处):
    // 1. 取消经 AbortSignal.onCancel(默认方法钩子)即时击杀 + 等待循环轮询
    //    checkAbort 双保险——初稿的 signal.controller() 不存在(it3 定型的
    //    AbortSignal 只有 checkAbort;钩子随 shell 这个首个消费者进入 seam)。
    // 2. 击杀对象是进程树(ProcessHandle.descendants() 先于本体)——
    //    destroyForcibly 只杀直接子进程;快速退出进程脱管的孙进程杀不到,
    //    真隔离(setsid)归 sandbox 切片。
    // 3. stdout/stderr 并发排水并各设上限(初稿 waitFor 后 readAllBytes 会
    //    写满管道缓冲死锁子进程);超限截断置标记、继续读丢弃。
    ShellResult execute(ShellRequest req, AbortSignal signal) throws Exception { /* ... */ }
}
```

### Provider（`harness.shell.docker`，plugin id `shell-docker`，it12.5）

**seam 不动的第二个 Provider**：同一 `ShellExecutor` 契约、同一 `ShellRequest/ShellResult`，
隔离强度从「同机进程约束」升到「环境级」——换的是实现，消费方（`shell-tool`）与
Definition 一字不改，选择完全落在组合数据上（base 里 `disabled: true`，
headless `--docker` overlay 禁 bash-local 行、启本行）。

```java
final class DockerShellExecutor implements ShellExecutor {
    // 承重决策(it12.5 七风险点,详见 plan/iteration-12.5.md):
    // 1. 挂载面即可写面——三档词表的容器同义实现,且比同机约束更强:
    //    READ_ONLY        → --read-only + ws :ro
    //    WORKSPACE_WRITE  → --read-only + ws :rw + --tmpfs /tmp
    //    DANGER_FULL_ACCESS → 不挂 --read-only(容器 OS 隔离仍保留)
    //    可写 = 显式挂载的那一个目录,整个容器根文件系统恒只读。
    // 2. 容器键 = (规范化 workspace, mode)——不同策略绝不共用容器(挂载面不同);
    //    容器名 jh-shell-<uuid> 永不按名复用(防跨组合串台);同键并发共享 = 预期语义。
    // 3. 击杀链路:docker exec 的 CLI 进程死 ≠ 容器内进程死。命令经 setsid --wait
    //    成为会话组长(pid 即 pgid)并把 pid 落盘 → 超时/取消对**进程组**发 TERM 再
    //    KILL(杀整树),CLI destroyForcibly 兜底。--wait 不可省:fork 模式下 setsid
    //    父进程立即 exit 0,会把失败命令吞成成功。
    // 4. 模型命令经环境变量 JH_COMMAND 传输——内容永不进 wrapper 文本,零转义面。
    // 5. 路径一律 toRealPath() 规范化后再挂载与 chdir:darwin 上 /var 是
    //    /private/var 的符号链接,容器内没有宿主的链接,两处拼写必须一致。
    // 6. apply 期双探针 fail loud:docker version(daemon 可达) + docker image inspect
    //    (镜像在场,不自动拉取——首调不被网络拖住)。行启用即执行意图。
    // 7. R3:容器生命周期挂 scope,onClose 即 rm -f 本执行器创建的全部容器。
    ShellResult execute(ShellRequest req, AbortSignal signal) throws Exception { /* ... */ }
}
```

`--entrypoint sleep` 是必需的而非修饰：镜像自带的 `ENTRYPOINT` 会劫持保活进程
（实测 `agent-runner:latest` 的 ENTRYPOINT 是 `java -jar /app/agent-runner.jar`）。

**已知残余（诚实记录，不假装已解）**：
- **网络策略不在词表内**——容器默认有网络出口。与 dsh 同款缺口，挂账。
- **无资源限额**（`--cpus`/`--memory`），挂账。
- **denial 标记以 `exit≠0` 为门**：模型若把越界写成 `echo x > /etc/y; echo $?`，
  wrapper 退出码被自己的最后一条语句抹平成 0，标记随之丢失（EROFS 仍在 stderr 里，
  模型读得到，但结构化的 `sandboxDenied` 位没了）。这是 bash-local 同款的 seam 属性，
  不是 docker 特有；修正归 seam 层（分类器产出 typed code），不属本切片。
- JVM 崩溃可留孤儿容器：`jh-shell-` 前缀可 grep 清理。

### Consumer（`harness.shell.tool`，plugin id `shell-tool`）

工具 execute 里只有**显式 resolve**（默认值集中一处，08 §7）+ 委托 provider：

```java
.execute((args, exec) -> {
    ShellRequest sr = resolve(args, exec.cwd());   // timeout 默认 30s + policy 显式 resolve 在此
    ShellResult r = shell.execute(sr, exec.signal());
    return ToolExecutionResult.success(format(r), r.exitCode() != 0);
})
.render(RenderIntent.terminal())

// format():叙述位在此渲染成模型可读文本——两个 Provider 共用同一处,
// 所以 it12.5 加 docker Provider 时消费方一字未改。
//   "exit: 1 [sandbox: a file effect was denied by the sandbox policy]"
//   + "(output truncated)" + "\n\nstdout:\n…" + "\nstderr:\n…"
```

---

## 6. 完整 Seam：Sandbox（同机进程约束）与 Approval（审批）

### Sandbox Definition（`harness.sandbox.sandbox`，it12 落定——dsh 形状）

**与宿主共享内核与文件系统**；容器/microVM/远程执行是换掉整条执行 seam，不挂在本服务
后面——这条判断在 it12.5 被验证：`shell-docker`（[§5](#5-完整-seamshell命令执行)）作为
第二个 `ShellExecutor` Provider 落地，`SandboxProvider` 一字未改。

后果值得记住：docker Provider **直接消费 `SandboxPolicy`，从不调用 `confine`**。
`confine` 的契约是「返回的 argv 受限执行」，而在容器后端强制点是容器边界（挂载面）
而非 argv——把恒等 argv 塞回 `ConfinedArgv` 就是对着接口撒谎。这也是 it12.5 撤销
「恒等 SandboxProvider」提案、改为撤掉 sandbox-policy 装载期 provider 在场校验的原因
（见 [plan/iteration-12.5.md](../plan/iteration-12.5.md) 风险点 3 与 iteration-12 修正表）。

```java
/** 文件效果模式词表（网络与进程可见性明示在词表外）。 */
public enum SandboxMode { READ_ONLY, WORKSPACE_WRITE, DANGER_FULL_ACCESS }

/** 逐调用携带的策略（不固定在 provider——两个消费者同刻可不同策略）。 */
public record SandboxPolicy(SandboxMode mode, Path workspaceRoot) {}

/** 逐调用解析：部署默认档 + 会话态修正（plan/mode fold 激活且默认档受限 → READ_ONLY）。 */
public interface SandboxPolicyService {
    ServiceKey<SandboxPolicyService> KEY = new ServiceKey<>("sandbox-policy");
    SandboxPolicy resolve(Session session);
}

public interface SandboxProvider {
    ServiceKey<SandboxProvider> KEY = new ServiceKey<>("sandbox");
    /** 包装 argv 使其受限执行——调用方以返回值替代自身 spawn；透传档不进（显式弃权）。 */
    ConfinedArgv confine(List<String> argv, SandboxPolicy policy);  // fail-closed
}

/** 包装后 argv + 本后端强制完备度 + 拒绝方言（EPERM/EROFS 等本后端专属）。 */
public record ConfinedArgv(List<String> argv, SandboxEnforcement enforcement,
                           List<String> denialSignatures) {}
```

**WritableRoots 单一来源**：workspace-write = workspace 根 + 平台临时区（realpath 规范化
去重——darwin `/tmp` 即 `/private/tmp`；Windows 不加 `/tmp`，盘符相对路径若被创建会
成为真实授予）。Seatbelt 授予与进程内 fs 围栏（fs-tool）都从这里取——「bash 能写而
写工具不能」的不对称不可能出现。

Provider（id `sandbox-local`）按**平台链**组形（dsh 对齐：平台→候选链；单候选只探
可用性，>1 候选才探针仲裁）：
darwin=[seatbelt]（SBPL `deny file-write*` + /dev/null + 可写根 subpath；功能探针
`sandbox-exec -p <profile> -- true`）；linux=[bwrap]（it12.7 落定——`--ro-bind / /` 整根
只读 + `--dev /dev` + `--die-with-parent`，workspace-write 追加可写根 `--bind`；拒绝
方言 `Read-only file system`/`Permission denied`；功能探针 `bwrap --ro-bind / / --dev
/dev --die-with-parent -- true`。需主机装 bubblewrap，非特权 userns 受限的主机
fail-closed——landlock 第二候选入 0.2.0：自限制后 exec、规则跨 execve 继承、allow-list
只授不拒）；win32=[]（windows-acl 入 0.2.0：WRITE_RESTRICTED 受限令牌 +
per-workspace SID 常设授予 + per-session 随机临时目录/SID，**enforcement=PARTIAL 及
两洞**——Everyone-可写外部对象仍可写、NTFS 硬链接别名越界，stderr 签名 + exit 127
fail-closed）。空链平台上受限 confine 一律 `SandboxUnavailableException`（code
SANDBOX_UNAVAILABLE）——**fail-closed，静默透传被禁止**。

**已知残余（诚实记录）**：读可见性与网络不在约束面（词表外，与 seatbelt 对齐）——
bwrap 链不加 `--unshare-pid`/`--unshare-net`，`--ro-bind / /` 下宿主文件系统整体
读可见、/proc 为宿主视图；非特权 userns 被内核策略禁用（如 AppArmor 收紧的
Ubuntu 24.04 默认态）的主机受限档 fail-closed；denial 标记以 `exit≠0` 为门
（§5 docker 同款 seam 属性，修正归 seam 层）。

**消费端接线**：shell——`ShellRequest` 携带非空策略，bash-local 对受限档 wrap argv 再
spawn；`ShellResult.sandboxDenied` 标记「沙箱拒了文件效果」（stderr 命中本后端方言 +
非零退出）——模型能分辨拒绝与命令失败。fs——fs-tool 变异工具在 READ_ONLY（含计划
模式）下直接拒（进程内围栏，模式级；WORKSPACE_WRITE 的路径边界由 fs-local root 与
sandbox workspace 对齐保证，漂移=交集生效）。容器/microVM/云 Provider 按社区声音
排期（it12.5 docker 起）。

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
3. **executor 拥有审计对**：`tool/call` 与 `tool/result` 由 executor **无条件落账**——工具碰不到审计对，在结构上无法"执行了但不留痕"（it11 修订）。工具可经 `ToolExecutionContext.session()` 追加**领域事件**（非审计，如 todo/write 快照、plan/mode 翻转）：Session.append 自身的 monitor 全序与 surface 校验是既有防线；跨执行者的日志交错序任意，事件关联靠内容（callId），永不靠相邻性。
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

### 实现落定（迭代 4）

- **`CallId` 归属 session.message**：ToolUseBlock/ToolResultEvent（session 域）需要它配对，llm 已依赖 session，反向引用即成环——dsh 的 type-only import 无此约束，Java 按「消息域身份在被日志的一侧」安置。
- **executor 的 session 是方法参数**（非构造器字段）：executor 跨会话共享，日志目标随调用到达。
- **`ApprovalService` Definition 落在 core/tools**（随第一个强制消费者）：内置 `Approvals.auto()/deny()` + `approval-auto` 插件；三模式真实现属 interaction 切片。组合必须先装载某个审批提供者——缺失在 apply 时 fail loud（R4 组合责任）。
- **`schemas()` 暂无 scope 参数**：per-agent 工具集 overlay 随 core/agent 切片进；当前返回全量（名称排序）。
- **kernel waterfall 语义异常裸抛**：AbortedException/IAE 等RuntimeException 不再裹 CompletionException（与 ScopeImpl.effect 同例）——executor 的取消传播依赖此契约。
- **ValueSchema 极简词表**（object/string/number/boolean + description，properties 全必填）；presenter（UiNode）暂缓至 UI 切片。
- **批内重复 callId 并行语义**：两路同 id 并发时占用者不确定，但恒有恰一个成功 + 恰一个 Duplicate 错误、双双留痕。

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
