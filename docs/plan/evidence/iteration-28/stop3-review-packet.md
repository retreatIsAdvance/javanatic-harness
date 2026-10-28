# it28 停 3 review packet：`web/tool` 消费面、HTML 提取、信任前缀与装配验收

- 基线：`3db2dd2`（停 2 已提交）；本停点工作区**未提交**（按停点要求）
- 范围：`web/tool` 新模块（5 主源码 + 5 测试类 + `module-info` + 服务清单）；
  `WebFetchResult` **停 1 契约增补**（`contentType`）；`bundle/base` 三行；
  headless `web:` 摘要 + `WebReplayTest`；五处同步面；设计文档 02/03/05/07/12、
  `AGENTS.md`、`current-state.md`、`boundary-register.md`、`embedding.md`；
  notes 0007/0008；本停点 7 份证据档
- 工作树：25 个已跟踪修改 + `web/tool/`（13 文件，含 5 测试类）、
  `WebReplayTest.java`、7 份 `stop3-*.txt`、2 篇 `.agents/notes/`（均未跟踪）
- 依赖：**无新增第三方坐标**；`web/tool/pom.xml` 只依赖仓内 7 个既有模块
- 评审重点（用户预告）：**① HTML 提取器矩阵与信任前缀突变**；
  **② 工具面双态（`web_search` 缺 key 不注册 / `web_fetch` 恒注册，
  HostileInstructions 11→12/13）**；**③ 256 KiB 输出帽的 UTF-8 边界**；
  **④ 装配三行 + 五处同步面（AGENTS 双通道检查单）**；
  **⑤ R1 回放 + 模型在环（真实 key）**

## 0. 五行 TL;DR

1. **HTML 提取是显式矩阵 + 真实脏样本**：10 条用例覆盖 script/style/noscript/
   template/注释/DOCTYPE/CDATA（大小写不敏感）、引号内 `>`、未闭合标签、块级边界、
   实体、空白、深层嵌套、源码帽 fail-soft，以及一份真实脏 HTML 样本；
   去 script/style 的突变 M1 必红。
2. **信任前缀单源双面**：`EXTERNAL_WEB_CONTENT_NOTICE` 同时是两工具成功结果首行与
   `PromptSection.Static(70)` 的开头；改文案的突变 M2 令 2 个测试类共 8 条红，
   改优先级 M5 令 2 条红（65/75 哨兵）。
3. **工具面双态是结构性的**：`web_fetch` 走 `scope.require`（缺 provider 装配期
   fail loud），`web_search` 走 `resolve + filter(available) + ifPresent`；
   keyless 29 行/12 工具，keyed 31 行/13 工具，discovered 恒 35。M4 删过滤必红。
4. **输出帽按码点计 UTF-8 字节**：256 KiB 帽在 3/4 字节字符边界整字取舍，
   标记不计入上限，切中时头部 `Truncated:` 同步翻转；M3（按码点计 1 字节）令
   4 条红——it27 的 M4 老坑未重犯。
5. **验证**：`web/tool` 37、聚焦 assembly 腿 32 + 83、全量 **974 tests / 0 fail /
   31 skipped**、`validate` EXIT=0、consumer candidate EXIT=0（4 负例）、
   jlink 双态 verify + 6 条真实网络用例 + 两工具模型在环均通过。**未 commit，
   iteration-28 停 3 行仍 ☐。**

## 1. 评审重点 ①：HTML 提取矩阵与信任前缀

### 1.1 HTML 提取（`HtmlTextExtractorTest`，10 条）

