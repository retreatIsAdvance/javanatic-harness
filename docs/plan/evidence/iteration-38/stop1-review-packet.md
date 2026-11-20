**新增模块自问**：零新模块、零 base 行、零新发布坐标、零依赖/pom/module-info 改动；ServiceLoader 双通道和 moditect 新登记均 N/A。未接 CLI/宿主，不提前宣布其四件套完成。

# it38 Stop 1 Review Packet — 修订取证完成，待评审（未放行）

日期：2026-11-20。基线 `b71c6174c3411c143229786a44b3ce00cfa253f0`，本停代码与证据未提交。终态15类191/0/0/0、生产prompt唯一300s哨兵、14项突变、五轮压力及全量package/validate均已完成；取证完成不等于停点获批。

## TL;DR

- **不变量增量**：注册级 deadline、T3 一次性结算与显错、有界终态墓碑；生产每轮 prompt 取消/关联/恢复；可执行 SSE 完整载荷、seq 续传、有界队列和关闭契约。R2 不增执行通道，B36/B37 不绕过，缺省写面仍关闭。
- **裁决点**：请复评停 1 契约及实现前移范围；R4 单独待裁：允许 `--verify --serve [--web-write]` 纯预检，零监听/轮/工具；`--web-write` 无 serve 仍 exit 2。未获裁前不改解析器。
- **风险自报**：WebChatControl、持久化 follower、Agent/AppBoot 生命周期桥及 CLI/UI 未接；请求/连接总数与编码前对象大小不由本停队列帽约束；summary 不是脱敏结果；本机证据不外推 CI 或浏览器验收。
- **证据入口**：[设计](stop1-write-seam-design.md)、[分轮红绿](stop1-tdd-evidence.txt)、[独立审查](stop1-revision-reviews.txt)、[14项突变](stop1-revision-mutation-review.txt)、[终态191项/300s](stop1-revision-final-focus-result.txt)、[全量197 XML](stop1-revision-full-xml-counts.txt)、[最终审计](stop1-revision-audit.txt)；完整命令、原始输出及限制见 §6。
- **若只能改一处**：保持“登记时固定截止 + settle/await/poll 共用截止”的单源裁决；任何宿主桥都不得把超时责任重新交给等待线程是否准时醒来。

## 1. Scope and stop boundary

本停交付可独立执行的接缝，而非已经上线的聊天工作台。接口进 rpc、实现宿主进 headless；rpc 测试不依赖 headless/Agent/AppBoot。headless 新增生产 `RelayApprovalPrompt` 与测试集成，未改变启动路径。

四确认原把大部分中继/SSE 实现安排停 2；为满足本停“可运行契约 + 严格 deadline + 真时钟 + 完整载荷”评审要求，状态机、每轮 prompt 和可注入传输前移。没有因此推进 WebChatControl/真实 follower/CLI/UI，不把前移视作停 2 放行。此范围前移列为本包显式裁决点。

旧默认 `RpcHttpServer(Handler)` 不注入 SSE source，合法 `/events` 仍 404；只有显式注入 `(Handler,SseEventSource,SessionCodecRegistry)` 才开放该流。当前 CLI 仍只读，`--web-write` 尚未解析。零新配置键、零新持久化事件。

## 2. Contract-bearing code

### Chat control

```java
public interface ChatControl {
    String start(String text);
    void send(String session, String text);
    void cancel(String session);
    void respondApproval(String requestId, boolean allow);
}
```

[ChatControl](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/ChatControl.java) 是 JSON-RPC 边界的四动词契约。[WorkbenchRpc](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/WorkbenchRpc.java) 先 requireChat 再校验参数，未注入时有效/非法参数均 -32601；start/send 只发起轮，不将异步轮结果折成 RPC 错误。在途 send -32006，不排队/steer；cancel 对无在途轮幂等，未知 session -32001。统一响应预算仍适用，但不是副作用回滚承诺。

### Approval relay

[ApprovalRelay 全文](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/ApprovalRelay.java)：`open(Duration)` → 私有构造 Request；`settle(id, Decision)` / `await(Request)` / `poll(Request)` 共享登记截止；`expire(id)`、`cancel(id)`、`close()` 不覆盖首个终态。包内时钟缝用于确定性测试，生产为 `System.nanoTime`。

