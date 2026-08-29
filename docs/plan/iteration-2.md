# 迭代 2 — core.session（草稿，待确认）

模块：core/session（设计：docs/design/03-session-event-sourcing.md）

> 状态：**四确认尚未与用户达成**——按 AGENTS.md 迭代仪式，确认并落盘后方可开工。
> 以下为提案，以对话确认为准。

## 四确认（提案）

- **内容**：`LoggedEvent` 信封（seq / 时间 / ignorable）、首批 `SessionEvent` sealed 事件、append-only `SessionEventLog`、Surface 投影、`SessionEventCodec` SPI（domain 零 Jackson 注解）
- **目标**：Session 是事件日志——模型历史是 derived 投影；R1 信封语义有测试
- **为什么**：R1（可重建性）的地基；后续 llm.replay 与 agent-loop 都消费这条日志
- **不做**：JSONL 持久化后端（后续切片）、模型请求落账（agent-loop 切片）、真实 LLM 接入

## 验收（确认时填写）

- [ ] …
