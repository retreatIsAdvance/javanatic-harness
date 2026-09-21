# 03 · Session — Event Sourcing

这是整个系统最有原则性的部分。**Session 不是对话历史，而是一个 append-only 的事件日志**，模型看到的 Message 列表是它的投影。

本篇同时是不变式 **R1（可重建性）** 的载体：见 §8。

## 1. 核心决策：事件类型体系（不靠 declaration merging）

dsh 用 TypeScript 的 `declare module` 做编译期 declaration merging 扩展 `SessionEventMap`。Java 没有等价物。三个候选方案：

| 方案 | 优点 | 缺点 | 选不选 |
|---|---|---|---|
| A. 全部事件一个 `sealed interface` | 编译期穷尽检查 | 扩展插件无法加新事件类型 | ❌ 违背 dsh merge-extensible 精神 |
| B. 运行时注册表 | 可扩展 | 失去 switch 穷尽检查 | ⚠️ 单独用不够 |
| **C. sealed 核心 + 注册表扩展** | 核心事件穷尽检查，插件可扩展 | 两套机制并存 | ✅ |

### 方案 C 的设计

**`seq` 不在事件上**：序号是日志的信封（envelope）属性，由 `Session.append` 在锁内统一分配——调用方不可能写错、跳号或并发撞号。事件 record 只携带业务事实。

```java
// io.javanatic.harness.session.event.LoggedEvent
/**
 * 日志条目信封：seq 与事件的配对。seq 单调、从 0 起、等于条目在日志中的下标。
 * 只有 Session.append 会构造它（锁内）；日志外流转的都是裸事件。
 */
public record LoggedEvent<T extends SessionEvent>(long seq, T event) {}
```

```java
// io.javanatic.harness.session.event.SessionEvent
package io.javanatic.harness.session.event;

/**
 * Session 日志的一个不可变事件。
 *
 * sealed：核心事件编译期穷尽；permits 含 14 个核心 record + 1 个 ExtensionEvent。
 * switch(SessionEvent) 配合 ExtensionEvent 分支处理扩展。
 *
 * 注意：seq 不在此（在 LoggedEvent 信封上）；time 在此——
 * R1 规定提示词组装只读日志不读环境时钟，事件自带时间是这条规则的载体。
 */
public sealed interface SessionEvent permits
    TurnStart, TurnEnd, StepStart, StepEnd,
    UserMessageEvent, AssistantMessageEvent, LlmRequestEvent,
    ToolCallEvent, ToolResultEvent,
    CompactionStart, CompactionSummary, CompactionEnd, RequestHeader,
    SessionEndSeedEvent, ExtensionEvent {

    /** Unix epoch 毫秒。 */
    long time();

    /** 事件类型名（持久化 key，跨实现兼容）。 */
    String type();

    /**
     * 读取方可安全跳过的未知事件标记。
     * 默认 false（required）：未知事件拒绝重建，而非静默丢弃。
     * 它由信封行序列化（不在 data 里），读取方无需解码事件即可决定跳过。
     */
    default boolean ignorable() { return false; }
}
```

**核心事件**（14 个 record；均无 seq）：