承载片段：

```java
private void expireIfDue(Entry entry) {
    if (entry.done == null && entry.bounded && clock.getAsLong() - entry.deadline >= 0) {
        finish(entry, Outcome.EXPIRED);
    }
}
```

settle 在同一 owner 锁内先执行该检查，再决定 ALLOW/REJECT；await/poll 同样检查，重复等待不重新计时。正 Duration 从 open 起算，0/负不设限，toNanos 溢出 fail loud。期前结算不因随后截止改变；nanoTime 符号回绕按差值判断。

- 活跃表只保存未决 Entry；首终态同锁移除并留下 outcome/retiredAt 墓碑。future 总在锁外 complete，观察回调不持状态锁。
- 墓碑 FIFO 最多 4096、TTL 600 秒，访问/迁移时惰性清理；容量或 TTL 淘汰后旧 id -32007，旧 Request 仍观察原终态，不复活墓碑。不是无限重对象留存，也不是准时物理销毁承诺。
- ALLOWED/REJECTED 后重放 -32008；EXPIRED/CANCELLED 后重放 -32009；未知 -32007。结算前双 respond 至多一个赢家；截止后双 respond 双 -32009。
- Request 不暴露完成权，minimalCompletionStage 的副本被修改也不能裁决；外来 relay 的 await/poll 拒绝。close 封闭 open、取消 pending、清空表，旧句柄仍可观察取消。

### Production prompt and recovery

[RelayApprovalPrompt 全文](../../../../examples/headless/src/main/java/io/javanatic/harness/examples/headless/RelayApprovalPrompt.java)：每 session/turn 一个不可复用租约，显式绑定 `Id<Session>`/turn，不依赖普通工具虚线程继承 ScopedValue，不扩 core ApprovalRequest。

`ApprovalPrompt.effectiveIdleTimeout` 为唯一 Duration 来源；ask 前/等待中/翻译异常与返回前检查取消，onCancel 快速路径加 50ms checkAbort/poll 兜底。ALLOW=true，REJECT/EXPIRED=false，取消/close 抛 AbortedException，不能伪装成用户拒绝。finally 撤销未决、移除元数据、清空取消监听引用盒。

subscribe 与初始完整快照同锁，revision/pending 同锁变更。每订阅容量 1，合并为最新完整 Snapshot，客户端完整替换 pending 列表；不是丢增量。close 发最后空快照并唤醒。revision 不等于日志 seq，不得放入 SSE id。Pending 显式关联 requestId/session/turn/toolName/summary；summary 可能包含工具参数，不宣称脱敏。

### Executable SSE

承载全文：[SseEventSource](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/SseEventSource.java)、[SseFrame](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/SseFrame.java)、[SseRequest](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/SseRequest.java)、[SseEncoder](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/SseEncoder.java)、[SseBuffer](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/SseBuffer.java)、[SseConnection](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/SseConnection.java)、[RpcHttpServer](../../../../workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/RpcHttpServer.java)。

- source.open 接收 `Id<Session>, afterSeq`；Opened.head 是打开时稳定日志头，空 -1；next 空表示永久 EOF，close 幂等唤醒 next。source 承担快照/增量原子接续，真实 SessionTail follower 的证明留停 2。
- 只有 GET `/events` 开流；HEAD 405/Allow GET、POST 404。B36 公共 Host/Origin 围栏先于 source.open，恶请求 open=0；B37 仍只要求 POST `/` 为 application/json，GET 不新增 JSON 请求体要求。
- Last-Event-ID 优先 query lastSeq；缺省 -1，排他续传包含 seq0。非法/<-1/超 head 400，未知会话404、open损坏500，均在200前具名失败；不钳位。200后 source/codec 失败记录日志并断流。
- Persisted 的 id=LoggedEvent.seq，data 保留完整 seq/type/ignorable/data，实际 SessionEventCodec 编码，不借 timeline 投影丢 payload。Control 与15秒 keepalive 注释无 id；心跳绝对截止不被业务帧推迟。传输拒绝 source 重复/倒退 seq。
- 每连接 reader/writer + 64帧/8MiB编码字节双帽；超任一帽即断流，不能丢帧继续。EOF 排空后关闭；overflow 清本连接队列，重连从客户端最后收到 id 继续。
- server.close 先 stop(0) 终止底层 socket，再清连接，避免欠缺 GET body 的排水阻塞。正常 EOF 自有 writer 仅在未中断时临时 interrupt 关闭 requestBody，再清人为位，保留尾 chunk；既有中断不清。source 回调锁外执行；late-open 由关闭登记锁封闭。

