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
        tools.register(ToolDefinition.of("example", "Calls example service", schema,
            (args, exec) ->
                ToolExecutionResult.success(svc.doSomething(args.readString("input")))));
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
                queue.put(FIN);                       // 哨兵：正常结束（End 只表干净 EOF）
            } catch (AbortedException e) {
                queue.put(new ErrorMarker(e));        // 取消不伪装成流尾：经错误通道原样上浮
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

### 实现落定（it12.6）

- **typed 失败**：`LlmCallException`（`llm/llm`，seam 类型）+ `Kind` 词表——`AUTH`(401/403) /
  `RATE_LIMIT`(429) / `SERVER`(5xx) / `NETWORK`(连接/读 IO) / `TIMEOUT`(空闲看门狗) /
  `PROTOCOL`(wire 解析：畸形 SSE、未知 finish_reason、流尾无 finish、其余 4xx) /
  `OVERFLOW`(it14：400 + 溢出厂商信号，由适配器分类；`retryable()=false`——溢出由压缩恢复、
  非传输重试可愈)。适配器把
  HTTP/IO/解析失败映射为 typed 抛出，**重试判定读 `Kind.retryable()`**（RATE_LIMIT/SERVER/
  NETWORK 可重试；AUTH/TIMEOUT/PROTOCOL/OVERFLOW 不可）——可重试性不再散落为数值判断。
  消费方按 kind 渲染可行动失败（AUTH 引查 key、RATE_LIMIT 稍后重试），不解析
  消息文本——**it14 落地**：REPL 渲染器按 `TurnEndReason.Error.kind`（`FailureKind`）出文案；
  kind 由 agent-loop 对 llm `Kind` 做穷尽 switch 映射（词表家在 core/session，避免
  llm↔session 依赖环）；溢出恢复判定同步改读 `Kind.OVERFLOW`（it10 消息文本匹配退役）。
  消息保留厂商细节（如 `deepseek http 401`）供人读，仅作附注不参与判定。
- **流尾无 finish**：此前由 `ChunkAssembly.fold` 以裸 IAE 兜底的 wire 违规，改为 adapter
  在流尾直抛 `PROTOCOL`——分类发生在知道 wire 事实的层。

### Consumer（agent-loop 内部）

agent-loop 通过 `scope.require(LlmService.KEY)` 拿到 LLM，try-with-resources 消费阻塞流，边收边落账（04 §7）。**agent-loop 不 import 任何 Provider**。

---

## 4. 完整 Seam：FS（文件系统）

### Definition（`harness.fs.fs`）

```java
public interface FsService {
    ServiceKey<FsService> KEY = new ServiceKey<>("fs");

    String read(Path path) throws IOException;       // 有界:超 maxReadBytes 截断,尾附 " (output truncated)"
    void write(Path path, String content) throws IOException;
    String edit(Path path, String oldString, String newString) throws IOException;  // 唯一匹配(it21);超上限 fail loud
    void delete(Path path) throws IOException;
    Listing list(Path path) throws IOException;      // 超 maxListEntries 截断,truncated 标志承载
    SearchResult search(String pattern, Path path) throws IOException;  // 字面搜索(it21);超 maxSearchMatches 截断
}

/** 搜索命中:path 为相对工作区根的路径("/" 分隔)。 */
public record Match(String path, int line, String text) {}

/** 搜索结果:按 (path, line) 升序 + 是否因命中上限截断。 */
public record SearchResult(List<Match> matches, boolean truncated) {}
```

### Provider（`harness.fs.local`，plugin id `fs-local`）

`Files.*` 的直接包装，无并发包装（阻塞语义，虚拟线程下安全）。有界化（it20）：
读取/编辑以 `maxReadBytes`（默认 256 KiB，与 shell-local `maxOutputBytes` 对称）、
列举以 `maxListEntries`（默认 1000）为界——读截断在文内标记、编辑超限 fail loud、
列举截断以 `Listing.truncated` 标志承载，不静默丢数据；同一组上限经行配置可调（12 §5）。
编辑语义（it21）：`oldString` 须唯一匹配——0 次 = `not found`；≥2 次 fail loud，
消息含出现次数与有界行号列表（补上下文消歧后重试）；空串直接拒。不再静默改第一处。
多处按**非重叠**计（`"aa"` 在 `"aaa"` 中算 1 处 = 唯一，续搜自 `at + len`）。
搜索语义（it21）：`pattern` 按**字面**串逐行匹配（非正则；空串拒）；`path` 可为文件或目录，
不存在走 `NoSuchFileException`；**不跟随符号链接**（`walkFileTree` 无 `FOLLOW_LINKS`，
`isRegularFile()` 对链接为假）、符号链接不产生结果。跳过两类文件：超 `maxReadBytes`
（读不动，与读上限同源）与二进制（前 8 KiB 含 NUL）。单行文本超 200 字符尾部 `…` 缩略，
尾随 `\r` 剥除。有界收集（it21 实现偏离，见 iteration-21）：走查序依文件系统而异，按收集序
截断会不确定——改为维持 **(相对路径, 行号)** 序最小的 `maxSearchMatches`（默认 200，行配置
`fs-local.searchMaxMatches`）条，被挤出者置 `SearchResult.truncated`。**确定性**：同一棵树
同一上限，输出恒定；内存占用 O(上限)。

### Consumer（`harness.fs.tool`，plugin id `fs-tool`）

```java
// 实况形状（节选；全量见 FsToolPlugin）：of(...) 渲染缺省 GENERIC；
// 非 GENERIC（TERMINAL/DIFF/LOCATIONS）走记录构造器 new ToolDefinition(..., render, tool, exempt)
tools.register(ToolDefinition.of("fs_read", "读取文件内容", READ_ARGS,
    (args, ctx) -> ToolExecutionResult.success(
        fs.read(Path.of(args.readString("path"))))));
```

注意 Consumer **不做审批**：审批是 ToolExecutor 的固定 stage（§8、R4），不是各工具的自觉。

**搜索工具（it21）**：`fs_search` 是第六个操作（`fs_read` / `fs_write` / `fs_edit` /
`fs_delete` / `fs_list` / `fs_search`），搜索是只读操作，plan 模式下放行。输出逐行
`相对路径:行号:行文本`（`\n` 连接），命中被上限挤出时尾附 `… (search truncated)`。
`path` 在 schema 中**必填**（`ValueSchema` 无「可选字段」语义，缺省值无处安放）——搜工作区根
由调用方显式传 `"."`，schema 描述里写明这一点，与实现同文本。

**读后修改保护（it21）**：`fs_edit` / `fs_write` 执行前，消费端把会话日志折叠成
「路径 → 最新内容事实」（`fs_read` 未截断结果 / `fs_edit` 返回的编辑后全文 /
`fs_write` 的 content 实参；只认成功结果，按 seq 末值胜，配对靠 callId 不靠相邻性），
与磁盘当前内容比对——不一致即 error result「file changed since read」，提示重读后重试。
事实全在日志：resume/fork 无内存镜像即恢复（纯 fold，同 `PlanModeService.foldActive`）。
边界（漏报方向，不产生误报）：从未成功读过 / 只读到截断内容 / 路径写法不同源
（相对与绝对、不同基准）→ 无事实即无保护；文件读不到时交底层操作给原生错误。
`fs_delete` **不设守卫**（P5 裁 edit/write 覆盖面）：删除读后被外部改动的文件仍是静默
销毁——已记录边界，实撞补 guard + 一测即成。
工具 schema/描述与实现同文本：`old_string` 描述与 `fs_edit` 描述同写「须唯一匹配」。

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

### Provider（`harness.shell.local`，plugin id `shell-local`；it25 S-b 由 `harness.shell.bash-local` 泛化）

```java
final class LocalShellExecutor implements ShellExecutor {
    // 生产语义(it6 落地,与初稿差异三处):
    // 1. 取消经 AbortSignal.onCancel(默认方法钩子)即时击杀 + 等待循环轮询
    //    checkAbort 双保险——初稿的 signal.controller() 不存在(it3 定型的
    //    AbortSignal 只有 checkAbort;钩子随 shell 这个首个消费者进入 seam)。
    // 2. 击杀对象是进程树(ProcessHandle.descendants() 先于本体)——
    //    destroyForcibly 只杀直接子进程;快速退出进程脱管的孙进程杀不到,
    //    真隔离(setsid)归 sandbox 切片。
    // 3. stdout/stderr 并发排水并各设上限(初稿 waitFor 后 readAllBytes 会
    //    写满管道缓冲死锁子进程);超限截断置标记、继续读丢弃。
    // 平台分派(it25 S-b):解释器由 ShellPlatform 按宿主决定,不在执行器里写死——
    //    POSIX   = ["bash","-c",command](现状语义不变)
    //    Windows = ["<pwsh.exe>","-NoProfile","-NonInteractive","-EncodedCommand",
    //               base64(UTF-16LE(command))](it25 S-c:载体无引号无空白,过
    //               provider→助手→pwsh 两跳不失真;-Command 内联形态会让
    //               hop-2 的 cmd 口径包裹式引号改写 command,已证伪)
    //    宿主无 pwsh 时 execute 期抛 ShellUnavailableException(fail-closed,不静默
    //    退化到 5.1:引号/转义/错误文形全变,标记与断言会跟着撒谎)。
    ShellResult execute(ShellRequest req, AbortSignal signal) throws Exception { /* ... */ }
}
```

平台化的三个观测面按平台登记（**换宿主 = 换方言**，不外推）：**拒绝方言**——沙箱拒写的
stderr 文形随后端与该平台记录（seam 契约不变：逐行、大小写不敏感 contains、exit≠0 门控，
[§6](#6-完整-seamsandbox同机进程约束与-approval审批)）；**退出码**——`$?`/`$LASTEXITCODE`
的取值面与「最后一条语句抹平退出码」的残余一致（§5 docker 残余）；
**引号**——POSIX 单双引号 vs PowerShell 单引号字面/双引号可展开。组合与 boot 在无 pwsh 的
Windows 上不受影响——fail-loud 点在 execute，不在装载期。

### Provider（`harness.shell.docker`，plugin id `shell-docker`，it12.5）

**seam 不动的第二个 Provider**：同一 `ShellExecutor` 契约、同一 `ShellRequest/ShellResult`，
隔离强度从「同机进程约束」升到「环境级」——换的是实现，消费方（`shell-tool`）与
Definition 一字不改，选择完全落在组合数据上（base 里 `disabled: true`，
headless `--docker` overlay 禁 shell-local 行、启本行）。

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
  模型读得到，但结构化的 `sandboxDenied` 位没了）。这是 shell-local 同款的 seam 属性，
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
    /** 后端可用性查询（it12.6）——verify 预警消费；探针与 confine 共用首探缓存。 */
    BackendStatus backendStatus();
}

/** 包装后 argv + 本后端强制完备度 + 拒绝方言（EPERM/EROFS 等本后端专属）。 */
public record ConfinedArgv(List<String> argv, SandboxEnforcement enforcement,
                           List<String> denialSignatures) {}

/** 后端可用性三态（it12.6）：Ready / NoBackend（平台链空，点名平台）/
 *  ProbeFailed（链上候选探针全败，点名明细）。
 *  Ready 三件（it24 链仲裁）：生效后端 + 强制完备度 + 链路细节
 *  （前候选失败明细 + 本后端能力行，无则空串）。 */
public sealed interface BackendStatus {
    record Ready(String backend, SandboxEnforcement enforcement, String detail)
        implements BackendStatus {}
    record NoBackend(String platform) implements BackendStatus {}
    record ProbeFailed(String platform, String detail) implements BackendStatus {}
}
```

**WritableRoots 单一来源**：workspace-write = workspace 根 + 平台临时区（realpath 规范化
去重——darwin `/tmp` 即 `/private/tmp`；Windows 不加 `/tmp`，盘符相对路径若被创建会
成为真实授予）。Seatbelt 授予与进程内 fs 围栏（fs-tool）都从这里取——「shell 能写而
写工具不能」的不对称不可能出现。

Provider（id `sandbox-local`）按**平台链**组形（dsh 对齐：平台→候选链；**选择期
fallback** 仲裁——一次探针、缓存、进程生命周期内不再切换；空链或候选全不可用一律
fail-closed）：
darwin=[seatbelt]（SBPL `deny file-write*` + /dev/null + 可写根 subpath；功能探针
`sandbox-exec -p <profile> -- true`）；linux=[bwrap, landlock]（it12.7 落定 bwrap
首候选——`--ro-bind / /` 整根只读 + `--dev /dev` + `--die-with-parent`，
workspace-write 追加可写根 `--bind`；拒绝方言 `Read-only file system`/`Permission
denied`；功能探针 `bwrap --ro-bind / / --dev /dev --die-with-parent -- true`。需主机
装 bubblewrap，非特权 userns 受限的主机落第二候选——it24 落定 landlock：JVM 自限制
助手（`Landlock`，FFM 直呼 `landlock_create_ruleset`/`landlock_add_rule`/
`landlock_restrict_self`）先自限再 exec 目标 argv，规则跨 execve 继承、allow-list
只授不拒；**入选门槛 = 内核接受的 rights 子集含 `WRITE_EFFECTS_V1` + `REFER` +
`TRUNCATE`**（缺者 fail-closed 等 bwrap 腿——ABI 1–2 能清空只读文件、跨目录 rename
不受控）；拒绝方言 `Permission denied`（EACCES，无 EROFS 面）；助手命令带
`-XX:-UsePerfData`、`-Dstderr.encoding=UTF-8`（助手诊断恒 UTF-8——消费侧 shell
执行器按 UTF-8 解码子进程 stderr，zh-CN 宿主的 GBK 原生编码会把中文诊断打成
乱码，it25 S-c；win32 助手共用同一构造面 `HelperLaunch`）与 `--enable-native-access`
（classpath/模块路径/镜像三形态按运行
事实择一，classpath 取助手类 code source——surefire 的 `java.class.path` 是空 booter
jar）；机制结论（ABI/rights/正对照）来自探针内实测而非版本猜测；助手退出码协议即诊断
词表——0 通过 / 10 平台或内核不支持 / 11 ABI 不足 / 12 机制未真拒写 / 13 应用失败 /
14 用法 / 127 exec 失败，非 0 一律 fail-closed，末行结论原样进 `Ready.detail` 或
`ProbeFailed.detail`）。
win32=[windows-acl]（it25 S-a 落地：**低完整性（Low IL）形状**——拓扑与 landlock 不同，
是「助手受限生子」：助手 JVM 不受限，子进程唯一生成路径 = 受限令牌
（`CreateRestrictedToken(DISABLE_MAX_PRIVILEGE, 0 受限 SID)` +
`SetTokenInformation(TokenIntegrityLevel, S-1-16-4096)`）经 `CreateProcessAsUserW` 生出，
令牌构造任一步失败即不生子；既定 WRITE_RESTRICTED + per-workspace SID 常设授予 +
per-session 随机 SID 形状已证物理不可用（`CREATE_NO_WINDOW` 下受限 SID 子进程必死
0xC0000142、借 Administrators 过启动则写限制形同虚设——S-0 实探），不得回头再试。
可写面 = **Low 标签面**：会话前助手纯 FFM 逐对象递归打标
（`ConvertStringSecurityDescriptorToSecurityDescriptorW` +
`SetNamedSecurityInfoW(LABEL_SECURITY_INFORMATION)`；目录带 (OI)(CI) 继承、既有子对象
逐个打——标签不向已存在子对象回溯，新对象靠继承），标签是持久元数据、0.2 不做还原；
MAC 不理会 DACL ⇒ 写 Low 标签对象放行、写中标签对象（含 DACL 已授权的）被拒；打标
成本实测 ≈0.23 ms/文件（2000 文件 942 ms vs 空树 463 ms 基线）。**enforcement=PARTIAL，
两洞如实（`Ready.detail` 携探针结论行，结构保证不冒充 FULL）**：洞 1 = 可写面 = 主机上
**一切** Low 标签对象（不止本会话树）；洞 2 = 递归打标**跟随 NTFS 硬链接**（硬链接无
reparse 属性，跳重解析点的守卫拦不住）⇒ 链入会话树的工作区外目标对象被标 Low、永久
进入可写面。退出码协议沿用 landlock 词表但**无 11**（本助手无 ABI 概念）：
0/10/12/13/14/127，探针七腿全过才 Ready、非 0 一律 fail-closed；拒绝方言
`Access is denied` / `拒绝访问`（GBK 控制台本地化文案）。空链平台上受限 confine 一律 `SandboxUnavailableException`（code
SANDBOX_UNAVAILABLE）——**fail-closed，静默透传被禁止**。

**查询面与 verify 观测（it12.6；it24 分两级）**：`backendStatus()` 把「首调用才炸」
提前成组合期可见事实——`--verify`（`AppBoot`，已依赖本模块）在组合含受限档时读它
（probe session 解析策略；无策略行或无同机 provider 的 docker 组合不预警——执行器
自身消费策略）：`NoBackend`/`ProbeFailed` → stderr **WARNING** 点名平台、后果（首次
受限调用 fail-closed）与出路（显式 `danger-full-access` overlay 弃权 / 装
bubblewrap / 换到有同机后端的平台）；`Ready` → 一行 **INFO 落点**（生效后端 + 完备度
+ 链路细节），使「档位真在生效」与「档位配了但不可用」在 verify 输出里可辨；
**exit 码不变**（观测非违规——PRODUCTION 违规仍 exit 1）。

**已知残余（诚实记录）**：读可见性与网络不在约束面（词表外，与 seatbelt 对齐）——
bwrap 链不加 `--unshare-pid`/`--unshare-net`，`--ro-bind / /` 下宿主文件系统整体
读可见、/proc 为宿主视图；非特权 userns 被内核策略禁用（如 AppArmor 收紧的
Ubuntu 24.04 默认态）的主机由第二候选 landlock 接住，两候选都不可用才 fail-closed；
bwrap 定位不到或不可用的宿主形态（NixOS 这类发行版布局：二进制在 store 里而不在
系统 PATH——与「未安装」同一探针结论）同理——`bwrap probe failed` 在诊断里实名登记，
链按序落到 landlock（CI 的干净容器冒烟即该形态的真跑登记：容器内无 bwrap，
`--verify` 必现该点名行）；
landlock 腿的写授权只到可写根——`/dev/null` 不在 `WritableRoots` 内 ⇒ 该腿下
`cmd > /dev/null` **被拒（EACCES）**；与 seatbelt 的 `(literal "/dev/null")` 放行、bwrap
的新 devtmpfs 不同，差异如实记此、不假装同构。此为 allow-list 语义的直接推论，
**已实测坐实**：D4 测量项
（`SandboxLocalTest#landlockDevNullWriteIsMeasuredForAdjudication`，正对照 + 放行/拒绝
两分支取证）在真 landlock 宿主（CI ubuntu runner，ABI v7）真跑得 `[D4] landlock leg
/dev/null write: DENIED (exit 1, sandboxDenied=true)`（run 36515333691）；终裁（2026-09-29）
= 选项 A「维持拒绝、按平台差异如实文档」；denial 标记以 `exit≠0` 为门
（§5 docker 同款 seam 属性，修正归 seam 层）。

**消费端接线**：shell——`ShellRequest` 携带非空策略，shell-local 对受限档 wrap argv 再
spawn；`ShellResult.sandboxDenied` 标记「沙箱拒了文件效果」（stderr 命中本后端方言 +
非零退出）——模型能分辨拒绝与命令失败。fs——fs-tool 变异工具在 READ_ONLY（含计划
模式）下直接拒（进程内围栏，模式级；WORKSPACE_WRITE 的路径边界由 fs-local root 与
sandbox workspace 对齐保证，漂移由装配期四键断言 fail loud——it21，见 07 §5）。容器隔离已由 shell-docker 提供（§5）；
后续 Provider 扩展按[总体迭代计划](README.md#phased-evolution-plan) 的生产场景、能力依赖
与验收闸门自主排期；microVM/云 Provider 未列入当前阶段范围。

### Approval Definition（`harness.interaction.approval`）—— 不是 stub

对一个执行 shell 命令的 harness，审批是安全边界，MVP 就有真实实现：

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

三个 Provider 齐备：`approval-auto`（AUTO，开发/测试档）、`approval-ask`（HUMAN_GATE，CLI 交互或 ACP 上报）、`approval-deny`（DENY_ALL，最小权限档）。`policy: production` 禁止 AUTO（07 §6）。**审批在 ToolExecutor 的固定 stage 调用**（§8），单个工具无法绕过；**免审批是工具属性声明**（`ToolDefinition.approvalExempt`，it22）——不是第四种模式，问答类工具据此在 deny 档下仍可澄清。

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

## 7.1 完整 Seam：MCP（it27；it27.1 注册表 + 双 transport + 轮屏障换代）

### Definition（`harness.mcp.mcp`，零第三方）

```java
public interface McpClientService {                      // 插件 mcp-registry 提供
    ServiceKey<McpClientService> KEY = new ServiceKey<>("mcp");
    List<McpServerConnection> connections();              // 按 server id 排序（稳定面）
    Optional<McpServerConnection> connection(String serverId);
    Disposable register(McpServerConnection connection);  // 装配期；跨 transport 撞 id fail loud
}

public interface McpServerConnection {
    String serverId();
    McpServerState state();                              // CONNECTED / RECONNECTING / FAILED
    List<McpToolDescriptor> tools();                     // 当前已知清单（刷新异步发布）
    long catalogGeneration();                            // 内容变化才递增（仅顺序变化不算）
    McpCallResult call(String toolName, String argumentsJson, AbortSignal signal);
}
```

`McpToolDescriptor(serverId, name, description, inputSchemaJson)` 的 schema 为 wire 原文 String——JSON 解析留在边界模块（transport 组帧与消费面映射各自持有，同 `core/tools` 信任边界先例）。`McpCallResult(content, isError)`：**工具级错误是数据**（远端 `isError` 标记，不抛、不炸 turn）；传输/协议失败走异常分档（`McpTimeoutException` / `McpProtocolException` / `McpConnectionException`）。**`DRIFTED` 已删除**（T5）：清单变化不是状态，只是代际差异；不可用事实才落 `state()`。消费面的候选快照预检失败另有 `McpCatalogSwapException`（见 Consumer）。

### Provider（`harness.mcp.stdio`，plugin id `mcp-stdio`）

手写最小 JSON-RPC 2.0 stdio 客户端：换行分帧（**T7 单帧 8 MiB 帽**）、`initialize` 协商、`tools/list`（分页上限 100 页）、`tools/call`、错误分档；子进程**装配期启动**、随 scope 回收（进程树清理）、stderr 转日志；env = 最小环境 + 行配置白名单；超时缺省 60s（server 级覆盖）；重连 500ms→30s ×10。远端 `notifications/tools/list_changed` 触发刷新，重连成功亦对账——清单内容变化递增 `catalogGeneration()`（仅顺序变化不算）。官方 Java SDK 不采（绑 Reactor + 不定版本面，与同步写法/虚拟线程纪律冲突；dsh 亦自持 mcp-client）。进程**不经沙箱**：MCP server 是组合者显式配置的信任面（同插件本身），启动失败装配期 fail loud 并回滚已启动的 server。

### Provider（`harness.mcp.http`，plugin id `mcp-http`，it27.1 新增）

手写最小 Streamable HTTP 客户端：POST JSON-RPC、JSON 与 SSE 响应分流、`Mcp-Session-Id` 捕获回传、`MCP-Protocol-Version` 头、GET SSE 通知通道（405/中断只 WARN）、404 会话失效（安全请求重做；`tools/call` 不自动重试）、DELETE 收尾；`HttpClient.Redirect.NEVER`（不跟重定向）、建连 10s 与单请求超时分离、断线 500ms→30s ×10 有限重连；**T7 8 MiB 帽同时覆盖响应体与单条 SSE 事件**。配置面（`McpHttpServerConfig`）校验：URL 拒 userinfo / fragment / **敏感 query 参数名**（percent-decode 后过 `ConfigValues.isSensitiveKey` 同一词表）、headers 拒保留头与控制字符、大小写不敏感重名 fail loud。连接只向 `mcp-registry` 注册，**不自己 provide `McpClientService`**（两个 transport 可同时在场，消费面只经注册表聚合）。

### Registry（`harness.mcp.mcp`，plugin id `mcp-registry`）

注册表是传输中立的组合面：server id 全局唯一（跨 transport 撞 id fail loud，不静默遮蔽）；`connections()` 按 id 排序；注销凭据随提供者 scope 收口（R3）。装配期零 server = 空列表（零连接、零网络、零进程、零事件、零工具）。stdio 与 HTTP 提供者只 `register`，**同一 key 不重复 provide**（kernel 同 scope 重复 provide fail loud）。

### Consumer（`harness.mcp.tool`，plugin id `mcp-tool`）

经 `McpClientService` 取连接面 → 候选快照全量构建（publicName / schema 映射 / 快照内去重 / 外部撞名预检）→ **预检先于注册面改动** → 换面（先 Dispose 旧句柄、再注册新句柄）。采纳只在**轮屏障**（T4）：`TURN_STOPPING`（notifyOrdered，监听异常包 `CompletionException`）换面；`PRE_STEP`（waterfall，异常原样传播；只有 `PreStepDecision.Enter` 才补换代并落账）。**轮内清单变化一律推迟**——REQUEST 之前 schema 已被读取，换面会造成请求 schema 与执行账本错位。预检失败 → `McpCatalogSwapException`：旧代保活、本轮 `TurnEndReason.Error`、server 仍是 `CONNECTED`，下一 `PRE_STEP` 重试；**被拒轮既不采纳也不落账**。`mcp/catalog` 每代全量快照（`ignorable=false`；**无 generation 字段，日志顺序即代际**，T8）：首非空 / 内容变化 / 空集退役才 append，与上代相等静默。请求 schema 读取用稳定读（前后代际一致，3 次探测防撕裂）。运行期**不 import transport**；插件 `requires "mcp-registry"` 是组合面绑定，transport 形态对消费面不可见。

结果处理：单条工具结果 **256 KiB 上限**（`mcp-tool.maxResultBytes`，正整数可配；UTF-8 完整字符边界；`" (output truncated)"` 标记不计入上限；成功与工具级错误同口径）。transport 的 T7 8 MiB 帧帽是**读侧协议上限**，与本上限分层——前者防单帧爆内存，后者控模型上下文面。schema 映射结果按 `(serverId, rawName, schema)` 缓存，WARN 每键一次；缓存键集随采纳代收缩（有界）。

### 公开命名（T2，纯函数）

`mcp__<serverId>__<raw>`；serverId 配置面拒含 `__`（`mcp__a__b__c` 拼接二义消解）。raw 合法（非空、无 `__`、仅 `[A-Za-z0-9_-]`、总长 ≤ 64）→ 原样；否则清洗（非法字符→`_`、折叠 `_` 串、去边界 `_`）+ 按剩余预算截断 + 追加 `_<hash8>`（`sha256(serverId \0 rawName)` 前 8 hex）并 WARN——同名确定性、长度有界，不静默丢工具。`mcp/catalog` 条目**直接记 publicName**，fold 不重跑算法；读侧缺 `publicName` 的旧账 fail loud（算法版本进 schema 事实）。

### Schema 边界（白名单）

wire JSON Schema → `ValueSchema` 白名单映射：`type`（object/string/number/integer/boolean/array，递归）+ `description` + `properties`/`required`（未列者包 `Opt`）+ `items`（单对象形）；`enum`/`const` 降级拼进 description（**提示，不执法**）。不支持构造（`oneOf`/`anyOf`/`allOf`/`not`/`$ref` 系/if-then-else/`prefixItems`/`contains`/依赖族/`unevaluated*`/`additionalProperties` 模式形/白名单外关键字）→ **该工具排除 + WARN**；顶层非 object 同排除；属性级结构失败 → 该属性排除 + WARN、工具仍注册。远端结果 `content` 文本化面统一（`outputSchema` 不消费）。

### 状态、取消与 T4 定论

`CONNECTED` / `RECONNECTING` / `FAILED` 三态只描述连接可用性；清单变化经 `catalogGeneration()` 表达，消费面在轮屏障比较代际决定是否换代。`FAILED` 时工具面保留当前清单，`call` 快速失败（连接错误结果降级，不静默挂起、不牵连当前轮）。取消沿 `AbortSignal` 传播（checkAbort 轮询 + onCancel 中止等待并尽力通知远端；取消是正常路径，`AbortedException` 向上传播，不落工具错误结果）。

**T4 热换代已落地（it27.1）**：单线程轮屏障下免双代——`TURN_STOPPING` 覆盖正常轮末，`PRE_STEP` 覆盖轮首补漏与被拒轮；两挂点各自必要（此前 M2/M3 突变证明删任一即漏换代）。

### dsh 对照挂账（it27 收口登记；it27.1 复核维持，T9）

`oneOf` 组合语义不映射；`enum`/`const` 只提示不执法；`outputSchema` 不消费；`taskSupport`（任务协商）不实现；`_hash8` 与合法 raw 名理论上可撞（如 raw 恰为 `x_1234abcd`），注册表 fail loud，退路为 dsh hash12 或前缀转义（命名算法变更须重裁 catalog 条目）。五项均为消费面语义，与 transport 无关——it27.1 引入 Streamable HTTP 后逐项复核，结论不变。

---

## 7.2 完整 Seam：Web（it28；双 Provider + 信任前缀）

### Definition（`harness.web.web`，零第三方）

```java
public interface WebFetchService {
    ServiceKey<WebFetchService> KEY = new ServiceKey<>("web-fetch");
    String id();
    String displayName();
    boolean available();                                       // 廉价本地检查，绝不联网
    WebFetchResult fetch(WebFetchRequest request, AbortSignal signal) throws WebException;
}

public interface WebSearchService {
    ServiceKey<WebSearchService> KEY = new ServiceKey<>("web-search");
    String id();
    String displayName();
    boolean available();                                       // 如凭据在场
    WebSearchResult search(WebSearchRequest request, AbortSignal signal) throws WebException;
}
```

`WebFetchResult(url, statusCode, contentType, body, truncated)`：`statusCode` 非 2xx **也是结果**（不抛），`body` 是 sealed 联合 `Html`/`Text`（Provider 已按内容策略分类并解码）；`truncated` 是内容事实（读侧上限），不是错误。`WebSearchResult(summary, sources, truncated)` + `WebSearchSource(url, title, snippet, pageAge)`。失败走 `WebException` 携带 9 码 `WebErrorCode`（`WEB_INVALID_URL` / `WEB_BLOCKED_ADDRESS` / `WEB_REDIRECT_BLOCKED` / `WEB_UNSUPPORTED_CONTENT_TYPE` / `WEB_TOO_LARGE` / `WEB_TIMEOUT` / `WEB_NETWORK_ERROR` / `WEB_PROVIDER_UNAVAILABLE` / `WEB_PROVIDER_ERROR`）；取消是 `AbortedException`，不包装、不翻译。`Content-Type` 进结果属**停 1 契约增补**（消费面外显 `Content-Type` 需要，且断言在停 1 测试内钉住）。

### Provider（`harness.web.fetch.http`，plugin id `web-fetch-http`）

钉地址公网抓取，零第三方。**URL 策略**：仅 `https`、拒 userinfo、长度 ≤ 2048、fragment 不参与请求。**地址闸（T2 ③）**：URL 字面 IP 与 DNS 解析结果走同一公网校验闸（IPv4/IPv6/NAT64/6to4/Teredo/mapped 全覆盖），一次解析、整组校验、钉住其一连接——JDK `HttpClient` 无逐请求地址钩子（DNS rebinding TOCTOU），`HttpsURLConnection` 继承进程级 Cookie/Authenticator/代理默认值，故手写 `SSLSocket`（SNI + endpoint identification、ALPN http/1.1）。**重定向（T3）**：301/302/303/307/308 一律按 GET 重发；跨源允许但**逐跳**重跑完整 URL 策略 + 地址闸 + 重新解析 + 重新钉地址；绝不转发请求头/凭据（本就没有）；拒 https→http 降级、坏/缺 `Location`、超 5 跳；相对 `Location` 按当前 URL resolve。**内容策略**：白名单 `text/html`、`application/xhtml+xml`（→ `Html`）与其余 `text/*`、JSON/XML 及 `+json`/`+xml`（→ `Text`）；缺头或不支持即 `WEB_UNSUPPORTED_CONTENT_TYPE`；charset 缺省 UTF-8，非法字节替换 U+FFFD（内容事实，不猜编码）；只接受 `Content-Encoding: identity`（T2 ②：服务器回 gzip/br/deflate 即显式 `WEB_UNSUPPORTED_CONTENT_TYPE`，不解码不猜测不重试）。**上限/超时/取消**：`Content-Length` 声明 > `maxBodyBytes`（缺省 2 MiB）→ `WEB_TOO_LARGE`（不读体）；缺 CL 或 chunked 读到上限即停收、置 `truncated=true`；超时 30s（连接 10s，读用剩余时间，可配）；`onCancel` 关闭 socket 唤醒阻塞读，捕获 `IOException` 后**先** `checkAbort()` 再翻译 `WEB_NETWORK_ERROR`。HTTP/1.1 与 chunked 手写解码（含 trailer 忽略、截断探测）。测试缝（resolver / gate / `SSLContext` 注入）只服务测试，不演化为生产回调面。

### Provider（`harness.web.search.deepseek`，plugin id `web-search-deepseek`）

DeepSeek 原生搜索（Jackson 边界）：`POST {baseUrl}/messages`（缺省 `https://api.deepseek.com/anthropic/v1`，模型缺省 `deepseek-v4-flash`，`anthropic-version: 2023-06-01`，`max_tokens` 4096，`max_uses` 5），请求体与 dsh 同形（`messages[0].content[0]` 为 `text`，文本 `Perform a web search for the query: <query>`；tools 单条 `web_search_20250305`）。映射只认结构化面：遍历 `content[]` 的 `web_search_tool_result` 块 → `web_search_result` 项（`url`/`title`/`page_age`）；snippet 来自 `text` 块 `citations[]` 的 `cited_text`（按 url 首次出现，dsh 同口径）；按 url 去重；`maxResults` 在 seam 侧截断并置 `truncated`。**无 `web_search_tool_result` 块 = `WEB_PROVIDER_ERROR`，绝不把散文答案刮成来源**；块在但零结果 = 合法空结果。传输 `Redirect.NEVER`、超时/取消/响应上限/错误分档；**凭据只进请求头**（不入日志/错误/结果）；`available()` 以 key 在场为准（keyless 组合该行 `disabled`，工具面少 `web_search`）。**错误消息有界**：非 2xx 错误体只读 16 KiB 前缀（保留状态码，超限不因体积再报错），消息经凭据遮断后截到 512 字符。

### Consumer（`harness.web.tool`，plugin id `web-tool`）

`web_fetch` **恒注册**：抓取 provider 缺失即装配期 `scope.require(WebFetchService.KEY)` fail loud（网络权限由组合者显式给）。`web_search` 仅在搜索 provider 在场且 `available()` 时注册——缺 key 是**少一个工具**，不是运行时失败（工具面双态：keyless 12 / keyed 13）。两工具均 `ToolDefinition.of`（**恒需审批，无豁免出口**）；单字段 schema（`url` / `query`）。`WebException` → 带稳定码的错误结果 `CODE: message`；`AbortedException` 原样穿透。

**信任前缀（T7）**：`WebContentTrust.EXTERNAL_WEB_CONTENT_NOTICE` 单源常量——两个成功结果首行恒为它；同一常量注册 `PromptSection.Static(70, …)` 系统提示段（plan=50、skill=60 之后；只读事实，R1 无损）。这是**软防线**：硬边界是公网地址闸、无凭据转发、审批与审计（B17 残余登记）。

**分层截断**：源码先按 `maxSourceChars`（缺省 512K 字符）截断；HTML 走线性提取（script/style/模板等剔除，标签中间截断 fail-soft）；再按 `maxOutputBytes`（缺省 256 KiB）在 **UTF-8 完整字符边界**截断（按码点累计字节、不整串编码；未配对代理按 3 字节计，宁早截不越上限；`" (output truncated)"` 标记不计入上限）；搜索侧 `maxResults`（缺省 8）截断、零来源显示 `No sources returned`；搜索结果首部外显 provider/model（辅助调用可审计）与来源字段实况 `Sources: N (titles: x, snippets: y)`——只陈述实际存在的字段，不诱导模型声明数据不支持的来源；工具描述同步声明 snippet 仅在 provider 提供时出现。**零新事件**：审计事实 = `tool/call` + `tool/result`，外部内容随结果入账，回放零联网。

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
    /** 当前 scope 可见的定义（同 schemas 的层合并口径；治理自述读它，R4，it22）。 */
    List<ToolDefinition> definitions(Scope scope);
    /** 该 scope 可见范围内按名解析。 */
    Optional<ToolDefinition> resolve(Scope scope, String name);
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

            // 3. 工具解析（先于审批：未知工具不改结果，问是噪声）
            ToolDefinition tool = registry.resolve(agentScope, call.name());
            if (tool == null) {
                return appendResult(call, ToolExecutionResult.error("Unknown tool"), turn, step);
            }

            // 4. 审批（固定 stage；免审批声明跳过——提问类工具不是副作用；拒绝 → error result，不炸 turn）
            if (!tool.approvalExempt()) {
                approval.require(new ApprovalRequest(summarize(call), call.arguments()), signal);
            }

            // 5. 执行（异常 → error result，即错误是数据；AbortedException 传播）
            ToolExecutionResult result = tool.tool()
                .execute(ToolArgs.parse(call.arguments(), tool.parameters()), execCtx(signal));

            // 6. tools/post-execute（waterfall）：观察/改写结果（spill 大输出等）
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
            new ToolResultBlock(call.id(), r.content(), r.isError()),
            r.concludesTurn(), new SurfaceOp.Append(), null));   // 停轮位随结果落账（it22）
    }                                                            // 无条件落账
}
```

**超时**作为固定 stage 由 resolve 携带（`ShellRequest.timeout` 等）+ provider 侧 waitFor/kill 兜底；wall-clock 上限是 ToolExecutor 的 ConfigService 配置项（可调参数在 config，07 §4），到点经 signal 取消。

### ToolDefinition 与 RenderIntent

```java
public record ToolDefinition(
    String name,
    String description,
    ValueSchema parameters,
    RenderIntent render,              // generic / terminal / diff / locations —— 设计期声明
    Tool tool,                        // ToolExecutionResult execute(ToolArgs, ToolExecutionContext)
    boolean approvalExempt            // 免审批声明（it22）：提问类工具不是副作用，审批 stage 跳过
) { /* of(...) / ofExempt(...) 工厂 */ }
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

