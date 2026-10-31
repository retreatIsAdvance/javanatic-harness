# 停 2 Provider 设计 — lsp/jdtls 真实生命周期

TL;DR（五行）
- 不变量增量：`lsp/jdtls` 开发真实 JDT LS Provider（`JdtlsProvider implements LspService`），自有 LSP wire 边界（Content-Length 字节帧 + JSON-RPC 2.0），零新第三方依赖（仅 jackson-databind，reactor 既有）；无 Plugin / ServiceLoader / 配置键 / 事件 / 装配行；实现尚未验收。
- 裁决点：T5 已裁自有边界（[裁决](stop2-t5-ruling.txt)）；三条边界按本设计实施和验收——JsonRpcPeer 每 transport 私有复制改写、Content-Length 按字节且 charset 恒 UTF-8、停 0 偏离升格客户端验收矩阵。本设计不替代停 1 已裁的窗口后原子截点与调用总期限。
- 风险自报：macOS 无 setsid 二进制，专属进程组靠系统 perl 包装器（平台约束披露）；四态真机区分尚待复现，不宣称「项目无错误」；Peer 背压与发布边界已有聚焦证据，Provider 接线仍在审查修复中，不能外推产品验收通过。
- 证据入口：`stop0-run-01/`（412 帧 / 18+1 请求 / 尾流）、`stop1-seam-design.md`（已裁 Provider 验收矩阵）、`stop2-review-backpressure-final.txt`、`stop2-review-publication-red-01.txt` / `stop2-review-publication-green-01.txt`；本停点真实 JDT LS 证据跑尚未完成。
- 若只能改一处：把「退出阶段继续解析并应答全部 server 请求，直到流关闭或宽限到期；到期未答记 CLOSE_FAILED」这条做成硬路径，且管道背压不得阻塞取消与 TERM→KILL 回收。

---

## 1. 范围与依据

- 停 1 已通过并关闭（[裁决](stop1-acceptance.txt)）：Provider 生命周期验收矩阵是停 2 硬验收，不以 seam 契约单测或停 0 单次退出替代。
- T5 已裁自有边界实现（[裁决](stop2-t5-ruling.txt)），三条实现边界见 §3、§4、§5。
- 停 0 证据事实面：JDT LS `1.63.0 / 202610191322` 裸进程闭环成立；412 帧、18 个 `window/workDoneProgress/create` 请求已答、退出尾流 id19 未答；诊断无 version；progress 早于 initialized；shutdown 响应 `{}`。
- 本停点只实现 Provider（含其聚焦测试与真实证据跑），不进 Consumer / 装配（停 3 / T7 另行点名）。回放零联网零进程义务不变：Provider 不注册任何回放路径，回放走既有 `tool/result` 账。

## 2. 模块布局

- 新模块 `lsp/jdtls`，artifactId `harness-lsp-jdtls`，JPMS `io.javanatic.harness.lsp.jdtls` exports / opens 同名包（opens 仅供同包测试反射），根聚合 `harness-lsp-aggregator` 增一个叶子。
- 依赖仅：`harness-lsp-lsp`（`LspService` 与观察值类型）+ `harness-kernel-core` + `harness-llm-llm`（`AbortSignal`）+ `jackson-databind`（parent 已管理版本；mcp/stdio 同款，非新第三方依赖）。协议解析只在边界层用 Jackson，结果映射为 `lsp/lsp` 纯值类型。
- 类布局（除 `JdtlsProvider` 与 `JdtlsLaunch` 外全部 package-private）：
  - `JdtlsLaunch`：启动档案（javaHome、serverDir、configurationDir、projectRoot、indexDir、homeDir、mavenUserSettings / mavenGlobalSettings 可选）；构造校验路径存在且 `javaHome/bin/java` 可执行；不校验「LS 是否可用」。
  - `JdtlsProvider`：实现 `LspService`；持有 peer、候选缓存、观察串行区；`close()` 走 §4 生命周期。
  - `LspPeer`：单进程会话（改编 mcp/stdio `JsonRpcPeer` 先例，见 §3）。
  - `LspFrameReader`：Content-Length 字节帧读取（改编 `BoundedLineReader` 先例）。
  - `DiagnosticsCache`：按文档 URI 的候选批次缓存，携带 `receiveSequence` 截点。
  - `ProjectHash`：项目输入清单 hash（§5）。
