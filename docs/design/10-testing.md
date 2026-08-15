# 10 · 测试策略

移植 dsh 的测试哲学：**不变式 companion、keyless snapshot 回放、provider fake**。不追求与 dsh 的 test runner 1:1，用 JUnit 5 + AssertJ。

## 1. 测试金字塔

```
                    ┌─────────────────┐
                    │  Real-API e2e   │  ← 需要 DEEPSEEK_API_KEY，无 key 自跳过
                    │  (few)          │
                    └────────┬────────┘
                             │
              ┌──────────────┴───────────────┐
              │  Keyless Snapshot Replay     │  ← 无 key，回放固定输出 vs 期望
              │  (agent 行为不变式)            │
              └──────────────┬───────────────┘
                             │
         ┌───────────────────┴───────────────────┐
         │  Unit + Integration (JUnit 5)         │  ← 组件级，invariant companion
         │  (most)                                │
         └───────────────────────────────────────┘
```

## 2. Invariant Companion 测试

dsh 的 `dsh-session/invariant` 是核心：它不测"正确性"，测"不变式始终成立"。JH 对应 `SessionInvariants`（见 [03 §8](03-session-event-sourcing.md)）。

### 属性测试（Property-Based）

用 jqwik 或手写 fuzzing，对 Session 操作验证不变式：

```java
class SessionInvariantTest {

    @Property
    void seqAlwaysContiguous(@ForAll("randomEvents") List<SessionEvent> events) {
        Session s = Session.create(SessionId.of("test"), events, minimalHeader());
        for (int i = 0; i < s.events().size(); i++) {
            assertThat(s.events().get(i).seq()).isEqualTo(i);
        }
    }

    @Property
    void surfaceOpProvenanceCoversShadowed(
            @ForAll List<SessionEvent> events) {
        // 任意事件序列后，surface 的 replace 操作的 sourceEventSeqs
        // 必须覆盖所有 shadowed seq
        SurfaceFoldResult fold = SurfaceManager.fold(events);
        for (SurfaceFoldReplacement rep : fold.replacements()) {
            assertThat(rep.shadowedSeqs())
                .allMatch(seq -> contains(rep.seq(), /* source */ events));
        }
    }

    @Property
    void deriveMessagesIsDeterministic(
            @ForAll List<SessionEvent> events) {
        Session s = Session.create(SessionId.of("t"), events, minimalHeader());
        List<Message> first = s.deriveMessages();
        List<Message> second = s.deriveMessages();
        assertThat(second).isEqualTo(first);  // 缓存不改变结果
    }
}
```

### Invariant 在每次 append 后校验

```java
class SessionAppendInvariantTest {

    @Test
    void appendingSurfaceEventWithInvalidReplaceThrows() {
        Session s = Session.create(SessionId.of("t"), List.of(), minimalHeader());
        // replace 引用不存在的 seq
        var bad = new AssistantMessageEvent(
            0, 0, 0, 0, dummyMessage(), null,
            new SurfaceOp.Replace(99, 100),  // 不存在的范围
            List.of());
        assertThatThrownBy(() -> s.append(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Replace range invalid");
    }

    @Test
    void appendingNonSerializableEventThrows() {
        Session s = Session.create(SessionId.of("t"), List.of(), minimalHeader());
        // 含不可序列化的字段（如匿名 lambda）
        var bad = ...; // 构造含 Function 字段的非法事件
        assertThatThrownBy(() -> s.append(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not JSON-serializable");
    }
}
```

## 3. Keyless Snapshot Replay —— agent 行为不变式

这是 dsh 最有特色的测试策略：**不依赖真实 API key**，用 replay provider 回放固定输出，验证 agent loop 的行为（事件序列、工具调用、turn 结构）符合期望。

### Replay Provider

