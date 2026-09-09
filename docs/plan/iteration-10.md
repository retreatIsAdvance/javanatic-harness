# 迭代 10 — 长跑能力:compaction + budget + resume + request-context(进行中)

模块:core/session(RequestHeader/CompactionEvent + 投影/codec)、core/agent-loop(CompactionService + 触发 + budget)、core/tools(Limits 增档)、core/system-prompt(上下文 section)、examples/headless(--resume + 持久会话根)、bundle/base(行)
提交:①路线修订+计划 → ②session 事件词表 → ③compaction 服务与触发 → ④budget 档 → ⑤resume + request-context → ⑥文档验收
四确认日期:2026-09-09

## 四确认(已确认 2026-09-09;路线修订同日落盘——0.1.0 判据 = 生产模拟场景进 CI 常绿)

- **内容**:
  1. **compaction 生产者(dsh 形状 + agentscope 校准,三方对照后修订)**:事件词表为 **3 个 log-only 事件**(`compaction/start` 日志锁 → `compaction/summary`(摘要文本 + provider/model/usage 审计——维护调用 R1 可重建 + shadowed 区间)→ `compaction/end`(解锁,error 记失败)),摘要本体走 `user/message` + `SurfaceOp.Replace` + `MessageSource.Compaction`(surface 事件类型不扩展);**切点按 tool 配对边界**(尾部估价累计到 retainTokens,边界拆配对则回退,非整轮);估价器 chars/2.5 + 每消息 5 + 工具调用 10/结果 8 开销(agentscope 校准,对中文诚实);摘要指令作为**最终 user message**(KV cache 前缀复用),7 段 checkpoint 格式,dsh 框架前缀 + 旧 checkpoint 合并规则;独立摘要模型路由(summarizationProvider/Model);**fail-closed**(截断/失败 → compaction/end error,重试有界,占位符摘要拒绝);摘要文本与 user/message 双落账
  1b. **溢出恢复(提入本迭代)**:runStepLoop 的 REQUEST_ERROR catch 里错误串匹配(`context_length_exceeded`/`maximum context`/`token limit`/`context length`)→ 强制压缩(无视阈值)→ 同 step 重试一次(有界);dsh/agentscope 共同的安全网,JH 的 REQUEST_ERROR waterfall 已就位
  2. **LoopGuard budget 档**:Limits 增 `maxBudgetTokens`(0=不限,二参便利构造兼容旧调用点);语义 = 累计**输出** tokens 超限优雅关轮(生成开销),上下文尺寸归 compaction 阈值——两旋钮各管一侧;PRODUCTION 档补断言非零
  3. **headless `--resume=<sessionId>`**:会话根默认 `~/.harness/sessions`(durable,测试注入 tempdir);load → seed 重建 → registry.resume 续轮号;session/end-seed 自动标 resume 分界(构造器既有行为)
  4. **request-context 最小集**:`RequestHeader` 事件(log-only,ignorable,cwd+ISO 日期)由 loop 轮首落账;SystemPromptService 组装读最新 RequestHeader 追加上下文 section——同日志必同提示词(R1 哈希覆盖)
- **目标**:一个会话能在真实模型下**跑长**——爆上下文前压缩、跨进程复活、超预算优雅停
- **为什么**:生产模拟门槛(it13 打 0.1.0 的判据)前半;compaction 机制 it2 预留至今无生产者,挂账最久
- **不做**:大工具结果卸载/修剪(it11,实现时采 agentscope 落盘 + read_file 提示式);token-meter 独立 seam(CJK 低估实际造成困扰再立);手动 /compact 命令(commands 未落地);并行多会话;subagent 继承;计划模式/todo(it11);sandbox/restriction(it12);压缩请求自身的 LlmRequestEvent 锚点(审计内嵌 compaction/summary,不混入模型请求流)

## 验收(证据 = 实际执行的命令与结果)

- [ ] CompactionEvent/RequestHeader:sealed 追加 + 投影 + codec 往返;ignorable 决策正确
- [ ] compaction:阈值触发 → 摘要落账 + surface 收缩(deriveMessages 变短)+ 尾部轮完整(tool_use/tool_result 配对不破);压缩后后续请求 R1 哈希仍全比对(重装同提示词);无 LLM 服务时不触发
- [ ] budget:累计输出超限 → turn/end(Error) 优雅停;Limits 三参与二参便利构造共存;PRODUCTION 断言零 budget 拒
- [ ] resume:进程 A 跑 N 轮 dispose → 进程 B(新 Runtime)`--resume` 续跑 → 轮号连续、日志含 resume 分界(session/end-seed);CLI 冒烟
- [ ] request-context:提示词含日期/cwd 且 R1 哈希覆盖(重放侧同组装);无 RequestHeader 时行为不变
- [ ] 全 reactor package 绿(240 → 260+);预算:session 增量 ≤150 / agent-loop 增量 ≤400 / system-prompt 增量 ≤40
- [ ] 文档:03(两事件实现落定)、04(step 边界触发 + 维护调用)、README/AGENTS(路线表 10✅)、iteration-10 验收

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