- 三角色纪律：`LspService.KEY` 仍是唯一门面；Provider 不导出协议类型；Consumer（停 3）只经 `LspService` 消费，看不见 wire。

## 3. Wire 边界（T5 边界 1、2 落地）

### 3.1 JsonRpcPeer 每 transport 私有（边界 1）

- `LspPeer` 在 `lsp/jdtls` 内复制 mcp/stdio `JsonRpcPeer` 的会话骨架（pending 表、读线程、stderr drain、取消记忆、断裂级联）并按 LSP 改写；输出为 64 项有界队列 + 唯一 writer，阻塞管道写不持状态锁或发布锁。**不建跨协议共享模块**。设计注记（裁决原文要求）：「若第三个消费者出现可重访抽取」。
- 错误分档：畸形帧 / 超限帧 / 非法通知载荷 = `PROTOCOL`，流不可再信，级联全部 pending；I/O 读失败 / 残帧 EOF / 进程死亡 = `CONNECTION`。保留首次断裂异常，后续请求、捕获和健康检查不得改码或降级为空。匹配 id 但 error 成员 → 该调用协议失败（流仍可用）；错 id / 无 id 响应 → 记日志丢弃，调用按自身 deadline 收口。
- **LSP 特有改写（不得照抄）**：
  - 分帧：`Content-Length: N\r\n\r\n` + N 字节体（§3.2），不是按行。
  - 响应解包：LSP 语义中 `result: null` 是合法成功响应（“无结果”），不能像 mcp `unwrapResult` 那样当协议失败——`null` result 映射为空列表 `Observed`；只有 `error` 成员或既无 result 也无 error 才是协议失败。
  - server→client 请求必须应答（§3.3）；mcp 的 `notifications/cancelled` 等无对应物，不搬运。

### 3.2 Content-Length 字节帧（边界 2）

- `Content-Length` **按字节数**计，charset **恒 UTF-8**：header 行用 ASCII 读，体按 UTF-8 解码；多字节字符字节数与体长必须吻合，不符即流级断裂。
- header 语法：`Content-Length: <digits>\r\n` 后接空行 `\r\n`；非数字、缺失、负值、头行总数超小上限（如 32）均 = **流级断裂**（沿 mcp/stdio 分层先例：残帧后流不可再信，不挽救、不回写、不重试单帧）。
- 单帧字节帽沿用 8 MiB（`MAX_FRAME_BYTES`，与 mcp/stdio 同档）：超限 = 流级断裂。传输层安全阀与消费面大小上限分层。
- 写出由唯一 writer 串行写 Content-Length 字节帧；`ObjectMapper` 输出 UTF-8 字节后计长，绝不按字符数。request 的总期限覆盖入队、flush 和响应；notify 成功返回必须已 flush，不以入队成功冒充同步完成。
- 取消或期限到达时，未开始写的帧撤出队列；已开始的帧继续完整写出以免破坏分帧，但调用者有界返回，不杀项目进程。队列溢出为 `PROTOCOL` 并级联失败；取消通知同队列，不为每次取消无限生成线程。

### 3.3 server 请求应答（边界 2 硬要求）

- 停 0 实测：18 个 `window/workDoneProgress/create` 全部已答（`facts-summary.txt`），尾流 id19 未答（`protocol-tail-analysis.txt`）。**探针可容忍，产品不可容忍。**
- 应答策略（全部应答、不悬挂远端；应答不等于接受能力）：
  - `window/workDoneProgress/create` → `result: null`（与停 0 已答 18 次同形状）。
  - 未实现动态注册语义时，不声明 `dynamicRegistration`，`client/registerCapability` / `client/unregisterCapability` 按未支持请求显式报错，不以 null 假称已接受注册；未实现 `workspace/configuration` 同理。
  - 未支持的 server 请求 → `error: {code: -32601, message: "method not found: <m>"}`。
  - JSON-RPC 无 id 的 method 消息是通知；已知通知按其形状解析，非法载荷报 `PROTOCOL`，不伪造 id 回复。未知通知记录而不假称收到可配对请求。
