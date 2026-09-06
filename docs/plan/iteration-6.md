# 迭代 6 — 真实能力三件套:shell + deepseek + JSONL 持久化(R1 闭环)(已完成)

模块:llm/llm(+onCancel 钩子)、core/agent-loop(增量)、shell 三模块、llm/deepseek、session/persistence + persistence-jsonl、examples/agent-spine(+R1 闭环测试)(设计:05 §5/§Provider、03 §6/§8)
提交:b4d400a(取消钩子 + shell)→ e488e89(deepseek)→ b9f34f7(persistence + jsonl + R1 闭环)→ 收尾提交
四确认日期:2026-09-05;验收日期:2026-09-06

## 四确认(已确认 2026-09-05,含生产级修订)

- **内容**:
  1. **shell 三模块**(it4 fs 范式第二次复制):`shell/shell` seam(ShellExecutor + ShellRequest/Result 含输出截断标记);`shell/bash-local`(进程树击杀 `descendants()`、双流并发排水、输出上限截断、显式 UTF-8、取消监听 + 轮询双保险、超时 fail loud);`shell/tool`(bash 工具,cwd 与 timeout 经插件构造器注入——工作目录是组合身份不是模型自由度,RenderIntent.terminal)
  2. **取消钩子机制**:`llm.AbortSignal` 增加 `default onCancel(Runnable)`;AbortController 实现同步触发(it5 砍掉的 listeners 随首个真实消费者落地;default 方法不破坏函数式接口)
  3. **llm/deepseek**:JDK HttpClient + SSE 流式 → StreamChunk;生产者虚拟线程 + 有界队列(64)背压;**传输韧性**(连接超时、空闲看门狗、429/5xx 有界重试 + 指数退避 + 抖动 + 尊重 Retry-After);**凭据脱敏**(DeepSeekOptions 覆写 toString 遮蔽 apiKey);**采样参数归一**(known keys 类型转换,未知透传);请求构造与 SSE 解析纯函数
  4. **session 持久化**:persistence seam(seam 自有 JsonValue 树——codec 纯函数化,seam 零 Jackson)+ persistence-jsonl(Jackson 映射 JsonValue、13 核心事件 codec + 消息子模型 codec、逐行信封含 ignorable、header.json、SessionStore 的 APPENDED 派发改 notifyOrdered 保序、增量 append + 断点续写 + load 重建 seq 校验)+ **R1 回放哈希测试**(落盘→load→deriveMessages+重组装 prompt/schema→sha256 全比对)+ resume 持久化重载(收 it5 欠账)
- **目标**:命令真执行、模型真调用、日志真落盘;R1 以同代码库重组装口径机器验证成立
- **为什么**:capability 三角色范式第二次复制验证可复制性;deepseek 承受真实厂商约束并偿还 it5 的重试欠账;JSONL 是 R1 的物理载体;shell+deepseek 组合 = 模型对真实机器产生真实副作用,是 R2 pipeline 的意义场景
- **不做**:CompositionManifest(R1 三规则的清单项随 07;本迭代同代码库重组装验证);ConfigService/CredentialsService(构造器注入 + 预留 from(config) 工厂);进程组真隔离 setsid(JDK 无原生 API,dsh 用 native 解决——本迭代为有界直跑:树杀 + 上限,残余逃逸路径记录在案);fs-local 根目录限制(生产阻塞项,挂账提前,sandbox/policy 切片);approval 三模式;bundle/headless;--verify;lock 文件(单进程追加);subagent/压缩/TodoWrite/RequestHeader;模型自选 timeout(schema 仅 required 参数,本迭代 timeout 完全组合期决定)

## 生产缺口挂账(显式,不静默)

| 缺口 | 归属 |
|---|---|
| fs-local 无根目录限制(任意绝对路径可读写) | sandbox/policy 切片,优先级排前 |
| 进程组真隔离(setsid;快速退出进程的脱管孙进程) | sandbox 切片 |
| CompositionManifest(跨版本回放) | 07 组合切片 |
| JSONL 多进程锁/并发 | 持久化后续 |

## 验收(证据 = 实际执行的命令与结果)