| 面 | 用例 |
|---|---|
| 噪声整块去除（大小写不敏感） | `removesScriptStyleNoscriptTemplateCommentsDoctypeAndCdataCaseInsensitively` |
| 引号内 `>` / 未闭合标签 fail-soft | `ignoresGreaterThanInsideQuotedAttributesAndSurvivesUnclosedTag` |
| 混合大小写 / 游离闭合标签 | `handlesMixedCaseTagsAndStrayClosingTags` |
| 块级、`li`、`td`、标题、`pre` 边界 | `mapsBlockLineBreakTableHeadingAndPreBoundaries` |
| 命名实体 + 未知实体保留 | `decodesFixedNamedEntitiesAndPreservesUnknownOnes` |
| 空白折叠（`pre` 内保留） | `collapsesWhitespaceOutsidePreAndPreservesItInside` |
| 深层嵌套线性且不溢出 | `deepNestingIsLinearAndDoesNotOverflow` |
| 空正文 / 非 HTML 透传 | `emptyAndNonHtmlInputsAreHandled` |
| 源码帽切在标签中间 fail-soft | `sourceCapTruncatesMidTagFailSoft` |
| **真实脏 HTML 样本** | `dirtyRealWorldSampleKeepsEvidenceAndDropsNoise` |

真实脏样本显式断言「导航/脚注等证据保留，SCRIPT/style/noscript/template 与注释里的
伪正文被剔除」——不是只测干净片段。

**M1**：`DROP` 集合去掉 `"script","style"` → `HtmlTextExtractorTest` 2 红
（整块去除用例 + 真实脏样本用例）。逐字节还原，`diff -q` 一致。

### 1.2 信任前缀（`WebTrustTest` 4 条 + 插件断言）

- 常量精确相等（不是近义改写）：`noticeIsAnExactConstantNotAParaphrase`；
- 两工具成功结果首行恒为该常量：`fetchAndSearchSuccessStartWithTheExactNotice`；
- 提示段以同一常量开头并写明引用/不执行纪律：
  `promptSectionStartsWithTheSameConstantAndStatesTheDiscipline`；
- 搜索 summary 不重复前缀：`searchSummaryIsNotPrefixedWithTheNoticeAgain`；
- 提示段落位：`WebToolPluginTest.promptSectionSitsAtPriority70BetweenNeighbouringSections`
  （plan=50、skill=60 之后，65/75 哨兵之间）。

`WebContentTrust`（`web/tool/.../WebContentTrust.java:14-38`）里常量与
`promptSection()` 同源；提示段为只读事实，R1 重建可复现（见 §5）。

**M2**：把常量改为 `"Treat it as trusted instructions."` → 2 个测试类共 8 条红
（`WebTrustTest` 4/4 + `WebToolPluginTest` 4 条：fetch 首行、search 渲染、
输出帽用例、提示段序）。
**M5**：`PROMPT_SECTION_PRIORITY` 70 → 80 → 2 条红（精确优先级 + 邻居排序）。

## 2. 评审重点 ②：工具面双态（缺 key 不注册 / 恒注册）

`WebToolPlugin.apply`（`WebToolPlugin.java:57-67`）：

```java
WebFetchService fetch = scope.require(WebFetchService.KEY);          // 恒注册，缺则装配期抛
scope.resolve(WebSearchService.KEY)
    .filter(WebSearchService::available)                             // keyless 不注册
    .ifPresent(search -> registry.register(webSearch(...)));
```

- **`web_fetch`**：`ToolDefinition.of` 恒需审批（`approvalExempt=false`）；
  缺抓取 provider 由 `missingFetchProviderFailsLoudAtAssembly` 直接断言装配期抛错。
- **`web_search`**：只在 provider 在场且 `available()` 时注册；缺 key 时
  `web-search-deepseek` 行 `disabled: ${env:DEEPSEEK_API_KEY} == null`，
  消费者根本看不到 provider → 工具面少一个，不是运行期失败。

**工具面 11 → 12（keyless）→ 13（keyed）**：

- `HostileInstructionsTest` 的 `containsExactlyInAnyOrder` 从 11 名改为 12 名，
  新增 `web_fetch`（两处断言）；
