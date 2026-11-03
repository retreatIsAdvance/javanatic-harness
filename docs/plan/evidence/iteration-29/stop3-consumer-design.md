# 停 3 Consumer 设计 — lsp/tool 工具面 + 装配 + 验收

TL;DR（五行）
- 不变量增量：新增 `lsp/tool` Consumer 模块（三工具 `lsp_diagnostics` / `lsp_definition` / `lsp_references`，
  仅依赖 Definition `lsp/lsp`，不 import Provider）；`lsp/jdtls` 增 `JdtlsPlugin` 装配件（双通道 SPI）；
  base bundle 只增 `lsp-tool` 行，`lsp-jdtls` 行由组合者显式添加——缺省组合零 LSP 工具、零进程、零网络。
- 裁决点：结果文本措辞纪律是本停点硬路径——四态 + 异常到文本的逐字映射冻结进测试断言，
  突变含「改成『项目无错误』必红」；「拉取时点无诊断」与停 2 §8 模板逐字一致。
- 风险自报：本停点修改已验收的 `lsp/jdtls`（插件 + document 路径围栏），按设计增量声明、回归全量 + 突变，
  非静默改；`RenderIntent.LOCATIONS` 全仓尚无渲染消费点，结果以纯文本行呈现、意图先落位；
  真机 definition / references 返回形状是 T6 一手证据缺口，S4 探针补、不符如实记录不包装。
- 证据入口：停 2 [终裁](stop2-acceptance.txt)（停 3 四点预告）、[stop2-provider-design.md](stop2-provider-design.md)
  （措辞模板与生命周期）、[it28 停 3 装配证据](../iteration-28/stop3-assembly-and-verify.txt)、
  [.agents/pits.md](../../../.agents/pits.md) 行集纪律（:28）与双通道 SPI 检查单（:29）。
- 若只能改一处：结果文本措辞纪律的逐字映射 + 「项目无错误」突变必红——这是模型可见面的唯一闸，
  其余（装配、计数、回放）都是它的承载面。

---

## 1. 范围与依据

- 停 2 已通过并关闭（[终裁](stop2-acceptance.txt)），用户点名放行停 3：Tool Consumer + 装配 + 验收。
  停 3 评审重点（用户预告四点）：① 工具输出措辞纪律（`lsp_diagnostics` 结果文本逐字守「拉取时点无诊断」，
  突变含「改成『项目无错误』必红」）；② 装配面老三样（bundle 行 + 四处消费面 + 双通道 SPI 清单）；
  ③ `--verify` 计数与工具面双态（LSP 工具随 server 在场与否，沿 web_search 先例）；④ R1 回放（零 LS 进程重建）
  + 模型在环（真 key + 停 0 夹具，runbook 沿 it26/it27.1/it28 形态）。
- T4（[iteration-29.md](../../iteration-29.md) T 表）：逻辑拉取起步，后台推送模型面挂账；禁止后台通知未经落账
  直接改模型上下文；优先复用 `tool/call` / `tool/result`，需要额外日志事实须举证；不盲抄 MCP 两个 hook。
- T6：URI / 路径权限、位置编码、Location / LocationLink 实际返回形状、跨文件和依赖源码、无结果 / 未就绪区分、
  帧帽 / 输出帽 / deadline；版本漂移时不得包装为当前定位。
- T7：回放零联网、零进程；工具进入既有治理；真实 LS 与 keyless fixture 分开；未安装 / 未启用 LS 如何显式表示；
  回放不能为重建请求启动 LS 或重查源码。
- 三角色纪律（05 §10 编译期保障）：`lsp/tool` 的 module-info 只有 `requires io.javanatic.harness.lsp`
  （Definition），不出现 `requires io.javanatic.harness.lsp.jdtls`——import Provider 会编译失败。

## 2. 模块布局与装配形状

- 新模块 `lsp/tool`：artifactId `harness-lsp-tool`，JPMS `io.javanatic.harness.lsp.tool`，plugin id `lsp-tool`。
  依赖仅 `kernel/core`（Plugin / Scope / ServiceKey）+ `core/tools`（ToolDefinition / ValueSchema / 执行上下文）
  + `llm/llm`（AbortSignal 类型）+ `lsp/lsp`（Definition）。lsp 聚合增一个叶子（共 3 叶子）。
