# it33 停 1 review packet —— workbench/projection 契约与五视图

**新增模块自问（it30 裁决）**：本停新增模块 `workbench/projection`（是）——纯库、非 capability 三角、无 plugin、base bundle 无加行、无 ServiceLoader 双通道；pits「新插件三件套」不适用。`dist/jh` moditect 模块清单登记**延至停 2**（headless 开始消费、出现运行时模块路径时登记，pits #38④）。

**TL;DR（五行）**

- **不变量增量**：① 五视图 = `List<SessionEvent>` → 结构化记录（seq 恒等于列表下标）的纯函数，同输入必同输出、零写路径；② 未知 ExtensionEvent 只按 `type()` 字符串渲染（recognized=false、detail=null），负载永不进输出；③ tools 配对键 = callId（R2 审计同键），与日志相邻性无关；④ messages 视图 = `Session.create(...).deriveMessages()` 透传，不复制投影规则。
- **裁决点**：设计未钉 tools 配对键 → 实现钉 callId（位置配对对乱序并行结果错配，有测试钉死）；usage 视图 perTurn 含「有 assistant 但未报告计量」的轮（usage=null，与 turns 视图同序）。
- **风险自报**：core `Session.create` 空 seed 重放有 IndexOutOfBoundsException 潜在边缘（M5 实证），投影以 isEmpty 短路兜住——core 侧未修，属 core 潜在缺陷，建议挂账；messages 视图对 surface 元数据非法的日志原样抛 IllegalArgumentException（投影不校正畸形日志）。
- **证据入口**：[stop1-evidence.md](stop1-evidence.md)（红 30 错 → 3 断言修钉 → 绿 30 测；validate 两次 0 错；突变 M1–M5 红证据 + 零残留）。
- **若只能改一处**：改 `timeline()` 的 unknown 分支——它是「投影层只依赖核心事件 + type 字符串」边界的唯一闸门，其余四视图都建立在核心事件可穷尽 switch 之上。

## 契约面（SessionProjection 公共 API + 视图形状）

```java
public final class SessionProjection {
    public static SessionViews project(SessionPersistence.Loaded loaded);   // 五视图聚合入口
    public static List<TurnSummary> turns(List<SessionEvent> events);       // 轮边界/状态/时长/步·工具·计量统计
    public static List<ToolStep>     tools(List<SessionEvent> events);      // (turn,step) 分组 call/result 配对 + 孤儿
    public static UsageSummary       usage(List<SessionEvent> events);      // token 按轮 rollup + 总量
    public static List<TimelineEntry> timeline(List<SessionEvent> events);  // 全事件一行一摘要（日志序）
    public static List<Message>      messages(List<SessionEvent> events, SessionHeader header);
}

record TurnSummary(int turn, boolean open, long startTime, Long endTime, String endReason,
                   int steps, int toolCalls, TokenUsage usage)
record ToolStep(int turn, int step, List<Pair> pairs, List<Orphan> orphans)
    record Pair(long callSeq, String name, String arguments, Long resultSeq, Boolean isError,
                boolean concludesTurn, long durationMillis)      // resultSeq/isError=null 结果未知;durationMillis=-1 未配对
    record Orphan(long resultSeq, boolean isError, boolean concludesTurn)
record UsageSummary(TokenUsage total, List<TurnUsage> perTurn)
    record TurnUsage(int turn, TokenUsage usage)                 // usage=null 该轮未报告
record TimelineEntry(long seq, long time, String type, boolean recognized, TimelineDetail detail)
    sealed interface TimelineDetail { Turn / Step / User / Assistant / LlmRequest / ToolCall /
                                      ToolResult / CompactionStart / CompactionSummary /
                                      CompactionEnd / Request / Instructions }  // 12 变体全嵌套本文件
record SessionViews(SessionHeader header, List<TurnSummary> turns, List<ToolStep> tools,
                    UsageSummary usage, List<TimelineEntry> timeline, List<Message> messages)
```

边界声明（SessionProjection Javadoc）：只依赖核心事件 record 与 `type()` 字符串；输入列表只读、输出不可变快照（列表/Map 冻结；LlmRequestEvent.params 已由 core `Map.copyOf` 防御，投影不产生新别名）；零写路径（不触写者锁、不追加事件）。

## 三行映射（设计增量条目 → 实现 → 测试）

| 设计增量（iteration-33.md） | 实现 | 测试 |
|---|---|---|
| workbench/projection 纯库模块（依赖 session + session/persistence seam；五视图纯函数） | `workbench/pom.xml` + `projection/pom.xml` + `module-info.java`（requires kernel.brand/core.session/session.persistence）+ 根 pom 组注册与 dependencyManagement | SessionProjectionTest（project 聚合 = 五视图分算 + jqwik 1000 试次不抛） |
| 未知事件边界声明（按 type 渲染、不解码、负载不进输出） | timeline `ExtensionEvent → unknown(i, ext)`（recognized=false/detail=null） | TimelineViewTest.unknownExtensionRendersTypeOnlyAndNeverTouchesPayload（bomb payload 的 toString 即炸）+ interleaved 序保持 |
| CLI 两 flag（`--session=`/`--session-view=`） | **停 2** | HeadlessOptionsTest（停 2 钉死） |

## 偏离清单

| 出现什么 | 为什么 | 待裁决 |
|---|---|---|
| tools 配对键钉为 callId（设计只写「call/result 配对 + isError + 耗时」，未钉键） | `ToolResultBlock.toolUseId` 是 R2 审计的同一把键；位置配对对乱序并行结果错配（M2 实证可破坏） | 无——契约内精化，非偏离 |
| usage 视图 perTurn 含「未报告计量」的轮（usage=null） | 「该轮没有报告」本身是信息（与「无 assistant 轮」区分）；与 turns 视图同序 | 无——UsageSummary Javadoc 已文档化 |
| messages 视图复用 `Session.create(...).deriveMessages()` | 一个事实一个家（R1 load+derive 路径）；零新 core API | 无 |
| 空 seed 短路守卫（core 潜在 IOBE） | 投影不背 core 的潜在缺陷；守卫成本一行 | **建议挂账 core**：`Session.create` 空 seed 越界（M5 实证） |
| 无 | 设计增量「零新事件、零写路径」——实现全程只读 load 侧 | 无 |

## 为什么是这个形状 / 若错靠什么发现

- **为什么是纯函数 + 结构化记录**：读侧是派生视图，事实源只有 append-only 日志；纯函数可单测、可复用 R1 的 load+derive 路径，W2 TUI / W3 server 三站共用一层（不各自手搓解析）。文本渲染留在 headless（停 2），投影层不引任何 I/O 依赖。
- **为什么 messages 透传 deriveMessages**：surface 投影规则已有且被 R1 回放验证（`JsonlPersistenceTest` 用同一等式）；复制规则 = 两处真相，漂移即错。
- **若错靠什么发现**：五视图各 4–8 个聚焦用例 + 1 个 jqwik 属性（任意事件序列折叠不抛、seq=下标）+ 5 例突变（callId 配对/未知边界/空 seed 守卫/toolCalls 折叠/未报告计量轮）。停 2 的真实会话检视（it31 双臂会话：委派树/真实 usage/工具失败/长轮次）是对夹具面的事实校验。
