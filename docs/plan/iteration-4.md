# 迭代 4 — core.tools + fs（R2 单一执行路径）（进行中）

模块：core/tools、fs/fs、fs/local、fs/tool + session permits 增量（设计：05 §4/§8、03 §1）

## 四确认（已确认 2026-09-02）

- **内容**：
  1. session：`ToolUseBlock`/`ToolResultBlock` 进 ContentBlock、`MessageSource.Tool(callId)`、**CallId 自 llm/llm 迁入 session.message**（解依赖环）、`ToolCallEvent`/`ToolResultEvent` 进 permits、DeriveMessage tool/result 投影
  2. core/tools（**首个 Jackson 边界模块**——模型 tool JSON 校验边界）：ToolRegistry（register→Disposable、schemas()、resolve）；ToolDefinition/ValueSchema 极简/ToolArgs（Jackson）/ToolExecutionResult/RenderIntent；ToolEvents（pre/post waterfall）；ApprovalService + Approvals.auto()/deny() + approval-auto 插件；ToolExecutorImpl 五段 pipeline（审计落账→去重→pre→审批→执行→post→落账），session 为方法参数，并行执行按序 join
  3. fs/fs（FsService 五操作+DirEntry）、fs/local（fs-local 插件）、fs/tool（fs-tool 五工具，不做审批）
  4. 测试 ~20 + 文档同步
- **目标**：R2 成立——模型副作用有且仅有一条路径且结构性留痕（四路径全部成对落账）；fs 走通完整三角色 + pipeline
- **为什么**：harness 安全核心（R2/R4 机制载体）；agent-loop 每步消费 executor；replay 的 tool_use 脚本从此有可执行下游
- **不做**：R2 架构测试（断言对象 agent-loop，切片 5）；Approval 三模式与 ask-user（interaction 切片）；完整 JSON Schema 词表；ToolResultEvent.meta/JsonValue（恒 null）；registry scope overlay（随 agent）；shell/sandbox；deepseek

## 验收（证据 = 实际执行的命令与结果）

- [ ] 全 reactor `mvn -B -q package` 绿
- [ ] 依赖边：core/tools = kernel 家族 + session + llm.llm + jackson-databind（唯一第三方）；fs 三模块零第三方
- [ ] 主代码合计 ≤1300 行
- [ ] 四路径成对落账：成功 / 工具抛异常 / pre-execute 否决 / 审批拒绝 → 各有 tool/call + tool/result（错误即数据，turn 不炸）
- [ ] 批内重复 callId → error result；未知工具 → error result
- [ ] AbortedException 穿透 pipeline 传播（不被吞成 error result）
- [ ] 并行执行、返回与输入同序
- [ ] post-execute waterfall 可改写结果
- [ ] fs 工具经完整 pipeline 读写临时目录；session 投影含 UserMessage(source=Tool, [ToolResultBlock])
- [ ] 文档同步：05（CallId 迁移/executor session 参数/schemas 暂缓）、02（Jackson 首入 core/tools）、README ✅

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
