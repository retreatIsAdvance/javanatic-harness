# 03 · Session — Event Sourcing

这是整个系统最有原则性的部分。**Session 不是对话历史，而是一个 append-only 的事件日志**，模型看到的 Message 列表是它的投影。

## 1. 核心决策：事件类型体系（不靠 declaration merging）

dsh 用 TypeScript 的 `declare module` 做编译期 declaration merging 扩展 `SessionEventMap`。Java 没有等价物。三个候选方案：

| 方案 | 优点 | 缺点 | 选不选 |
|---|---|---|---|
| A. 全部事件一个 `sealed interface` | 编译期穷尽检查 | 扩展插件无法加新事件类型 | ❌ 违背 dsh merge-extensible 精神 |
| B. 运行时注册表 `SessionEventRegistry` | 可扩展 | 失去 switch 穷尽检查 | ⚠️ 单独用不够 |
| **C. sealed 核心 + 注册表扩展** | 核心事件穷尽检查，插件可扩展 | 两套机制并存 | ✅ |

### 方案 C 的设计

**核心 12 种事件** 用 `sealed interface`（编译期穷尽）：

```java
// io.dsh.core.session.event.SessionEvent
package io.dsh.core.session.event;

import java.util.OptionalLong;

/**
 * Session 日志的一个不可变条目。
 *
 * sealed：核心事件编译期穷尽。
 * permits 子句包含 12 个核心 record + 1 个 ExtensionEvent（插件扩展出口）。
 * switch(SessionEvent) 配合 default 处理 ExtensionEvent。
 */
public sealed interface SessionEvent permits
    TurnStart, TurnEnd, StepStart, StepEnd,
    UserMessageEvent, AssistantChunkEvent, AssistantMessageEvent,
    ToolCallEvent, ToolResultEvent,
    TodoWriteEvent, RequestHeaderEvent, SessionEndSeedEvent,
    ExtensionEvent {

    /** 事件在日志中的单调位置（seq = log.length）。 */
    long seq();

    /** Unix epoch 毫秒。 */
    long time();

    /** 事件类型名（持久化 key，跨语言兼容）。 */
    String type();

    /**
     * 读取方可安全跳过的未知事件标记。
     * 默认 false（required）：未知事件拒绝重建，而非静默丢弃。
     */
    default boolean ignorable() { return false; }
}
```

**核心事件**（12 个 record，1:1 对应 dsh）：

```java
public record TurnStart(long seq, long time, int turn) implements SessionEvent {
    @Override public String type() { return "turn/start"; }
}

public record TurnEnd(long seq, long time, int turn, TurnEndReason reason) implements SessionEvent {
    @Override public String type() { return "turn/end"; }
}

public record StepStart(long seq, long time, int turn, int step) implements SessionEvent {
    @Override public String type() { return "step/start"; }
}

public record StepEnd(long seq, long time, int turn, int step) implements SessionEvent {
    @Override public String type() { return "step/end"; }
}

public record UserMessageEvent(
    long seq, long time,
    UserMessage message,
    SurfaceOp surfaceOp,           // surface 事件必填
    List<Long> sourceEventSeqs     // 可空：provenance
) implements SessionEvent, SurfaceEvent {
    @Override public String type() { return "user/message"; }
}

public record AssistantChunkEvent(
    long seq, long time, int turn, int step, StreamChunk chunk
) implements SessionEvent {
    @Override public String type() { return "assistant/chunk"; }
    // 非 surface 事件：无 surfaceOp / sourceEventSeqs（编译期保证，非继承 SurfaceEvent）
}

public record AssistantMessageEvent(
    long seq, long time, int turn, int step,
    AssistantMessage message,
    TokenUsage usage,              // 可 null
    SurfaceOp surfaceOp,
    List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {
    @Override public String type() { return "assistant/message"; }
}

public record ToolCallEvent(
    long seq, long time, int turn, int step,
    CallId callId, String name, String arguments  // arguments 是原始 JSON 字符串
) implements SessionEvent {
    @Override public String type() { return "tool/call"; }
}

public record ToolResultEvent(
    long seq, long time, int turn, int step,
    ToolResultMessage message,
    ToolError error,               // 可 null
    JsonValue meta,                // 可 null：工具私有展示数据
    SurfaceOp surfaceOp,
    List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {
    @Override public String type() { return "tool/result"; }
}

public record TodoWriteEvent(long seq, long time, List<TodoItem> todos) implements SessionEvent {
    @Override public String type() { return "todo/write"; }
}

public record RequestHeaderEvent(
    long seq, long time, EpochHeader header, RequestHeaderReason reason
) implements SessionEvent {
    @Override public String type() { return "request/header"; }
}

public record SessionEndSeedEvent(long seq, long time) implements SessionEvent {
    @Override public String type() { return "session/end-seed"; }
}
```