```java
// io.dsh.llm.replay.ReplayAdapter —— test-support 模块
public final class ReplayAdapter implements LlmAdapter {

    private final List<List<StreamChunk>> scriptedResponses;
    private int callIndex = 0;

    public ReplayAdapter(List<List<StreamChunk>> responses) {
        this.scriptedResponses = responses;
    }

    @Override
    public Flow.Publisher<StreamChunk> stream(
            LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        return subscriber -> {
            List<StreamChunk> response = scriptedResponses.get(callIndex++);
            subscriber.onSubscribe(new Flow.Subscription() {
                private int i = 0;
                @Override public void request(long n) {
                    for (long c = 0; c < n && i < response.size(); c++) {
                        subscriber.onNext(response.get(i++));
                    }
                    if (i >= response.size()) subscriber.onComplete();
                }
                @Override public void cancel() { i = response.size(); }
            });
        };
    }
}
```

### Snapshot 测试模式

```java
class AgentLoopSnapshotTest {

    @Test
    void singleTurnWithOneToolCall_replaysExpectedEventSequence() {
        // 准备：replay provider 脚本化两次响应
        // 第一次：模型调 fs_read 工具
        // 第二次：模型给出最终文本回答
        List<List<StreamChunk>> scripted = List.of(
            List.of(  // 第一次模型响应：工具调用
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read", "{\"path\":\"/tmp/a\"}"),
                new StreamChunk.Finish(FinishReason.TOOL_USE)
            ),
            List.of(  // 第二次模型响应：最终文本
                new StreamChunk.Delta("The file contains 'hello'"),
                new StreamChunk.Finish(FinishReason.STOP)
            )
        );

        // 用 replay provider 装配一个最小 agent
        try (var runtime = new FiberRuntime()) {
            Context ctx = runtime.rootContext();
            // 注册 replay LLM
            LlmService llm = new InMemoryLlmService();
            llm.registerAdapter("replay", new ReplayAdapter(scripted));
            ctx.provide(LlmService.KEY, llm);
            // 注册 fake fs（返回固定内容）
            ctx.provide(FsService.KEY, new FakeFs(Map.of("/tmp/a", "hello")));
            // 注册 fs_read 工具
            ctx.provide(ToolRegistry.KEY, new ScopedToolRegistry(ctx.get(Events.class)));
            new FsToolPlugin().apply(ctx);
            // 注册 agent loop
            AgentLoopPlugin loop = new AgentLoopPlugin();
            loop.apply(ctx);

            AgentRegistry agents = ctx.get(AgentRegistry.KEY);
            AgentHandle handle = agents.create(ctx, CreateAgentOptions.builder()
                .sessionId(SessionId.of("snap-1"))
                .agentOptions(AgentOptions.builder().provider("replay").model("test").build())
                .build()).join();

            handle.agent().followup(UserMessage.of("Read /tmp/a", MessageSource.human()));
            handle.agent().whenIdle().join();

            // 断言事件序列（snapshot）
            List<SessionEvent> events = handle.agent().session().events();
            assertThat(eventTypes(events)).containsExactly(
                "turn/start",
                "user/message",
                "step/start",
                "user/message",       // admitted input
                "request/header",
                "assistant/chunk",    // delta tool use
                "assistant/chunk",    // finish
                "assistant/message",
                "tool/call",
                "tool/result",
                "step/end",
                "step/start",         // 第二个 step（模型消费 tool result 后）
                "request/header",
                "assistant/chunk",    // delta text
                "assistant/chunk",    // finish
                "assistant/message",
                "step/end",
                "turn/end"
            );

            handle.dispose().join();
        }
    }

    private List<String> eventTypes(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::type).toList();
    }
}
```

### Snapshot 录制/回放

- **回放模式**（无 key，CI 默认）：ReplayAdapter 读 `src/test/resources/snapshots/<test-name>.json`，逐 chunk 回放。
- **录制模式**（需 key，`-Dsnapshot.record=true`）：用真实 DeepSeek adapter 跑测试，把响应序列化到 json 文件。

对应 dsh 的 `test:snapshot` vs `test:snapshot:record`。

## 4. Provider Fake —— 隔离 capability seam

每个 seam 在测试中用 fake provider：

