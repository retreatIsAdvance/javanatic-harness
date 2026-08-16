# 10 · 测试策略

移植 dsh 的测试哲学：**不变式 companion、keyless snapshot 回放、provider fake**。不追求与 dsh 的 test runner 1:1，用 JUnit 5 + AssertJ + jqwik（属性测试，BOM 已含）。

**四条运行不变式各有测试形态**（§1.5）：R1 回放哈希比对、R2 架构测试、R3 回滚测试、R4 verify 断言。

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

### R1–R4 的测试映射

| 不变式 | 测试形态 | 本篇 |
|---|---|---|
| R1 可重建 | 回放会话 → 重组装 prompt/schema → sha256 比对 LlmRequestEvent | §3.4 |
| R2 执行一致 | 架构测试：toolCalls 分发点全库唯一；审计 append 在 executor | §6 |
| R3 副作用消除 | 插件失败回滚测试 + teardown 顺序测试 + 吊销后重解析测试 | §5 |
| R4 治理完备 | `--verify` 的档位断言（production 拒绝 AUTO 等） | §7 |

## 2. Invariant Companion 测试

dsh 的 `dsh-session/invariant` 是核心：它不测"正确性"，测"不变式始终成立"。JH 对应 `SessionInvariants`（[03 §7](03-session-event-sourcing.md)）。

### 属性测试（jqwik）

```java
class SessionInvariantTest {

    @Property
    void envelopeSeqAlwaysContiguous(@ForAll("randomEvents") List<SessionEvent> events) {
        Session s = Session.create(SessionId.of("test"), events, minimalHeader());
        List<LoggedEvent<? extends SessionEvent>> log = s.events();
        for (int i = 0; i < log.size(); i++) {
            assertThat(log.get(i).seq()).isEqualTo(i);   // 信封 seq = 下标，结构性
        }
    }

    @Property
    void provenanceCoversShadowed(@ForAll("validSurfaceOps") List<SessionEvent> events) {
        // 任意合法事件序列 fold 后，每个 replace 的 sourceEventSeqs
        // ⊇ shadowed、全部 < 自身 seq、无重复
        SessionInvariants.validate(Session.create(SessionId.of("t"), events, minimalHeader()).events());
    }

    @Property
    void deriveMessagesIsDeterministic(@ForAll("randomEvents") List<SessionEvent> events) {
        Session s = Session.create(SessionId.of("t"), events, minimalHeader());
        assertThat(s.deriveMessages()).isEqualTo(s.deriveMessages());   // 缓存不改变结果
    }
}
```

### append 边界

```java
class SessionAppendTest {

    @Test
    void invalidReplaceRangeThrows() {
        Session s = Session.create(SessionId.of("t"), List.of(), minimalHeader());
        // replace 引用不存在的 seq → 拒绝 append（日志不变）
        var bad = new AssistantMessageEvent(0L, 0, 0, dummyMessage(), null,
            new SurfaceOp.Replace(99, 100), List.of());
        assertThatThrownBy(() -> s.append(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Replace range invalid");
        assertThat(s.seq()).isZero();
    }

    @Test
    void mutableListInputCannotPolluteLog() {
        // structuralFreeze：append 后修改传入 list，日志内容不变
        var content = new ArrayList<String>(List.of("a"));
        var msg = new UserMessageEvent(0L, new UserMessage(content, MessageSource.human()),
            new SurfaceOp.Append(), null);
        s.append(msg);
        content.add("polluted");
        assertThat(s.events().getLast().event().message().content()).containsExactly("a");
    }
}
```

## 3. Keyless Snapshot Replay —— agent 行为不变式

dsh 最有特色的测试策略：**不依赖真实 API key**，用 replay provider 回放固定输出，验证 agent loop 的行为（事件序列、工具调用、turn 结构）符合期望。

### Replay Provider（阻塞流）

```java
// io.javanatic.harness.llm.replay.ReplayAdapter
public final class ReplayAdapter implements LlmAdapter {

    private final List<List<StreamChunk>> scriptedResponses;
    private final AtomicInteger callIndex = new AtomicInteger();

    @Override
    public Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        List<StreamChunk> response = scriptedResponses.get(callIndex.getAndIncrement());
        return response.stream();   // 阻塞流：脚本回放无需队列
    }
}
```