**插件扩展出口**：

```java
/**
 * 插件扩展的事件类型。核心 switch 走 default 分支处理它。
 *
 * 扩展插件实现此接口（不继承核心 record），并在启动时通过
 * SessionEventRegistry 注册其 type name → class 映射。
 *
 * ignorable() 由扩展实现决定：信息性事件返回 true（未知读取方跳过），
 * 结构性事件返回 false（未知读取方拒绝重建）。
 */
public non-sealed interface ExtensionEvent extends SessionEvent {
    // 扩展插件自由实现；type() 必须返回稳定的字符串 key
}
```

**编译期穷尽 + 运行时扩展的共存**：

```java
// 核心代码里 switch 核心事件，default 处理扩展
String describe(SessionEvent e) {
    return switch (e) {
        case TurnStart ts -> "Turn " + ts.turn() + " started";
        case TurnEnd te -> "Turn " + te.turn() + " ended: " + te.reason();
        case UserMessageEvent um -> "User: " + um.message();
        case AssistantMessageEvent am -> "Assistant: " + am.message();
        // ... 其余核心事件
        case ExtensionEvent ext -> ext.type() + " (extension)";
    };
}
```

Java 25 的 `switch` pattern matching 对 `sealed` 做**穷尽检查**（此特性自 21 final）：漏掉一个核心分支编译报错；`ExtensionEvent` 是 `non-sealed`（开放），必须用 default 或 case 覆盖。这完美复刻 dsh 的 "closed union ends in assertNever; merge-extensible unions fall through documented default"。

## 2. SurfaceEvent — 产生消息的事件子集

只有三种事件进入有序 surface（投影成模型看到的 Message）：

```java
// io.dsh.core.session.event.SurfaceEvent
package io.dsh.core.session.event;

/**
 * 标记接口：产生 LLM 消息的事件子集。
 * 只有 UserMessageEvent / AssistantMessageEvent / ToolResultEvent 实现。
 * 编译期保证：只有 SurfaceEvent 能携带 SurfaceOp 和 sourceEventSeqs。
 */
public interface SurfaceEvent {
    SurfaceOp surfaceOp();
    List<Long> sourceEventSeqs();
}
```

### SurfaceOp — 如何进入有序 surface

