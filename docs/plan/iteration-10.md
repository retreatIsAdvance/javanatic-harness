# 迭代 10 — 长跑能力:compaction + budget + resume + request-context(已完成)

模块:core/session(RequestHeader/CompactionEvent + 投影/codec)、core/agent-loop(CompactionService + 触发 + budget)、core/tools(Limits 增档)、core/system-prompt(上下文 section)、examples/headless(--resume + 持久会话根)、bundle/base(行)
提交:2e04830(计划修订)→ e3ed238(事件词表+codec)→ 47c76d1(compaction)→ 5840add(budget)→ 1da80f3(resume+context)→ 收尾
四确认日期:2026-09-09;验收日期:2026-09-10

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

- [x] 事件词表:4 新事件(3 compaction + request/header)+ MessageSource.Compaction;codec 往返(JsonlPersistenceTest 扩到 17 事件全历史逐字段 equals);全部 ignorable(log-only);**user/message codec 补 Replace+sourceEventSeqs 持久化**(此前只写 Append——压缩 checkpoint 重载不丢,R1 补洞)
- [x] CompactionTest 3(脚本 adapter 三段:tool_use+高 usage→摘要→终答):事务序列 start→user/message(replace)→summary→end;审计含 provider/usage;投影首条=Compaction source;belowThreshold 不触发;配对测试:边界回退越过 tool/result,盖写区间只到 user/message(seq 1)
- [x] BudgetGuardTest 5(累计输出超限拒绝/零=不限/在预算内过/二参工厂保持零/config 路径解析);PRODUCTION 断言零 budget 拒(AppBootTest 同步)
- [x] HeadlessResumeTest:新 Runtime 跨进程 load→seed 重建→registry.resume 续跑,两轮 turn/start + session/end-seed 分界在案
- [x] SystemPromptImpl 读最新 request/header 追加上下文 section(cwd+date);loop 轮首落账;无事件返回空(行为不变);三个 loop 序列断言更新(request/header 在 turn/start 后)
- [x] 全量见收尾;预算:session +~120 / agent-loop +~640(>400:溢出恢复+估价器+摘要指令属计划内增量,记录在案)/ system-prompt +~38
- [x] 文档:03 实现落定增补两段、AGENTS 现状、README 路线表 10✅

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| e3ed238 | user/message codec 只写 Append——压缩 checkpoint 的 Replace 语义重载即丢(往返测试当场抓) | codec 补 Replace + sourceEventSeqs 持久化;R1 补洞 |
| 47c76d1 | 估价器漏 ToolResultEvent(返回 0)→ 尾部预算永不满足,配对测试"nothing to compact" | 补 ToolResultEvent 估价(content/2.5 + 消息 5 + 工具块 9) |
| 47c76d1 | CompactionPlugin 双构造器:config 路径 apply 里 new 第二实例 provide——状态分裂(测试经注册表触发的服务实例与 config 解析实例不同构) | 单构造器 + apply 内解析 config 构造实现并 provide(同 ApprovalAsk 模式) |
| （it10.1） | DEFAULT_MAX_CONTEXT_TOKENS=60k 绝对默认依据薄弱——64K 模型下 94%(靠溢出恢复兜底)、128K 下 47%(浪费窗口)、V4-1M 下 6%;dsh 源码证实阈值应模型相对(0.8 × contextWindow,容量未知 fail loud),测试绿掩盖了默认值站不住 | 阈值改 `contextWindow × thresholdRatio(0.8)`/绝对覆盖两路径 + 缺容量 apply fail loud;base 行 disabled(base 不猜模型窗口);ConfigValues.doubleValue;比例/覆盖/fail-loud 三测试 |

| 全程 | headless 测试类多次 python 批量补丁后 import 块损坏(裸 import/FQN 混入)——FormatterException 连锁 4 轮 | 逐次修;教训再证:改测试 import 用小步 Edit 而非批量正则 |