### 实现落定（it22）：免审批声明与停轮结果

- **pipeline 顺序定稿**：批前导落账全部 `tool/call` → 派发前耐久屏障 → pre-execute waterfall → **工具解析** → 审批 → 执行 → post-execute waterfall → 审计落账 `tool/result`。解析**先于**审批：未知工具不改结果（无副作用可言），先问人是噪声——`--approval=ask` 下模型编造的工具名不该弹 y/N。
- **免审批是工具属性声明，不是第四种审批模式**：`ToolDefinition.ofExempt(...)` 置 `approvalExempt=true`（`of(...)` 缺省要求审批），executor 据此**跳过审批 stage**。名可从 `ToolRegistry.definitions(Scope)` 枚举——治理摘要的 `approval:` 行点名免审批工具（07 §6），`--approval=deny` 档下免审批工具仍可执行（提问不是副作用，拒批不该堵住澄清）。
- **停轮位（`concludesTurn`）从声明到落账贯通**：`ToolExecutionResult.concluding(content)` 是第三种态（成功且停轮；`success`/`error` 恒 `false`），`appendResult` 取 `result.concludesTurn()` 落盘。消费侧（agent-loop）与 wire 侧（codec）在位已久——it22 前唯一缺环是 executor 硬编码 `false`（预留位空置无生产者）。
- **`ask_user` 的答复通道 = 下一轮 user message**：提问是正常结果 + 停轮，答复是下一条 `user/message`——问答两半都是日志事实，回放不重放提问（R1），也不需要任何输入通道（非交互场景结构上不可能挂等）。

