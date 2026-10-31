# 迭代 29 — Java LSP Minimal Loop（停 2 已通过；停 3 S1–S4 已交付，S5 模型在环待真 key 跑）

日期：2026-10-31。当前模块：`lsp/lsp` Definition 已通过停 1；`lsp/jdtls` Provider 已交付、真实生命周期取证完成、[停 2 已通过并关闭](evidence/iteration-29/stop2-acceptance.txt)；`lsp` 聚合包含这两个叶子。停 3（Tool Consumer + 装配 + 验收）获用户点名授权开工，S1 `JdtlsPlugin` 装配件、S2 `lsp/tool` 三工具 Consumer、S3 装配面已交付（base 增 `lsp-jdtls` 禁用行 + `lsp-tool` 行、四处消费面、`--verify` 双态）；S4 产品验收三腿已取证：R1 回放零进程（[stop3-replay.txt](evidence/iteration-29/stop3-replay.txt)）、jlink 镜像三态真跑（[stop3-jlink.txt](evidence/iteration-29/stop3-jlink.txt)）、真实 LS 三能力探针（[stop3-real-three-capabilities.txt](evidence/iteration-29/stop3-real-three-capabilities.txt)）；S5 模型在环（真 key）待用户手动跑。未验收不等于发布 0.3.0 授权。后续 `core/agent-loop`、`bundle/base`、`examples/headless`、`dist/jh` 与外部消费闭包按 T4/T6/T7 裁决推进。

**状态：停 0 已通过并关闭（裸进程工程闭环限定结论）；JDT LS 路线确认，compiler API 备选不触发；T1–T3 方向已裁并随停 1 落定，停 1（seam 契约）已通过并关闭；T5 已裁自有边界实现并随停 2 落地，停 2（Provider 真实生命周期）已通过并关闭；停 3（Consumer + 装配 + 验收）已获点名授权开工，S1–S4 已交付（装配面 + R1 回放 + jlink 三态 + 真机三能力探针均取证），S5 模型在环待真 key 跑。** [停 0 裁决](evidence/iteration-29/stop0-acceptance.txt)、[停 1 裁决](evidence/iteration-29/stop1-acceptance.txt)、[T5 裁决](evidence/iteration-29/stop2-t5-ruling.txt)与[停 2 裁决](evidence/iteration-29/stop2-acceptance.txt)是现行依据；[停 1 审查包](evidence/iteration-29/stop1-review-packet.md)与[停 2 审查包](evidence/iteration-29/stop2-review-packet.md)冻结保留当时提案。停 2 评审重点（用户点名）：诊断四态在真实 JDT LS 下的区分（用停 0 夹具复现）、「拉取成功 + 代稳定 + 等待窗」三条件的账面表述严守「拉取时点无诊断」措辞纪律。T4、T6、T7 未整体批准；停 3 停点预告（用户点名）：工具输出的措辞纪律（`lsp_diagnostics` 结果文本逐字守「拉取时点无诊断」，突变含「改成『项目无错误』必红」——S4 已按此名目取证）、装配面老三样（bundle 行 + 四处消费面 + 双通道 SPI 清单）、`--verify` 计数与工具面双态、R1 回放（零 LS 进程）+ 模型在环。产品验收（S5 模型在环）未执行，不等于发布 0.3.0 授权。

## 四确认

- **内容**：Java 诊断 / 定义 / 引用三能力最小闭环；独立裸进程停 0 已确认 JDT LS 路线，停 1 seam 契约已通过并关闭，停 2 Provider 真实生命周期已通过并关闭（≥3 启停 / TERM→KILL / 退出应答 / 四态真机区分取证完成），停 3 S1–S4（Tool Consumer 与装配、R1 回放 / jlink 三态 / 真机三能力探针）已交付，S5 模型在环进行中。诊断携 JH 屏障拉取时计算并落账的文件 / 项目 hash 观察锚；LS version 仅参考，不证明 LS 分析来源或完成。消费面以轮屏障逻辑拉取起步；语言服务器以可信全权外部进程运行、不入沙箱，主动宿主 JDK 探测须披露，索引目录可配置。协议依赖、Consumer 与装配细节不因范围批准而自动落定。
- **目标**：用户在固定 Java 项目中可定位真实定义与引用、读到携 JH 观察时点 hash 的诊断，修改后辨认观察结果变化，而不是把未就绪当成“没有错误”。拉取成功、项目代稳定且完整等待窗后无诊断，仅称“拉取时点无诊断”，不证明 LS 已完成目标版本分析或“项目无错误”。通过真实入口证明三能力相对纯文本搜索的收益；取消、超时、进程退出及 keyless 回放均可验证。JH 观察稳定性与 R1 请求可重建性分别验收，产品验收尚未完成。
- **为什么**：逐字对齐 0.3 路线“优先 Java 的诊断、定义和引用”；先用真实 LS 闭环证伪，避免先建抽象后发现候选不可用。逻辑拉取把模型可见结果限制在受控调用及落账路径内，避免引入另一条异步上下文入口；不扩为多语言平台，控制单人维护成本。
- **不做**：多语言通用平台、完整编辑器功能、补全 / 重命名 / code action / 自动修改源码、后台诊断直接推入模型上下文、自动安装或自动升级 LS、通用进程框架重构、MCP 命名算法改造、capability graph 生成器、Web 预算语义变更、版本发布。compiler API 直出诊断是正式备选，本次不触发；它不是隐式降级，更不能冒充已完成 LSP 三能力。

裁定来源：范围四确认与 [2026-10-29 停 0 终裁](evidence/iteration-29/stop0-acceptance.txt)、[2026-10-29 停 1 终裁](evidence/iteration-29/stop1-acceptance.txt)、[2026-10-29 T5 裁决](evidence/iteration-29/stop2-t5-ruling.txt)与 [2026-10-31 停 2 终裁](evidence/iteration-29/stop2-acceptance.txt)。停 0、停 1、停 2 均已通过并关闭；T5 已裁自有边界实现（三条实现边界随裁决归档并随停 2 落地），停 3（Tool Consumer + 装配 + 验收）已获用户点名授权开工。T4、T6、T7 未整体批准，不提前实现三能力产品验收之外的装配。

