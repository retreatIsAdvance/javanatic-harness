# 迭代 11 — todo_write + 计划模式

模块:core/tools(ValueSchema.Arr)、core/todo(新)、core/plan(新)、core/session(并发契约)、core/system-prompt(PromptSection sealed)、core/agent-loop(PRE_STEP args)、session/persistence-jsonl(ServiceLoader codec 发现)、kernel/config(requireBool)、bundle/base(行)
四确认日期:2026-09-10

## 四确认(已确认 2026-09-10;R2 张力修正同日:照 dsh,工具经 context.session() 直写领域事件)

- **内容**:
  1. **WP0 kernel/config**:`ConfigValues.requireBool`——fail-loud 家族布尔版,与 requireString 对称(todo 的 `allowParallelInProgress` 是部署必选项,dsh Config.required 先例)
  2. **WP1 ValueSchema.Arr**:`Arr(description, items)` 入 permits;ToolArgs.validate 补分支(isArray + 逐元素递归);`readList(field)` → List\<ToolArgs\>(元素即已按对象 schema 校验的子 reader);ScopedRegistry.toNode 补 array 分支(sealed switch 编译器强制,无遗漏面)
  3. **WP2 core/todo + R2 修正 + Session 并发契约**:
     - `ToolExecutionContext(AbortSignal, Session)`——todo 工具内直接 `context.session().append(TodoWriteEvent)`,解析/校验/落账一处完成,「落账=模型所写」结构性成立(dsh 形状;采纳用户质疑,弃监听器重解析方案)
     - R2 Javadoc 修正回写(ToolCallEvent/ToolDefinition/ToolExecutorImpl):审计对(tool/call+tool/result)归 executor 无条件落账;工具可经 context.session() 追加领域事件,Session.append 的同步与 surface 校验是既有防线。两条 R2 架构断言不变
     - Session.append 并发契约显式化(工具成为并行写者后契约转为公开承诺):Javadoc 四点——任意线程可并发调用、monitor 建全序且每次 append 原子、跨执行者交错序任意而事件关联靠内容(callId)永不靠日志相邻性、观察者锁内同步执行须快且不得再 append;两个机械测试钉住(N 线程 × M 事件连续性;同批并行工具交错配对)
     - core/todo 模块:TodoStatus 枚举(pending/in_progress/completed)+ TodoItem + TodoWriteEvent(ExtensionEvent,"todo/write",ignorable=false)+ TodoWriteCodec + TodoPlugin(id "todo",requires "tools",allowParallelInProgress requireBool 必配;校验:content trim 非空、不重复、非并行时 ≤1 in_progress、status 枚举收窄——Str schema 表达不了归工具;成功叙述含三态计数)
     - 扩展 codec 发现改 ServiceLoader:persistence-jsonl apply() 里 ServiceLoader.load(SessionEventCodec.class)(module-info `uses` + 各事件模块 `provides` + META-INF/services 双注册)——消除行序依赖,后续扩展事件同此路径
  4. **WP3 core/plan + PromptSection sealed**:
     - PromptSection 转 sealed:Static(priority, content) / Dynamic(priority, Function\<Session,String\>);assemble 跳过空产出(动态段未激活=空串);现有使用点同 PR 更新
     - core/plan:PlanModeEvent+PlanModeCodec("plan/mode",ignorable=false——影响提示词,静默丢弃毁 R1);PlanModeService(KEY "plan-mode":foldActive 纯 fold 末值胜、active、sectionFor);PlanModePlugin(id "plan",requires tools+system-prompt,双装配路径,section requireString;exit_plan_mode:非 plan 模式→error result、plan 须 `# ` 标题开头、过审→**直接落账 plan/mode(false)**(todo 同款直写);PromptSection.Dynamic(50, sectionFor)——dsh plan:policy order 50 对齐)
     - **PRE_STEP args 增 session 被撤销**(实施中发现,见修正表):JH 的 PRE_STEP 是轮级裁决(runTurn 的 admit,一次),dsh 的步界提交依赖步级钩子;照搬会把「下一步生效」偷换成「下一轮生效」。exit 直写使工具批 join 后的下一次 assemble 即读到 inactive——字面为真。步级扩展点挂账(消费者:/plan 命令、sandbox 期批内规划态读取)
  5. **WP4 bundle + 文档**:base 行 todo(allowParallelInProgress: false——单 agent headless 诚实默认)+ plan(section:规划期指导文);root pom + 双 module-info;05/04/03/README/本文件