- **退出阶段应答**：shutdown 响应收到后、exit 通知之后，继续解析并应答全部已解析 server 请求直到流关闭或退出宽限到期；到期仍有未应答请求 → 生命周期失败记录（计入 CLOSE_FAILED 面），不伪称全答。这条是 TL;DR 的「若只能改一处」硬路径。
- reader 只把应答加入有界写队列，不直接写管道；writer 成功 flush 后才增加 answered 计数。入队失败或宽限到期不能计已答，reply 与 shutdown/exit 在 beginClose 后仍受理。

## 4. 生命周期矩阵（T3 契约落地）

### 4.1 启动

- 启动档案同停 0 探针（`stop0-probe.py` argv 与 `effective-settings.txt`）：`javaHome/bin/java -Declipse.application=org.eclipse.jdt.ls.core.id1 -Dosgi.bundles.defaultStartLevel=4 -Declipse.product=org.eclipse.jdt.ls.core.product -Xmx1G -Duser.home=<homeDir> -Djava.io.tmpdir=<homeDir>/tmp -Dmaven.repo.local=<homeDir>/.m2/repository --add-modules=ALL-SYSTEM --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -jar <serverDir>/plugins/org.eclipse.equinox.launcher_*.jar -configuration <configurationDir> -data <indexDir>`，cwd=projectRoot，env 显式 `JAVA_HOME` / `PATH` / `HOME` / `TMPDIR`。
- **专属进程组**：POSIX 用会话包装器使子进程自成 session（pgid == 子 pid，同停 0 断言 `pgid == process.pid`）：
  - Linux：`/usr/bin/setsid -- <argv>`（exec 原位，pid 保留）；包装器只增加一个 `--`，原 argv 的空格与分隔符原样保留；setsid 缺失时直接启动并在证据中披露「无专属组」。
  - macOS：系统 perl 包装器 `/usr/bin/perl -MPOSIX -e 'POSIX::setsid() and exec @ARGV' -- <argv>`（POSIX 模块为系统 perl 自带）；perl 缺失时降级直接启动 + 披露。
  - Windows：直接启动；Job / 进程树实现本停点不覆盖，packet 明示平台限制（沿 win32 PARTIAL 先例）。
- initialize 基本形状沿停 0（rootUri=projectRoot、workspaceFolders、clientInfo `jh-lsp-jdtls`），capabilities 只声明实际支持的 `window.workDoneProgress`、`textDocument.publishDiagnostics.versionSupport`；未实现的配置查询 / 动态注册不得声明。initialize 后发 `initialized` 通知，自身有界（默认 60s），超时 = `LspException(CONNECTION)`。索引独占与初始化失败回滚仍按停 1 验收，不以该默认期限替代调用者期限。

### 4.2 关闭序列与升级

1. 不等待观察区锁即置 closed、拒新请求，通过 `peer.beginClose()` 级联 fail 在途普通请求与通知；退出控制帧与 server reply 仍可进入队列。
2. `shutdown` 请求（有界，默认 5s）→ 收到**任何** result（含 `{}`，偏离 D1）计已应答 → `exit` 通知。
3. 退出排水：继续解析并应答 server 请求（§3.3），直到 stdout EOF 或排水宽限（默认 2s）到期；到期未答记未完成。
4. 进程仍活 → `ProcessHandle.destroy()`（TERM，仅发信号，不提前关管道）→ 有界等待（默认 2s）。不得用 `Process.destroy()`：本机 JDK 的该方法会同时关闭 stdin/stdout/stderr，导致 TERM 后到达的请求漏计、把未全答伪称成功。排水宽限到期后仍读帧计未答，收束阶段才关流；回归证据见 `stop2-term-pipes-red.txt` / `stop2-term-pipes-green.txt`。
5. 仍活 → 对已拥有的后代进程句柄先 KILL，再 KILL 本体，有界等待；保留已捕获的身份以复验，不按进程名批量清理。
6. **复验**：全部已捕获的数值 PID 与专属 pgid 用 `ProcessHandle.of` + 有界 `ps -axo pid=,ppid=,pgid=` 复验无残留行；EPERM / 不可查 / 查询超时 = 保守判「未证消失」。
7. 有界收束 writer / reader / stderr drain 三条线程，确认 pending=0；阻塞输出管道在 KILL 之前不得关闭或抢其锁。`finish()` 返回 false 不得忽略。stdout EOF 只指帧边界上的真实流结束，reader 异常退出不冒充 EOF。
8. 任一步不收敛 → `LspException(CLOSE_FAILED)`，不伪称清理成功；停止不回滚 LS 文件写入（B23/B25 边界不变）。