```java
// io.dsh.core.session.event.SurfaceOp
package io.dsh.core.session.event;

/**
 * 事件如何进入有序 surface。
 * sealed 对应 dsh 的 discriminated union：
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
// io.dsh.core.session.Session
package io.dsh.core.session;

import java.util.*;

/**
 * Event-sourced session：一个 append-only 的 SessionEvent 日志。
 *
 * Message 历史是 derived（deriveMessages()），不单独存储。
 * 所有事件和嵌套数据在 append 时 deep-freeze（不可变快照），
 * 返回的 event.data 永远是冻结副本，调用方的可变输入无法污染日志。
 *
 * Plain class（非 Service）：通过 SessionStore.create() 创建实例。
 */
public final class Session {

    private final SessionId id;
    private final SessionHeader header;
    private final List<SessionEvent> log = new ArrayList<>();
    private final SurfaceManager surface = new SurfaceManager();

    private volatile long firstLiveSeq = 0;

    // ────────── 构造 ──────────

    Session(SessionId id, SessionHeader header) {
        this.id = Objects.requireNonNull(id);
        this.header = Objects.requireNonNull(header);
    }

    /**
     * 用 seed 事件构造（replay / fork / resume）。
     * seed 事件被校验、deep-freeze 后追加；firstLiveSeq = seed.size()。
     */
    static Session create(SessionId id, List<SessionEvent> seed, SessionHeader header) {
        Session s = new Session(id, header);
        for (SessionEvent e : seed) {
            s.log.add(deepFreeze(e));
            s.surface.applyCommitted(e);
        }
        s.firstLiveSeq = seed.size();
        return s;
    }

    // ────────── append ──────────

    /**
     * 追加一个事件到日志。同步通知观察者（通过 SessionStore 的 publication hooks）。
     * 热路径不阻塞 IO：持久化插件异步 buffer。
     *
     * @throws IllegalArgumentException 如果 data 不可 JSON 序列化（对应 dsh isJsonValue 校验），
     *         或 surface 元数据非法（marker shape、唯一 sourceEventSeqs、replace 范围有效）
     */
    public synchronized <T extends SessionEvent> T append(T event) {
        // 1. 校验 JSON 可序列化（一次递归遍历，读+校验+冻结）
        T frozen = deepFreeze(event);
        // 2. surface 校验（若是 SurfaceEvent）
        if (frozen instanceof SurfaceEvent se) {
            surface.validateCandidate(se);
        }
        // 3. 追加（seq = log.size() 已由调用方或工厂赋值，这里幂等检查）
        log.add(frozen);
        // 4. 推进 surface 投影
        if (frozen instanceof SurfaceEvent se) {
            surface.commit(se);
        }
        // 5. 触发 publication hooks（由 SessionStore 注入）
        notifyObservers(frozen);
        return frozen;
    }

    // ────────── 投影 ──────────

    /** 派生 LLM 消息历史（缓存：每个 surface node 首次见到时投影）。 */
    public List<Message> deriveMessages() {
        return surface.projectMessages(log);
    }

    /** 当前 surface（只读视图）。 */
    public SessionSurface surface() {
        return surface.view();
    }

    /** 最新的 request/header，或空。 */
    public Optional<EpochHeader> requestHeader() {
        return foldRequestHeader(log);
    }

    // ────────── 访问 ──────────

    public SessionId id() { return id; }
    public SessionHeader header() { return header; }
    public List<SessionEvent> events() { return List.copyOf(log); }
    public long seq() { return log.size(); }
    public long firstLiveSeq() { return firstLiveSeq; }
}
```

### deepFreeze — 不可变快照

```java
/**
 * 深度冻结事件：递归把 List/Map/record 转为不可变副本。
 * 对应 dsh 的 "events and their nested data are deep-frozen at acceptance"。
 *
 * 实现：用 Jackson 序列化 + 反序列化得到不可变副本（Jackson 配置 IMMUTABLE）。
 * 同时完成 JSON 可序列化校验（一石二鸟）。
 */
@SuppressWarnings("unchecked")
private static <T extends SessionEvent> T deepFreeze(T event) {
    try {
        ObjectMapper m = IMMUTABLE_MAPPER; // 配置了 IMMUTABLE + 无 unknown
        String json = m.writeValueAsString(event);
        return (T) m.readValue(json, event.getClass());
    } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "SessionEvent not JSON-serializable: " + e.getOriginalMessage(), e);
    }
}
```

## 4. SurfaceManager — 有序 surface 投影

1:1 移植 dsh 的 `surface.ts`：