## Scope and Evidence Baseline

- 前序 it28 已关账；工作基线 `cd39e36`。Skills / MCP / Web 已实现，但路线完成不等于版本发布获批；阶段路线事实源见 [设计导航](../design/README.md#phased-evolution-plan)。
- `lsp/lsp` Definition 已写并通过停 1 评审：[seam 提案](evidence/iteration-29/stop1-seam-design.md)、[审查包](evidence/iteration-29/stop1-review-packet.md)与[验证汇总](evidence/iteration-29/stop1-verification-summary.txt)（EXIT=0）已落盘并获[用户放行](evidence/iteration-29/stop1-acceptance.txt)。`LspService` 依赖 `kernel/core` + `llm/llm`；JPMS `io.javanatic.harness.lsp` exports / opens `io.javanatic.harness.lsp`。停 1 批仅交付 Definition。`lsp/jdtls` Provider 已交付（`JdtlsProvider` + `LspPeer`/`LspFrameReader` 自有 Content-Length 字节帧 wire + `Session` 进程所有权 + `DiagnosticsCache`；真实 JDT LS 证据跑 3 轮启停 + 四态真机区分取证），并经[停 2 评审通过并关闭](evidence/iteration-29/stop2-acceptance.txt)。停 3 S1–S3 已交付：`lsp/jdtls` 增 `JdtlsPlugin` 装配件、新模块 `lsp/tool`（`LspToolPlugin` + 三工具），双通道 SPI（`provides` + `META-INF/services`）齐全；配置键 B27/B28 已入 12-api-stability 表；base bundle 增 `lsp-jdtls` 禁用行 + `lsp-tool` 行（37 行 = 24 可配 + 13 无配，缺省组合零进程零工具）。S4 产品验收三腿已取证：`LspReplayTest` 回放双腿全绿（零 LS 进程重建、事件逐字节重放；[stop3-replay.txt](evidence/iteration-29/stop3-replay.txt)）+ 措辞纪律双突变必红（含用户点名「项目无错误」）；jlink 镜像三态 verify（keyless 30/37/0、启用档 31/37/0 + 三工具、行序负例 31/37/0 + 零工具，均 EXIT=0 零残留；[stop3-jlink.txt](evidence/iteration-29/stop3-jlink.txt)）；真实 LS 三能力探针 3 轮启停 ALL ASSERTIONS PASSED（diagnostics / definition / references 真机 REQUEST_RESPONSE 取证；[stop3-real-three-capabilities.txt](evidence/iteration-29/stop3-real-three-capabilities.txt)）。S5 模型在环（真 key）未执行。
- MCP 已有长驻子进程、异步清单通知和轮屏障换代。LSP 的新增难点是文档 / 项目模型 / 索引的状态，以及 JH 诊断观察锚的稳定性，不是首次面对异步外部服务；观察 hash 不保证 LS 分析来源版本。
- `ShellExecutor.execute` 是一次性命令入口，不是长驻双向协议通道。MCP stdio 的模块虽导出包，内部 `JsonRpcPeer` 等仍为 package-private，且现有按行帧不能直接当作 LSP transport。T5 已裁自有边界实现（[裁决](evidence/iteration-29/stop2-t5-ruling.txt)）：lsp/jdtls 内复制并按 LSP Content-Length 帧改写，不建跨协议共享模块；生命周期经验（有界 IO、所有权、退出收敛）可参考，wire 层不复用。
- 当前 FS 没有现成的 typed 文档变更事件；workspace 同值约束由 `AppBoot.verifyWorkspaceAlignment` 检查五个配置入口。如何观测模型编辑、shell 编辑及外部编辑，须落实 T1 的已裁方向并在 T4 明确消费衔接，不能假设轮屏障自然保证索引就绪。

## Stop 0 — JDT LS Reality Gate

**硬前置：在任何 JH 实现代码（包括模块骨架）之前完成。** 输入限定独立夹具，私有工作目录包括 project / index / home / tmp / config_mac_arm，不拿真实仓库试写；不主动读取或配置加载用户私有配置、凭据或工作资料。这不构成 OS 访问隔离，也不证明可信外部 LS 从未访问监测目录之外的资源；实际宿主 JDK 自动发现见取证。探针脚本、夹具和取证不属于 JH Provider 实现，不得借探针夹带产品代码。

### Version Pin

下表区分工件静态取证与首次真实运行；运行成功不代替人工停点和 T 项裁定。不能用 `latest`、IDE 内置插件名或“本机可用”代替版本锁定。

| 必记项 | 当前状态 |
|---|---|
| JDT LS 版本、完整 build 号、官方工件来源与工件 SHA-256 | 官方 milestones 1.63.0 / 202610191322；完整 51143565 bytes，SHA-256 `b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4` 与官方匹配；见[续传记录](evidence/iteration-29/stop0-download-ranges-01/summary.txt) |
| 实际 launcher / server bundle 版本与完整启动 argv | launcher `1.8.0.v20260804-1928`、server `1.63.0.202610191322`、commit `d98abba8f04cf041542d9ebb6df0d472a1395606`；[工件核验](evidence/iteration-29/stop0-artifact-verification.txt)与[真实 argv / 结果](evidence/iteration-29/stop0-run-01/result.txt)分别留存 |
| JDK 版本、OS / 架构、夹具项目形状 | Java25.0.4.1 / macOS26.2 arm64；单 Maven 模块 + 单 JPMS 模块，含 Java25 final `import module`，无 preview；[合法磁盘编译 EXIT0](evidence/iteration-29/stop0-fixture-legal-check.txt)，LS 收到错误 buffer 后给真实类型错误。未覆盖跨 Maven 模块或 JH 全仓 |
| 所依据的 LSP 规范版本与 initialize 实际 capabilities | [一手研究](evidence/iteration-29/stop0-source-research.txt)依据 LSP3.17；实际 definition/references provider=true、未宣告 diagnosticProvider。普通诊断无 version；初始化顺序、shutdown `{}` 与尾流请求未答见[审查包](evidence/iteration-29/stop0-review-packet.md) |
| 工作目录、索引目录、项目写入清单、启动及退出耗时 | JH 仓外 scratch `jh-it29-stop0-20261029/`；initialize约3.855秒、首条类型错误约103.478秒；exit0/EOF/专属组消失。五目录常规文件快照新增356（项目9）、修改0、删除0；实际下载及宿主 JDK 发现均披露，详见[事实汇总](evidence/iteration-29/stop0-jdt-ls-probe.txt) |

官方归档内 `git.build.version` 与运行 `serverInfo` 均为 `1.63.0-SNAPSHOT`，与 OSGi 完整 build 并存；运行日志 `eclipse.buildId=unknown` 也原样保留。下载来源仍是官方 milestones，不抹掉内部字段或改称另一个通道。客户端经用户点名授权完成[双入口自检](evidence/iteration-29/stop0-client-authorized-green-02.txt)与[8 项突变 / 复绿](evidence/iteration-29/stop0-client-mutations-round2.txt)；它们是替身测试，不代替真实 JDT 证据。

### Probe Sequence and Pass Criteria

1. 建立无凭据的隔离 Java 夹具，记录源码字节 hash、项目配置与版本锚；显式指定 LS 索引目录，记录启动前目录清单。检查 Java 25 / JPMS / Maven 项目适配范围，不把普通单文件成功外推为全仓兼容。
2. 以裸进程启动钉定 build，经实际 LSP 帧完成 `initialize` 请求 / 响应，再发 `initialized`。保存能力响应与服务端请求处置记录；握手成功本身不算通过。
3. 打开包含已知编译错误的 Java 文件（`textDocument/didOpen`，显式文档版本），收到可定位到该文件和错误位置的真实诊断。记录诊断到达方式、是否携带版本、项目导入 / 索引就绪依据；空列表、日志中的“ready”或超时都不能代替这一条。
4. 修改夹具并提高文档版本，观测更新与清空诊断的实际行为，为 T1 提供证据。若无法可靠关联版本或证明清空，明确标为能力缺口，禁止用通知到达时的“当前 hash”补盖证明。进一步观察定义 / 引用 capability 与最小请求结果，为三能力范围提供可行性输入，不将 capabilities 宣告当验收。
5. 发 `shutdown` 并收到响应，再发 `exit`；有界等待，记录退出码、stdio / 读写线程收敛、相关子进程无遗留以及实际目录变化。强制回收只用于失败清场，不能冒充正常退出成功；不按进程名批量杀用户其他 Java 进程。
6. 保存实际命令、关键协议帧 / 输出、每段 `EXIT=`、版本与夹具 hash、目录写入对账；出停 0 packet 后主动停下，不进入 JH 实现。

**通过判据**：启动 → initialize / initialized → 打开文件 → 收到真实诊断 → shutdown / exit 形成可复核闭环，且版本、路径和回收证据齐全。三能力、诊断新鲜度和项目适配观察的缺口须在 T 清单逐项裁定；停 0 通过不自动使这些项通过。

**失败出口**：启动、握手、诊断或退出任一环失败即停止、保存证据并重裁候选；只允许安全清场与失败取证，不自动更换 LS build、引入依赖或切 compiler API。正式备选为 JDK compiler API 直接诊断：它不走 LSP，不能交付本轮定义 / 引用承诺；若采用，须用户明确重裁范围、改四确认与路线验收口径后再开工。

## Technical Decisions — T List

T0 与 T1–T3 方向以[停 0 终裁](evidence/iteration-29/stop0-acceptance.txt)为准；seam 形状已随[停 1 终裁](evidence/iteration-29/stop1-acceptance.txt)落定。T4–T7 未自动全部通过。Provider 依赖、默认目录、传输帽和装配参数不在 seam 批偷定。

| # | 裁决项 | 已定方向 / 候选形状 | 尚需落实与拒绝判据 |
|---|---|---|---|
| T0 | 候选与版本锁定 | **已裁**：JDT LS 1.63.0 / 202610191322；compiler API 不触发 | 保留工件 hash / 内部 SNAPSHOT；实测限单 Maven + JPMS，不外推全仓 |
| T1 | **诊断语义** | **已裁**：JH 观察时点文件 / 项目 hash；LS version 只参考；四态 + 非空结果 | 类型与观察算法已随停 1 落定；稳定项目代 + 完整等待窗 + 拉取成功才可称“拉取时点无诊断”；不证明分析来源 / 完成，产品验证待 Provider/Consumer |
| T2 | **LS 权限** | **已裁**：可信全权外部进程、不入沙箱；治理在工具面；主动宿主 JDK 探测须披露 | 项目写入、缓存、下载、宿主发现均保留；私有 HOME/env 非 OS 隔离，工具拒批不撤销进程副作用；Provider 配置落实仍待交付 |
| T3 | **进程生命周期** | **方向已裁**：所有权显式、有界等待、取消 / 关闭收敛；产品请求必须应答（含退出） | 契约要求随停 1 落定：≥3 次启停、忽略 TERM 后 KILL + 专属组复验；EPERM 未证消失。产品测试尚未执行；Provider 实现与生命周期矩阵在停 2，不将 R3 当回滚项目文件 |
| T4 | 轮屏障采纳与 R1 / R2 | 逻辑拉取起步；后台推送模型面挂账；禁止后台通知未经落账直接改模型上下文 | 按需工具调用怎样与文档同步 / 轮屏障衔接；候选缓存何时采纳、结果先落账再可见；优先复用 `tool/call` / `tool/result`，是否需要额外日志事实须举证；不盲抄 MCP 两个 hook |
| T5 | 协议与三角色边界 | **已裁**：自有边界实现（[裁决](evidence/iteration-29/stop2-t5-ruling.txt)）；不引入 LSP4J / 新第三方依赖 | 三条实现边界：① JsonRpcPeer 每 transport 私有、lsp/jdtls 复制并按 LSP 帧改写，不建跨协议共享模块（若第三个消费者出现可重访抽取）；② Content-Length 按字节、charset 恒 UTF-8，畸形 Content-Length = 流级断裂，server 发起请求必须全部应答；③ 停 0 偏离清单升格为客户端验收矩阵。不得因 MCP 有 JSON-RPC 就直接复用按行 transport |
| T6 | 定义 / 引用与结果边界 | 固定 Java 任务验证，不以字符串搜索冒充语义结果 | URI / 路径权限、位置编码、Location / LocationLink 等实际返回形状、跨文件和依赖源码、无结果 / 未就绪区分、帧帽 / 输出帽 / deadline；版本漂移时不得包装为当前定位 |
| T7 | 装配、默认行为与验收闭包 | 回放零联网、零进程；工具进入既有治理；真实 LS 与 keyless fixture 分开 | 未安装 / 未启用 LS 如何显式表示；CLI 与 Java 嵌入、`--verify` 与外部消费闭包、文档同步；回放不能为重建请求启动 LS 或重查源码 |

### Diagnostic Freshness Contract

- **观察锚**：屏障拉取时由 JH 计算并落账文件 / 项目 hash，描述 JH 当时看到的状态；LS `version` 若出现只作参考标注，不作锚。禁止将观察 hash 冒充 LS 分析来源证明。
- **未就绪**：JH 尚未完成初始化、项目输入同步或建立本次观察窗，不作空结果。
- **过期**：本次观察前后项目代或源码 / 项目 hash 变化；不得将旧缓存包装成本次观察结果。无法检测两次采样间完全恢复的外部改动，不宣称全时段文件系统审计。
- **超时**：本次 deadline 到期且未形成合格快照；与进程死亡 / 协议错误分开，不能归为空结果。
- **拉取时点无诊断**：拉取成功 + 项目代稳定 + 明确等待窗之后仍无诊断。只是 JH 观察事实，不证明 LS 完成目标版本分析，更不能表述“项目无错误”。旧称“真零诊断”不再承载来源 / 完成证明。停 2 产品面（Provider/Consumer 账本）必须严守此措辞纪律，评审重点由用户点名。
- 正常非空诊断携同一观察锚，亦不承诺 LS 来源版本。无 version 的迟到通知仍可能混入本次观察，依赖 / 索引变化也不总可观测，不以 hash 隐去这些限制。R1 回放重建已落账结果，不重查源码 / 不启动 LS。类型、等待窗与稳定判据已随停 1 评审落定。

### Logical Pull Is Not Protocol Pull

“拉取式”描述的是 **JH 对模型的消费方式**，不是预先断言 JDT LS 实现 `textDocument/diagnostic`。协议层若只提供 `publishDiagnostics`，可以接收并暂存候选结果，但后台接收不直接改模型上下文。T1 已裁定用 JH 拉取时点文件 / 项目 hash 作观察锚，不证明 LS 分析来源版本或完成；观察类型与稳定判据已随停 1 评审落定。选用哪一帧、何时采纳、落账和返回等消费 / 协议细节仍待 T4/T5 裁定。`TURN_STOPPING` / `PRE_STEP` 是既有参考点，不是诊断完成信号。

推送式模型面（例如新增 `lsp/diagnostics` 事件主动注入）本轮不做；重访条件是固定任务证明拉取形态不足，且有明确的观察锚语义、落账 / 回放、资源及生命周期方案，经另行裁定后再实施。

## 设计增量（ADDED / MODIFIED / REMOVED）

T1–T3 方向已裁并随停 1 落定，以下区分已写并通过的 Definition 与后续待实现的 Provider / Consumer；停 2 Provider 获准开工，Consumer / 装配不因停 1 通过而自动开工：

- **ADDED / D1**：`lsp/lsp` Java 三能力 Definition（已写并通过停 1 评审），仅 `LspService`、纯值类型与校验；真实 JDT LS Provider 由停 2 实现。对应 02 模块布局、05 能力面、12 导出 / seam / 0.3 增量；根 reactor 增一个聚合 + 一个叶子，不增装配行。
- **ADDED / D2**：`LspAnchor` / `LspObservation` / `LspEvidence` 与 `LspResult` 四态已写，不证明 LS 分析来源 / 完成；纯值测试不证明真实时钟、文件扫描、通知缓存或落账行为。03 日志事实、04 消费时机与 05 工具结果的消费衔接仍待 T4；本批不增事件。
- **ADDED / D3**：LS 全权进程信任与主动宿主探测披露，以及索引目录、所有权、取消 / 回收边界；对应 05、06、07、09 及边界登记表。T2/T3 方向已裁，契约要求与验收矩阵已随[停 1 评审](evidence/iteration-29/stop1-acceptance.txt)落定；Provider 真实生命周期验收在停 2 执行。
- **MODIFIED / D4**：组合行与消费闭包（02/07/12、base/headless/dist/consumer/embedding），仅在 T7 确认实际新增行后同步，数量不得预填。
- **REMOVED**：无既有运行行为删除；移除诊断须证明 LS 来源版本 / 分析完成的产品承诺，改为 JH 观察锚（[ADR 0009](../../.agents/notes/0009-lsp-observation-anchor.md)）。订正“LSP 接口已完整”等过期现状不构成删除已实现能力。

## 锚点（候选；最终类型与符号在对应停点评审前补齐）

| 锚点（文件:符号） | 预期改动 / 约束 | 完成 |
|---|---|---|
| `docs/plan/evidence/iteration-29/`：停 0 探针与夹具 | 独立裸进程闭环，不依赖 JH；证据与审查提案冻结 | 已通过并关闭（限定结论），见[用户裁决](evidence/iteration-29/stop0-acceptance.txt) |
| `lsp/lsp/src/main/java/io/javanatic/harness/lsp/`：`LspService` / `LspResult` / `LspObservation` 等 | 三能力契约与纯值观察模型；停 1 仅 Definition，不实现 Provider | Definition 已写、聚焦 17 项 EXIT=0；停 1 已通过并关闭，非产品验收 |
| `lsp/jdtls`：`JdtlsProvider` / `LspPeer` / `Session` / `DiagnosticsCache`（[停 2 设计](evidence/iteration-29/stop2-provider-design.md)） | 长驻进程、有界 IO、所有权与生命周期矩阵 | 已交付；[停 2 已通过并关闭](evidence/iteration-29/stop2-acceptance.txt)（真实证据跑见 [S5](evidence/iteration-29/stop2-s5-real-evidence.txt)；QUIET_WINDOW 的区分证据来自夹具测试而非真机，见[停 2 packet](evidence/iteration-29/stop2-review-packet.md) §5/§6） |
| 拟新增 LSP Tool Consumer；`core/agent-loop` 的轮屏障消费点 | 受控拉取与落账；最终 hook / 注册位置待 T4 | 停 3 进行中（获 [2026-10-31 点名授权](evidence/iteration-29/stop2-acceptance.txt)）：`lsp/tool` 三工具已交付（S2），S4 回放 / jlink 三态 / 真机三能力取证完成，S5 模型在环待真 key 跑 |
| `bundle/base/.../boot/AppBoot.java:verifyWorkspaceAlignment` | 评估 workspace 对齐入口与 LS 索引目录的不同语义，不把缓存目录当源码根 | 否 |
| base `bundle.yml`、headless/dist/consumer POM、`docs/embedding.md` | 新组合行同步面与 ServiceLoader 双通道 | 已同步（S3：四处消费面 + SPI 4/4；S4：jlink 镜像三行实测生效） |
| `docs/design/{02,03,04,05,06,07,09,12}-*.md`、边界登记表 | 按实际契约增量同步，未涉及的条目不机械改写 | 本批 02/05/06/12、导航与边界已同步；03/04/07/09 按后续实际消费 / 装配增量触发 |

## 审查停点

| 停点 | 覆盖锚点 / 类 | 状态 |
|---|---|---|
| 停 0 | 钉版本 JDT LS 裸进程真实闭环、项目 / 索引写入与退出证据 | **通过并关闭（限定结论）**，见[用户裁决](evidence/iteration-29/stop0-acceptance.txt)；冻结提案不回写 |
| 停 1 | 三能力 seam、观察时点模型、Provider 所有权 / 回收 / 协议应答的契约与验收矩阵 | **通过并关闭**，见[用户裁决](evidence/iteration-29/stop1-acceptance.txt)；[审查包](evidence/iteration-29/stop1-review-packet.md)冻结保留当时提案。Definition 聚焦 17 项、10 项突变及 full EXIT=0；本批不实现 Provider，≥3 次启停 / KILL / server 全应答均未真实验证，仍为停 2 Provider 硬验收，不能以契约单测代替 |
| 停 2 | Provider 真实生命周期：≥3 启停 / TERM 忽略时 KILL 升级 + 进程组复验 / 退出应答 / JDT LS 偏离清单对照 | **通过并关闭（2026-10-31）**，见[用户终裁](evidence/iteration-29/stop2-acceptance.txt)；[审查包](evidence/iteration-29/stop2-review-packet.md)冻结保留当时提案。T5 三条实现边界随停 2 落地（[裁决](evidence/iteration-29/stop2-t5-ruling.txt)）；设计见 `stop2-provider-design.md`。评审重点（用户点名）：诊断四态在真实 JDT LS 下的区分、账面表述严守「拉取时点无诊断」措辞纪律——均达标（7 突变必红→字节还原、focused/validate/full 304/0/0、真实证据跑 3 轮 + 四态真机区分）；QUIET_WINDOW 的区分证据来自夹具测试而非真机（真机无确定性实例，如实记录不凑证据） |
| 停 3 | Tool Consumer、轮屏障 / 落账 / R1、权限与装配闭包、三能力真实任务 | 进行中（获 [2026-10-31 点名授权](evidence/iteration-29/stop2-acceptance.txt)开工）。S1 `JdtlsPlugin`、S2 `lsp/tool` 三工具、S3 装配面已交付；S4 产品验收三腿取证完成：措辞纪律双突变必红（含用户点名「项目无错误」，[stop3-replay.txt](evidence/iteration-29/stop3-replay.txt)）、jlink 三态真跑（[stop3-jlink.txt](evidence/iteration-29/stop3-jlink.txt)）、真机三能力探针 ALL ASSERTIONS PASSED（[stop3-real-three-capabilities.txt](evidence/iteration-29/stop3-real-three-capabilities.txt)）；S5 模型在环待用户真 key 跑。停 3 评审重点预告（用户点名）：① 工具输出的措辞纪律（`lsp_diagnostics` 结果文本逐字守「拉取时点无诊断」，突变含「改成『项目无错误』必红」）；② 装配面老三样（bundle 行 + 四处消费面 + 双通道 SPI 清单）；③ `--verify` 计数与工具面双态（LSP 工具随 server 在场与否，沿 web_search 先例）；④ R1 回放（零 LS 进程重建）+ 模型在环（真 key + 停 0 夹具，runbook 沿 it26/it27.1/it28 形态）；收口前独立核对验收和发布边界 |

每个停点 packet 必须首部五行 TL;DR：不变量增量 / 裁决点 / 风险自报 / 证据入口 / 若只能改一处；正文包含契约承载段、设计增量 → 实现 → 测试映射、偏离及“为什么是这个形状 / 若错靠什么发现”。证据先落盘，到点不提交、不越点，等待用户新鲜点名放行。

## Verification Prerequisite

[verify-stop.sh](../../integration/verify-stop.sh) 是方法论前置交付，按用户最新裁定与文档整理合为一个预置提交；**不得晚于 it29 停 1**。实现使用 [Python 标准库编排器](../../integration/verify_stop.py)，行为测试见 [test_verify_stop.py](../../integration/test_verify_stop.py)。22 个行为测试、11 项突变及修复后真实 full 验证已完成，见[独立脚本停点 packet](evidence/iteration-29/verify-stop-review-packet.md)；用户已点名“脚本交付放行”，见[验收记录](evidence/iteration-29/verify-stop-acceptance.txt)，不视为 LSP T 项放行。已有承诺见 [it27.1 评审记录](evidence/iteration-27.1/stop2-review-round1.txt)；本交付不宣称另有未经核验的“五件套”已全部完成。

### Interface and Layers

`bash integration/verify-stop.sh <docs|focused|full|consumer> --log-dir <全新目录> [--base <tree-ish>] [--module <模块目录>]`

- 必须显式选择一层及新证据目录，父目录须存在，已有目录（包括空目录）不复用；相对日志路径以调用者当前目录为准。`--base` 缺省 `HEAD`，记录解析后的 tree 与实际 HEAD；审查已提交变化时显式指定更早基线。范围为基线到工作树 / 暂存区的路径并集，加非忽略的未跟踪文件，只按目录项排除本次日志目录（指向日志的源码符号链接仍属改动）；内容检查针对工作树，不冒称验证了 index 的文件内容或未变化文件的反向引用。
- `--module` 仅限 focused，必须为仓库内含 POM 的单个目录，支持空格，拒绝路径越界、列表和选项注入。工作目录固定为脚本所属 Git 仓库；不接收任意 Maven 透传参数。
- 四层都执行公共机械检查：工作树与 index 的 `git diff --check`、变化范围清单、变动 Markdown 的简单行内相对链接目标存在性。docs 额外拒绝 `.md/.txt` 以外改动；其他层允许源码改动。链接检查不访问网络，锚点 / 引用式链接 / HTML / 复杂或多行 Markdown / 源码路径引用 / 文档语义始终须人工核验，记录在 `documents.txt`，不能将脚本绿灯当作完整文档审查。

| 层 | 公共检查后的命令 | 包含关系与边界 |
|---|---|---|
| docs | 无 Maven | 仅文档机械检查，不证明源码 / 架构正确 |
| focused | `mvn -B -q -pl <module> -am test` | reactor 聚焦；不包含 full / consumer |
| full | `mvn -B -q validate`、`mvn -B -q package` | 两命令顺序执行；不另跑 focused，不包含 consumer |
| consumer | `bash integration/verify-consumer.sh candidate` | 独立补充闭包，不包含 full；显式 candidate，绝不默认 all / release |

consumer 沿用既有脚本，候选 install 自带 `-DskipTests`，因此**不能替代 full**；wrapper 仅保留整个 consumer 脚本的退出码，不虚称取得其每一内部命令的原始退出码。每次将 `CONSUMER_WORK` 固定在本次日志目录下，覆盖宿主同名变量以免复用旧仓。Maven / candidate 可能下载构建依赖，不等于离线；不自动启动模型 / LS / 发布探针。

### Tools and Evidence

- 需要 Bash、Python 3.9+、Git；非 docs 层另需 Maven / 合适的 JDK。`PYTHON`、`MVN` 为单个可执行路径或 PATH 命令名，未设时分别用 `python3` / `mvn`，显式无效路径立即失败，不猜旧安装兜底；相对 `MVN` 路径先按调用者目录固定为绝对路径，不能被仓库内同名文件替换。`JAVA_HOME` 沿用调用者设置；本机按根 AGENTS 的 Maven 3.10.0 / JDK 25 配方调用。不读取或转储凭据与整份环境，仅记录命令、工具版本及本次生成的路径覆盖值；子命令输出原样留存，发布证据前仍须人工查敏感信息。
- 每步独立 `.txt` 包含实际 argv 的 shell 引号表示、输出及 `EXIT=`，`summary.txt` 为 JSON 格式汇总（层 / 基线 / 范围 / 步骤 / 开始结束时间 / 退出码）。直接连接日志文件而非 `tee` 管道；文件先打开才启动子命令，首个失败终止。参数错误 / 日志 I/O 错误为 2，缺工具为 127，不可执行为 126；子命令非零码原样保留（信号终止转 128 + 信号），即使后续汇总写入也失败，已知子命令失败码仍优先。
- 不能创建目录或启动 Python 时，证据只能写 stderr 并非零退出；中途 I/O 失败不承诺完整落盘。没有成功的最终汇总即不可声称完成。脚本不汇总 surefire XML；full 表示本次 package 退出成功，不自行推断每个模块本次执行了多少测试。
- 不接入 Git hooks、不提交 / 推送、不替代人工停点放行。脚本自测用标准库临时 Git 仓库和可执行边界夹具，不产生提交或修改用户 Git 配置；真实 Maven 结果与 wrapper 夹具结果分开取证。门禁改动须突变必红、逐字节还原、复绿，或如实记录安全拦截及等效断言。

## 取证

停 0 的[综合取证](evidence/iteration-29/stop0-jdt-ls-probe.txt)与[审查包](evidence/iteration-29/stop0-review-packet.md)已落盘，且已获[用户限定通过并关闭](evidence/iteration-29/stop0-acceptance.txt)：真实类型错误、warning-only 更新、定义 / 引用正例与正常退出有证据；诊断来源版本和真零仍无证明，不因 T1 改用观察锚而改写实测事实。停 1 Definition 已写、出包并获[用户通过放行](evidence/iteration-29/stop1-acceptance.txt)：最终聚焦 17 项、10 项突变、full 构建及两轮独立审查处置见下表；停 3 及三能力产品验收未执行。full 先于后续测试增补，生产源码未变；最终聚焦覆盖全部测试增补，不混同执行时间面。Provider 的 ≥3 次启停、忽略 TERM 后 KILL / 专属组复验及退出阶段 server 请求应答仍须停 2 真实取证，不以 seam 契约单测或停 0 单次正常退出替代。下表明确区分已存在的链接与计划文件名；各停点出包前只核验该 packet 引用的证据，收口时核验全表。

| 文件（相对 `docs/plan/evidence/iteration-29/`） | 覆盖 / 状态 |
|---|---|
| `stop0-jdt-ls-probe.txt`、`stop0-review-packet.md`、`stop0-acceptance.txt` | 已落盘并限定通过；工件 / 版本、真实协议 / 诊断 / 定位、写入 / 下载 / 宿主发现与退出；终裁与冻结提案分开 |
| [stop1-seam-design.md](evidence/iteration-29/stop1-seam-design.md)、[stop1-verification-summary.txt](evidence/iteration-29/stop1-verification-summary.txt) | 已落盘；契约提案、构建 / hash 与计数面说明；不代表用户批准或 Provider 验收 |
| [stop1-final-focused-02.txt](evidence/iteration-29/stop1-final-focused-02.txt)、[stop1-full-01/summary.txt](evidence/iteration-29/stop1-full-01/summary.txt) | 17 项纯值测试与 full validate/package 各 EXIT=0；统计以模块汇总计，不把被覆盖后的 XML 残留 7 项当总数 |
| [stop1-mutations-01/summary.txt](evidence/iteration-29/stop1-mutations-01/summary.txt)、[stop1-mutations-02/summary.txt](evidence/iteration-29/stop1-mutations-02/summary.txt) | 共 10 项行为断言必红，源码逐字节还原、终检无残留 |
| [stop1-review-round1.txt](evidence/iteration-29/stop1-review-round1.txt)、[stop1-review-round2.txt](evidence/iteration-29/stop1-review-round2.txt)、[stop1-review-packet.md](evidence/iteration-29/stop1-review-packet.md)、[stop1-acceptance.txt](evidence/iteration-29/stop1-acceptance.txt) | 设计初审及源码复审处置已归档；packet 已出并经用户通过放行，评审记录随批提交入仓 |
| `stop2-t5-ruling.txt` | T5 裁决已归档：自有边界实现 + 三条实现边界 + pits.md 通则挂收口批 + push 仍等点名 |
| `stop2-provider-design.md` | 停 2 Provider 设计已落盘：模块布局 lsp/jdtls、Content-Length 自有边界、生命周期矩阵、偏离对照表、四态真机区分、措辞纪律 |
| `stop2-*.txt`、`stop2-review-packet.md`、`stop2-acceptance.txt` | 停 2 Provider 真实生命周期已通过并关闭：7 突变红绿、focused/validate EXIT=0、full 304/0/0、真实 JDT LS 证据跑 3 轮 + 四态真机区分；[S5 证据](evidence/iteration-29/stop2-s5-real-evidence.txt)与[源快照](evidence/iteration-29/stop2-s5-real-run/)已入仓；零联网零进程回放与真实三能力任务已随停 3 S4 取证（[stop3-replay.txt](evidence/iteration-29/stop3-replay.txt) / [stop3-real-three-capabilities.txt](evidence/iteration-29/stop3-real-three-capabilities.txt)） |
| `stop3-assembly-and-verify.txt`、`stop3-consumer-design.md` | 停 3 S1–S3 装配面已交付：base 37 行、`--verify` keyless 30/37/0、fail-loud、SPI 4/4、四处消费面、candidate 双腿；S4 三证据档见上 |
| `closeout-gates.txt` | 未执行；新鲜门禁、全量、消费闭包、最终验收逐条对应 |

## 验收（证据 = 实际执行的命令与结果）

- [x] 四确认按本轮裁定落盘，已定方向与待裁 T 项分开；compiler API 备选和失败停止规则明确。
- [x] 停 0：具体 build 钉死，裸进程工程闭环获 [2026-10-29 用户限定通过并关闭](evidence/iteration-29/stop0-acceptance.txt)；JDT LS 路线确认、compiler API 备选不触发。协议偏离和诊断语义缺口保留；仅放行停 1 seam 契约，不等于产品验收通过。
- [x] 停 1：三能力 seam 契约、观察时点模型与 Provider 生命周期验收矩阵获 [2026-10-29 用户通过放行](evidence/iteration-29/stop1-acceptance.txt)；Definition 聚焦 17 项、10 项突变、full EXIT=0。不实现 Provider，不等于产品验收通过。
- [x] 停 2：Provider 真实生命周期获 [2026-10-31 用户通过放行](evidence/iteration-29/stop2-acceptance.txt)：≥3 次重复启停、忽略 TERM 后 KILL 升级与专属组复验、退出阶段 server 请求全应答（每轮 unanswered=0）、JDT LS 偏离清单 D1–D4 对照、四态真机区分与措辞纪律自检全部有据；7 突变必红→字节还原复绿、focused/validate EXIT=0、full 304/0/0（lsp/jdtls 177/0/0）。**QUIET_WINDOW 的区分证据来自夹具测试而非真机**——真机上该态是时序依赖、无法确定性触发，清洁文件实测为 PUBLISHED 空数组并如实记录（设计 §7 预案），不泛化不凑 QUIET_WINDOW；不等于产品验收通过。
- [ ] T0–T7 逐项裁定，最终承载类与锚点补齐，设计 / 边界先行；T0 与 T1–T3 方向已裁并随停 1 落定，T5 已裁并随停 2 落地，T4/T6/T7 未整体批准。
- [x] `verify-stop.sh` 在停 1 前验收：实现、22 测、11 突变和真实 full 证据已齐，用户已点名放行；见[验收记录](evidence/iteration-29/verify-stop-acceptance.txt)。
- [ ] 诊断携 JH 文件 / 项目 hash 观察锚；四态与非空诊断可区分，已知旧缓存与观测到的外部编辑 / 项目代变化不得包装为本次合格快照；不证明 LS 分析来源 / 完成，无 version 迟到通知等不可观测限制须披露。契约已随停 1 落定，Provider 面已随停 2 取证（四态真机区分 + 措辞纪律自检），Consumer 面已随停 3 S4 取证（措辞纪律双突变必红含「项目无错误」+ 真机三能力探针），模型在环待 S5。
- [ ] 定义 / 引用均以真实 Java 请求完成；错误符号、跨文件、空结果与不支持 / 未就绪的语义可验证。真机路径已随停 3 S4 取证（3 轮 definition → Calc.java@2:19、references → App.java@5:15 + Calc.java@2:19，REQUEST_RESPONSE）；错误符号 / 空结果语义由聚焦夹具测试承担（S2），模型在环闭环待 S5。
- [ ] 轮屏障消费与工具执行路径保持 R1/R2；后台通知不能绕过落账改上下文；回放零联网、零进程。R1 回放双腿已随停 3 S4 取证（LspReplayTest：重建零 LSP 进程、事件逐字节重放、live/replay 双腿全绿）。
- [ ] 进程权限、项目内写入、缓存 / 索引目录及默认启用规则有文档与真实观测；不把只读工具说成只读进程。
- [ ] 初始化失败、请求超时、取消、服务崩溃、scope 关闭均收敛；进程 / 线程 / pending 请求无遗留，索引目录冲突显式失败。Provider 面已随停 2 取证（真实 close 证据行 + 夹具 KILL 升级复验 + 聚焦超时 / 取消矩阵），产品（Consumer / 装配）面已随停 3 S4 取证（真机探针 3 轮 close 四要素 + 零残留；jlink 启用/负例两腿真实进程随 scope 关闭收敛 + pgrep 零残留）。
- [ ] CLI 与 Java 外部接入、组合行、ServiceLoader 双通道、`--verify`、consumer candidate 与设计同步齐全。已随停 3 S3/S4 取证（S3 装配档：SPI 4/4、四处消费面、candidate 双腿；S4 jlink 三态 verify 逐字命中）。
- [ ] 固定任务经真实模型 / LS 完成诊断 → 定位 → 受控编辑 → 再诊断闭环，记录相对纯文本搜索的具体收益和限制。
- [ ] 收口逐项勾验收、证据与测试统计口径核对，更新当前状态卡；发布仍另设验收和授权。
- [ ] 收口批挂账（用户点名，本批不混入）：`.agents/pits.md` 补通则「verbatim 捕获文件需 `-text !eol` + whitespace 豁免，SHA 对账为检测面」（出处 [T5 裁决](evidence/iteration-29/stop2-t5-ruling.txt)）；范围外旧措辞（02 树漏 skill、05 §9 旧「停 0 待裁」、根 README 构建计数）统一收。

## 修正

停 1 设计初审的同步完成定义 / reader 截点与溢出、空数组批次 version、文件删除表示及位置请求形状已落实；源码复审未报 Critical / Important，两处 Minor 测试漏检（跨项目根 Stale、REQUEST_RESPONSE 携 version）已补并以 M9/M10 检出。测试辅助 switch 另按规范补 fail-loud default；最终聚焦 17 项复绿。计数改用 Maven 模块汇总，原 XML 残留测量保留并澄清，JUnit/jqwik 同名类 surefire XML 互相覆盖已回填 AGENTS 测试计数口径条。上述是本批交付和协调者核验，用户停 1 批准见[验收记录](evidence/iteration-29/stop1-acceptance.txt)。一次性探针客户端已修正未实现注册假成功、缺诊断数组假空、shutdown 参数、合法磁盘 / 错误 buffer 分离和退出排水；macOS 未回收进程组 EPERM 的保守判定已补测试并回填 `.agents/pits.md`。最终客户端字节与真实运行 hash 相同；早期失败 / 拦截和冻结评审原文不改写。

停 2 独立审查（R3-1–R3-4）四项修复已落实并以 7 突变检出；S5 真实证据跑暴露两处探针自身缺陷已诚实修正并登记偏离：post-close 措辞「pid unknown (capture missed)」把无残留表述为捕获失败（违反同一措辞纪律）→ 改为「no live launcher child (residual-free)」；state D 立即 pull 预算 1s 在本机 warm handshake（≈1.0–1.5s）下使「initialize 未完成」前提失效 → 收窄 200ms 后确定命中 INITIALIZING（首轮输出保留为过程记录，不销毁）。S4 计数初版误标「全模块 177」已订正为全 reactor 304/0/0（lsp/jdtls 177/0/0）。用户停 2 批准见[验收记录](evidence/iteration-29/stop2-acceptance.txt)。

## 设计偏离

当前 JH 增量为已通过停 1 的 `lsp/lsp` Definition 与开发中的 `lsp/jdtls` Provider；无 Consumer / 装配。T1–T3 方向已获[用户终裁](evidence/iteration-29/stop0-acceptance.txt)并随停 1 落定，T5 已裁自有边界实现；这不改变 T4/T6/T7 未整体批准及产品验收未执行的状态。T1 改用 JH 观察时点 hash，不再承诺证明 LS 分析来源 / 完成；裁决史见 [ADR 0009](../../.agents/notes/0009-lsp-observation-anchor.md)。

停 2 草稿曾把截点写在窗前，与已裁停 1 契约冲突；现按原裁决恢复「同步 flush → 完整等待窗 → reader 发布锁下原子截取 → after 采样」。这是实现 / 草稿纠偏，不是重裁，不改冻结停 1 文件。独立审查还发现轮间编辑、坏数据假空、总期限、背压关闭及 Linux 包装 argv 问题；已有局部红绿不代表 Provider 整体验收通过。

首次探针观察到：初始化顺序偏离、shutdown `{}`、退出尾流请求未答；两次诊断无 version，修复后仍含 warning 而非真零。实际仅单 Maven 模块 + JPMS，不能覆盖一手研究提案的双 Maven 模块；项目 / 缓存写入、公共下载和宿主 JDK 自动发现均有证据。详见[冻结的停 0 偏离提案](evidence/iteration-29/stop0-review-packet.md#deviations-and-decisions)，不回写实验事实。

现行产品方向为“宽进严出”：接受已观察兼容形状并记录偏离，不将未知请求假回复为成功；观测偏离与尾流对照验收矩阵已随停 1 契约落定，停 2 Provider 按此清单对照验收（D1–D4 全勾 + 生命周期矩阵逐轮取证）。探针尾流未答可作为实验残余保留，但产品客户端须应答全部 server 请求（含退出阶段）——停 2 真机每轮 unanswered=0 已闭合；无 version / 未见空数组是观察能力缺口，不一概称违反 LSP。T2 主动宿主探测披露与 T3 生命周期硬验收已随停 2 取证，T4/T6/T7 不因上述批准而整体通过。JDT LS 路线确认，compiler API 备选本次不触发；将来若采用仍须范围重裁，不是静默降级。停 2 两处配方偏离（TimedOut 取 deadline==window 的等长下界、state D 探针预算收窄）已按「诚实记录」登记于[停 2 packet](evidence/iteration-29/stop2-review-packet.md) §7，不重裁。
