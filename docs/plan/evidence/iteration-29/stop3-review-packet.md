# it29 停 3 review packet：`lsp/tool` 消费面、装配与三能力验收

- 基线：`92b2d81`（停 3 S1–S3 已提交）；S4 已提交 `3934f7f`（产品验收三腿批）
  与 `1e09873`（pending-push 状态记录）；S5（runbook + 本 packet）工作区**未提交**
- 范围：停 3 全停点——S1 `lsp/jdtls` `JdtlsPlugin` + `JdtlsPluginConfig`（B27 八键）；
  S2 新模块 `lsp/tool`（`LspToolPlugin` 三工具 + `LspToolOutput` + B28 五键）；
  S3 装配面（base 两行 + 四处消费面 + `--verify` lsp 摘要）；S4 产品验收三腿
  （`LspReplayTest`、jlink 三态 verify、`JdtlsThreeCapabilityProbe` 真机三能力）+
  三份证据档 + iteration-29 / current-state 同步；S5 模型在环 runbook
- 工作树：S1–S4 均已提交；未提交仅 `stop3-model-in-loop-runbook.md` 与本 packet
- 依赖：**无新增第三方坐标**；`lsp/tool/pom.xml` 只依赖仓内既有模块
- 评审重点（用户预告）：**① 工具输出的措辞纪律（`lsp_diagnostics` 结果文本逐字
  守「拉取时点无诊断」，突变含「改成『项目无错误』必红」）**；
  **② 装配面老三样（bundle 行 + 四处消费面 + 双通道 SPI 清单）**；
  **③ `--verify` 计数与工具面双态（LSP 工具随 server 在场与否，沿 web_search
  先例）**；**④ R1 回放（零 LS 进程重建）+ 模型在环（真 key + 停 0 夹具）**

## 0. 五行 TL;DR

1. **措辞纪律是逐字模板 + 三条腿断言**（不变量增量）：`LspToolOutput` 四态模板
   钉死（空批次「拉取时点无诊断」/ 位置「本次请求返回 0 个位置」/ 非空「诊断 n 条」
   / Stale / TimedOut），由 `LspToolOutputTest`（14 项）、`LspToolPluginTest`（8 项）
   与端到端 `LspReplayTest` 期望文本三条腿共同锁定；用户点名突变「项目无错误」→
   22 项中 3 红必红，字节还原（SHA 98f2bb12…）复绿。
2. **装配面老三样齐全**（裁决点）：base 37 行（`lsp-jdtls` 禁用行 + `lsp-tool`
   行，24 可配 + 13 无配）、四处消费面（dist/jh jlink 三行、headless pom 三坐标、
   consumer candidate 三坐标、embedding 计数 11→14）、双通道 SPI 4/4——S3 装配档
   逐项取证，jlink 镜像内三模块实测生效。
3. **`--verify` 双态是行序结构、不是配置开关**（风险自报）：keyless 30/37/0
   零工具；replace 启用 31/37/0 三工具（恒 approval-required）；remove+include
   行序负例 31/37/0 零工具且 `lsp:` 行插件序翻转为「lsp-tool + lsp-jdtls」——
   双态由 Consumer 在场 + Provider 缺席承载（沿 web_search 先例，不猜原因）。
   LS 仍以用户全权进程运行、不入沙箱（B23 不变），治理在工具调用面。
4. **R1 回放零 LS 进程重建 + 真机三能力 3 轮全过**（证据入口）：回放双腿事件
   逐字节重放、重建腿零 service 调用；真机探针 3 轮启停 diagnostics / definition
   （Calc.java@2:19）/ references（App.java@5:15 + Calc.java@2:19）REQUEST_RESPONSE
   取证，每轮 close 四要素 + 零残留——[stop3-replay](stop3-replay.txt) /
   [stop3-jlink](stop3-jlink.txt) / [stop3-real-three-capabilities](stop3-real-three-capabilities.txt)。
5. **若只能改一处：`LspToolOutput` 逐字模板**——它是「拉取时点无诊断」纪律的
   唯一承载点，装配计数、工具双态、回放期望文本全部从它读出语义；改它任何
   一个字，三条腿至少一条必红。

## 1. 评审重点 ①：工具输出措辞纪律

### 1.1 四态模板（`lsp/tool/.../LspToolOutput.java`）