- `lsp/jdtls` 增装配件 `JdtlsPlugin`（plugin id `lsp-jdtls`）：Provider 模块自带 Plugin（沿 mcp/stdio、
  web/search-deepseek 先例）。读行配置（键白名单、坏值 fail loud）→ 构造 `JdtlsLaunch` → 校验 → 启动
  `JdtlsProvider` → `scope.provide(LspService.KEY, provider)` → `scope.onClose(provider::close)`。
  module-info 增 `provides io.javanatic.harness.kernel.plugin.Plugin with ...JdtlsPlugin`，
  resources 增 `META-INF/services/io.javanatic.harness.kernel.plugin.Plugin`（双通道缺一不可，pits.md :29）。
- `lsp/tool` 插件 `LspToolPlugin`：`scope.resolve(LspService.KEY).ifPresent(service -> 注册三工具)`
  （沿 WebToolPlugin :64-66 的 web_search 双态先例）。Provider 不在场 → 少三工具，无运行时降级、无报错。
- 工具注册：`ToolDefinition.of("lsp_diagnostics", …)`（GENERIC、需审批）；`lsp_definition` / `lsp_references`
  用记录构造器置 `RenderIntent.LOCATIONS`、`approvalExempt=false`（LSP 工具恒需审批——驱动可信外部进程 +
  读项目源码，不设豁免出口）。`requires Set.of("tools")`（无 system-prompt 段；信任前缀沿 web 先例的
  提示段本批不引入——LSP 结果是项目内事实，不是外部内容，B20 类比不适用）。

### 双态与行序

- base bundle 增两行：`lsp-jdtls` **禁用行**（`disabled: true`，沿 compaction 先例）+ `lsp-tool` 可配行。
  serverDir / configurationDir / projectRoot 是组合者提供的路径、无有意义缺省——禁用行不挂载任何实现，
  缺省组合 = 零 LSP 工具、零进程、零网络（B15 同口径）。
- 行序不变式：`lsp-jdtls` 行在 `lsp-tool` 之前（bundle 行序 = apply 序；Provider 先 provide，
  Consumer 后 resolve）。组合者用 `replace: true` 原地启用（07 §2 Replace 变体，位置保持）；
  改用 remove + include 会落到行尾 = 双态失效零工具（S4 真机负例证据——jdtls 进程 eager 启动，
  负例需真夹具目录，非仓内可复现）。serverDir 缺失或不存在 → 装配期 fail loud（B1 先例），
  不回退为「无工具」。

## 3. 行配置键（新外部输入 → 边界 B27）

| 行 | 键 | 缺省 | 校验 |
|---|---|---|---|
| lsp-jdtls | javaHome | `java.home` 系统属性（主动探测，披露句入治理摘要） | 路径存在 + bin/java（Windows 为 bin/java.exe）可执行（JdtlsLaunch 构造校验） |
| lsp-jdtls | serverDir | **无（必填）** | 存在；缺失 = fail loud |
| lsp-jdtls | configurationDir | **无（必填）** | 存在；缺失 = fail loud |
| lsp-jdtls | projectRoot | **无（必填）** | 存在；缺失 = fail loud |
| lsp-jdtls | indexDir | `<homeDir>/index` | 目录可建 |
| lsp-jdtls | homeDir | `java.io.tmpdir/jh-lsp-<8位>`（临时数据目录，披露句入治理摘要） | 目录可建 |
| lsp-jdtls | mavenUserSettings / mavenGlobalSettings | 无 | 可选；给出则存在 |
| lsp-tool | timeoutSeconds | 30 | 正整数 |
| lsp-tool | waitSeconds | 15 | 正整数且 ≤ timeoutSeconds |
| lsp-tool | maxDiagnostics | 50 | 正整数 |
| lsp-tool | maxLocations | 50 | 正整数 |
| lsp-tool | maxOutputBytes | 262144 | 正整数 |

- 键白名单解析 + 坏值 fail loud（沿 `DeepSeekSearchConfig.parse` 先例）。未知键拒绝（fail loud，不静默忽略）。
- `homeDir` 缺省落在临时目录：LS 缓存 / 索引不触碰用户真实 HOME（B23 隐私面）；临时目录生命周期随 OS，
  文档披露「重启后索引重建」。

## 4. 工具面：schema、围栏与措辞纪律

### 4.1 三工具 schema

- `lsp_diagnostics`：`document`（Str，必填；项目内路径）+ 可选 `timeoutSeconds` / `waitSeconds`（正整数，
  缺省取行配置；waitSeconds ≤ timeoutSeconds）。GENERIC。
- `lsp_definition` / `lsp_references`：`document` + `line` + `column`（非负整数）。**位置编码 0 基 UTF-16**
  （LspPosition 契约），描述逐字声明。LOCATIONS。