```java
public record TurnStart(long time, int turn) implements SessionEvent {
    @Override public String type() { return "turn/start"; }
}
public record TurnEnd(long time, int turn, TurnEndReason reason) implements SessionEvent {
    @Override public String type() { return "turn/end"; }
}
public record StepStart(long time, int turn, int step) implements SessionEvent {
    @Override public String type() { return "step/start"; }
}
public record StepEnd(long time, int turn, int step) implements SessionEvent {
    @Override public String type() { return "step/end"; }
}

public record UserMessageEvent(
    long time,
    UserMessage message,
    SurfaceOp surfaceOp,           // surface 事件必填
    List<Long> sourceEventSeqs     // provenance，可空
) implements SessionEvent, SurfaceEvent {
    @Override public String type() { return "user/message"; }
}

// assistant/chunk（流式事实，log-only、投影不读）与 todo/write 不在核心记录里——
// 两者都是扩展事件（家分别在 core/agent-loop、core/todo），机制见下「插件扩展出口」。

public record AssistantMessageEvent(
    long time, int turn, int step,
    AssistantMessage message,
    @Nullable TokenUsage usage,
    SurfaceOp surfaceOp,
    List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {
    @Override public String type() { return "assistant/message"; }
}

/**
 * R1 锚点：每次 LLM 请求的指纹。log-only（非 surface）。
 * 内容不重复存——消息窗口可由 [fromSeq, toSeq] 重投影；哈希证明确定性。
 */
public record LlmRequestEvent(
    long time, int turn, int step,
    String systemPromptSha256,
    String toolsSchemaSha256,
    long messagesFromSeq,          // 本次请求消息窗口的日志区间（闭区间）
    long messagesToSeq,
    Map<String, String> params     // model、temperature 等请求参数
) implements SessionEvent {
    @Override public String type() { return "llm/request"; }
    @Override public boolean ignorable() { return true; }  // 遥测性：旧读取方可跳过
}

public record ToolCallEvent(
    long time, int turn, int step,
    CallId callId, String name, String arguments  // arguments 是原始 JSON 字符串
) implements SessionEvent {
    @Override public String type() { return "tool/call"; }
}

public record ToolResultEvent(
    long time, int turn, int step,
    ToolResultBlock block,         // 模型可见结果（错误即数据，isError 在 block 上）
    boolean concludesTurn,         // 该结果是否终结本 turn（ask_user 类工具为 true）
    SurfaceOp surfaceOp,
    List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {
    @Override public String type() { return "tool/result"; }
}

public record RequestHeader(long time, String cwd, String date) implements SessionEvent {
    @Override public String type() { return "request/header"; }
    @Override public boolean ignorable() { return true; }  // 遥测性：旧读取方可跳过
}
public record SessionEndSeedEvent(long time) implements SessionEvent {
    @Override public String type() { return "session/end-seed"; }
}
```

**插件扩展出口**：

```java
/**
 * 插件扩展的事件类型。核心 switch 用显式分支处理它。
 *
 * 扩展插件实现此接口（不继承核心 record），并在插件加载时注册
 * SessionEventCodec（见 §6）——序列化能力在 codec 上，不在事件上。
 *
 * ignorable() 由扩展实现决定：信息性事件返回 true（未知读取方跳过），
 * 结构性事件返回 false（未知读取方拒绝重建）。
 */
public non-sealed interface ExtensionEvent extends SessionEvent {
    // 扩展自由实现；type() 必须返回稳定的字符串 key
}
```

**TurnEndReason 是开放接口**（对应 dsh 的 merge-extensible TurnEndReasonMap）：核心变体 Completed/Aborted/Error 嵌套其中，agent-loop 等切片追加自己的变体，消费方 switch 用文档化默认分支。`Error(message, kind)` 携 `FailureKind` 失败分类（`AUTH/RATE_LIMIT/SERVER/NETWORK/TIMEOUT/PROTOCOL/OVERFLOW/UNKNOWN`）——词汇家在 core/session，llm 的 `LlmCallException.Kind` 由 agent-loop 做穷尽 switch 映射（`llm requires session`，核心类型反向引用成依赖环）；消费方按 kind 渲染可行动文案，不解析消息文本。**SurfaceEvent 是独立标记接口**（不继承 SessionEvent——sealed permits 之外的事件侧出口）：事件同时实现两者才携带 surface 元数据；扩展事件走 `ExtensionEvent + SurfaceEvent` 组合。

**`assistant/chunk` 的机制结论（it14）**：chunk 是扩展事件（家 `core/agent-loop`）+ ServiceLoader codec，**不进** sealed permits——`StreamChunk` 属 `llm` 且 `llm requires session`，核心 permits 引用它成依赖环（JPMS 编译期即拒）；在 session 另造 chunk 词表则同一 wire 事实两个家（违「一个事实只有一个家」）。log-only、投影不读（装配仍走 assistant/message），`ignorable=true`（信息性——未知读取方跳过不改变重建语义）。