```java
class FakeFs implements FsService {
    private final Map<Path, String> files;
    FakeFs(Map<String, String> files) {
        this.files = files.entrySet().stream().collect(
            Collectors.toMap(e -> Path.of(e.getKey()), Map.Entry::getValue));
    }
    @Override public CompletableFuture<String> read(Path p) {
        String content = files.get(p);
        return CompletableFuture.completedFuture(content == null
            ? null  // 或 throw
            : content);
    }
    // write/edit/delete 追踪调用
}

class RecordingShell implements ShellExecutor {
    private final List<String> commands = new ArrayList<>();
    private final Map<String, ShellResult> responses;
    @Override public CompletableFuture<ShellResult> execute(ShellRequest req, AbortSignal s) {
        commands.add(req.command());
        return CompletableFuture.completedFuture(
            responses.getOrDefault(req.command(), new ShellResult(0, "", "", Duration.ZERO)));
    }
    public List<String> executedCommands() { return List.copyOf(commands); }
}
```

**测试纪律**：fake 只实现接口需要的最小行为，不模拟 provider 内部实现。对应 dsh "Tests describe behavior, not correctness"。

## 5. Kernel 单元测试

### Context / ServiceRegistry

```java
class ServiceRegistryTest {

    @Test
    void provideThenGetReturnsImpl() {
        FiberRuntime rt = new FiberRuntime();
        Context ctx = rt.rootContext();
        TestService impl = new TestServiceImpl();
        Subscription sub = ctx.provide(TestService.KEY, impl);

        assertThat(ctx.get(TestService.KEY)).isSameAs(impl);
        sub.close();
        assertThatThrownBy(() -> ctx.get(TestService.KEY))
            .isInstanceOf(ServiceNotAvailableException.class);
    }

    @Test
    void duplicateProvideFailsLoud() {
        FiberRuntime rt = new FiberRuntime();
        Context ctx = rt.rootContext();
        ctx.provide(TestService.KEY, new TestServiceImpl());
        assertThatThrownBy(() -> ctx.provide(TestService.KEY, new TestServiceImpl()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
    }
}
```

### Events / Waterfall

```java
class EventsWaterfallTest {

    @Test
    void waterfallChainsListenersInOrder() {
        Events events = new Events(currentThreadExecutor());
        EventKey<String> KEY = EventKey.waterfall("test", String.class);

        events.subscribe(KEY, (carrier, payload) -> {
            WaterfallArgs<String> wp = (WaterfallArgs<String>) payload;
            return "A(" + wp.next().invoke() + ")";
        }, null, false);
        events.subscribe(KEY, (carrier, payload) -> {
            WaterfallArgs<String> wp = (WaterfallArgs<String>) payload;
            return "B(" + wp.next().invoke() + ")";
        }, null, false);

        String result = events.waterfall(KEY, null, List.of(), () -> "inner").join();
        assertThat(result).isEqualTo("A(B(inner))");
    }

    @Test
    void waterfallVetoByNotCallingNext() {
        Events events = new Events(currentThreadExecutor());
        EventKey<String> KEY = EventKey.waterfall("test", String.class);

        events.subscribe(KEY, (carrier, payload) -> "VETO", null, false);  // 不调 next
        events.subscribe(KEY, (carrier, payload) -> {
            WaterfallArgs<String> wp = (WaterfallArgs<String>) payload;
            return wp.next().invoke();
        }, null, false);

        String result = events.waterfall(KEY, null, List.of(), () -> "inner").join();
        assertThat(result).isEqualTo("VETO");  // 第一个 listener 短路
    }

    @Test
    void scopeFilterDeliversUpwardOnly() {
        // agent scope A (parent=root)
        // listener on A 收 A 的事件
        // listener on root 收 A 的事件（root 是 A 祖先）
        // listener on B（兄弟）不收 A 的事件
        // ...
    }
}
```

### Fiber teardown order

```java
class FiberTeardownOrderTest {

    @Test
    void effectsDisposedInLIFOOrder() {
        FiberRuntime rt = new FiberRuntime();
        List<String> order = new CopyOnWriteArrayList<>();

        Fiber root = rt.rootFiber();
        root.addCloseable(() -> order.add("first"));   // 先注册
        root.addCloseable(() -> order.add("second"));
        root.addCloseable(() -> order.add("third"));   // 后注册

        root.dispose().join();

        assertThat(order).containsExactly("third", "second", "first");  // LIFO
    }

    @Test
    void childFiberDisposedBeforeParent() {
        FiberRuntime rt = new FiberRuntime();
        List<String> order = new CopyOnWriteArrayList<>();

        Fiber parent = rt.rootFiber();
        Fiber child = parent.spawnChild();
        child.addCloseable(() -> order.add("child-effect"));
        parent.addCloseable(() -> order.add("parent-effect"));

        parent.dispose().join();

        // child 先 dispose（因为 child 的 dispose 挂在 parent 的 effect 栈，LIFO）
        assertThat(order).containsExactly("child-effect", "parent-effect");
    }
}
```