```java
// io.dsh.core.session.surface.SurfaceManager
package io.dsh.core.session.surface;

class SurfaceManager {

    // surface nodes 的 seq 列表（有序）
    private final List<Long> nodes = new ArrayList<>();
    // replace 操作计数（每次 replace 递增，供增量消费者区分"纯追加"vs"重写"）
    private int replaceGeneration = 0;

    // 缓存：seq → 投影出的 Message（首次见到时计算）
    private final Map<Long, Message> messageCache = new HashMap<>();

    /** 校验候选事件能否 commit（不实际修改）。 */
    void validateCandidate(SurfaceEvent event) {
        SurfaceOp op = event.surfaceOp();
        long seq = event.seq();
        long[] sourceSeqs = event.sourceEventSeqs() == null
            ? new long[0]
            : event.sourceEventSeqs().stream().mapToLong(Long::longValue).toArray();

        switch (op) {
            case SurfaceOp.Append a -> {
                // append 无额外约束
            }
            case SurfaceOp.Replace r -> {
                // start/end 必须是当前 surface 的有效 seq
                int startIdx = indexOf(r.start());
                int endIdx = indexOf(r.end());
                if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) {
                    throw new IllegalArgumentException(
                        "Replace range invalid: [" + r.start() + "," + r.end() + "]");
                }
                // provenance：sourceEventSeqs 必须包含所有被 shadow 的 seq
                long[] shadowed = nodes.subList(startIdx, endIdx + 1)
                    .stream().mapToLong(Long::longValue).toArray();
                assertProvenance(sourceSeqs, shadowed, seq);
            }
        }
    }

    /** 提交事件到 surface。 */
    void commit(SurfaceEvent event) {
        switch (event.surfaceOp()) {
            case SurfaceOp.Append a -> nodes.add(event.seq());
            case SurfaceOp.Replace r -> {
                int startIdx = indexOf(r.start());
                int endIdx = indexOf(r.end());
                // 替换 [startIdx, endIdx] 为新 seq
                nodes.subList(startIdx, endIdx + 1).clear();
                nodes.add(startIdx, event.seq());
                replaceGeneration++;
                // 失效缓存（被 shadow 的 node 不再有效）
                for (long s = r.start(); s <= r.end(); s++) messageCache.remove(s);
            }
        }
    }

    /** 投影为 Message 列表（缓存）。 */
    List<Message> projectMessages(List<SessionEvent> log) {
        List<Message> result = new ArrayList<>(nodes.size());
        for (long seq : nodes) {
            Message m = messageCache.computeIfAbsent(seq, s -> {
                SessionEvent e = log.get((int) s);
                return DeriveMessage.project(e);
            });
            if (m != null) result.add(m);
        }
        return Collections.unmodifiableList(result);
    }

    private int indexOf(long seq) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == seq) return i;
        }
        return -1;
    }
}
```

### DeriveMessage — 每个事件的投影规则

```java
// io.dsh.core.session.surface.DeriveMessage
class DeriveMessage {

    /**
     * 把一个 surface node 投影成 Message（对应 dsh deriveEventMessage）。
     * null = 该事件不产生消息（但 surface 里不会出现 null，因为只有 surface 事件才进 surface）。
     */
    static Message project(SessionEvent e) {
        return switch (e) {
            case UserMessageEvent um -> {
                // user/message：直接拿 content（source 区分 human / inject / steering）
                yield new UserMessage(um.message().content(), um.message().source());
            }
            case AssistantMessageEvent am -> {
                // 空 content 的 assistant/message 跳过（max-tokens 截断但无内容）
                if (am.message().content().isEmpty()) yield null;
                yield am.message();
            }
            case ToolResultEvent tr -> {
                // tool/result：包装成 user-role 消息携带 tool-result block
                yield new UserMessage(
                    List.of(new ToolResultBlock(tr.message())),
                    MessageSource.tool());
            }
            default -> throw new IllegalStateException(
                "Non-surface event in surface projection: " + e);
        };
    }
}
```

## 5. SessionStore — 活跃会话管理