### Snapshot 测试模式

```java
class AgentLoopSnapshotTest {

    @Test
    void singleTurnWithOneToolCall_replaysExpectedEventSequence() {
        // 脚本：第一次响应调 fs_read；第二次给最终文本
        List<List<StreamChunk>> scripted = List.of(
            List.of(
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read", "{\"path\":\"/tmp/a\"}"),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(
                new StreamChunk.Delta("The file contains 'hello'"),
                new StreamChunk.Finish(FinishReason.STOP)));

        try (var runtime = new Runtime()) {
            Scope root = runtime.root();
            // LLM：Definition 默认实现（id "llm"）+ replay adapter
            new LlmPlugin().apply(root);
            root.require(LlmService.KEY).registerAdapter("replay", new ReplayAdapter(scripted));
            // fake fs + 工具 + loop（治理依赖齐全：R4——装配即证明）
            root.provide(FsService.KEY, new FakeFs(Map.of("/tmp/a", "hello")));
            root.provide(ApprovalService.KEY, ApprovalService.auto());
            new FsToolPlugin().apply(root);
            new AgentLoopPlugin().apply(root);

            AgentHandle handle = root.require(AgentRegistry.KEY).create(root,
                CreateAgentOptions.builder()
                    .sessionId(SessionId.of("snap-1"))
                    .agentOptions(AgentOptions.builder().provider("replay").model("test").build())
                    .build());
            handle.agent().followup(UserMessage.of("Read /tmp/a", MessageSource.human()));
            handle.agent().whenIdle().join();

            // 断言事件类型序列（user/message 在 turn 层只出现一次）
            assertThat(eventTypes(handle.agent().session().events())).containsExactly(
                "turn/start",
                "user/message",        // turn 层落账（pre-step 之后）
                "step/start",
                "request/header",      // epoch 首
                "llm/request",         // R1 指纹
                "assistant/chunk",     // delta tool use
                "assistant/chunk",     // finish
                "assistant/message",
                "tool/call",           // executor 落账
                "tool/result",         // executor 落账
                "step/end",
                "step/start",
                "llm/request",
                "assistant/chunk",     // delta text
                "assistant/chunk",     // finish
                "assistant/message",
                "step/end",
                "turn/end");

            handle.disposeAndAwait();
        }
    }
}
```

### 录制/回放

- **回放**（无 key，CI 默认）：ReplayAdapter 读 `src/test/resources/snapshots/<test>.json`。
- **录制**（需 key，`-Dsnapshot.record=true`）：真实 DeepSeek adapter 跑测试，响应序列化落盘。

### R1 回放哈希测试

```java
class ReconstructabilityTest {

    @Test
    void replayedRequestHashesMatchLoggedFingerprints() throws Exception {
        Session session = jsonl.load(SessionId.of("snap-1"));       // 从持久化事实出发
        CompositionManifest manifest = session.header().composition();

        List<LoggedEvent<? extends SessionEvent>> log = session.events();
        for (LoggedEvent<? extends SessionEvent> e : log) {
            if (!(e.event() instanceof LlmRequestEvent req)) continue;
            // 重组装到该 seq 前缀：窗口 = deriveMessages(log[0..req.messagesToSeq])
            String prompt = reassembleSystemPrompt(manifest, prefix(log, req));
            String schemas = reassembleToolSchemas(manifest, prefix(log, req));
            assertThat(sha256(prompt)).isEqualTo(req.systemPromptSha256());
            assertThat(sha256(schemas)).isEqualTo(req.toolsSchemaSha256());
        }
        // 全绿 = R1 成立：代码演进改变提示词时，此处立刻红
    }
}
```

## 4. Provider Fake —— 隔离 capability seam