## 6. Agent Loop 集成测试

```java
class AgentLoopIntegrationTest {

    @Test
    void preStepRejectClosesTurnWithNoStep() {
        // 注册一个 agent/pre-step listener 返回 Reject
        // 验证 turn 有 turn/start 和 turn/end(completed) 但无 step/start
    }

    @Test
    void cancelDuringToolExecutionAbortsTurn() {
        // 启动一个 turn，工具执行中途 cancel
        // 验证 turn/end reason = aborted
    }

    @Test
    void injectedContextAppearsInNextStepNotCurrent() {
        // agent.inject(context) 在 step 执行中调用
        // 验证 context 不出现在当前 step，出现在下一个 step 的 user/message
    }

    @Test
    void concludesTurnToolResultEndsTurnAtItsStep() {
        // 工具返回 concludesTurn=true
        // 验证 turn 在该 step 后结束
    }
}
```

## 7. 配置/组合测试

```java
class ProfileBundleTest {

    @Test
    void patchReplacesRowById() {
        List<ConfigRow> base = List.of(
            new ConfigRow("a", "com.A", Map.of(), false),
            new ConfigRow("b", "com.B", Map.of(), false));
        Patch patch = new Patch(List.of(
            new PatchRow("a", true, "com.A2", Map.of(), false)));

        List<ConfigRow> result = AppBoot.applyPatch(base, patch);
        assertThat(result.get(0).plugin()).isEqualTo("com.A2");  // 替换
        assertThat(result.get(1).plugin()).isEqualTo("com.B");   // 不变
    }

    @Test
    void patchOnMissingIdFailsLoud() {
        List<ConfigRow> base = List.of(new ConfigRow("a", "com.A", Map.of(), false));
        Patch patch = new Patch(List.of(
            new PatchRow("nonexistent", true, "com.X", Map.of(), false)));

        assertThatThrownBy(() -> AppBoot.applyPatch(base, patch))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nonexistent");
    }
}
```

## 8. 测试约定（移植 dsh 测试策略）

| dsh 约定 | JH 落地 |
|---|---|
| 测行为不测正确性 | fake provider 追踪调用，断言调用序列 |
| 改变过时行为同时改测试，PR 说明原因 | AssertJ 断言 + commit message 说明 |
| 非 trivial 的模型/用户可见行为必须有 keyless snapshot | ReplayAdapter + 事件序列断言 |
| 包测试/e2e/mock fixture 不替代可运行示例 | `examples/headless` 真实跑通 |
| fixture 在 macOS/Linux 可回放 | 纯 Java，无平台依赖 |
| 修 fixture 不修 normalizer | 断言精确，不做"归一化" |

## 9. 覆盖率策略

dsh CI 要求 `packages/*/*/src` 100% per-file 覆盖。JH 不强求 100%，但：

- **kernel 模块**（context/fiber/events/scope）：目标 95%+（核心基础设施）
- **core 模块**（session/agent-loop/tools）：目标 90%+（主干逻辑）
- **provider 模块**（llm-deepseek/fs-local/bash-local）：目标 80%+（外部依赖多，靠 e2e 补）

用 JaCoCo 生成报告，CI gate 检查阈值。

## 10. CI 建议（非 MVP 必须，但推荐）

```yaml
# .github/workflows/ci.yml（建议）
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25' }
      - run: mvn -B test                      # 单元 + 集成
      - run: mvn -B jacoco:report             # 覆盖率（需在父 POM 配 jacoco plugin）
      - run: mvn -B compiler:compile          # JPMS 一致性（编译即检查）
      - if: ${{ env.DEEPSEEK_API_KEY != '' }}
        run: mvn -B test -Dtest=*E2E* -Dsnapshot.record=false  # 有 key 跑 e2e
```