**与 dsh 事件清单的差异**：dsh 另有 `request/context`（provider/model/contextWindow 路由元数据，不参与重建）——JH 事件集暂缺，随 llm 切片补进；JH 的 `LlmRequestEvent`（哈希 + 窗口）是 dsh 没有的 R1 显式化（dsh 在 request/header 存 system/tools **原文**，JH 存指纹靠重组装验证——日志更轻，代码演进漂移会被回放暴露）。

**编译期穷尽 + 运行时扩展的共存**：Java 25 的 switch pattern matching 对 sealed 做穷尽检查（21 起 final）：漏掉一个核心分支编译报错；`ExtensionEvent` 是 `non-sealed`（开放），switch 用显式分支覆盖。这复刻 dsh 的 "closed union ends in assertNever; merge-extensible unions fall through documented default"。

## 2. SurfaceEvent — 产生消息的事件子集

只有三种事件进入有序 surface（投影成模型看到的 Message）：

```java
// io.javanatic.harness.session.event.SurfaceEvent
/** 标记接口：产生 LLM 消息的事件子集。编译期保证只有它们携带 SurfaceOp。 */
public interface SurfaceEvent {
    SurfaceOp surfaceOp();
    List<Long> sourceEventSeqs();
}

/**
 * 事件如何进入有序 surface：
 * - Append：尾追加（普通 user/assistant/tool 消息）
 * - Replace：替换 surface 中 [start, end]（inclusive）的节点，用于 compaction 摘要
 */
public sealed interface SurfaceOp permits SurfaceOp.Append, SurfaceOp.Replace {
    record Append() implements SurfaceOp {}
    record Replace(long start, long end) implements SurfaceOp {}
}
```

## 3. Session — append-only 日志

```java
// io.javanatic.harness.session.Session
package io.javanatic.harness.session;

/**
 * Event-sourced session：append-only 的 SessionEvent 日志 + 信封序号。
 *
 * - seq 在 append 锁内分配（调用方无法提供），连续性结构性成立
 * - 事件在 append 时结构冻结（不可变快照）；序列化不在这一层（codec 属持久化 seam）
 * - Message 历史是 derived（deriveMessages），不单独存储
 *
 * Plain class（非 Service）：经 SessionStore 创建。
 */
public final class Session {

    private final SessionId id;
    private final SessionHeader header;
    private final List<LoggedEvent<? extends SessionEvent>> log = new ArrayList<>();
    private final SurfaceManager surface = new SurfaceManager();
    private long firstLiveSeq = 0;

    // ────────── 构造（replay / fork / resume）──────────

    static Session create(SessionId id, List<SessionEvent> seed, SessionHeader header) {
        Session s = new Session(id, header);
        for (SessionEvent e : seed) {
            s.log.add(new LoggedEvent<>(s.log.size(), structuralFreeze(e)));
            s.surface.commit(s.log.getLast());   // seed 重放也要推进投影
        }
        s.firstLiveSeq = seed.size();
        return s;
    }

    // ────────── append（唯一写入口，锁内完成全部四步）──────────

    /**
     * 追加事件：seq 分配、冻结、surface 校验与提交、观察者通知，全部在 同一把锁内。
     *
     * @throws IllegalArgumentException surface 元数据非法（replace 范围、provenance）
     */
    public synchronized <T extends SessionEvent> LoggedEvent<T> append(T event) {
        // 1. 结构冻结：List/Map 递归 copyOf，杜绝调用方可变输入污染日志
        T frozen = structuralFreeze(event);
        // 2. seq 分配（锁内，调用方无参与）
        LoggedEvent<T> entry = new LoggedEvent<>(log.size(), frozen);
        // 3. surface 校验 + 提交（校验失败则不落日志——先验证后变更）
        if (frozen instanceof SurfaceEvent se) {
            surface.validateCandidate(entry.seq(), se);
            surface.commit(entry);
        }
        // 4. 落日志
        log.add(entry);
        return entry;
    }

    // ────────── 投影与访问（快照读，同样持锁）──────────

    /** 派生 LLM 消息历史（surface node 投影，带缓存）。 */
    public synchronized List<Message> deriveMessages() {
        return surface.projectMessages(logSnapshot());
    }

    public synchronized SessionSurface surface() { return surface.view(); }
    public synchronized Optional<EpochHeader> requestHeader() { return foldRequestHeader(logSnapshot()); }

    public SessionId id() { return id; }
    public SessionHeader header() { return header; }
    public synchronized List<LoggedEvent<? extends SessionEvent>> events() {
        return List.copyOf(log);          // 快照：调用方遍历时不受并发 append 影响
    }
    public synchronized long seq() { return log.size(); }
    public long firstLiveSeq() { return firstLiveSeq; }

    private List<LoggedEvent<? extends SessionEvent>> logSnapshot() { return List.copyOf(log); }
}
```