| 结果 | 逐字模板 |
|---|---|
| 非空诊断 | `拉取成功（hash <p8>，项目代 <g>，等待窗 <t>s）；诊断 <n> 条：` + 每行 `  <rel>:<行>:<列> [<SEVERITY>] <message> (code)`；锚首行恒完整、不参与截断 |
| QUIET_WINDOW 空 | `拉取成功（hash …）、项目代稳定（前后锚相同）、完整等待窗（elapsed Xs ≥ required Ys），拉取时点无诊断。` |
| PUBLISHED 空数组 | `拉取成功（hash …）；LS 发布空诊断批次（0 条）。` |
| 位置空 | `拉取成功（hash …）；本次请求返回 0 个位置。` |
| Stale / TimedOut | `观察过期（前后锚不同：… → …）——不包装为本次观察结果。` / `超时（Xs）——不是无诊断。` |

禁词面：「项目无错误」「没有错误」等包装措辞不得出现——**不是注释承诺，
是断言**：`LspToolOutputTest` 14 项逐字断言（含禁词面断言）、
`LspToolPluginTest` 8 项渲染断言、`LspReplayTest` 端到端期望文本，
三条腿任一改写措辞必红。预算帽（条数 / 字节 UTF-8 完整字符边界、标记不计入）
在 render 层，锚首行恒保留。

### 1.2 双突变取证（破坏必红 → 字节还原复绿）

- **M1** 非空 header「拉取成功」→「拉取完成」：`LspReplayTest` 1 红
  （expected 拉取成功 / but was 拉取完成）。
- **M2（用户点名「改成『项目无错误』必红」）** QUIET_WINDOW 模板
  「拉取时点无诊断」→「项目无错误」：`LspToolOutputTest` 2 红 +
  `LspToolPluginTest` 1 红（22 项中 3 红）。
- 还原：`cp` 备份逐字节、SHA `98f2bb12741376542bcadd04c667c15de4c4fb63` 双份
  一致，复跑 22/0/0/0 复绿。日志：`/tmp/it29-lsp-replay-mut.log`、
  `/tmp/it29-lsp-tool-mut-red.log`、`/tmp/it29-lsp-tool-mut-regreen.log`。

若错靠什么发现：模板与三处断言是同一字符串的三个采样点；任何一层改写措辞，
至少一条腿红——S4 实测两条突变均红，纪律可执行。

## 2. 评审重点 ②：装配面老三样（S3 已交付，本停点复核）

1. **bundle 行**：`bundle/base/.../bundle.yml` 37 行（24 可配 + 13 无配），
   `lsp-jdtls` 禁用行在 `lsp-tool` 之前（Provider 先 apply）。缺省组合
   30/37/0 零进程零工具（B15 口径）；禁用行是缺省组合可 boot 的承载——
   摘除即装配期坏配置 fail-loud（S3 突变 B 已证：boot 抛
   `config missing required 'serverDir'`）。
2. **四处消费面**（行集变化同提交同步）：dist/jh jlink `--add-modules` 三行；
   examples/headless pom 三坐标；integration/consumer-sample candidate-closure
   三坐标（11→14）；docs/embedding.md §2 计数与说明。S4 jlink 腿实测镜像内
   三模块生效（§3）。
3. **双通道 SPI 4/4**：`lsp/jdtls` 与 `lsp/tool` 均 `module-info provides` +
   `META-INF/services` 同名注册（S3 装配档 §六核对）。

为什么是这个形状：`lsp-jdtls` 以禁用行进 base 是 web_search 的 keyless 禁用
同口径——serverDir / configurationDir / projectRoot 无有意义缺省，缺省组合
零 LSP 进程；组合者 `replace: true` 原地启用（Replace 位置保持 = 行序不变式
由 base 行位承载）。若错靠什么发现：`--verify` 计数（30↔31）与 lsp 摘要行
双态（tools=0↔3）在三条 jlink 腿逐字断言。

## 3. 评审重点 ③：`--verify` 计数与工具面双态（jlink 三态真机）

镜像 `dist/jh/target/jlink-image/bin/jh`（S4 重建 EXIT=0），三腿均
`JDK_JAVA_OPTIONS=-Duser.home=<隔离>` + pgrep 前后零 launcher 进程：

| 腿 | composition | lsp 摘要行（逐字） | EXIT |
|---|---|---|---|
| keyless | 30 rows, 37 discovered, 0 unreferenced | `lsp: lsp-tool; tools=0` | 0 |
| 启用档（replace:true） | 31 rows, 37 discovered, 0 unreferenced | `lsp: lsp-jdtls + lsp-tool; tools=lsp_definition,lsp_diagnostics,lsp_references (approval-required)` | 0 |
| 行序负例（remove+include 落行尾） | 31 rows, 37 discovered, 0 unreferenced | `lsp: lsp-tool + lsp-jdtls; tools=0` | 0 |