## 3. Design → implementation → test

| 设计增量 | 实现 | 测试及拒绝侧 |
|---|---|---|
| §1 launch / 缺省关 | ChatControl、WorkbenchRpc、RpcJson | WorkbenchRpcWriteTest：四方法正例、非法参数、关闭先于校验；rpc 独立构建 |
| §2 deadline / T3 / 回收 / 每轮恢复 | ApprovalRelay、RelayApprovalPrompt | ApprovalRelayTest：登记边界、期前/期后、双 respond/重放、4096/4097、600s、只读句柄；RelayApprovalPromptTest：取消双机制/关联/订阅快照；WebApprovalRelayContractTest：真实 ToolExecutor 300s/1s |
| §3–4 围栏 / 完整流 / 游标 / 背压 | RpcHttpServer、SseEventSource、SseEncoder、SseRequest、SseConnection、SseBuffer | RpcHttpServerTest/SseTest + Encoder/Request/Buffer/ConnectionTest；WebSseCodecContractTest 四真实 assistant/chunk 变体 HTTP 往返及 header续传；边界突变、raw Socket 关闭红绿 |

§5 的 CLI/R4 不在上表冒充实现。T5 IME/composer、exit5 答复、T6 opt-in 生产开启与 T7 jobs 延后均保留原裁决，未重新决策。

为什么是这个形状：rpc 只接受构造注入的接缝，因而可以脱离 headless 验证协议与围栏；单个短 owner 锁把截止检查和终态迁移放在同一裁决点，锁外通知避免回调交叉死锁；prompt 用每轮租约显式绑定取消与会话，容量1完整快照避免增量丢失；SSE 用独立 reader/writer 和编码后双预算隔离慢消费者。若形状有误，受控时钟边界/并发首答、回调屏障、真实 Socket 欠缺请求体、完整 codec 往返与定向突变分别观察其失败，唯一真钟哨兵只负责证明现实300秒等待，不替代这些确定性断言。

## 4. Review findings and disposition

[独立审查归档](stop1-revision-reviews.txt)记录原审查者与范围，不把归档代理当成又一轮审查，也不把静态意见当运行结果。

| 发现 | 处置 | 证据 |
|---|---|---|
| 注册 deadline 未钉死，迟到 ALLOW 可赢 | open 固定 deadline；三个入口共用判断 | 分轮证据 §11.1；deadline-boundary/early/settle 定向突变 |
| 审批初审 P3：1ns 竞赛退化成纯到期 | 9/10/11ns 各200轮，waiter/observer/respond 一致 | ApprovalRelayTest 22项；强化复验41/0/0/1（当轮慢测跳过） |
| 审批初审 P3：容量缩到1仍可能绿 | 第4096条最旧仍-32008，第4097条才-32007 | retention-capacity 突变1/1/0/0→1/0/0/0 |
| SSE初审 P1：欠缺GET body导致关闭排水死锁 | stop-first + EOF writer请求体关闭 | [raw双红](stop1-sse-close-raw-red-01-result.txt)53/2/0/0 → [stop-first](stop1-sse-close-stop-first-01-result.txt)53/1/0/0 → [EOF绿](stop1-sse-close-eof-green-01-result.txt)53/0/0/0 → [追加断言后绿](stop1-sse-close-final-green-01-result.txt)54/0/0/0 |
| SSE初审 P2：latch不证明waiter已阻塞 | 改用TIMED_WAITING有界屏障 | buffer-wakeup 删除notifyAll必红、还原绿 |
| SSE关闭复审非阻断建议：补原始interrupt=true | true/false共用helper，检查回调及返回中断位 | interrupt-preserve 定向1/1/0/0→1/0/0/0；终态SseConnectionTest为8项 |