### structuralFreeze — 不可变快照（构造时归一实现）

不可变快照不走 append 时点的递归拷贝函数，而是**由每个 domain record 的 compact 构造器实现**：List/Map 组件在构造时 `copyOf` 归一（拒绝 null 元素），调用方可变输入在构造那刻即被隔离——append 收到的已是不可变图。相比"序列化往返冻结"（JSON dump + parse）零反射/注解依赖；代价是不再顺带证明"可 JSON 可序列化"——该证明移到持久化边界（§6 codec），这是有意的分层：不可变性是 Session 域的性质，可序列化是持久化 seam 的性质。手写 `ExtensionEvent` 实现者自负同责（Javadoc 契约）。

## 4. SurfaceManager — 有序 surface 投影

```java
// io.javanatic.harness.session.surface.SurfaceManager
class SurfaceManager {

    private final List<Long> nodes = new ArrayList<>();       // surface node 的 seq（有序）
    private int replaceGeneration = 0;                        // 区分"纯追加"vs"重写"
    private final Map<Long, Message> messageCache = new HashMap<>();

    /** 校验候选事件能否提交（不改任何状态）。provenance 在变更前检查。 */
    void validateCandidate(long seq, SurfaceEvent event) {
        switch (event.surfaceOp()) {
            case SurfaceOp.Append a -> { /* 无额外约束 */ }
            case SurfaceOp.Replace r -> {
                int startIdx = indexOf(r.start());
                int endIdx = indexOf(r.end());
                if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) {
                    throw new IllegalArgumentException(
                        "Replace range invalid: [" + r.start() + "," + r.end() + "]");
                }
                long[] shadowed = nodes.subList(startIdx, endIdx + 1)
                    .stream().mapToLong(Long::longValue).toArray();
                assertProvenance(event.sourceEventSeqs(), shadowed, seq);
            }
        }
    }

    /**
     * provenance 规则（对应 dsh，含全部细则）：
     * 0. sourceEventSeqs 缺省（null）= 不记录来源，除 Replace 外合法；
     *    Replace 必须提供且覆盖 shadowed 段；
     *    非 surface 事件携带 surface 元数据在 JH 由类型系统拒绝（SurfaceEvent 接口之外无此元数据）；
     * 1. sourceEventSeqs ⊇ 全部被 shadow 的 seq；
     * 2. 所有 source seq < 当前 seq（不许引用未来）；
     * 3. 无重复；
     * 4. 显式空列表仅 assistant/message 合法（已知空流），其余事件提供时必须非空。
     */
    private static void assertProvenance(List<Long> sourceSeqs, long[] shadowed, long seq) { /* ... */ }

    /** 提交（append 追加；replace 换段并失效被 shadow 的投影缓存）。 */
    void commit(LoggedEvent<? extends SessionEvent> entry) { /* 见下表语义 */ }

    /** 投影为 Message 列表（缓存；replace 时失效被 shadow 段）。 */
    List<Message> projectMessages(List<LoggedEvent<? extends SessionEvent>> log) { /* ... */ }
}
```

| 操作 | validate | commit |
|---|---|---|
| Append | 无额外约束 | `nodes.add(seq)` |
| Replace[start,end] | 范围有效 + assertProvenance | 换段为新 seq、`replaceGeneration++`、失效被 shadow 段缓存 |

### DeriveMessage — 每个事件的投影规则