### 4.3 验收矩阵（停 1 契约逐项对照）

| 项 | 契约要求 | 证据形态 |
|---|---|---|
| ≥3 启停 | 同钉定 build，每次 exit/EOF/pending/线程/专属组证据 | 真实 JDT LS 证据跑：每轮 EXIT=、EOF 时间、pending=0、线程 join、ps 复验行 |
| TERM 被忽略 | TERM→KILL 升级 + 复验组；EPERM 不当消失 | 可控子进程夹具（吞 TERM）驱动同一关闭实现；真实 Provider 关闭路径调用同一实现 |
| 退出应答 | 窗口内完成所有已解析请求后关闭，超时记录未完成并失败 | shutdown 前后请求配对、退出洪流夹具；真实跑对照尾流 id19 形态 |
| 重复正常退出 | 不单次外推 | 证据跑 ≥3 轮，逐轮独立落盘 |

## 5. 观察算法实现义务（停 1 契约落地）

### 5.1 串行观察区与截点

- 总 deadline 在公开方法入口创建，涵盖可取消的锁排队、初始化等待、输入读取、同步 flush、响应、等待窗和收尾采样；不得在获得锁后或切换步骤时重置。取消检查覆盖所有等待及结果返回前；收尾采样越过 deadline 不得返回 `Observed`。
- 每次 pull 进入单一串行观察区，遵循停 1 已裁顺序：before 输入采样 → 全文与变更同步 flush → 完整等待窗（单调时钟）→ 在 peer 发布锁下**原子冻结 sequence + candidate + overflow** → after 采样 → 期限与稳定性复验。该锁与 reader 发布整条通知互斥，不能读到半发布候选。
- 截点在窗后：窗口内收到的批次可以计入本轮；截点后新批只影响后续观察。每 URI 末值胜，捕获不可变快照后再来的覆盖不影响本轮。`LspEvidence.receiveSequence` = 计入批次的序号（QUIET_WINDOW 用截点值）；缓存清代与捕获使用同一发布边界。
- before/after 锚：`LspDocument(uri, sha256)` + `LspAnchor(projectRoot, incarnation, generation, projectHash, document)`；两者完全相等且文档存在才合格，否则 `Stale(before, after)`。incarnation 每次启动新 UUID。记住上次观察的输入，轮间和窗内输入变化均递增 generation 并失效旧缓存，不能用旧批次配新 hash。
- 首次文档同步发 `didOpen`；已打开文档内容变更发全文 `didChange`，版本递增，同一份字节同时产生发送文本与文档 hash；其他已打开文档的变更也须同步。after 采样发现文档删除时保留缺失锚返回 Stale；调用开始时文件缺失为 IO，不合成空结果。
- projectHash 覆盖**明示输入清单**：projectRoot 下 `pom.xml` 与 `src/**/*.java`（递归）的字节 SHA-256 合成；清单写死在 Provider 文档，非 LS 索引。
- 溢出：候选缓存按文档有界（条数帽 + UTF-8 字节帽）；超帽 = `LspException(PROTOCOL)`（沿停 1「溢出=PROTOCOL」）。缺 diagnostics 数组 / 非法条目 / 不可表示的位置不跳过，不伪造合法空数组。
- 失败分档：进程死亡 / 读失败 → `CONNECTION`；畸形帧 / 超限 / 应答形状非法 → `PROTOCOL`；写盘 / 读盘失败 → `IO`；取消原样抛 `AbortedException`。通过 peer 健康检查保留原始失败码；**任何失败不得降级为空结果**，deadline 到期才 `TimedOut`。

