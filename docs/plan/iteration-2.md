# 迭代 2 — core.session（已完成）

模块：core/session（设计：docs/design/03-session-event-sourcing.md；dsh 实现对照后修订）
提交：c70ffe1（类型层）→ 8a7efbf（Session/投影 + kernel Runtime.KEY）→ 6b57d2e（Store/Invariants）→ 收尾提交
验收日期：2026-08-31

## 四确认（已确认 2026-08-31）

- **内容**：`core/session` 全模块——
  1. `LoggedEvent` 信封（seq 在 append 锁内分配）+ `SessionEvent` sealed（8 事件 + `ExtensionEvent` 出口）
  2. 消息模型 `.message` 子包（**归属 session**，修 02：dsh 靠 TS type-only import 借用 llm 类型，Java 无此机制）
  3. `Session`：append 临界区（冻结→seq→surface 校验→落账→通知）；observer 构造注入；防重入 guard
  4. `SurfaceManager`：Append/Replace、provenance 全细则、replaceGeneration 缓存失效
  5. `SessionStore` + `SessionEvents` 四键 + `SessionStorePlugin` + `SessionInvariants.validate`
  6. `TurnEndReason`：开放接口（核心 Completed/Aborted/Error）
- **目标**：Session 作为 append-only 事件日志成立——seq 结构性连续、事件深冻结、投影纯函数、无效 Replace 变更前被拒
- **为什么**：五大基石之二（Session 是事件日志）的载体；llm.replay/agent-loop/持久化全部消费这条日志
- **不做**：codec+JSONL；六事件（AssistantChunk/ToolCall/ToolResult/RequestHeader/TodoWrite/RequestContext）随消费切片；tool/result 替换约束；fork/resume；R1 哈希回放；压缩生产者

## 验收（证据 = 实际执行的命令与结果）

- [x] 全 reactor `mvn -B -q package` 绿（38 项目；kernel 34 + session 32 测试全过）
- [x] `core/session` 零第三方、零 Jackson；`requires` = kernel + kernel.brand（02 的消费者按需加 brand 模式，非计划书面的"唯一 kernel"——诚实记录差异）
- [x] 主代码 884 行 ≤1500（`find core/session/src/main -name "*.java" | xargs wc -l`）
- [x] jqwik 属性：并发 append（1–8 线程 × 1–40 事件，千次采样）seq 集合恰为 0..n-1、无重复无跳号
- [x] 冻结隔离：构造后调用方改可变集合不影响事件（SessionEventTypesTest.constructionFreezesMutableCollections）
- [x] provenance 拒绝 ×4（未来 seq / 缺 shadowed / 重复 / 非 assistant 空列表）+ 豁免 ×1（assistant/message 空数组）
- [x] Replace 语义：投影中被覆盖段消失、日志不动、范围非法拒绝；replaceGeneration 经投影缓存失效间接验证（包私有，无直接断言——诚实记录）
- [x] 投影规则：user 逐字透传、assistant 空 content 不产生消息
- [x] 防重入：观察者内 append 抛 IllegalStateException 且 guard 复位；观察者异常 contained
- [x] end-seed：带 seed 构造自动补 marker、完整日志重开不重标、null seed 无 marker
- [x] ignorable：默认 false、LlmRequestEvent true、扩展事件自决（CronFired true）
- [x] ExtensionEvent：自定义事件可 append、可遍历、不变式复核通过
- [x] TurnEndReason 可扩展：测试侧自定义变体（TimedOut）通过编译与使用
- [x] 文档同步：02（Message 归属 session + 理由）、03（observer 接线、end-seed、provenance 细则、TurnEndReason/SurfaceEvent 形状、request/context 差异注记、构造时归一替代 structuralFreeze 函数）

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （验收过程中，非验收后） | 属性测试的收集列表自身并发丢写（普通 ArrayList），首轮 falsify 296→269——产品日志无恙，测试 bug | 收集改 `Collections.synchronizedList`；此例说明属性测试同时检验测试自身 |