```java
class FakeFs implements FsService {
    private final Map<Path, String> files;
    @Override public String read(Path p) { return files.get(p); }   // 阻塞签名（05 §2）
    // write/edit/delete/list 追踪调用
}

class RecordingShell implements ShellExecutor {
    private final List<String> commands = new CopyOnWriteArrayList<>();
    @Override public ShellResult execute(ShellRequest req, AbortSignal s) {
        commands.add(req.command());
        return new ShellResult(0, "", "", Duration.ZERO);
    }
    public List<String> executedCommands() { return List.copyOf(commands); }
}
```

**测试纪律**：fake 只实现接口需要的最小行为，不模拟 provider 内部实现。对应 dsh "Tests describe behavior, not correctness"。

## 5. Kernel 单元测试（含 R3）

### Scope / 服务解析

```java
class ScopeTest {

    @Test
    void provideThenResolveWalksChain() {
        try (var rt = new Runtime()) {
            Scope root = rt.root();
            Scope child = root.child();
            root.provide(TestService.KEY, impl);
            assertThat(child.resolve(TestService.KEY)).contains(impl);
        }
    }

    @Test
    void childShadowsParent_sameScopeDuplicateFailsLoud() {
        try (var rt = new Runtime()) {
            Scope root = rt.root();
            Scope child = root.child();
            root.provide(FsService.KEY, globalFs);
            child.provide(FsService.KEY, agentFs);        // overlay：子盖父 ✅
            assertThat(child.require(FsService.KEY)).isSameAs(agentFs);
            assertThat(root.require(FsService.KEY)).isSameAs(globalFs);   // 父不变
            assertThatThrownBy(() -> root.provide(FsService.KEY, dup))    // 同层重复 ❌
                .hasMessageContaining("already registered");
        }
    }

    /** R3：provider scope 关闭后，解析再也查不到——无僵尸引用。 */
    @Test
    void closedScopeServicesUnresolvableWithoutCacheEviction() {
        try (var rt = new Runtime()) {
            Scope root = rt.root();
            Scope provider = root.child();
            provider.provide(FsService.KEY, fs);
            Scope consumer = root.child();
            assertThat(consumer.resolve(FsService.KEY)).isPresent();
            provider.close();
            assertThat(consumer.resolve(FsService.KEY)).isEmpty();   // 每访问重解析
        }
    }
}
```

### Events / Waterfall（同步语义）

```java
class EventsTest {

    @Test
    void waterfallChainsInOrder_andSync() {
        try (var rt = new Runtime()) {
            Scope root = rt.root();
            root.events().onWaterfall(KEY, (carrier, args) -> "A(" + args.next().invoke() + ")");
            root.events().onWaterfall(KEY, (carrier, args) -> "B(" + args.next().invoke() + ")");
            String result = rt.events().waterfall(KEY, root, null, List.of(), () -> "inner");
            assertThat(result).isEqualTo("A(B(inner))");   // 无 .join()：同步返回
        }
    }

    @Test
    void nextInvokedTwiceThrows_vetoShortCircuits() { /* ... */ }

    @Test
    void firstOfReturnsFirstNonNull_allNullEmpty() { /* bail 替代物的查询语义 */ }

    @Test
    void eventsBubbleUpward_notDownward() {
        // A=root.child(), B=root.child()（兄弟）
        // root 的订阅收 A 派发的事件 ✅；B 的订阅不收 ❌（06 §7 场景）
    }
}
```

### Teardown 顺序与回滚（R3）

```java
class TeardownTest {

    @Test
    void effectsDisposedInLIFOOrder() { /* third → second → first */ }

    @Test
    void childScopeClosedBeforeParentEffects() { /* child-effect → parent-effect */ }

    /** R3：插件 apply 失败 → 子 scope 立即回滚 → 半注册不留痕。 */
    @Test
    void pluginFailureRollsBackItsScope() {
        try (var rt = new Runtime()) {
            Scope root = rt.root();
            Plugin halfThenFail = pluginWithId("p", scope -> {
                scope.provide(FsService.KEY, fs);
                scope.events().onGlobal(SomeEvent.KEY, listener);
                throw new IllegalStateException("boom");
            });
            assertThatThrownBy(() -> new PluginLoader().loadAll(root, List.of(halfThenFail)))
                .hasMessageContaining("rolled back");
            assertThat(root.resolve(FsService.KEY)).isEmpty();   // 服务已回收
        }
    }
}
```