```java
class DeriveMessage {
    /** 把一个 surface node 投影成 Message。null = 不产生消息（空 content 截断等）。 */
    static Message project(SessionEvent e) {
        return switch (e) {
            case UserMessageEvent um ->
                new UserMessage(um.message().content(), um.message().source());
            case AssistantMessageEvent am ->
                am.message().content().isEmpty() ? null : am.message();
            case ToolResultEvent tr ->
                new UserMessage(List.of(new ToolResultBlock(tr.message())), MessageSource.tool());
            default -> throw new IllegalStateException("Non-surface event in surface: " + e.type());
        };
    }
}
```

## 5. SessionStore — 活跃会话管理与事件键

```java
// io.javanatic.harness.session.SessionEvents
/** session 域的事件键常量（Definition 持有，Provider/Consumer 引用）。 */
public final class SessionEvents {
    public static final EventKey<Session> CREATED =
        EventKey.notify("session/created", Session.class);
    public static final EventKey<Session> DISPOSED =
        EventKey.notify("session/disposed", Session.class);
    /** 持久化 barrier：notifyAndWait，全部 flush listener 完成才返回；任一失败使 barrier 失败。 */
    public static final EventKey<Session> FLUSH =
        EventKey.notify("session/flush", Session.class);
    public static final EventKey<LoggedEvent<? extends SessionEvent>> APPENDED =
        EventKey.notify("session/appended", LoggedEvent.class); // unchecked：见 08 §2
}
```

```java
// io.javanatic.harness.session.SessionStore
/**
 * 活跃会话存储：Session 实例的内存仓库。对应 dsh 的 ctx.sessions。
 * 持久化不在此实现：持久化插件订阅 session/appended 异步落盘。
 */
public final class SessionStore {

    private final ConcurrentHashMap<SessionId, Entry> store = new ConcurrentHashMap<>();

    /** 创建会话。header 携带组合清单（R1，见 §8）。 */
    public Session create(Scope owner, SessionId id, CreateOptions options) {
        Session session = Session.create(id, options.seed(), options.header());
        store.put(id, new Entry(session));
        owner.events().onGlobal(SessionEvents.CREATED, (carrier, s) -> { });
        owner.onClose(() -> store.remove(id));
        return session;
    }

    public Session get(SessionId id) { /* ... */ }
    public List<Session> list() { /* ... */ }

    /** 从 source 会话的稳定前缀 fork：seed 校验"不能结束在 open turn 内"。 */
    public Session fork(Scope owner, SessionId source, Long boundary, SessionId childId) { /* ... */ }

    /** 持久化 barrier：等所有持久化 listener 完成。 */
    public CompletableFuture<Boolean> flush(Scope origin, Session session) {
        origin.require(Runtime.KEY).events()
            .notifyAndWait(SessionEvents.FLUSH, origin, session, session);
        return CompletableFuture.completedFuture(true);
    }
}
```

`SessionStorePlugin`（id `session-store`）在 `apply(Scope)` 注册 `SessionStore` 服务。

**观察者接线（迭代 2 实现）**：dsh 经模块私有 WeakMap attachment 让 Session.append 触达 store 的发布钩子；JH 改为**构造注入**——`Session.create(id, seed, header, observers)` 持不可变 `Observer` 列表，store 注入 `(s, e) -> bus.notify(APPENDED, owner, s, e)`。观察者逐个 contained（异常记日志，不影响 append 返回值）；**防重入**：观察者内再 append 抛 `IllegalStateException`（dsh 教训——synchronized 同线程可静默重入，交错即腐化）。发布事件需拿总线：kernel `Runtime` 现以 `Runtime.KEY` 把自己注册为 root 服务，任意 scope `require` 后经 `events()` 派发。end-seed 由构造器自动补记（seed 已以其结尾则不重标），构造路径不通知观察者——持久化在创建时快照全量日志即可看到 marker。

## 6. 持久化 — codec 属于 seam

### SessionEventCodec — 序列化在持久化边界