- 启用档：真实拉起钉定 LS，scope 关闭收敛（close 证据行 exit=0、unanswered=0、
  pending=0、ioThreadsStopped=true；stdoutEof=false 系 close 距启动 4s、perl
  包装 stdout 未及 EOF，如实记录）。
- 行序负例：`lsp:` 行插件序翻转是负例的直接证据——两行都挂载（31 rows）、
  lsp-tool 先 apply 时 LspService 尚未提供 → 零工具；lsp-jdtls 行仍在行尾
  正常 apply（真实拉起 LS 进程），不是「行丢失」。
- 双态机制：`LspToolPlugin` 走 `scope.resolve(LspService.KEY)`（Consumer 在场、
  Provider 缺席 = 零工具，不猜原因）——与 web_search 缺 key 不注册同一结构；
  工具恒 `approval-required` 声明，AUTO 档放行面由审批插件承载。

若错靠什么发现：keyless 计数由 `HeadlessVerifyTest` 逐字钉死（30/37/0 +
tools=0）；启用档与负例由本停点 jlink 真机腿逐字断言；任何行序 / 行集改动
必在某一腿红掉。

## 4. 评审重点 ④：R1 回放 + 真机三能力 + 模型在环

### 4.1 R1 回放（`LspReplayTest`，1 条两腿；headless 84→85）

- live 腿：FakeLsp（implements Plugin，只接线 diagnostics；definition /
  references 抛 UnsupportedOperationException）+ 两轮脚本，断言
  `fake.calls()==1`、结果文本逐字等于期望（含「拉取成功（hash bbbbbbbb，
  项目代 3，等待窗 16.0s）；诊断 1 条」）、事件含 tool/call+tool/result、
  工具名 containsExactly(`lsp_diagnostics`)、无 lsp/ 类型事件。
- 回放腿：全新 FakeLsp + 空脚本，内容逐字节相等、事件
  containsExactlyElementsOf(liveEvents)、`replayLsp.calls()==0` ——
  零 LS 进程、零联网重建。
- 诚实记录：期望文本初稿等待窗 15.0s 被红掉——`LspObservation` 记录序为
  (before, after, requiredWait, elapsedWait, …)，渲染取 elapsedWait，
  订正 16.0s 复绿。

### 4.2 真机三能力（`JdtlsThreeCapabilityProbe` + `integration/verify-stop3-real.sh`）

钉定 1.63.0 / 停 0 夹具，3 轮启停（冷 1 + 暖 2），每轮：
- diagnostics：PUBLISHED 非空（warning-only code 268435844，与停 2 S5 A2
  同源复现）；
- definition（App.java@5:15）：REQUEST_RESPONSE 唯一位置 → Calc.java@2:19；
- references（同位置）：REQUEST_RESPONSE 2 位置 = Calc.java@2:19（声明，
  includeDeclaration）+ App.java@5:15（调用）；
- close 证据行四要素（exit=0 / unanswered=0 / pending=0 / ioThreadsStopped）
  + post-close 零残留；pgid==pid（专属组）。
`verdict: ALL ASSERTIONS PASSED`，PROBE_EXIT=0。探针为 test 域 main 类
（类名非 *Test），模块测试计数不受影响。

### 4.3 模型在环（真 key，待用户跑）

[runbook](stop3-model-in-loop-runbook.md) 已出（沿 it26/it27.1/it28 形态）：
Run A `lsp_diagnostics`（warning-only 夹具，断言结果文本逐字措辞纪律）、
Run B `lsp_definition` + `lsp_references`（断言 Calc.java@2:19 与双位置）、
§四 治理面与凭据结构检查（Authorization / x-api-key 计数为 0，key 值禁入
对话）。**用户真 key 手动跑，回贴清单见 runbook 末节。**

## 5. 验证证据

| 面 | 结果 |
|---|---|
| LspReplayTest 聚焦 | 1/0/0/0 EXIT=0（live+replay 双腿） |
| lsp/tool 聚焦（突变复绿） | 22/0/0/0 EXIT=0（LspToolOutputTest 14 + LspToolPluginTest 8） |
| headless 全量 | 85/0/0/0（停 3 前 84 + 回放 1） |
| 全 reactor（`-pl bundle/base,examples/headless,lsp/tool,lsp/jdtls -am test`） | BUILD SUCCESS；32/85/188/28/32 全 0/0/0 |
| `mvn -q validate` | EXIT=0 |
| jlink 三态 verify | 三腿均 EXIT=0 + pgrep 零残留 |
| 真机三能力探针 | ALL ASSERTIONS PASSED（3 轮） |
| consumer candidate 双腿 | S3 已过（4 负例，226s；S3 装配档 §五） |

