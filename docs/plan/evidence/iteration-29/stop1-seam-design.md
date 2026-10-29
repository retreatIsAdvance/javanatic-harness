# LSP Observation Seam Design

状态：停 1 待审提案；开工依据为 [停 0 终裁](stop0-acceptance.txt)。本文件不是 T4–T7 全项批准，也不是 Provider 运行验收。

## Scope and Alternatives

仅交付 `lsp/lsp` Definition、纯值校验、聚焦测试与设计同步。无进程、协议解析、工具、事件 codec、配置键或组合行。原停 1 中 Provider / 生命周期运行面仍未完成，须另获实现授权并完成本文件矩阵才可进入消费面闭环；不将它们改名为已验收。

比较：直接暴露 JSON-RPC / didOpen / 通知回调会把状态机泄漏给每个调用者；照搬 MCP 注册表会先建多 server 平台；采用单项目 `LspService` 三个同步方法，把通知缓存和进程细节留给 Provider。keyless 替身可以实现同一接口，但本批不造一个假 Provider 冒充行为验证。

## Interface

`LspService` 的 `diagnostics(LspQuery, AbortSignal)`、`definition(LspPositionQuery, AbortSignal)`、`references(LspPositionQuery, AbortSignal)` 返回 `LspResult<LspDiagnostic>` 或 `LspResult<LspLocation>`。引用包含声明。调用阻塞、可运行于虚拟线程；取消原样抛 `AbortedException`，不包装为超时。`close()` 由拥有者调用，必须幂等、有界，失败抛 `LspException`，消费者不得关闭借用接口。初始化由 Provider 装配完成，不暴露启动命令或裸 frame。

- `LspQuery(URI document, Duration timeout, Duration observationWindow)`：正时长，window ≤ timeout；timeout 从方法进入计，包含初始化等待、同步、窗口、读取、收尾采样，不得每步重置。URI 仅做绝对层级 URI 语法检查；realpath、权限、scheme 白名单在 Provider/T6 落实，值类型不是权限闸。
- `LspPosition(line, character)`：零基、UTF-16 code units，均非负。`LspRange(start, end)` 左闭右开，不倒置；`LspLocation(uri, range)` 不冒充权限许可。跨文件结果各自带 URI；依赖源码 / LocationLink 映射属于 T6 未交付项。
- `LspPositionQuery(LspQuery query, LspPosition position)`：复用 query 的总 timeout / window，不另开计时预算。
- `LspDocument(uri, Optional<String> sha256)`：hash 为观察到的源码字节 SHA-256，小写 64 hex；Optional.empty 只表示采样确认文件不存在，不表示读取失败。窗口内删除可返回 Stale；起点文件不存在 / 无法读取走 IO，不开始成功观察。`LspAnchor(projectRoot, incarnation, generation, projectHash, document)`：projectRoot 是绝对层级 URI；incarnation 为每次 Provider 启动的新 UUID；generation 非负，观测输入变更 / 崩溃失效时递增、不回退，重启不可复用 incarnation。projectHash 是明确输入清单的摘要，不是 LS 索引或全项目正确性证明。
- `LspObservation(before, after, requiredWait, elapsedWait, capturedAt, evidence)`：before/after 必须完全相等且文档存在；正 requiredWait，elapsedWait ≥ requiredWait。capturedAt 为最终采样墙钟，仅审计使用；等待用单调时钟。`LspEvidence(origin, receiveSequence, serverVersion)` 保留批次来源（QUIET_WINDOW / PUBLISHED_DIAGNOSTICS / REQUEST_RESPONSE）、本地已解析消息截点序号（非负）与可选 LS version；仅 PUBLISHED_DIAGNOSTICS 可带 version，明确空数组亦能保留它。QUIET_WINDOW 不允许非空 items。构造校验只能验证提供的值，不能证明 Provider 真的等过或读过文件。
- `LspResult<T>` sealed：`Observed(observation, items)`，不可变、无 null；`NotReady(reason)`（INITIALIZING / SYNCHRONIZING / CLOSED）；`Stale(before, after)`（同一查询目标，至少一个锚字段变化）；`TimedOut(timeout)`（正时长）。空集合仅在 Observed 内合法，不新增“项目无错误”状态。
- `LspDiagnostic(range, severity, code, source, message)`：可选 code/source；severity 含 UNSPECIFIED，不能把缺失严重级别伪造成 ERROR。serverVersion 在批次 LspEvidence 中以 OptionalInt 按 LSP signed integer 原样参考（不当观察锚），不挂单条诊断以免空数组丢信息。诊断范围归属于查询文档。
- `LspException` 携 `LspErrorCode`：CONNECTION / PROTOCOL / UNSUPPORTED / IO / CLOSE_FAILED。非法调用值抛 IllegalArgumentException/null 抛 NullPointerException；服务退出、损坏帧不是空列表。

## Observation Algorithm Required of a Provider