```java
// io.javanatic.harness.session.persistence.SessionEventCodec
/**
 * 一种事件类型的序列化器。核心 14 种的 codec 由 jsonl provider 实现；
 * 扩展事件的 codec 经 ServiceLoader 发现（it11 落定：事件模块 module-info
 * provides + META-INF/services 双注册，持久化 provider 发起 load——
 * 注册不依赖插件装载行序，未装载事件插件的组合也能回放其日志）。
 * domain record 上零 Jackson 注解——持久化不反向腐蚀 Definition。
 */
public interface SessionEventCodec<T extends SessionEvent> {
    String type();                    // 与事件 type() 一致，注册时校验
    Class<T> typeClass();
    void write(T event, JsonSink out);
    T read(JsonSource in);
}

/** codec 注册表：type → codec。重复注册 fail loud；核心 codec 由 provider 预置。 */
public final class SessionEventCodecs {
    public static Disposable register(Scope scope, SessionEventCodec<?> codec) { /* ... */ }
    public static Optional<SessionEventCodec<?>> forType(String type) { /* ... */ }
}
```

**fail-loud 分层**（对应 dsh "self-contained at load, otherwise at earliest resolvable point"）：

- codec 重复注册 / type 不匹配：注册时抛。
- 事件类型无 codec 却到达持久化：**该类型首次 flush 时抛**——序列化能力属于持久化边界，Session.append 不感知 JSON。
- 加载时未知 type：信封行的 `ignorable` 字段为 true 则跳过（无需解码事件体），否则拒绝重建。

**扩展事件的生产实例（it11）**：`todo/write`（整表快照，log-only）与 `plan/mode`（模式翻转，log-only）均走 `ExtensionEvent` 出口——不动 sealed permits、零核心 switch 改动，各自带 ServiceLoader codec。两者的 `ignorable=false`：todo 静默丢快照丢已落账状态、plan/mode 静默丢提示词依据（毁 R1 重建），未知读取方应拒绝而非跳过。

**（it14）第三个生产实例 `assistant/chunk`**：流式事实（逐 chunk 落账，渲染/TTFT 消费），`ignorable=true`（投影不读，装配仍走 assistant/message）。机制结论与归属（家 `core/agent-loop`，不进核心 permits 的依赖环理由）见 §1。

### JSONL 布局与行格式

```text
~/.harness/sessions/<sessionId>/header.json   ← SessionHeader（含组合清单）
~/.harness/sessions/<sessionId>/log.jsonl     ← 逐行信封
~/.harness/sessions/<sessionId>/.writer.lock  ← 写者锁（FileChannel.tryLock，it19）

行格式（信封字段 + data；ignorable 在信封层，未知类型可不解码即决策）：
{"seq":12,"type":"tool/result","ignorable":false,"data":{...}}
```

```java
// io.javanatic.harness.session.persistence.jsonl.JsonlPersistence
/**
 * JSONL 后端：订阅 session/appended 增量 append 行（记录 lastFlushedSeq，
 * 断点续写）；save() 全量重写（fork 导出用）；load() 逐行重建并校验 seq 连续。
 */
public final class JsonlPersistence implements SessionPersistence {
    /* load: 读行 → 校验 envelope.seq == 行号（跳号/重复拒绝加载）
             → codec 查找 → 未知 type 时按信封 ignorable 决策 */
}
```

### 实现落定（it6）