## 6. Agent Loop 集成测试 + R2 架构测试

```java
class AgentLoopIntegrationTest {

    @Test void preStepRejectClosesTurnWithNoStep() { /* turn/start + turn/end，无 step */ }

    @Test void cancelDuringToolExecutionAbortsTurn() { /* turn/end reason = aborted */ }

    @Test void injectedContextAppearsInNextStepNotCurrent() { /* inject 的时序 */ }

    @Test void concludesTurnToolResultEndsTurnAtItsStep() { /* 数据驱动停 turn */ }

    @Test void driverExitWindowDoesNotLoseWakeup() {
        // send() 恰好落在 driver 退出窗口 → finally 重查 hasWork → 新 turn 仍被驱动
    }
}
```

### R2：唯一分发点架构测试

```java
class ToolDispatchArchitectureTest {

    /** 模型 toolCalls 的执行分发点全库唯一：只有 ToolExecutorImpl 消费 ToolUseBlock 列表。 */
    @Test
    void onlyToolExecutorDispatchesModelToolCalls() {
        // ArchUnit（test-support 依赖）：扫描 io.javanatic.harness.. 全部生产类，
        // 断言调用 ToolExecutor.execute 的类 ∈ { AgentLoopImpl }；
        // 断言构造 ToolResultEvent 的类 ∈ { ToolExecutorImpl }（审计 append 归属）。
        // 新增第二条分发路径（如某插件直接 execute 模型 toolCall）→ 本测试红。
    }
}
```

## 7. 配置/组合测试 + R4 verify

```java
class ProfileBundleTest {

    @Test
    void patchReplacesRowConfig() {
        List<ConfigRow> base = List.of(
            new ConfigRow("fs-local", Map.of("root", "/a"), false),
            new ConfigRow("shell-bash-local", Map.of(), false));
        Patch patch = new Patch(List.of(
            new PatchRow("fs-local", /*replace*/ true, Map.of("root", "/b"), false)));

        assertThat(AppBoot.applyPatch(base, patch))
            .element(0).isEqualTo(new ConfigRow("fs-local", Map.of("root", "/b"), false));
    }

    @Test
    void patchOnMissingPluginFailsLoud() { /* "nonexistent" → IllegalStateException */ }

    @Test
    void compositionIsTwoWayExplicit() {
        // 行引用不存在的 id → fail；发现的插件未被任何行引用 → fail（07 §5）
    }
}
```

```java
class GovernanceVerifyTest {

    @Test
    void productionPolicyRejectsAutoApproval() {
        // policy=production + approval-auto → verify 以非零退出并指出违规项
    }

    @Test
    void standardPolicyAcceptsAutoApproval_butVerifyStillRequiresMountedGovernance() {
        // standard + approval-auto → exit 0（治理已挂载，档位允许 AUTO）
    }

    @Test
    void missingLoopGuardFailsAtAssemblyNotAtRuntime() {
        // 不注册 LoopGuard 的组合 → boot 装配期抛（构造器强制，R4 类型层）
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

dsh CI 要求 100% per-file。JH 不强求，但：

- **kernel 模块**（scope/events/plugin）：目标 95%+（核心基础设施）
- **core 模块**（session/agent-loop/tools）：目标 90%+（主干逻辑）
- **provider 模块**（llm-deepseek/fs-local/bash-local）：目标 80%+（外部依赖多，靠 e2e 补）

JaCoCo 报告 + CI gate。

## 10. CI 建议

```yaml
# .github/workflows/ci.yml（建议）
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25' }
      - run: mvn -B verify                    # 单元 + 集成 + 架构测试 + 覆盖率
      - run: java -jar examples/headless/target/headless.jar --profile headless --verify
                                              # R4：治理断言进 CI，无 key 可跑
      - if: ${{ env.DEEPSEEK_API_KEY != '' }}
        run: mvn -B test -Dtest='*E2E*'       # 有 key 跑 e2e；无 key 自跳过
```