---

## 9. 完整 Seam 清单（MVP + 留接口）

| Seam | Definition 模块 | MVP Provider(s) | Consumer | 状态 |
|---|---|---|---|---|
| LLM | `llm.llm` | `deepseek`, `replay` | agent-loop | ✅ |
| Tools | `core.tools` | （registry+executor 内建） | 各 tool 模块 | ✅ |
| FS | `fs.fs` | `local` | `fs.tool` | ✅ |
| Shell | `shell.shell` | `local` | `shell.tool` | ✅ |
| Sandbox | `sandbox.sandbox` | `local`（OFF 透传）| shell/terminal/fs | ✅ stub |
| Session Persistence | `session.persistence` | `jsonl` | persistence 插件 | ✅ |
| Approval | `interaction.approval` | `auto` / `ask` / `deny` | ToolExecutor 固定 stage | ✅ **真实** |
| Commands | `interaction.commands` | （registry 内建） | headless REPL | ✅ |
| MCP | `mcp.mcp` | `mcp-stdio`（stdio）+ `mcp-http`（Streamable HTTP，it27.1）；两者经 `mcp-registry` 聚合 | `mcp.tool` | ✅（it27.1；轮屏障换代 + `mcp/catalog` + 双 transport）|
| Web | `web.web` | `web-fetch-http`（钉地址公网抓取）+ `web-search-deepseek`（原生搜索，keyless 行 disabled）| `web.tool` | ✅（it28；双 Provider + 信任前缀 + 分层截断）|
| Subagent / LSP / Terminal / Compaction | 各 Definition | —（留接口）| — | 🔌 接口 |

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
| `ToolDefinition` + defineTool DSL | `ToolDefinition.of(...)`（GENERIC，要审批）/ `ofExempt(...)`（免审批）；非 GENERIC 渲染走记录构造器 | 名称/描述/schema/执行体四参数 |
| `tools/pre-execute` / `post-execute` waterfall | `ToolEvents.PRE/POST_EXECUTE` waterfall | |
| 审批在工具内可选挂 | **ToolExecutor 固定 stage + 构造器强制** | R2/R4 收紧 |
| 工具自己写日志/结果 | **executor 拥有 tool/call + tool/result 落账** | R2 |
| `RenderIntent` | enum（`GENERIC`/`TERMINAL`/`DIFF`/`LOCATIONS`） | 实况；`of(...)` 恒 `GENERIC`，其余走记录构造器 |
| LLM 流式 AsyncIterable | 阻塞 `Stream<StreamChunk>` + 有界队列 | 无协议税 |
| capability graph | 扫描 module-info 生成 | 可选 |