- **JsonValue 树**:codec 以 seam 自有的 JSON 树成为纯函数(seam 零 Jackson);Jackson 互译归 jsonl 后端。初稿的 JsonSink/JsonSource 流式端口未采用——树形状可无 Jackson 单测。
- **注册表是服务**:`SessionCodecRegistry`(ServiceKey "session-codecs")而非静态表——注册即 effect、随插件 scope 回收(R3);初稿的静态 register 造型会泄漏。
- **APPENDED 改 notifyOrdered**:异步派发下两次 append 的落盘任务可能交错,JSONL 行序无法保证;同步顺序派发以 append 路径内联文件写为代价(单进程、逐事件 flush,当前量级可接受;异步双写随持久化后续)。
- **写侧 fail loud 的路径**:session append 观察者异常按契约 contained(记日志不炸 append)——无 codec 的类型在观察者内只留 ERROR 日志与数据缺口,真正的 fail loud 显形在 `save()` 直调路径。扩展事件插件必须随插件注册自身 codec(装载期组合责任)。
- **（it10）compaction 词表**:`compaction/start`(日志锁)+ `compaction/summary`(审计:摘要文本 + provider/model/usage——维护调用 R1 可重建 + shadowed 区间)+ `compaction/end`(解锁,error 记失败),全部 log-only ignorable;摘要本体走 `user/message` + `Replace` + `MessageSource.Compaction`——surface 事件类型不扩展(dsh 形状)。切点按 tool 配对边界(非整轮);估价 chars/2.5 + 结构开销(agentscope 校准);触发用末次 inputTokens 实数;阈值 = `contextWindow × thresholdRatio(默认 0.8)` 或绝对 `maxContextTokens` 覆盖,容量未知 apply 即 fail loud(不猜窗口大小,base 行默认 disabled);溢出恢复在 loop 的 request-error catch(错误串匹配 + 强制压缩 + 同 step 有界重试)。`request/header`(cwd+ISO 日期)轮首落账,提示词组装读最新值追加上下文 section。
- **（it10）user/message codec 持久化 Replace 语义**:surfaceOp Replace + sourceEventSeqs 进盘(此前只写 Append)——压缩 checkpoint 重载后投影不丢,R1 补洞。
- **（it14）chunk 落账与 Error kind**:`assistant/chunk` 边消费边落(折叠装配语义不变,渲染/TTFT 消费);`turn/end` 的 `Error` 增 `kind` 编解码——**旧日志缺 kind → 回放缺省 `UNKNOWN`**(message 保留、渲染回退 message-only,durable resume 不断)。
- **（it19）单写者保护**:会话目录级 `.writer.lock`(`FileChannel.tryLock`,不等待)——第二写者(另一进程或同 JVM 另一实例)占用即 `WriterLockException` fail loud,拒绝而非合并;写者随 CREATED backfill 创建,随 DISPOSED 或持久化插件 scope 收拢释放(幂等双路径);`load` 对外来写者先试锁(占用即拒——撕裂尾修复是写操作,不得与在写者并发),本实例为写者时在写者 monitor 内修复+读。`SessionStore.create` 对已存在 id 改 `putIfAbsent` + fail loud(静默覆盖会让两个 owner 的 `onClose` 交叉移除)。
- **（it19）屏障对账口径**:`flushBarrier(expectedSeq)` 先对账(已写行数必须追平 `session.seq()`,不符即抛)再 fsync——append 观察者异常按契约 contained 吞掉的写失败只在此显形;`writeEnvelope` 跳号护栏(信封 seq > 已写行数即拒),洞不被后续写假性追平。派发前调用点见 04 §13;`SessionStore.flush` 把 listener 失败包成 `DurabilityException`(收敛为 `FailureKind.DISK`)。
- **（it19）恢复收口**:resume 装载后由 `SessionRecovery` 分析未完成尾形(悬空调用 = 消息级 tool_use ∪ 审计级 tool/call − 已配对 tool/result;开着的 turn/step),以恢复事实闭合:**不自动重放**——error `tool/result`(文案「结果未知,可自行核验」,`sourceEventSeqs` 引悬空调用/消息 seq)+ `step/end` + `turn/end(Aborted("interrupted"))`;事实在 end-seed **之后**追加(`firstLiveSeq` 语义内)、分析纯函数、收口幂等。理由:悬空 tool_use 使 resume 后首个真实请求违反 OpenAI 配对契约(400)。
- **（it19.1）surface codec 的 provenance 落盘口径**:写侧 `sourceEventSeqs` **非 null 即写**(与 `Replace` 分支解耦——Append 亦然),读侧按 key 存在即回(此前仅 Replace 落盘,Append——含 it19 恢复事实——重载丢来源,provenance 只是进程内契约);旧日志缺键读为 null(v0 口径,无兼容垫片)。往返矩阵与旧格式读侧用例在 `JsonlPersistenceTest`。

