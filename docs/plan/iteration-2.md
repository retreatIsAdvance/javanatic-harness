# 迭代 2 — core.session（进行中）

模块：core/session（设计：docs/design/03-session-event-sourcing.md；dsh 实现对照后修订）

## 四确认（已确认 2026-08-31）

- **内容**：`core/session` 全模块——
  1. `LoggedEvent` 信封（seq 在 append 锁内分配）+ `SessionEvent` sealed（8 事件：TurnStart/TurnEnd/StepStart/StepEnd/UserMessageEvent/AssistantMessageEvent/LlmRequestEvent/SessionEndSeedEvent + `ExtensionEvent` 出口）
  2. 消息模型 `.message` 子包：`Message`（sealed：UserMessage/AssistantMessage）、`ContentBlock`（sealed：TextBlock）、`MessageSource`、`TokenUsage`——**归属 session**（修 02：dsh 靠 TS type-only import 借用 llm 类型，Java 无此机制）
  3. `Session`：append 临界区四步（冻结→seq→surface 校验→落账+通知）；**observer 列表构造注入**（补 03 空白：dsh 经 WeakMap attachment 接线）；**防重入 guard**（观察者内再 append 抛）
  4. `SurfaceManager`：Append/Replace、provenance 全细则（含 assistant/message 空数组豁免、字段缺省=不记录、非 surface 事件带元数据拒绝）、replaceGeneration 缓存失效
  5. `SessionStore` + `SessionEvents` 四键 + `SessionStorePlugin` + `SessionInvariants.validate`
  6. `TurnEndReason`：**non-sealed 可扩展**（核心变体 Completed/Aborted/Error 起步，其余随 agent-loop 进）
- **目标**：Session 作为 append-only 事件日志成立——seq 结构性连续、事件深冻结、投影纯函数、无效 Replace 变更前被拒；`deriveMessages` 可用；R1 地基就位
- **为什么**：五大基石之二（Session 是事件日志）的载体；llm.replay/agent-loop/持久化三个后续切片全部消费这条日志
- **不做**：codec SPI + JSONL（session/persistence 切片）；AssistantChunk/ToolCall/ToolResult/RequestHeader/TodoWrite/**RequestContext** 六事件随各自消费切片进 permits（dsh 有 request/context、JH 无——已记入 02 修正）；tool/result 单节点只改 content 的替换约束（随 ToolResultEvent）；fork/resume；R1 哈希回放测试（需提示词组装）；压缩的生产者（本迭代只有 Replace 机制）

## 验收（证据 = 实际执行的命令与结果）

- [ ] 全 reactor `mvn -B -q package` 绿
- [ ] `core/session` module-info 唯一 `requires` = kernel；零第三方、零 Jackson
- [ ] 主代码 ≤1500 行（`find core/session/src/main -name "*.java" | xargs wc -l`）
- [ ] jqwik 属性：随机并发 append 后 seq 集合恰为 0..n-1、无重复无跳号
- [ ] 冻结隔离：append 后调用方改可变集合不影响日志
- [ ] provenance 拒绝用例 ×4：引用未来 seq / 缺 shadowed / 重复 / 非 surface 事件携带元数据；豁免用例 ×1：assistant/message 空数组
- [ ] Replace 语义：投影中被覆盖段消失、replaceGeneration 递增、范围非法（start/end 不在 surface、start>end）拒绝
- [ ] 投影规则：user 原样进 surface；assistant 空 content 不产生消息
- [ ] 防重入：观察者内 append 抛 IllegalStateException；观察者抛异常不影响 append 返回与其他观察者（contained）
- [ ] end-seed 编排：带 seed 构造自动补 marker；seed 已以 end-seed 结尾不重标
- [ ] ignorable：默认 false、LlmRequestEvent true
- [ ] ExtensionEvent：插件自定义事件可 append、可遍历
- [ ] TurnEndReason 可扩展：测试侧实现自定义变体通过编译与 append
- [ ] 文档同步：02（Message 归属 + request/context 注记）、03（observer 接线、end-seed、provenance 细则、TurnEndReason）

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
