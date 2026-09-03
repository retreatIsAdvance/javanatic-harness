# 迭代 4 — core.tools + fs（R2 单一执行路径）（已完成）

模块：core/tools、fs/fs、fs/local、fs/tool + session permits 增量（设计：05 §4/§8、03 §1）
提交：eb840b5（session 工具词表 + CallId 迁移）→ f8cb9fe（tools + kernel waterfall 修复）→ 4930d27（fs 三模块）→ 收尾提交
验收日期：2026-09-02

## 四确认（已确认 2026-09-02）

- **内容**：session 工具词表（ToolUseBlock/ToolResultBlock/MessageSource.Tool/两事件/投影 + CallId 迁入）；core/tools（ToolRegistry/ToolDefinition/ToolArgs(Jackson)/ToolExecutionResult/ValueSchema/RenderIntent/ToolEvents/ApprovalService+auto/ToolExecutorImpl 五段 pipeline）；fs 三模块（FsService/fs-local/五工具 fs-tool）
- **目标**：R2 成立——模型副作用有且仅有一条路径且结构性留痕；fs 走通完整三角色 + pipeline
- **为什么**：harness 安全核心（R2/R4 机制载体）；agent-loop 每步消费 executor；replay 的 tool_use 脚本从此有可执行下游
- **不做**：R2 架构测试（随切片 5）；Approval 三模式（interaction 切片）；完整 JSON Schema 词表；meta/JsonValue；registry scope overlay；shell/sandbox；deepseek

## 验收（证据 = 实际执行的命令与结果）

- [x] 全 reactor `mvn -B -q package` 绿（38 项目，**106 测试**：kernel 35 + session 33 + llm 17 + replay 6 + tools 13 + fs 6）
- [x] 依赖边：core/tools = kernel 家族 + session + llm.llm + **jackson-databind（仓库首个第三方，仅此模块）**；fs 三模块零第三方；fs/tool 的 provider 仅 test-scope
- [x] 新增主代码 974 行 ≤1300（tools+fs 862 + session 增量 112；session 总 996 ≤1500）
- [x] 四路径成对落账：成功（tool/call+tool/result 各一）/ 工具抛异常（error result）/ pre-execute 否决（"vetoed: policy"）/ 审批拒绝（"denied"）——全部 2 条日志事件
- [x] 批内重复 callId：恰一成功 + 恰一 Duplicate 错误、4 条留痕；未知工具 → "Unknown tool" error result
- [x] AbortedException 穿透 pipeline 传播（只留 tool/call，不伪造结果）——依赖 kernel waterfall 语义异常裸抛（本次修正）
- [x] 并行执行返回与输入同序（慢工具在首、快工具在后 → ["first","second"]）
- [x] post-execute waterfall 改写结果（"spilled"）生效
- [x] fs 端到端：四插件装载 → 5 schema 名称排序 → fs_write+fs_read 经 pipeline → 投影两条 UserMessage(source=Tool)；LocalFs 直测 5 项（含 edit 首处替换/缺文 fail loud）
- [x] 文档同步：01（waterfall 裸抛契约）、02（core/tools 行：llm.llm 依赖 + Jackson 边界）、05（实现落定七条）、README ✅

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （验收过程中） | kernel waterfall 把语义 RuntimeException 裹进 CompletionException——executor 的 AbortedException catch 失效，取消被吞成 error result（tools 测试抓出） | kernel 改为裸抛（与 ScopeImpl.effect 同例）+ kernel 测试 waterfallRethrowsSemanticRuntimeExceptionsUnwrapped；旧 next-twice 测试随契约更新 |
| （同上） | duplicate-callId 测试过约束：并行同 id 谁占坑不确定，断言「首个成功」为伪稳定 | 改断言真契约（恰一成功/恰一 Duplicate/4 条留痕），3 轮连跑确认 |
| （同上） | record 规范构造器不可收窄可见性（JLS）——ToolExecutionPlan 想藏构造器被编译器拒 | 工厂 + Javadoc 不变量（语言边界，记录于此防再犯） |