- 工具描述（模型可见）写明：结果是「JH 拉取时点观察」，不含「分析完成 / 项目无错误」类承诺。

### 4.2 document 路径围栏（T6 URI / 路径权限）

- **围栏在 Provider 层**（`lsp/jdtls` 增量）：projectRoot 知识只在 Provider 有；Consumer 重复校验既弱又漂移。
  `JdtlsProvider` 在观察入口（didOpen / diagnostics / definition / references）校验 document URI
  规范化后落在 projectRoot 内，越界抛 `LspException(LspErrorCode.UNSUPPORTED, "document outside project root")`。
  工具把该异常映射为 error 结果文本。
- 这是对已验收模块的修改：按设计增量声明，配突变（摘除围栏必红——越界 document 仍被读取即红）+ 回归全量。
- 不承诺 OS 级隔离：Provider 进程本身是用户全权 LS（B23），围栏只约束 JH 侧「把哪些文件交给 LS」的面，
  不约束 LS 自发扫描（它本来就全权）。

### 4.3 结果文本措辞纪律（逐字模板，冻结进测试断言）

诊断工具（lsp_diagnostics）四态 + 异常 → 文本：

| 结果 | 逐字模板 |
|---|---|
| Observed 非空 | `拉取成功（hash <p8>，项目代 <generation>，等待窗 <elapsed>s）；诊断 <n> 条：` + 每条 `  <rel>:<line>:<col> [<SEVERITY>] <message>` （code/source 在场则附 `(<code>)`）；条数帽后追加 `…（截断）` |
| Observed 空（QUIET_WINDOW） | 「拉取成功（hash a…a）、项目代稳定（前后锚相同）、完整等待窗（elapsed ≥ required），拉取时点无诊断。」——与停 2 §8 模板逐字一致 |
| Observed 空（PUBLISHED 空数组） | `拉取成功（hash <p8>）；LS 发布空诊断批次（<n> 条=0）。` 不得称「项目无错误」 |
| NotReady | `未就绪（<INITIALIZING|SYNCHRONIZING|CLOSED>）——不是诊断结果。` |
| Stale | `观察过期（前后锚不同：<before p8> → <after p8>）——不包装为本次观察结果。` |
| TimedOut | `超时（<timeout>s）——不是无诊断。` |
| LspException | `error`：`LSP 错误（<LspErrorCode 字面>）：<message>` |

定义 / 引用工具：

| 结果 | 逐字模板 |
|---|---|
| Observed 非空 | `拉取成功（hash <p8>）；位置 <n> 条：` + 每行 `  <rel>:<line>:<col>–<endLine>:<endCol>`；条数帽后 `…（截断）` |
| Observed 空 | `拉取成功（hash <p8>）；本次请求返回 0 个位置。` 不得称「符号不存在 / 无定义」 |
| 其余态 / 异常 | 同上表逐字（NotReady / Stale / TimedOut / error） |

- 锚（项目 hash 前 8 位）恒在首行——观察时点随结果文本进入 `tool/result` content：模型可见 = 已落账（T4）。
- 禁令面（沿停 2 口径，本停点细化）：结果文本模板所在主源码（含注释/javadoc）grep
  `项目无错误|分析完成|诊断已同步|真零|符号不存在` 0 命中；测试侧仅允许 doesNotContain
  执法断言出现字面（执法面，非输出面）。停 2 的「测试 0 命中」在工具面不成立——执法断言
  必须以禁词字面为对照，此细化见 §9。
- **突变**：把 QUIET_WINDOW 模板换成「项目无错误」或把空数组文案换成「无诊断完成」→ 对应聚焦测试必红。

### 4.4 结果预算（T6 输出帽）

- 条数帽：maxDiagnostics / maxLocations（截断置 `truncated` 语义，追加截断标记行）。
- 字节帽：maxOutputBytes，UTF-8 完整字符边界截断 + `" (output truncated)"`（B21 同口径；锚首行与
  条数帽标记不参与截断）。超限时锚首行保留——观察事实不可被截断吞掉。
- deadline：timeoutSeconds 是 LspQuery 总期限（Provider 内覆盖入队 / flush / 响应 / 窗口）；工具层不另设
  二次超时（executor 无独立超时 stage，取消走 context.signal() → AbortSignal 原样传播，停 1 契约）。

## 5. 落账、轮屏障与 R1（T4 / T7）