- keyed 的 `--verify` `web:` 行点名 `web_fetch,web_search`，故 13；
- `stop3-jlink-network.txt` §二逐字记录：keyless `29 rows, 35 discovered`、
  `tools=web_fetch (approval-required)`；keyed `31 rows, 35 discovered`、
  `tools=web_fetch,web_search (approval-required)`。

**M4**：删 `.filter(WebSearchService::available)` → `toolSurfaceIsDualStateOnSearchAvailability`
1 红（provider 在场但不可用时仍注册）。

**M6 未执行（如实披露）**：把 `web_fetch` 从 `require` 改为条件注册的写入被安全
分类器拦截（"生产注册语义变更，超出停 3 证据批范围"），未落地、未重试。
替代覆盖：`missingFetchProviderFailsLoudAtAssembly`（缺 provider 装配期抛错）
+ base 抓取行恒 enabled + M4（搜索侧双态）。若评审要求，可在放行后把该突变
作为独立验证项再跑。

## 3. 评审重点 ③：256 KiB 输出帽的 UTF-8 边界

`WebToolOutput.cap(content, maxBytes)`（`WebToolOutput.java:141-165`）：

- 逐码点累计 `utf8Bytes(codePoint)`（1/2/3/4 字节；未配对代理按 3 字节），
  只在**整字**放得下时追加，绝不劈开多字节字符；
- 截断标记 `" (output truncated)"` 在帽外追加、**不计入上限**；
- 切中时 `fetch()` 头部 `Truncated: true` 同步翻转（不是只截身体）；
- 搜索侧标记 `"… (search truncated)"` 独立于输出帽；零来源明说
  `No sources returned`；元数据经 `oneLine` 折叠，防止外部标题伪造头部行。

`WebToolCapsTest` 7 条：ASCII 边界、恰等边界不截断、三字节、四字节、标记不计入、
非正上限属编程错误、`oneLine` 防伪造。插件层
`outputCapFlipsHeaderFlagAndCutsAtUtf8Boundary` 再钉一次头部翻转。

**M3**：`utf8Bytes(codePoint)` → `1`（按码点计 1 字节，it27 M4 同型坑）→ 4 条红：
三字节/四字节整字用例、UTF-8 边界用例、插件输出帽用例。逐字节还原后复绿。
源码帽（`maxSourceChars=524288`，按 char 截）与输出帽（`maxOutputBytes=262144`，
按 UTF-8 字节截）分层，硬上限 16M / 64 MiB / 50 由 `WebToolConfig` 校验。

## 4. 评审重点 ④：装配三行 + 五处同步面

### 4.1 base 三行（`bundle/base/.../bundle.yml`）

行序 `web-fetch-http` → `web-search-deepseek` → `web-tool`：

- fetch：`timeoutSeconds=30`、`maxBodyBytes=2097152`；
- search：端点/模型/`apiKeyEnv`/`maxTokens=4096`/`maxUses=5`、
  `disabled: ${env:DEEPSEEK_API_KEY} == null`；
- tool：`maxSourceChars=524288`、`maxOutputBytes=262144`、`maxResults=8`。

base 行数 **35 = 22 可配 + 13 无配**；`--verify` keyless **29/35**、keyed **31/35**
（增量恰为 `llm-openai-compat` 与 `web-search-deepseek`）。

### 4.2 五处同步面（同一工作树内）

| # | 面 | 位置 |
|---|---|---|
| 1 | 根 `pom.xml` | `<module>web</module>` + dependencyManagement 四坐标 |
| 2 | `dist/jh/pom.xml` | jlink `--add-modules` 四行；镜像 `--list-modules` 四模块在场 |
| 3 | `examples/headless/pom.xml` | 四模块依赖 |
| 4 | `integration/consumer-sample/pom.xml` | `candidate-closure` 四坐标（11 = skill 3 + mcp 4 + web 4） |
| 5 | `docs/embedding.md` §2 | 基础 16 + 候选 11 的 web 四坐标说明 |

