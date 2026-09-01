# 迭代 3 — llm seam + replay provider（进行中）

模块：llm/llm（Definition）、llm/replay（Provider）（设计：05 §3、10 §3）
包名：`io.javanatic.harness.llm` / `io.javanatic.harness.llm.replay`（模块名不变，包名去冗余段，与 session 同法）

## 四确认（已确认 2026-09-01）

- **内容**：
  1. `llm/llm`：`LlmService`（KEY/stream/registerAdapter→Disposable）+ 路由默认实现（`LlmPlugin` id `llm`：未知 provider fail loud 列出已注册项；重复注册 fail loud）；`LlmAdapter`；`StreamChunk` sealed（Delta/DeltaToolUse/Usage/Finish + `FinishReason`）；`LlmCallConfig`（provider/model，采样参数随 deepseek 切片扩）；`LlmRequest`（system/messages/tools/params，依赖 core/session 的 Message——验证迭代 2 归属决策）；`ToolSchema` 极简（name/description/parametersJson）；`CallId` 品牌 + `AbortSignal`/`AbortedException`（checkAbort 协议）；`ChunkAssembly` 纯函数（chunk 流折叠为 AssembledStep）
  2. `llm/replay`：`ReplayAdapter`（内存脚本逐次回放、耗尽 fail loud、chunk 间 checkAbort）+ `ReplayPlugin`（id `llm-replay`，`requires={"llm"}`，注册经 `scope.onClose` 挂回收）
  3. kernel 小增量：`Disposable.of(AutoCloseable)` 公有工厂（无栈撤销凭据——服务内部注册表的注销器）
  4. 测试 ~15 + 文档同步（05 §3 定稿、README 路线 ✅）
- **目标**：LLM capability seam 成立——一个 Definition 常驻、多 Provider 挂 adapter、Consumer 不 import 任何 Provider（JPMS 强制）；keyless 回放可用
- **为什么**：路线第 3 刀（keyless 测试依赖 replay 先于 deepseek）；三角色范式首个完整落地；厂商差异被压缩进单个翻译点（Adapter）
- **不做**：deepseek 真实 provider（SSE/队列/生产线程/认证，下一刀）；JSON snapshot 文件加载（Jackson 随 agent-loop 快照测试引入）；`AssistantChunkEvent` 仍不进 permits（生产者是 loop）；R1 哈希回放；abort 完整取消传播（仅 checkAbort 协议）

## 验收（证据 = 实际执行的命令与结果）

- [ ] 全 reactor `mvn -B -q package` 绿
- [ ] `llm/llm` requires = kernel + brand + core/session；`llm/replay` requires = llm.llm + kernel（+按需 brand）；零 Jackson、零第三方
- [ ] 主代码合计 ≤600 行
- [ ] 路由：注册后按 provider 分发、config/request 原样到达 adapter
- [ ] 未知 provider fail loud（消息含已注册清单）；重复注册 fail loud
- [ ] Disposable.of 注销后即失效；幂等
- [ ] 组装折叠：文本拼接 / tool-use 按 id 累积 arguments / usage 末次生效 / 缺 Finish fail loud / Finish 后再有 chunk fail loud
- [ ] replay：脚本按序回放、第 N+1 次 fail loud、chunk 间 checkAbort 生效（AbortedException）
- [ ] 插件装配：llm + llm-replay 经 PluginLoader 装载成功（requires 顺序）；replay 插件 apply 失败回滚后 adapter 消失（R3 端到端）
- [ ] 文档同步：05 §3（实际形状 + CallId/ChunkAssembly/Disposable.of 增量）、README 路线表与状态行

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
