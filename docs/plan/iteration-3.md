# 迭代 3 — llm seam + replay provider（已完成）

模块：llm/llm（Definition）、llm/replay（Provider）（设计：05 §3、10 §3）
提交：879a91a（seam）→ 77eb73f（replay，amend 含模块 requires 修正）→ 收尾提交
验收日期：2026-09-01

## 四确认（已确认 2026-09-01）

- **内容**：`llm/llm` seam（LlmService 路由 + LlmAdapter + StreamChunk 词表 + LlmCallConfig/LlmRequest/ToolSchema + CallId/AbortSignal + ChunkAssembly 纯函数）；`llm/replay`（脚本回放 adapter + 插件）；kernel `Disposable.of` 工厂
- **目标**：LLM capability seam 成立——一个 Definition 常驻、多 Provider 挂 adapter、Consumer 不 import Provider；keyless 回放可用
- **为什么**：路线第 3 刀（keyless 测试依赖 replay 先于 deepseek）；三角色范式首个完整落地；厂商差异压缩进单个翻译点
- **不做**：deepseek 真实 provider；JSON snapshot 文件；AssistantChunkEvent；R1 哈希回放；abort 完整取消传播

## 验收（证据 = 实际执行的命令与结果）

- [x] 全 reactor `mvn -B -q package` 绿（kernel 34 + session 32 + llm 11 + replay 5 = 82 测试）
- [x] `llm/llm` requires = kernel + brand + core/session；`llm/replay` = llm.llm + kernel + brand + **core.session**（计划写"按需"，实测测试直用 Message 类型必须自声明——非 transitive 政策的预期行为，如实记录）；零 Jackson、零第三方
- [x] 主代码合计 467 行 ≤600
- [x] 路由：config/request 原样到达 adapter（verbatim 测试）
- [x] 未知 provider fail loud（消息含已注册清单）；重复注册 fail loud
- [x] Disposable.of 注销后即失效、幂等（kernel 侧由 replay 卸载路径间接覆盖 + llm 服务直测）
- [x] 组装折叠六则：文本拼接 / tool-use 按 id 累积 / 顺序保持 / usage 末次 / 缺 Finish / Finish 后分块（各一用例）+ name 冲突 fail loud
- [x] replay：脚本按序、耗尽 fail loud、checkAbort 生效（AbortedException）
- [x] 插件装配：llm + llm-replay 装载成功；缺 llm 时 fail loud；**replay 插件 apply 失败回滚后 adapter 消失、清单为空**（R3 端到端）
- [x] 文档同步：05 §3（实现落定段 + 词汇类型段修正：Message 移 session、FinishReason 三值）、README 路线表与状态

## 验收后修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （验收过程中，非验收后） | llm/replay 测试直用 session Message 类型但模块未 requires core.session——非 transitive 政策下每个触碰方必须自声明，编译期抓获于推送前 | 模块与 pom 补 requires/依赖，amend 进 77eb73f；05 增补 JPMS 注意事项 |
| （同上） | ChunkAssembly 首版 ToolBuilder.build() 丢失 CallId；Spliterators API 误用 | 编译期/评审发现即修 |