```java
// io.dsh.core.session.SessionStore
package io.dsh.core.session;

/**
 * 活跃会话存储。Session 实例的内存仓库 + publication hooks。
 *
 * 对应 dsh 的 ctx.sessions。
 * Persistence 不在此实现：持久化插件订阅 session/event，flush 时异步落盘。
 */
public final class SessionStore {

    private final Events events;
    private final ConcurrentHashMap<SessionId, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    record Entry(Session session, Fiber ownerFiber, List<EventListener> observers) {}

    /**
     * 创建会话（由 owner fiber 拥有：dispose 时移除 + 停止通知）。
     */
    public Session create(Context ownerCtx, SessionId id, CreateOptions options) {
        Session session = Session.create(id, options.seed(), options.header());
        // install publication hooks
        // enter + announce（对应 dsh 的 prepare/enter/announce 三步）
        store.put(id, new Entry(session, ownerCtx.fiber(), new CopyOnWriteArrayList<>()));
        events.emit(SessionEvents.CREATED, session, session);
        ownerCtx.addCloseable(() -> {
            events.emit(SessionEvents.DISPOSED, session, session);
            store.remove(id);
        });
        return session;
    }

    public Session get(SessionId id) {
        Entry e = store.get(id);
        return e == null ? null : e.session();
    }

    public List<Session> list() {
        return store.values().stream().map(Entry::session).toList();
    }

    /**
     * 从 source 会话的稳定前缀 fork 一个 child 会话。
     */
    public Session fork(Context ownerCtx, SessionId source, Long boundary, SessionId childId) {
        Session src = require(source);
        long end = boundary != null ? boundary : src.seq() - 1;
        List<SessionEvent> seed = src.events().subList(0, (int) end + 1);
        // 校验：prefix 不能结束在 open turn 内
        assertEndsBetweenTurns(seed);
        SessionId cid = childId != null ? childId : generateId();
        SessionHeader childHeader = src.header().forFork(cid);
        return create(ownerCtx, cid, new CreateOptions(seed, childHeader));
    }

    /** 持久化 barrier：等所有持久化 listener 完成。 */
    public CompletableFuture<Boolean> flush(Session session) {
        return events.parallel(SessionEvents.FLUSH, session, session)
            .thenApply(v -> true);
    }

    private SessionId generateId() {
        return SessionId.of("session-" + idCounter.incrementAndGet());
    }
}
```

## 6. JSONL 持久化

```java
// io.dsh.session.persistence.jsonl.JsonlPersistence
package io.dsh.session.persistence.jsonl;

/**
 * JSONL 后端：每行一个 SessionEvent（JSON）。
 * 对应 dsh 的 session-persistence-jsonl。
 *
 * 文件结构：
 *   ~/.harness/sessions/<sessionId>/header.json     ← SessionHeader
 *   ~/.harness/sessions/<sessionId>/log.jsonl       ← 逐行 SessionEvent
 *   ~/.harness/sessions/<sessionId>/log.jsonl.lock  ← 进程锁（防止并发写）
 */
public final class JsonlPersistence implements SessionPersistence {

    private final Path baseDir;
    private final ObjectMapper mapper;

    @Override
    public void save(Session session) {
        Path dir = baseDir.resolve(session.id().value());
        Files.createDirectories(dir);
        // header
        Path headerFile = dir.resolve("header.json");
        mapper.writeValue(headerFile.toFile(), session.header());
        // log（全量重写——简单可靠；增量优化见下）
        Path logFile = dir.resolve("log.jsonl");
        try (var writer = Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (SessionEvent e : session.events()) {
                writer.write(mapper.writeValueAsString(e));
                writer.newLine();
            }
        }
    }

    @Override
    public Session load(SessionId id) {
        Path dir = baseDir.resolve(id.value());
        SessionHeader header = mapper.readValue(
            dir.resolve("header.json").toFile(), SessionHeader.class);
        List<SessionEvent> events = new ArrayList<>();
        try (var reader = Files.newBufferedReader(dir.resolve("log.jsonl"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode node = mapper.readTree(line);
                String type = node.get("type").asText();
                Class<? extends SessionEvent> cls = SessionEventRegistry.classFor(type);
                if (cls == null) {
                    if (node.has("ignorable") && node.get("ignorable").asBoolean()) {
                        continue; // 跳过未知 ignorable 事件
                    }
                    throw new IllegalStateException("Unknown event type: " + type);
                }
                events.add(mapper.readValue(line, cls));
            }
        }
        return Session.create(id, events, header);
    }
}
```