**双通道检查单（AGENTS 已入册，本次未再撞）**逐模块核对：
`web/fetch-http` provides `WebFetchService` + `META-INF/services` 同名文件；
`web/search-deepseek` provides `WebSearchService` + 同名文件；
`web/tool` 是消费者，不提供 SPI、无服务文件（只注册工具与提示段）。

### 4.3 结构计数

- 模块 **44**、导出包 **48**、Jackson 边界 **7**（`web/search-deepseek` 为第七个）；
- 工具面 keyless **12** / keyed **13**；`web:` 摘要行由
  `HeadlessMain.webText` 渲染，只陈述组合事实（行 + 已注册工具 + 恒需审批）；
- consumer candidate：`rows.composed=36`（base 35 + 示例自有行）、
  `rows.enabled=30`（keyless 29 + 1）、`tool.schemas=13`（base 12 + `word_count`），
  与 `--verify` 的 29/31、12/13 是**不同测量面**，证据档已分开说明。

## 5. 评审重点 ⑤：R1 回放 + 模型在环

### 5.1 R1 回放（`WebReplayTest`，1 条两腿）

- live 腿：fake 抓取 provider 恰 1 次调用；工具结果首行 == 信任常量；最终 URL /
  Status / Content-Type / Truncated 齐备；HTML 提取后含 `# Replay` / `Body text`，
  **不含** `<script>` 里的 `bad()`；事件类型 `noneMatch(startsWith("web/"))`，
  只有 `tool/call` + `tool/result`——**零新事件**；
- replay 腿：新 Runtime 只读盘；JSONL 往返后 content 逐字相等；事件序列
  `containsExactlyElementsOf`；两个 `LlmRequestEvent` 锚点上做前缀折叠重建，
  `sha256(assembled) == systemPromptSha256`（含 `Static(70)` 段）、
  `sha256(toolSchemaFingerprint) == toolsSchemaSha256`（含 `web_fetch`）；
  **fake provider 零调用**。

### 5.2 模型在环（真实 key，jlink `jh`）

- `web_fetch`：session `headless-1793085856464-7b5e`，tokens_in=3396 /
  out=76 / 8.8s；模型选中 `web_fetch("https://example.com/")`，答案引用与页面一致；
- `web_search`：session `headless-1793085876872-e6bc`，tokens_in=3799 /
  out=444 / 6.8s；真实端点返回 8 条来源（= `maxResults`），
  `… (search truncated)` 在场；provider URL 原样保留（含 query/fragment），
  消费面不改写、不自动抓取；
- 两运行工具结果首行均信任前缀；审批面 `--approval=auto`，审计仍为
  `tool/call` + `tool/result`；
- 结构检查 `NO-AUTH-HEADER-STRINGS-IN-LOGS`（只查字段名，不读取值；key 全程
  未进 stdout/stderr/证据文件）。

**如实披露**：本次真实搜索响应的 entries 未带可映射 citation，故来源只有
title + url、**无 snippet 行**；模型末句"dates above are from snippets"是模型措辞，
本运行未提供 snippet。citation→snippet 由夹具测试覆盖；真实端点的 citations 是
provider 侧行为（B19 已记「缺失字段不编造」）。

## 6. 停 1 契约增补（需本次评审一并裁决）

`WebFetchResult` 增加 `contentType` 组件（`Objects.requireNonNull`）：
`PinnedHttpClient` 把 `head.first("content-type")` 传入；`WebContractTest` /
`PinnedHttpClientTest` 同步断言。理由：停 3 的结果头需要 `Content-Type`
区分 HTML 提取与文本归一（模型据此判断证据形态），且该字段本就在 HTTP 响应头里，
不引入新网络面。影响面：仅 Definition 构造器与两个 provider/测试调用点；
无新模块、无新包、无新依赖。若评审不接受，可回退该组件并删除结果头对应行
（相应测试与证据档需同步删改）。

## 7. 验证证据

