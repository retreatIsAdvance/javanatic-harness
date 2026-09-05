# 迭代 5 — agent-loop 竖切收口(已完成)

模块:core/agent、core/agent-loop、core/system-prompt、examples/agent-spine(设计:04、10 §6、02;05 §4 消费)
提交:ff7ed3d(agent 契约)→ 73d2cbb(system-prompt)→ 62eaef8(agent-loop)→ c8dcd27(agent-spine + R2 架构测试)→ 收尾提交
四确认日期:2026-09-03;验收日期:2026-09-05

## 四确认(已确认 2026-09-03)

- **内容**:`core/agent` 公开契约(Agent 接口、Inbox 双队列、AgentCancelCause sealed、AgentRegistry + ScopedValue initiator、AgentHandle、AgentEvents 五键 + PreStepDecision、AgentOptions);`core/system-prompt` 最小组装(KEY + contributor 注册 + assemble(Session) 只读日志);`core/agent-loop` 驱动(AgentLoopImpl 十依赖构造器、driver 虚拟线程 + 唤醒竞态修复、Turn/Step 状态机、请求指纹落账、executor 唯一调用点、AbortController first-cause-wins、LoopGuard max-turns/max-steps、AgentLoopPlugin);`examples/agent-spine` 可运行竖切(11 插件直装、replay 含 tool_use、终态打印事件序列);R2 架构测试(ArchUnit,锚类定位导入)
- **目标**:竖切闭环——一条输入从 Inbox 进入,经 pre-step 落账、Step 状态机调 replay 模型、tool_use 经唯一 executor 真跑 fs 工具、结果回填、终答、TurnEnd 落账;全程事件日志可投影,示例真实跑通
- **为什么**:路线「最小完整竖切」最后一刀;loop 是 R1 落账侧 / R2 分发点 / R4 构造器强制的载体;it3 的 replay 与 it4 的 executor 至今没有真实消费者,竖切不闭环则 shell/deepseek/persistence 无处可挂
- **不做**:examples/headless(依赖 bundle 组合层,随 06/07 切片);Round 外层策略;AssistantChunkEvent 遥测;RequestHeader/RequestContext/TodoWrite 事件;resume 持久化重载(in-memory get 即 resume);REQUEST_ERROR 完整重试词表(firstOf 钩子 + 有界同 step 重试);LoopGuard budget 档;shell/sandbox/deepseek/approval 三模式/--verify

## 验收(证据 = 实际执行的命令与结果)

- [x] 全 reactor `mvn -B package` 绿(38 项目,**140 测试 0 失败**:kernel 36 + session 33 + systemprompt 4 + llm 17 + replay 5 + tools 13 + fs 6 + agent 13 + agentloop 13 + spine 4)
- [x] 依赖边:core/agent = kernel + brand + session(零第三方);core/agent-loop = agent + tools + system-prompt + llm + kernel 家族 + **llm-replay(test-scope,keyless 测试依赖,it3 先例)**;system-prompt = kernel + session;ArchUnit 1.5.0 仅 examples/agent-spine test-scope(仓库第二个第三方)
- [x] 主代码预算:agent 514 ≤600 / agent-loop 793 ≤800 / system-prompt 112 ≤200(`find <m>/src/main -name "*.java" | xargs wc -l`)
- [x] 集成测试五则(AgentLoopTest 13 测试):preStepReject 无 step 关轮 / cancelDuringTool → Aborted("user")且不伪造 tool/result / inject 在 tool/result 后、下一 step/start 前认领 / concludesTurn...(经直接构造 + stub executor 断言数据驱动停轮,生产 executor 恒 false 无法触发,分支保留待 interaction 切片)/ driver 退出窗口经 turn-stopping steer 复活(2 turns)
- [x] 收敛与恢复:guard 超限 turn/end(Error) 不留开轮;REQUEST_ERROR 有界重试(2 次 llm/request)与默认 Error 收口;resume 续轮号 [1,2];resume 缺会话 NoSuchElementException fail loud;dispose 取消+注销+Aborted("disposed");maintenance 空闲执行/busy 拒绝
- [x] 端到端:agent-spine 真实运行(`java --module-path … -m …/SpineMain`,输出 13 事件:turn/start → user/message → step/start → llm/request(prompt sha) → assistant(text+ToolUseBlock) → tool/call fs_read → tool/result(真文件内容) → step/end → step 1 → … → turn/end);SpineMainTest 断言同一序列 + deriveMessages 含 Tool 结果投影
- [x] R2 架构测试:ONLY_LOOP_DISPATCHES(execute 唯一调用点 ∈ {AgentLoopImpl})+ ONLY_EXECUTOR_BUILDS(ToolResultEvent 构造 ∈ {ToolExecutorImpl})+ 目标存在性健全检查;**红证**:插入 RogueDispatch 第二分发点 → 规则精确报 `RogueDispatch.java:14` → 删除恢复绿
- [x] Inbox 并发不丢失(4×25 双队列翻转);AbortController first-cause-wins;initiator driver 内绑定/外部 null + **虚拟线程不继承**(JDK 25 终版语义,契约测试钉住);AgentHandle dispose 幂等
- [x] 文档同步:04(§2 Supplier、§3 无 id 去重、§9 AbortSignal 视图、§10 终版 JEP 506、§11 Disposer 形状、§15 实现落定)、README 路线表(it5 完成;headless 归 06/07)、AGENTS(现状/布局/已知坑 ×4)

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| 62eaef8 | **dispose 竞态**:AgentHandle 形状是构造期物化的热 future,agent 创建瞬间 teardown 线程即启动,cancel 清 inbox 与首个 turn 赛跑——消息随机消失、turn 偶发丢失(循环 20 次调试测试钉住:APPEND 后无 CLAIM 即被 CLEAR) | dispose 改为 Disposer 接口(延迟触发能力)+ AgentHandle.once 单次组合子;Registry 链尾注销同样 once 包装;04 §11 回写 |
| 62eaef8 | runTurn 的 guard 检查在 reason-try 外,超限会留下开着的 turn/start | guard 移入 try,超限以 turn/end(Error) 收口 |
| 62eaef8 | 模型侧意外 RuntimeException 未收敛,driver 异常完成而 turn 悬开;且异常被吞进无人 join 的 future | 补 catch RuntimeException → turn/end(Error)(turn 隔离)+ driver future whenComplete ERROR 日志(fail loud) |
| c8dcd27 | ArchUnit 1.3.0 的 ASM 不认 Java 25 字节码(major 69),静默丢类(警告走无 provider 的 slf4j 被吞)→ 规则空转假绿 | 升 1.5.0(镜像上限);加目标存在性健全检查防空规则 |
| c8dcd27 | ArchUnit 自动 classpath 发现在 surefire manifest-boot jar 下拿到 0 真实条目;jar 形态依赖的 CodeSource 是 file: 指向 jar 文件,Location.of(Path) 不导入 | 锚类 CodeSource 定位:file 目录走 Path、file .jar 转 jar: URI、其余走 Location.of(URL) |
| 全程 | VS Code jdt.ls 与 Maven 并发写 target/ 交叉污染("Unresolved compilation problems" ECJ 假类、陈旧编译结果) | 干净失败先删可疑模块 target/ 重建;`.vscode` 关 autobuild + 忽略 APILeak;AGENTS 已知坑回填 |