### 5.2 三能力

- `diagnostics(LspQuery)`：窗口后分类——本截点内有 `textDocument/publishDiagnostics` 批次 → `Observed(PUBLISHED_DIAGNOSTICS, items, evidence)`（items 含 warning；无 version 时 `serverVersion` 空，偏离 D3）；无批次 → `Observed(QUIET_WINDOW, empty, evidence)`，账面表述只能是「拉取时点无诊断」。批次带 version 且 `versionSupport` 已声明时保留 `serverVersion`（仅参考，不作锚）。
- `definition / references(LspPositionQuery)`：发 `textDocument/definition|references`（零基 UTF-16 位置，同 `LspPosition`；references 明确 `context.includeDeclaration=true`）；**必须**拿到成功响应（`result` 成员存在，`null` 合法 → 空列表）才有 `Observed(REQUEST_RESPONSE)`。响应按 `LspLocation(uri, range)` 映射（`LocationLink[]` 取 target）；非法条目整体 `PROTOCOL`，不得跳过造成假空。不得用文本搜索或通知沉默合成。
- REQUEST_RESPONSE 同样经过 query 指定的真实完整等待窗与 before/after 复验；requiredWait 来自 query，elapsedWait 为单调时钟实测，不能以 1ms 地板或 `Math.max` 伪造等待证据。
- NotReady 语义映射：initialize 未完成 → `INITIALIZING`；initialize 完成但该文档 sync 帧未 flush → `SYNCHRONIZING`；close 后 → `CLOSED`。NotReady 不携空列表。
- `TimedOut`：总 deadline（调用入口起、不逐步重置）先于合格快照到期；与进程死亡 / 协议错误严格分开。

## 6. 停 0 偏离清单 → 客户端验收矩阵（T5 边界 3）

| # | 停 0 偏离（冻结事实） | 客户端处理 | 测试 |
|---|---|---|---|
| D1 | shutdown 响应 `{}`（非 null） | 任何 result 成员（含 `{}`）计已应答后发 exit | 夹具回 `{}` 与回 `null` 两条路径均继续退出序列 |
| D2 | progress / workDoneProgress 早于 initialized（47 条先导消息、7 个 create 请求） | 不因未 initialized 拒收；create 请求照常答 `null`；$/progress 通知记录不挂起 | 夹具在 initialize 响应前发 create 请求 + $/progress → 请求被答、通知不阻塞 |
| D3 | 诊断无 version | 接受无 version 批次，`serverVersion` 空；version 永不当地锚 | 夹具无 version publish → `Observed(PUBLISHED_DIAGNOSTICS)` 且 version 空 |
| D4 | 退出尾流请求未答（id19） | 退出排水继续应答到流关闭 / 宽限到期；到期未答记 CLOSE_FAILED | 夹具在 shutdown 响应后发 create 请求 → 产品应答后才关流；计数全答 |

探针尾流未答是实验残余，不作为产品行为；无 version / 未见空数组仍是观察能力缺口表述，不宣称违反 LSP。

## 7. 四态真机区分（用户点名评审重点，停 0 夹具复现）

真实 JDT LS 复制停 0 夹具工作区 `jh-it29-stop0-20261029/project` 到停 2 自有运行目录，逐态取证；不重写停 0 原夹具或冻结证据，每态保存实际源文件与差异：

- `Observed(PUBLISHED_DIAGNOSTICS)`：保留类型错误夹具（int→String）→ 真实批次（code 16777233），hash 锚齐全；warning-only 用例原样作为非空诊断证据。
- `Observed(QUIET_WINDOW)`：新增独立清洁用例并明示与停 0 夹具的差异，完整等待窗后若无候选批次，账面只写「拉取时点无诊断」；若 LS 实际发布空数组则记 `PUBLISHED_DIAGNOSTICS`，不能改造证据凑 QUIET_WINDOW。
- `Stale`：等待窗内外部改写文件 → 前后锚不同 → Stale（含跨项目根变体由聚焦测试补）。
- `NotReady`：启动后立即 pull（initialize 未完成）→ INITIALIZING；close 后 pull → CLOSED。
- `TimedOut`：紧 deadline（< 窗口时长则契约校验拒绝，取 deadline 略大于窗口但在同步阶段即耗尽）→ TimedOut；对照：窗口期进程被杀 → `LspException(CONNECTION)` 而非 TimedOut、非空结果。