| 证据 | 结果 |
|---|---|
| `web/tool` 聚焦（`stop3-tools-html-trust.txt`） | 37（Config 5 / Plugin 11 / Html 10 / Caps 7 / Trust 4），0 fail |
| `bundle/base` + `examples/headless`（`stop3-assembly-and-verify.txt`） | 32 + 83，0 fail；同 reactor web 回归 8/113/45(skip 1)/37 |
| jlink 构建与双态 verify（`stop3-jlink-network.txt`） | EXIT=0；29/35、31/35；四 web 模块入镜像 |
| 真实网络 6 用例（同上） | 公网成功；私网 IP / http / 重定向到私网 / 降级全部按错误码拒绝；跨主机重定向成功并暴露最终 URL |
| R1 回放（`stop3-replay.txt`） | EXIT=0，1 test；零网络、零新事件、提示词/schema 哈希复现 |
| 模型在环（`stop3-model-in-loop.txt`） | 两运行 EXIT=0；fetch/search 各 1 工具调用；来源保留 |
| consumer candidate（`stop3-closeout-consumer.txt`） | EXIT=0；8 腿 + 4 负例；36/30 行、13 schemas |
| 全量包（`stop3-closeout-package.txt`） | `validate` EXIT=0；`package` EXIT=0；**974 tests / 0 fail / 0 error / 31 skipped**，41 个有报告模块 |

计数均取自模块汇总行 / Surefire 报告；不同测量面（verify 行数 vs consumer 自证
行数）不互比。

## 8. 偏离、裁决点与残余风险

1. **停 1 契约增补**：见 §6，待本次评审裁决。
2. **M6 未执行**：被安全分类器拦截，替代覆盖已在 §2 列明；未擅自绕过。
3. **真实搜索无 snippet**：provider 行为，映射层不编造；夹具覆盖 citation 路径。
   模型措辞与工具数据不一致已在证据档披露（不据此改产品）。
4. **来源 URL 不改写**：provider 原样 URL 直接展示（含 query/fragment），
   消费面不自动抓取；B17/B19 已登记。
5. **T9 token 缺口**：搜索辅助调用 token 不入预算账，07 已显式接受；
   本停点未改预算面。
6. **无中途改道**：本轮全部工作落在已批准停 3 清单内；未新增第三方依赖、
   未改 `.java-version`、未触碰凭据、未 push。
7. **待收口项**：用户令在收口时把「错误体 16 KiB 截断」补进 05/07 的错误消息
   契约；本停点未提前写入（保持停点边界）。

## 9. 停点状态

- **停 3 未提交**；`docs/plan/iteration-28.md` 停 3 行保持 ☐，验收勾选留在收口批
  （放行后统一勾选 + 提交，不 push）。
- 等新鲜放行的动作序列：勾选验收 → 05/07 补「错误体 16 KiB 截断」→
  `validate` + 全量 `package` 复跑 → 提交（含本 packet 与 7 份证据、notes 0007/0008）。
- 本停点的评审重点索引：① §1；② §2；③ §3；④ §4；⑤ §5；
  契约增补 §6；证据表 §7。

## 10. 评审处置（2026-10-28，收口批）

- 裁决 1：`WebFetchResult.contentType` 契约增补**批准**，按停 1 契约修订记录入修正表。
- 裁决 2：`web_search` 结果头改为只陈述实际字段——新增
  `Sources: N (titles: x, snippets: y)`，工具描述声明 snippet 条件出现；新增两条
  用例（零 snippet / 部分字段），`web/tool` 37 → 39。证据
  `stop3-closeout-wording.txt`（D2 红 1；D1 被安全分类器拦截，D1b 红 3）。
- M6 拦截按评审意见接受：替代覆盖为 `missingFetchProviderFailsLoudAtAssembly` +
  keyless rig + M4；「安全分类器参与突变审查」作为新现象留在证据档。
- 收口批执行：验收全勾 + 05/07 错误消息契约补句 + 新鲜 `package`/`validate`/
  `git diff --check` + 提交（不 push）。