**增量写优化**（可选，MVP 可先全量）：记录 `lastFlushedSeq`，只 append 增量行。

## 7. SessionEventRegistry — 扩展事件类型注册

```java
// io.dsh.core.session.event.SessionEventRegistry
package io.dsh.core.session.event;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展事件类型注册表。
 *
 * 核心事件（sealed）不需要注册（编译期已知）。
 * 插件扩展事件（ExtensionEvent 子类）必须在此注册 type→class 映射，
 * 以便 JSONL 持久化反序列化。
 *
 * 对应 dsh 的 known-event-types（运行时注册版）。
 */
public final class SessionEventRegistry {

    private static final Map<String, Class<? extends ExtensionEvent>> TYPES = new ConcurrentHashMap<>();

    /** 注册扩展事件类型。重复注册同 type fail loud。 */
    public static void register(String type, Class<? extends ExtensionEvent> cls) {
        Class<?> existing = TYPES.putIfAbsent(type, cls);
        if (existing != null && existing != cls) {
            throw new IllegalStateException(
                "Event type '" + type + "' already registered: " + existing);
        }
    }

    /** 查 type 对应的 class（核心事件返回对应 record class）。 */
    @SuppressWarnings("unchecked")
    public static Class<? extends SessionEvent> classFor(String type) {
        return switch (type) {
            case "turn/start"       -> TurnStart.class;
            case "turn/end"         -> TurnEnd.class;
            case "step/start"       -> StepStart.class;
            case "step/end"         -> StepEnd.class;
            case "user/message"     -> UserMessageEvent.class;
            case "assistant/chunk"  -> AssistantChunkEvent.class;
            case "assistant/message"-> AssistantMessageEvent.class;
            case "tool/call"        -> ToolCallEvent.class;
            case "tool/result"      -> ToolResultEvent.class;
            case "todo/write"       -> TodoWriteEvent.class;
            case "request/header"   -> RequestHeaderEvent.class;
            case "session/end-seed" -> SessionEndSeedEvent.class;
            default -> TYPES.get(type);
        };
    }

    /** 所有已知类型（核心 + 扩展），用于 known-event-types 生成。 */
    public static Set<String> allTypes() {
        Set<String> all = new HashSet<>(TYPES.keySet());
        all.addAll(Set.of(
            "turn/start","turn/end","step/start","step/end",
            "user/message","assistant/chunk","assistant/message",
            "tool/call","tool/result","todo/write",
            "request/header","session/end-seed"));
        return Collections.unmodifiableSet(all);
    }
}
```

插件通过 `Plugin.apply(ctx)` 调用 `SessionEventRegistry.register("my/event", MyEvent.class)`。JPMS 的 `provides` 无法直接表达这个（注册是运行时副作用），所以走显式注册 API。

## 8. 不变式（invariant companion）

对应 dsh 的 `dsh-session/invariant`。MVP 用一个简单的 invariant checker：