审批初审未确认 P0–P2；SSE 初审两项已修；SSE 关闭复审在其聚焦范围未确认 P1/P2。这不是“全部边界无风险”的保证。自报覆盖边界：重复/倒退 seq、Long.MAX_VALUE、Control 穿插尚未独立加例，不伪称逐格覆盖。另一次文档/API 一致性独立只读审查未发现可确认问题，其范围与限制同见归档；不以静态意见代替运行结果。

## 5. R4 decision request — no CLI change yet

现有 HeadlessMain 仍拒绝 serve+verify，web-write 尚未解析。建议只给 verify 放宽下面两个组合，verify 优先、纯预检；保留其他互斥（任务文本/resume/sessions/session/workbench/dump）。

| 参数组合 | 拟议行为 | web-write证明 | 监听/轮/工具 |
|---|---|---|---|
| --verify | 既有预检 | off | 均无 |
| --serve | 只读server | banner off | 仅监听 |
| --serve --web-write | 写面server | banner on | 请求来才开轮 |
| --verify --serve | 新增纯预检 | verify off | 均无 |
| --verify --serve --web-write | 新增纯预检 | verify on | 均无 |
| --web-write / --verify --web-write | 缺serve，用法exit2 | 不装配 | 均无 |
| --verify --serve --port=N | 只校验N数字范围，不试占用/绑定 | off | 均无 |

预检不要求模型 key，不代表模型连通性；policy失败 exit1，用法失败 exit2。替代案是保留互斥、verify只证明自身off，on只归banner，但弱于完整R4表达。**本包提出裁决，不自行放宽。**

## 6. Verification ledger

### Final source and focused tests

终态源码先冻结，再运行；[SHA清单](stop1-revision-source-sha256.txt)覆盖36个Java文件，[全文快照](stop1-revision-source-snapshot.txt)保留基线及源码。冻结后没有修改Java源码/测试；最终字节复核随全量之后执行。

[15类终态结果](stop1-revision-final-focus-result.txt)及[原始日志](stop1-revision-final-focus.log)：2026-11-20T17:07:28.354453 至 17:13:08.626456，Maven EXIT=0，**191/0/0/0，MISSING=[]**。运行 `stop1-check.py revision-final-focus examples/headless`，准确类名和完整命令在结果档；显式启用 `-Dit38.realClock=true test`，该轮包含且仅包含一个300s慢哨兵。

```text
RELAY_REAL_CLOCK configuredSeconds=-1 elapsed=PT5M0.013325667S toolExecutions=0 lateAllow=-32009
RELAY_REAL_CLOCK configuredSeconds=1 elapsed=PT1.012295708S toolExecutions=0 lateAllow=-32009
```

当前生产 RelayApprovalPrompt + ApprovalAskPlugin + ToolExecutor 的非交互默认300秒与显式1秒覆盖同轮验证：超时结果为拒绝、prompt/relay未决清空、迟到ALLOW拒绝后工具执行仍0。没有模型/API key，不是生产serve/Agent宿主接线。15类计数逐类见结果档，不能将后续全量跳过慢测的XML拼入这一轮。

### Mutation and pressure

[14项独立突变核验](stop1-revision-mutation-review.txt)：每项预期行为红→逐字恢复→复绿，全部28份result无缺失XML；绿侧均EXIT=0且F/E/S=0。所涉6个生产目标恢复SHA一致。deadline-early的旧PASS=False及b37的预期连带error保留解释，不把日志诊断冒充测试失败。

### Full build

[全量命令与EXIT](stop1-revision-full-result.txt)、[原始日志](stop1-revision-full.log)、[同窗XML清单](stop1-revision-full-xml-counts.txt)：`stop1-check.py revision-full - - package` 后执行 `stop1-audit.py counts revision-full`；Maven实际命令为 `/Users/abel_yang/Documents/apache-maven-3.10.0/bin/mvn -B -q package`，JAVA_HOME取JDK25。窗口2026-11-20T17:13:48.042379至17:18:21.997513，EXIT=0；197份同窗XML合计 **1614/0/0/25**，排除2份旧XML。该XML测量面为1589通过、25跳过，不是1614全通过。

全量未启用realClock，WebApprovalRelayContractTest为2/0/0/1；本轮仅跑1秒覆盖，不第二次等待300秒。记录器在`TESTS=-`时不选择单类，result中的FRESH_TOTAL=0不是全量计数，须配套读取xml-counts。JUnit/jqwik同名XML覆盖仍是本测量面限制。构建完成不等于镜像真跑或生产serve验收。