- **目标**:模型获得任务清单与规划协作两种 logged 状态——多步工作的自我追踪 + 执行前人审规划;扩展事件路径(ExtensionEvent+codec)落定两个生产实例
- **为什么**:dsh 对照系里 todo/plan 是 agent 自治性的最小闭环(规划-执行-追踪);it9 冻结的四个 seam 不动,全部走既有扩展点;0.1.0 生产模拟(it13)需要 plan 模式演示人工审阅路径
- **不做**:plan 模式只读强制(it12 sandbox——提示词级指导先行为诚实);/plan 命令与切换叙述消息(commands/inbox seam 未落地);审阅选项+反馈回传(ask-user seam;it11 审批骑 executor 固定 stage:ask 模式人工拒=继续规划);todo/plan 的 UI 投影 seam(session-projection 未立);多会话并发

## 验收(证据 = 实际执行的命令与结果)

- [x] 全 reactor `mvn -B package` 绿(**254 测试** 0 失败 0 错误;新增 13 = todo 6 + plan 6 + session 并发 1)
- [x] 事件序测试:tool/call → todo/write → tool/result(单工具);并行批次交错日志配对靠 callId 成立、slow 审计对被隔 ≥3 事件(相邻性非契约钉进门禁)
- [x] todo 校验矩阵:空/重复 content、非并行 >1 in_progress、未知 status 全拒且零 todo/write;requireBool 缺配 fail loud
- [x] Session 并发测试①:8 虚拟线程 × 200 append → 全部落账、seq 连续 0..1599、观察者逐条恰好一次、重入拒绝 1600 次
- [x] plan:fold 末值胜(无事件=inactive)、exit 非 plan 模式/无 `# ` 标题拒、**loop 级**事件子序断言(turn/start…tool/call→plan/mode→tool/result…turn/end)、动态 section 激活/翻转三态、同批并行 exit 幂等
- [x] codec:todo/write + plan/mode 经 jsonl 往返逐字段 equals;ServiceLoader classpath 模式(META-INF/services)生效
- [x] headless `--verify` 通过(19 插件行);真跑一次:4 条 todo/write 快照落盘(in_progress 单条推进 → 全 completed,持久化 log.jsonl 可查)
- [x] R2 两条架构断言原样绿;文档同步(05 R2 修订+并发契约、03 扩展事件实例、README、本文件)

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| (实施中) | 初版 commitPending(pending/WeakHashMap/步界提交)存在 TOCTOU:读 pending→fold→append→清除跨两把锁无原子性,并发 select 丢更新、并发提交重复落账 | 修锁序(session→pending)后旋即随下一条整体撤销——机制本身语义错位 |
| (实施中) | dsh 步界 pending 提交移植锚点错位:dsh 的 agent/pre-step 是**步级**钩子,JH 的 PRE_STEP 是**轮级**(runTurn admit 一次);照搬则 exit 后余下 step 的提示词仍带 plan:policy,「下一步生效」变「下一轮生效」 | exit_plan_mode 改为**直接落账** plan/mode(false)(todo 同款);pending 机制与 TOCTOU 面一并消除;步级扩展点挂账(/plan 命令、sandbox 批内读者出现时按 dsh 形状补) |
| (实施中) | 测试组合漏 SystemPromptPlugin(plan 的 requires 含它)→ 三测 rollback | 补齐组合;顺带验证批内 requires 校验 fail loud 行为正确 |