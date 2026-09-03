# 迭代 5 — agent-loop 竖切收口(进行中)

模块:core/agent、core/agent-loop、core/system-prompt、examples/agent-spine(设计:04、10 §6、02;05 §4 消费)
提交:①契约(system-prompt + agent)→ ②agent-loop 驱动 → ③agent-spine 示例 + 文档 → ④R2 架构测试
四确认日期:2026-09-03

## 四确认(已确认 2026-09-03)

- **内容**:`core/agent` 公开契约(Agent 接口、Inbox 双队列、AgentCancelCause sealed、AgentRegistry + ScopedValue initiator、AgentHandle、AgentEvents 五键 + PreStepDecision、AgentOptions);`core/system-prompt` 最小组装(KEY + contributor 注册 + assemble(Session) 只读日志);`core/agent-loop` 驱动(AgentLoopImpl 十依赖构造器、driver 虚拟线程 + 唤醒竞态修复、Turn/Step 状态机、请求指纹落账、executor 唯一调用点、AbortController first-cause-wins、LoopGuard max-turns/max-steps、AgentLoopPlugin);`examples/agent-spine` 可运行竖切(八插件直装、replay 含 tool_use、终态打印事件序列);R2 架构测试(ArchUnit,test-scope)
- **目标**:竖切闭环——一条输入从 Inbox 进入,经 pre-step 落账、Step 状态机调 replay 模型、tool_use 经唯一 executor 真跑 fs 工具、结果回填、终答、TurnEnd 落账;全程事件日志可投影,示例真实跑通
- **为什么**:路线「最小完整竖切」最后一刀;loop 是 R1 落账侧 / R2 分发点 / R4 构造器强制的载体;it3 的 replay 与 it4 的 executor 至今没有真实消费者,竖切不闭环则 shell/deepseek/persistence 无处可挂
- **不做**:examples/headless(依赖 bundle 组合层,随 06/07 切片;路线表文字随本迭代修正为 agent-spine);Round 外层策略;AssistantChunkEvent 遥测;RequestHeader/RequestContext/TodoWrite 事件(loop 走 null 分支);resume 持久化重载(in-memory get 即 resume,JSONL 随 persistence 切片);REQUEST_ERROR 完整重试词表(只做 firstOf 钩子 + 默认 Error 收口);LoopGuard budget 档(随 deepseek);shell/sandbox/deepseek/approval 三模式/--verify

## 设计决策(与设计稿差异,实现后回写 04)

- **AbortController 适配 llm.AbortSignal**:it3 已定型 `llm.AbortSignal` 为仅 `checkAbort()` 的接口且 `ToolExecutor.execute` 消费它;loop 侧 controller 生产该接口视图,cause 经 loop 读取后以 String 进 `TurnEndReason.Aborted(cause)`(session 词表保持 String,避免 session 反向依赖 agent)。设计 04 §9 的 record 形状不采用
- **架构测试放 examples/agent-spine test scope**:R2 断言「全库唯一分发点」,只有看得到全部生产模块的 classpath 才有资格下断言
- **ArchUnit(test-scope,仅 examples)**:删掉自研类扫描与断言 DSL;不污染任何生产模块

## 验收(证据 = 实际执行的命令与结果)

- [ ] 全 reactor `mvn -B -q package` 绿(38 项目,测试数 ≥140)
- [ ] 依赖边:core/agent = kernel 家族 + session(零第三方);core/agent-loop = agent + tools + system-prompt + llm + kernel 家族;system-prompt = kernel + session;ArchUnit 仅 examples/agent-spine test-scope
- [ ] 主代码预算:agent ≤600 / agent-loop ≤800 / system-prompt ≤200(超了先删)
- [ ] 集成测试五则:preStepReject 无 step 关轮 / cancelDuringTool → aborted / inject 进下一 step 不进当前 / concludesTurn 数据驱动停轮 / driver 退出窗口不丢唤醒
- [ ] Inbox 并发 append/claim 属性测试;AbortController first-cause-wins;initiator 绑定(driver 内非 null、外部线程 null);create/dispose 全链(cancel→idle→scope 回收)
- [ ] 端到端:agent-spine 真实运行(命令 + 事件序列输出落案);事件序列断言 = turn/start → user/message → step/start → llm/request → assistant/message → tool/call → tool/result → step/end → … → turn/end(completed)
- [ ] R2 架构测试:调用 ToolExecutor.execute 的类 ∈ {AgentLoopImpl}、构造 ToolResultEvent 的类 ∈ {ToolExecutorImpl};含红证(故意插第二条分发点 → 红)
- [ ] 文档同步:04(AbortController 形状修正 + 实现落定)、02(agent-spine 落位)、README 路线表修正、AGENTS 现状

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