### Final validation and audit

[validate结果](stop1-revision-validate-result.txt)及[原始日志](stop1-revision-validate.log)：`stop1-check.py revision-validate - - validate`，2026-11-20T17:18:53.510347至17:18:58.754644，Maven EXIT=0。validate只验门禁，不将零测试行当测试通过。

[最终审计](stop1-revision-audit.txt)：`stop1-audit.py audit`，36个冻结Java逐项SHA相等、四文档本地链接均存在、diff/换行检查通过，FAILURES=[]、EXIT=0；[94份原始日志SHA](stop1-revision-log-sha256.txt)另存。终稿文档链接更新后再运行同一审计，报告TIME为最后核验时刻。另有[记录完整性核验](stop1-revision-record-integrity.txt)：终态三份result的日志SHA/同窗计数一致，重读全量197份XML，mtime及计数均与归档相同。终检rpc/src突变标记零残留，pom/module-info/bundle/HeadlessMain差异为空。

仍有Spotless Unsafe弃用警告、HEAD长度警告与负例触发的SSE missing codec/null source诊断；这些不等于测试failure/error，不声称零警告。

### CPU pressure

已完成的本轮压力：[脚本](stop1-pressure.py)与[报告](stop1-revision-pressure.txt)。10个自有CPU忙循环进程、5轮顺序执行 ApprovalRelayTest/RelayApprovalPromptTest/SseConnectionTest/SseBufferTest；每轮 EXIT=0、51/0/0/0、MISSING=[]。255次测试实例是51个独立用例重复5次，不是255个不同用例。每个压力进程43秒左右CPU时间，全部自有PID已wait回收，ALL_OWNED_REAPED=True。

证据工具：[逐轮记录器](stop1-check.py)、[突变脚本](stop1-mutations.py)、[终态审计](stop1-audit.py)。每个result含cwd、完整Maven命令、START/END、EXIT、准确测试名和同窗XML、日志SHA。XML不是全部引擎调用数；不混轮、不以文件名green判绿。

## 7. Deviations and limits

- 首批不是完整TDD；未留存的初始RED不补造。修订红绿逐轮归档，编译/门禁红与行为红分开，35轮历史汇总见证据档§11。codec测试初次GREEN不倒写成RED；后续载荷突变另记。
- deadline-early 突变被捕获为 RpcException（Surefire error），旧分类器只认 failure 导致 audit PASS=False；保留原记录并附[裁定](stop1-mut-deadline-early-adjudication.txt)，不修改测试期望或伪造failure。
- 旧300.046872625秒属于旧测试lambda，旧慢测wrapper还曾错填UI类名且Maven退出码未留档；旧122/0/0/1、8文件快照和validate保持历史，不能拼成当前终态证明。
- 宿主仍须约束活跃审批数、并行轮和连接数；64帧/8MiB不是全局资源预算，也不限制编码前对象。墓碑TTL惰性清理、不是准时擦除。TCP背压时不承诺15秒实时送达心跳。
- 当前恢复快照只在生产prompt实例内线性化；生产host须按session选择租约并在轮finally关闭，不能跨session广播。真实持久化 follower 尚未实现，内存source不能证明其无缝衔接。
- 本机macOS/JDK25证据；未跑CI四平台、真实serve聊天、浏览器IME/审批/断线重连或用户亲跑。后续生产桥必须重新验完整执行/取消/审计链。
- `.gitattributes` 仅对 stop1 原始日志增字节保真例外，不放宽生产源码/测试格式门禁。不改权限、依赖、模块或CLI互斥。

## 8. Stop

停 1 验收仍未勾选。未 commit、未 push、未进入停 2；本地既有待推仅 `0e4f958`、`b71c617` 两笔（由 `git log --oneline origin/master..HEAD` 核对，未查询远端）。旧push授权不可复用。四确认通过不等于停 1 放行。本包已收齐修订证据，现正式提交评审：请裁定停 1 契约及实现前移范围，并单独裁定 §5 R4 拟案；在收到新鲜点名放行前保持停下，不替用户作裁决。