1. 检查取消、已关闭、能力与文档访问范围；未完成初始化 / 同步返回 NotReady 或在总 deadline 内等待，不返回 Observed。
2. 在同一项目串行观察区采样 JH 管理的输入清单，计算 before；将文档全文与变更同步给 LS。输入清单至少含项目 Java 源文件与 Maven/JPMS 配置；具体根、排除项、符号链接、依赖变化检测在 T6/Provider packet 列出并接受审查，未定义清单不得产出 projectHash。本批不声称已实现文件扫描。
3. “同步完成”仅指客户端将本次全文/变更帧完整写出（含本地flush），不是服务端应用确认；此后开始显式窗口。只采本 incarnation / 本观察缓存代的候选；换代丢弃旧缓存。push 候选每 URI 末值胜，缺 diagnostics 数组为协议错误，不是空数组；version 不作门。
4. 窗口后与 reader 的候选发布锁互斥，截取当前已完整解析消息的本地递增序号并原子复制候选，再采样 after；截止该序号的诊断必须纳入，后来的消息留给后次观察，不无界追赶。未完整解析的wire字节不算已接收候选。reader或队列溢出必须显式PROTOCOL失败，不丢消息后声称空结果（容量在T5钉定）。这只是客户端缓存提取成功，**不是协议 pull / LS 已完成分析证明**；不能用“无 error”代替“无 diagnostic”，警告亦非空。
5. 前后项目输入/代/进程 incarnation 不同 → Stale；deadline 到期且还没形成合格快照 → TimedOut；传输/协议失败 → typed exception；取消优先原样传播。在 deadline 之前形成的合格快照才可 Observed。
6. 诊断 Observed 空仅表述“拉取时点无诊断”；定义/引用必须另有真实请求成功响应才能 Observed（不能从通知沉默合成空定位）。三者都在返回前稳定复验。落账属于后续 Consumer，后台不得直接改模型上下文。

残余：无 version 的迟到 LS 分析仍可能到达新窗口；前后相同 hash 不能发现中间改动后恢复（ABA）；未纳入清单的依赖 / 宿主环境 / LS 索引变化不被证明。已接受观察语义不隐藏这些限制。等待窗只提供可审计观察机会，不是索引完成屏障。

## Lifecycle and Ownership Requirements

单项目 Provider 的拥有 scope 持有进程与索引目录独占锁；同目录冲突 fail loud，不共享未审缓存。初始化失败回滚自有资源；未授权自动重启（本提案关闭自动重启）。取消一个请求只结束该请求及其 pending，不默认关闭整个项目；关闭拒新请求、终结全部 pending，并使旧缓存不可再采纳。

正常 shutdown 响应后发 exit，继续协议读/应答到关闭传输；不能立即退化为无人应答的 raw drain。有界宽限后 TERM、再宽限后 KILL，回收已拥有子进程后复验专属组；EPERM 未证消失。不得按名称全机杀。脱组后代、Windows Job/进程树实现与平台限制须在 Provider packet 明示；macOS 探针不证明四平台运行。关闭失败须上报 CLOSE_FAILED，不伪称清理成功；停止不回滚 LS 文件写入。

## Observed JDT LS Deviations and Acceptance Matrix

| 观察项 | 原证据 | 产品客户端策略 | 必须验证，当前均未执行 |
|---|---|---|---|
| publishDiagnostics 无 version | protocol seq379/400 | 接受、记录缺字段；观察锚由 JH 计算 | 缺 version、迟到与换代测试，不补盖来源证明 |
| didChange 后 warning-only，未见 [] | seq400 / 90秒等待 | 不修夹具、不将警告过滤成零；无[]并非必然协议违规 | 完整窗口空缓存/明确[]/警告非空/过期/超时分别断言 |
| shutdown 返回 {} 而非 null | seq411 | 接受此已观察形状并记录偏离；其他损坏结果分档 | null 与 {} 两分支、错误响应不可当成功 |
| initialized 甚至 initialize 响应前 progress 请求 | 原 journal 前段 | reader 自启动即服务双向请求；记录顺序偏离 | 初始化在途响应 progress/create，未知请求 -32601 |
| journal18个请求已答，尾流id19未答 | [尾流分析](stop0-run-01/protocol-tail-analysis.txt) | 产品仍解析并应答退出阶段请求；窗口内完成所有已解析请求后关闭，超时记录未完成并失败 | shutdown 前后请求配对、退出洪流、有界关闭；不得称探针全答 |
| 工具清单通知类比 | 用户纪律类比，不是JDT观测方法名 | 仅候选缓存，不未经落账改模型上下文 | T4 R1/R2验证，不编造JDT发过MCP方法 |
| 重复正常退出 | 停0只有1次 | 不由单次外推 | 同钉定build ≥3次启停，每次exit/EOF/pending/线程/专属组证据 |
| TERM被忽略 | 本批无真实证据 | TERM→KILL、wait后复验组，EPERM不当消失 | 可控子进程夹具 + 自有组检查；真实Provider关闭路径调用同一实现 |
| 主动探测、项目写入、下载 | [有效设置](stop0-run-01/effective-settings.txt) / 目录快照 | 可信全权披露，不假装隔离 | T2配置/索引冲突/失败回滚；不读取用户凭据作探测 |

## Delivery Boundary

本批纯值测试证明拒绝坏参数、结构性区分结果、观察锚相等与窗口校验、不可变集合；不证明进程/时钟/文件扫描/队列/模型落账行为。T4 hooks/事件、T5传输依赖与预算、T6路径/输入清单/定位映射、T7装配仍须审查。停 1 packet 出包即停，无 commit/push/发布。