- [x] 全 reactor `mvn -B package` 绿(测试数见收尾记录)
- [x] 依赖边:shell 三模块零第三方(shell/seam 还需 llm.llm——AbortSignal 词表,非 transitive 政策自声明);deepseek = llm + kernel 家族 + session + Jackson + jdk.httpserver(测试);persistence seam 零 Jackson;persistence-jsonl + Jackson;onCancel 为 default 方法,既有 lambda 零改动
- [x] 取消钩子:同步触发/已取消后注册立即执行/动作异常不阻断/never() 无操作(AbortControllerTest 5 测试)
- [x] shell:LocalBashExecutorTest 6 测试(echo/exit 3/stderr/超时击杀 <5s/取消击杀含孙进程——后台 sleep pid 实测消失/截断到 1000 字节/env 透传)+ ShellToolEndToEndTest 2 测试(pipeline 成对落账、proof.txt 真落盘、exit 7 → isError)
- [x] deepseek:DeepSeekAdapterTest 8 测试(假服务端:SSE 六形态组装/请求形状断言/temperature 数值化+未知键透传/429 Retry-After 重试计数 2 次/5xx 三次耗尽上抛/401 不重试/看门狗 <3s 掐断/取消 AbortedException/toString 不含明文 key);DeepSeekE2ETest 真实 API(无 key 自跳过)
- [x] JSONL:JsonlPersistenceTest 6 测试(13 事件全历史逐事件 equals 往返 + deriveMessages 相等/header 文件/缺会话 NoSuchElementException/删行跳号拒绝/ignorable 跳过与不可忽略拒绝/无 codec 在 save() fail loud);13 事件 = 核心 10 + 信封
- [x] R1 闭环:R1ReplayHashTest——竖切会话(2 步 tool_use)落盘 → 新 Runtime load 重建 → 重组装提示词 + 注册表 schema → 两条 LlmRequestEvent 双哈希相等、deriveMessages 4 条相等
- [x] 预算:shell 379≤600 ✓;deepseek 653>550、persistence 854>700——超因生产硬化(看门狗/重试/脱敏/多分块解码)与 10 事件 codec 体积,检视无脂肪,预算上修 deepseek 700 / persistence 900
- [x] 文档同步:05(shell 三处修正 + 实现落定段)、03(实现落定段)、04(§9 listeners 落地)、README 路线表、AGENTS 现状/已知坑(HttpClient 行为 + sleep 纳秒坑)

## 真实模型全栈 e2e(头号证据)

- `RealModelAgentE2ETest`(agent-spine,无 key 自跳过):deepseek-chat 真模型收到中文任务 → 发出真 tool_use(bash echo 重定向)→ 经 executor pipeline 真执行 → proof.txt 真落盘 → 模型读回确认 → 终答 turn/end。7.5s 全绿,副作用与审计成对。

## 验收后修正(如有)

| 提交 | 缺陷 | 修正 |
|---|---|---|
| e488e89 | **真实 wire 合并事件**:DeepSeek 结束分块把 content:""+finish_reason+usage 合并在同一事件,解码器先查 usage 提前返回 → Finish 永远缺失,真实 e2e 报「缺 Finish」;本地假服务端用的是 OpenAI 分离形状没暴露 | 解码器改每事件产出 0..3 分块;假服务端测试补合并形状语义;真实 e2e 复跑通过 |
| e488e89 | **Thread.sleep 纳秒坑**:看门狗 `sleep(timeoutNanos/4)` 把纳秒传给毫秒重载 → 单次 sleep ≈20.8 小时,看门狗永不触发(测试恰 5s 失败暴露) | 改 `sleep(Duration)`(dividedBy(4) 下限 50ms);AGENTS 已知坑回填 |
| e488e89 | HttpClient 阻塞读与 close 的行为是看门狗设计前提——未实证前连改三版(轮询 EOF 盲/忙等) | 探针实证:远端关闭 read=-1、本地 close 唤醒抛 IOException;据此定型「阻塞读 + 看门狗 close」形状,实证结论回写 05 |
| b9f34f7 | **R1 组合依赖**:重建侧 Runtime 未挂 fs 工具插件 → 注册表空 → schema 指纹 = 空串哈希 ≠ 锚点。R1 重建要求同一组合——正是 07 CompositionManifest 要形式化的事实 | 测试挂同一组合后全绿;教训记入本表,manifest 随 07 |
| b9f34f7 | append 观察者异常按 it2 契约 contained——无 codec 的 fail loud 无法经 append 路径抛出(测试期望落空) | fail loud 落在 save() 直调路径;03 实现落定记录该分层;扩展插件必须随插件注册 codec |
| 全程 | jdt.ls 与 Maven 抢写 target(复发两处:"Unresolved compilation problems" 假类、陈旧编译错) | 干净失败先删模块 target 再重建;.vscode 已关 autobuild |