- **load 重建**:逐行信封,seq == 行号校验(跳号/重复拒绝);未知 type 按信封 ignorable 跳过或拒绝;header 往返含 FORMAT_VERSION。

## 7. 不变式（invariant companion）

对应 dsh 的 session invariant。`SessionInvariants.validate(List<LoggedEvent<?>>)` 逐条检查：

- **信封连续**：`seq[i] == i`（load 与 append 双侧结构性保证，此处复核）
- **turn/step 单调且嵌套**：turn 号 = TurnStart 计数（**1 起**）；step = turn 内序号（**0 起**）——两侧起点不对称是**有意口径**（对齐生产 loop 与 04 规格），勿当 bug 修；step 必在 turn 内；tool/call 与 tool/result 同 step 配对
- **provenance**：surface 事件的 sourceEventSeqs 全部 < 当前 seq、无重复；Replace 时 ⊇ shadowed
- **end-seed**：只在 seed 末尾出现一次
- **R1 复核**：LlmRequestEvent 的 `[messagesFromSeq, messagesToSeq] + systemPromptSha256 + toolsSchemaSha256` 重推导一致（回放测试用，见 10）

## 8. 不变式 R1：可重建性

> **R1（可重建性）**：模型任意一轮看到的完整请求内容，可从持久化事实（日志 + 组合清单）重建，且重建的正确性可被机器验证。

模型看到的 = 系统提示词 + 消息投影 + 工具 schema + 参数。逐项的重建路径：

| 内容 | 来源 | 重建方式 |
|---|---|---|
| 消息历史 | 日志 | `deriveMessages()` 纯函数（压缩后亦然——压缩也是事件） |
| 系统提示词 / 工具 schema | 插件集 + 配置 + 会话事实 | 组合清单（SessionHeader 内）+ 代码重组装 |
| 请求参数 | LlmRequestEvent.params | 直接读 |
| 正确性证明 | LlmRequestEvent 哈希 | 重组装后比对 SHA-256 |

三条配套规则，缺一则 R1 为假：

1. **组合清单持久化**：`SessionHeader` 携带 `CompositionManifest`——plugin id + 版本摘要 + 影响模型可见输出的配置值 + harness 版本。无清单的日志无法回答"这份提示词是谁组装的"。
2. **提示词组装只读日志**：组装器读事件（时间取 `event.time()`，不取 wall clock）；环境注入（日期、平台）进组合清单或事件。违反则哈希比对永远失败，回放测试红。
3. **哈希锚点**：每步一条 LlmRequestEvent（§1），内容不重复存，可推导 + 哈希定值。代码演进悄悄改变提示词时，旧会话回放的哈希比对立刻暴露。

回放验证（10 的测试形态）：`load() → deriveMessages() + 重组装提示词/schema → sha256 与 LlmRequestEvent 比对`。全绿 = R1 成立。

## 9. 与 dsh 对齐

| dsh 概念 | JH 实现 | 备注 |
|---|---|---|
| `SessionEventMap`（merge-extensible） | sealed 核心 + ExtensionEvent | 核心 sealed，扩展 non-sealed |
| 事件自带 seq | **信封 LoggedEvent，append 锁内分配** | 修 dsh 教训：调用方无法写错 |
| `surfaceOp` / `sourceEventSeqs` 条件字段 | SurfaceEvent 接口方法 | 编译期保证 |
| provenance | assertProvenance（validate 阶段，变更前） | ⊇ shadowed、< 当前、无重复 |
| `SurfaceManager` 增量投影 | SurfaceManager + 缓存失效 | 1:1 |
| `deriveMessages()` | 纯函数 + 快照读（同锁） | 并发 append 下无撕裂读 |
| deep-freeze（序列化往返） | structuralFreeze（内存拷贝）+ codec 在 seam | 不可变性与序列化解耦 |
| `ignorable` 前向兼容 | 信封行字段，未知类型免解码跳过 | 1:1 |
| `known-event-types` 生成 | SessionEventCodecs 运行时注册 | 换了机制 |
| 请求可重建 | **LlmRequestEvent + CompositionManifest（新增）** | R1 载体，超出 dsh 的显式化 |