```java
// io.dsh.core.session.invariant.SessionInvariants
package io.dsh.core.session.invariant;

/**
 * Session 日志不变式校验器。
 *
 * 核心不变式：
 * - seq 连续（seq[i] == i）
 * - turn/step 编号单调
 * - step 必须在 turn 内（turn/start 后、turn/end 前）
 * - tool/call 和 tool/result 同 step 配对
 * - surface 事件的 sourceEventSeqs 指向更早的有效 seq
 * - session/end-seed 仅由构造器写入（校验：只在 seed 末尾出现一次）
 */
public final class SessionInvariants {

    public static void validate(List<SessionEvent> events) {
        long expectedSeq = 0;
        int currentTurn = -1;
        int currentStep = -1;
        boolean inTurn = false;
        boolean inStep = false;

        for (SessionEvent e : events) {
            if (e.seq() != expectedSeq) {
                throw new InvariantViolation("seq gap: expected " + expectedSeq + ", got " + e.seq());
            }
            expectedSeq++;

            switch (e) {
                case TurnStart ts -> {
                    if (inTurn) throw new InvariantViolation("nested turn");
                    inTurn = true;
                    currentTurn = ts.turn();
                    if (currentTurn != expectedTurn(events, e.seq())) {
                        throw new InvariantViolation("turn number not monotonic");
                    }
                }
                case TurnEnd te -> {
                    if (!inTurn) throw new InvariantViolation("turn/end without start");
                    if (inStep) throw new InvariantViolation("turn/end inside step");
                    inTurn = false;
                }
                case StepStart ss -> {
                    if (!inTurn) throw new InvariantViolation("step outside turn");
                    inStep = true;
                    currentStep = ss.step();
                }
                case StepEnd se -> {
                    if (!inStep) throw new InvariantViolation("step/end without start");
                    inStep = false;
                }
                case ToolCallEvent tc -> {
                    if (!inStep) throw new InvariantViolation("tool/call outside step");
                }
                case ToolResultEvent tr -> {
                    if (!inStep) throw new InvariantViolation("tool/result outside step");
                }
                case SurfaceEvent se -> {
                    if (se.sourceEventSeqs() != null) {
                        for (long s : se.sourceEventSeqs()) {
                            if (s >= e.seq()) throw new InvariantViolation(
                                "sourceEventSeq " + s + " >= current seq " + e.seq());
                        }
                    }
                }
                default -> { /* 其他事件无 turn/step 约束 */ }
            }
        }
    }
}
```

## 9. 关键不变式总结

| 不变式 | 校验点 | 失败行为 |
|---|---|---|
| `seq = log.length`（连续性）| append 时 | 拒绝 append |
| 所有 event.data JSON 可序列化 | append 时（deepFreeze） | 拒绝 append |
| SurfaceEvent 必带 surfaceOp | 编译期（sealed 接口） | 编译失败 |
| Replace 的 start/end 是有效 surface seq | append 时（validateCandidate） | 拒绝 append |
| Replace 的 sourceEventSeqs 覆盖所有 shadowed seq | append 时 | 拒绝 append |
| step 必须在 turn 内 | invariant companion | 违规报告 |
| tool/call 和 tool/result 同 step 配对 | invariant companion | 违规报告 |
| 未知事件 type | load 时 | ignorable=true 跳过；否则拒绝重建 |
| 持久化 seq 连续（含 assistant/chunk）| load 时 | 拒绝 load（chunk 不能过滤）|

## 10. 与 dsh 对齐

| dsh 概念 | JH 实现 | 备注 |
|---|---|---|
| `SessionEventMap`（merge-extensible） | `sealed interface SessionEvent` + `ExtensionEvent` | 核心 sealed，扩展 non-sealed |
| `SurfaceEventType` | `interface SurfaceEvent` | 三种实现 |
| `surfaceOp` 条件字段 | SurfaceEvent 接口方法 | 编译期保证 |
| `sourceEventSeqs` provenance | `SurfaceEvent.sourceEventSeqs()` + `assertProvenance` | 1:1 |
| `SurfaceManager` 增量投影 | `SurfaceManager` | 1:1 |
| `deriveMessages()` 缓存 | `messageCache`（seq→Message） | 1:1 |
| `session/end-seed` | `SessionEndSeedEvent` record | 1:1 |
| `ignorable` 前向兼容 | `SessionEvent.ignorable()` + load 时校验 | 1:1 |
| `isJsonValue` 校验 | `deepFreeze`（Jackson 双向） | 一石二鸟 |
| `known-event-types.ts` 生成 | `SessionEventRegistry.allTypes()` 运行时 | 换了机制 |