- **不新增日志事件**：`tool/call`（arguments 含 document / line / column）+ `tool/result`（content 含锚与
  结果文本）复用既有 codec（session/persistence-jsonl CoreCodecs :124-146）。举证：锚文本进 content =
  模型可见必已落账；回放不需要任何 codec 扩展。
- 轮屏障：工具即按需调用（同 web_fetch）；不引入新 barrier、不缓存跨轮结果。Provider 内部同步 flush →
  完整等待窗 → 发布锁下原子截取（停 1 契约、停 2 实现）即屏障语义；Consumer 每次调用构造新 LspQuery。
- R1：零改动回放路径——llm/replay 重放 tool/call + tool/result，不启动 LS、不重查源码（既有保证 +
  WebReplayTest :106-115 先例）。新增 LSP 版回放测试：夹具会话（FakeLspService 实现 Definition 接口）
  真跑落账 → keyless 回放 → 断言零 LSP 进程 + tool/result content 字节一致。
- 真实 LS 与 keyless fixture 分开：聚焦测试用 FakeLspService（lsp/tool 测试内实现 Definition 接口，
  不需要真实进程）；真实任务验证走 S4 探针与 S5 模型在环。

## 6. 装配面（评审重点 ②）

- bundle/base bundle.yml：+2 行——`lsp-jdtls`（禁用，`disabled: true`）+ `lsp-tool`（可配）→
  **37 行（24 可配 + 13 无配）**（§2 双态）；`bundle/base/pom.xml` 测试面加 lsp 两坐标
  （base 测试模块路径需见两行插件）。
- 四处消费面（pits.md :28 行集纪律，缺一即半装配）：
  - `dist/jh/pom.xml`：jlink 模块清单 + harness-lsp-tool（与 lsp/jdtls 一起，行集纪律同提交）；
  - `examples/headless/pom.xml`：叠加依赖 + harness-lsp-tool；
  - `integration/consumer-sample/pom.xml`：candidate-closure profile + lsp 三坐标
    （harness-lsp-lsp / harness-lsp-jdtls / harness-lsp-tool）→ 候选闭包 11 → **14 坐标**；
  - `docs/embedding.md` §2：候选腿计数 11 → **14**。
- 双通道 SPI 检查单（pits.md :29）：`JdtlsPlugin` 与 `LspToolPlugin` 各一对
  `module-info provides` + `META-INF/services/io.javanatic.harness.kernel.plugin.Plugin`，逐项核对。
- `--verify` 计数与治理摘要：`HeadlessMain.governanceSummary` 增 `lsp:` 行逻辑（沿 mcpText/webText 先例）——
  缺省组合（lsp-jdtls 禁用行不挂载）`lsp: lsp-tool; tools=0`（Consumer 在场、
  Provider 缺席 = 零工具，不猜原因）；组合者 `replace: true` 启用 jdtls 行时
  `lsp: lsp-jdtls + lsp-tool; tools=lsp_diagnostics,lsp_definition,lsp_references (approval-required)`；
  两行都被 profile 移除时 `lsp: -`。
  rows / discovered / unreferenced 由既有 counting 自动产出（it28 口径：rows = mounted 行、
  discovered = 全行发现的插件）：缺省 keyless 档 30 rows / 37 discovered / 0 unreferenced；
  replace 启用 jdtls 行 → 31 / 37 / 0（keyed 32 / 37）。`verify-consumer.sh candidate` 复跑。

## 7. 验收与证据面（评审重点 ③④）

| 步 | 内容 | 证据 |
|---|---|---|
| S1 | `lsp/jdtls` 装配件：JdtlsPlugin 配置解析 / 白名单 / 坏值 fail loud / 缺省探测披露 / onClose 关进程 / 双通道 | 聚焦 + 突变 + validate |
| S2 | `lsp/tool`：三工具 schema / 围栏 / 措辞逐字断言（含「项目无错误」突变必红）/ 截断 / 双态 / LOCATIONS | 聚焦 + 突变 |
| S3 | 装配面：bundle 行 + 四处消费面 + SPI 清单 + `--verify` 两档计数 + consumer candidate | stop3-assembly 证据档 |
| S4 | R1 回放零进程证据 + jlink 镜像真跑 + `--verify` 启用档（replace 启用 jdtls 行 31/37/0 + 工具面三态）与行序负例（remove+include 落行尾 → 双态失效零工具）+ 真实 LS 三能力探针（definition / references 真机取证，沿停 2 探针先例：钉定 1.63.0 / 停 0 夹具，逐轮启停 + close 证据行） | stop3-replay / stop3-jlink / stop3-real-three-capabilities |
| S5 | 模型在环 runbook（用户真 key 手动跑，沿 it28 stop3-model-in-loop 结构：命令 + EXIT + session id + stats + 工具账本逐字 + 治理面 + 凭据结构检查）+ review packet（五行 TL;DR）→ 停下等点名 | stop3-model-in-loop + stop3-review-packet.md |