真机证据 + 聚焦夹具测试双面；夹具（fake JDT LS，Content-Length 帧可控脚本）覆盖真机难造的路径（畸形 Content-Length、超限帧、吞 TERM、退出洪流、错 id 响应、error 成员）。

## 8. 「拉取时点无诊断」措辞纪律（用户点名评审重点）

- 账面三条件逐字口径：**拉取成功 + 项目代稳定 + 完整等待窗**，结论只能是「拉取时点无诊断」。代码注释、日志、证据、packet 不得将「项目无错误」「分析完成」「诊断已同步」「真零」作为正面结论；说明这些推论不成立的风险文字可保留。
- hash 锚描述 JH 观察时点，不证明 LS 分析来源 / 完成；LS `version` 仅参考。
- 停 2 证据跑与评审包首尾自检这条纪律；QUIET_WINDOW 结果的证据行固定模板：「拉取成功（hash a…a）、项目代稳定（前后锚相同）、完整等待窗（elapsed ≥ required），拉取时点无诊断。」

## 9. 验证计划

1. 聚焦测试（`lsp/jdtls` 模块，JUnit + jqwik，计数用 Maven 模块汇总口径）：
   - 帧层：字节计长、UTF-8 多字节、畸形 header（非数字 / 缺失 / 负值 / 超头数）→ 流级断裂；8 MiB 帽；残帧后不重试单帧。
   - 应答层：D1–D4 夹具矩阵；未知 server 请求 -32601；无 id 请求记录不假答；退出排水到期未答 → CLOSE_FAILED。
   - 生命周期：夹具子进程吞 TERM → KILL 升级 + ps 复验；EPERM 保守未证消失；≥3 轮循环启停（夹具面）；close 幂等、拒新请求、pending 清零。
   - 观察层：串行观察区截点归属（批次在截点前 / 后）、溢出 = PROTOCOL、null result → 空列表、REQUEST_RESPONSE 不可由通知合成、四态构造校验。
2. 突变（脚本化，沿用 stop1-mutations.py 约定）：字节计数突变、应答策略突变（不答 create / 不答尾流）、退出排水突变（shutdown 后即 raw drain）、截点归属突变——破坏必红 → 还原复绿。
3. 真实 JDT LS 证据跑（本机代跑）：钉定 `1.63.0 / 202610191322`；≥3 启停逐轮证据（EXIT / EOF / pending / 线程 / ps 复验）；TERM→KILL 路径用夹具驱动同一关闭实现（矩阵口径）；退出阶段请求应答对照尾流 id19 形态；四态真机区分（§7）。
4. 出停 2 review packet 后停下等用户新鲜点名；Consumer / 装配不动。

## 10. 边界登记与维护义务

- 本停点新增外部输入面：`JdtlsLaunch`（javaHome / serverDir / configurationDir / projectRoot / indexDir / homeDir / maven settings）——按维护规则登记边界 B 行（B26：LSP Provider 输入面——路径只校验存在，不验证内容；索引 / home 目录写入是 LS 进程行为，工具治理不覆盖进程）。装配面（配置键 / ServiceLoader 行）出现时另行登记。
- Provider 已进入实现与审查修复，尚未验收；完成后回填 05 §8.1、§9 与边界表，不宣称「LSP 接口已完整」。独立审查发现的六项问题及已裁时序纠偏逐项留证，不能用先前聚焦绿或压力跑绿抵消未覆盖缺陷。

## 11. 停 2 评审包预告

出包含：本设计、聚焦测试汇总、突变汇总、真实 JDT LS 证据跑目录（逐轮）、四态真机取证、措辞纪律自检、偏离对照勾选。评审重点按用户点名执行：四态真机区分、措辞纪律。停下等新鲜点名，不自动进 Provider→Consumer 串接。