## 6. 偏离、裁决点与残余风险

1. **行序负例输出强于设计预告**：`lsp:` 摘要行插件序翻转为
   「lsp-tool + lsp-jdtls」——设计 §7 预告「31 rows + tools=0」，实跑还暴露
   行序本身；证据如实记录，不按预告改写。
2. **启用档 stdoutEof=false**：close 距启动仅 4s，perl 包装 stdout 未及 EOF；
   exit=0 / unanswered=0 / ioThreadsStopped=true + pgrep 零残留，收尾证据
   完整，已在 jlink 证据档诚实记录。
3. **QUIET_WINDOW 区分证据来自夹具测试而非真机**（沿用停 2 口径）：真机
   warning-only 批次（code 268435844）与停 2 S5 A2 同源，措辞纪律由夹具
   三腿锁定。
4. **Maven 假红一次 + 假绿一次**（§6.4 初稿误写「假红两次」，收口批订正）：
   ① 突变首跑 cwd 漂移致 "Could not find the selected project in the reactor"
   ——假红（测试未跑、非突变之力）；② 全 reactor 首跑 echo 遮蔽 Maven 失败 +
   陈旧 surefire XML 呈貌似计数——假绿（退出码遮蔽家族第三次现形，读日志自捕、
   钉根重跑 BUILD SUCCESS）。均已记入 replay 证据档 §四-b，红绿依据均为钉根后
   的运行。
5. **工具序**：启用档工具按名排序输出（definition, diagnostics, references），
   与设计预告的三工具集合一致，顺序以实测输出为准。
6. **B27/B28 边界登记留收口批**：boundary-register 现止于 B26（其行已注明
   「装配面（配置键 / ServiceLoader 行）出现时另行登记」）——S4 未提前写入，
   收口批统一登记。
7. **无中途改道**：全部工作落在已批准停 3 清单内；无新增第三方依赖、未改
   `.java-version`、未触碰凭据、未 push（9 个待推提交，等点名）。
8. **范围外旧措辞挂账**（用户点名，留收口批）：02 tree 缺 skill、05 §9 旧
   「停 0 待裁」、root README build count——本停点未混入。

## 7. 停点状态

- S1–S4 已提交（`d9a37ec` / `92b2d81` / `3934f7f` / `1e09873`，未 push）；
  S5 runbook 与本 packet 未提交。
- 评审重点索引：① §1；② §2；③ §3；④ §4；验证 §5；偏离与残余风险 §6。

## 8. 评审处置（收口批，2026-10-31）

用户对停 3 的裁决：[总判：通过放行](stop3-acceptance.txt)（冻结原文）——S4 三腿
独立核验属实（含「假绿自捕…处置正确 ✅ 如实」），S5 runbook 用户已用真 key 亲自
执行完毕：Run A 全过、Run B definition 过 + references 三次 TimedOut（模型逐字用
四态措辞「超时（30s）——不是无诊断」）、Run C（评审方追加的分类探针）全过——
Run B 超时分类为多查询会话首查索引暖机 / 争用，非产品缺陷；§四 Authorization=0 /
x-api-key=0；fs_read "path escapes workspace root" 为夹具拓扑产物（LSP 项目根在
fs workspace 根之外）。收口批按裁决执行：

1. [模型在环结果落盘](stop3-model-in-loop.txt)（Run A/B/C 实录 + 偏离分类 + §四
   计数；Run C 标注「评审方追加的分类探针」）；
2. iteration-29 验收全勾（模型在环项有据：诊断 → 定位闭环经真实模型 + 真实 LS
   完成；受控编辑 → 再诊断段不在本迭代范围、未执行，不凑证据）；边界表 B27/B28
   登记；范围外旧措辞统一收（02 目录树补 skill、lsp 子树实况与 bundle 错行；
   05 §8.1/§9 旧「产品验收未执行」；根 README 构建计数）；提交（不 push）；
3. it29 已关账 = 0.3 四站全落（Skills / MCP / Web / LSP）；只剩 push 与
   「0.3.0 发布验收」的独立对话。

本 packet 冻结保留当时提案（§6.4 假红 / 假绿订正除外，为收口批如实修正）。