- 模型在环固定任务（停 0 夹具）：诊断（真实类型错误）→ 定位（definition 指向 Calc.twice）→ 受控编辑 →
  再诊断闭环；记录相对纯文本搜索的具体收益与限制。
- 真机 definition / references 返回形状（Location vs LocationLink、跨文件）如实取证：与 Provider 归一不符
  即按停 2 口径记录为观察能力缺口并重裁，不包装为已完成。

## 8. 边界登记（本停点新增）

- **B27 LSP Provider 配置输入面**：组合者显式配置信任面；键白名单、未知键 / 坏值 fail loud；
  serverDir / configurationDir / projectRoot 必填存在；javaHome 缺省探测披露；homeDir / indexDir 缺省
  临时目录披露；缺省组合零行零进程；写入 / 下载仍是 LS 进程行为（B23）。
- **B28 LSP 工具输入围栏与结果措辞 / 预算**：document 必须规范化后落在 projectRoot 内（Provider 层围栏，
  越界 UNSUPPORTED）；line / column 非负；四态 + 异常到文本的逐字措辞纪律（空结果只称「拉取时点无诊断 /
  本次请求返回 0 个位置」）；条数帽 + 字节帽 + truncated 标记；恒需审批。

## 9. 偏离与诚实记录预案

- **S3 装配机制修正（本设计 §2/§6 原稿修正，非停 2 裁决改动）**：原稿写「lsp-jdtls 不进 base、
  组合者用 `before: lsp-tool` 锚点插入；缺省 36 discovered」。执行期证伪——`PluginLoader.discover()`
  是模块路径全域 ServiceLoader，双向校验要求「每个被发现的插件都被某行引用（含 disabled 行）」；
  jdtls 坐标在 headless 测试路径 / consumer 候选闭包 / jh 镜像三条腿都在场，缺省组合必然
  37 discovered，无行的裸在场即 boot fail。修正为 web_search 先例结构：base 携带
  `lsp-jdtls` 禁用行（`disabled: true`，compaction 先例），组合者 `replace: true` 原地启用
  （Replace 位置保持 = 行序不变式由 base 行位承载）；缺省 30 rows / 37 discovered / 0 unreferenced，
  启用后 31 / 37 / 0。缺省组合仍零进程零工具（B15 口径不变）。负例证据（remove+include 落行尾 =
  双态失效零工具）因 jdtls 进程 eager 启动需真夹具目录，移至 S4 真机跑。
- 修改已验收模块（lsp/jdtls：JdtlsPlugin + 围栏）是设计增量，不是重裁：停 2 冻结的 Provider 核心行为
  （观察算法、生命周期、关闭序列）零改动；回归全量 + 突变覆盖增量。
- 修改已验收模块（core/tools）：新增只读器 `ToolArgs.readOptionalLong`（返回 `OptionalLong`），
  纯增加、零既有行为改动——工具级可选参数（timeoutSeconds / waitSeconds）需要「缺失 = 取行配置、
  在场 = 校验并覆盖」的读法，既有读取器只有 `readLong`（缺失抛异常）。配聚焦测试 + 突变
  （换成恒抛 / 恒空必红）。不重裁、不落新 B：B28 已覆盖工具可选参数语义。
- 禁令面口径细化（偏离停 2 字面口径，不放松禁令本身）：停 2 记录「主源码 / 测试 0 命中」，
  其背景是 Provider 段尚无工具面、无测试需要引用禁词。本停点工具面出现后，测试侧必须以
  doesNotContain 执法断言钉死禁词——字面必然出现在测试源码。细化后的口径：结果文本模板所在
  主源码（含注释/javadoc）0 命中；测试侧仅允许执法断言字面。禁令不放松：模板中出现禁词仍必红
  （M1–M3 突变）。
- LOCATIONS 意图先落位：全仓无 LOCATIONS 渲染消费点（llm/openai-compat RequestBody 只看 content 字符串），
  本批结果以纯文本行呈现；UI 切片实现点击渲染时回填，不为此动 wire 层。
- 若真机三能力探针暴露 Location / LocationLink 形状缺口：如实记录（停 0 / 停 2 口径），不静默适配。
