# it28 停 2 review packet：DeepSeek 搜索 Provider（结构化映射 + 真实探针闸）

- 基线：`b8f9631`（停 1 已提交）；本停点工作区**未提交**（按停点要求）
- 范围：`web/search-deepseek` 新模块（配置、wire、provider、映射、插件、夹具/用例、
  真实探针）；`web/pom.xml` 注册该模块。**不 commit、不进停 3**
- 工作树：`web/pom.xml` 修改 + `web/search-deepseek/` 14 个未跟踪文件
  （1 pom + 5 主源码 + module-info/服务清单 + 6 测试类/夹具；`.jqwik-database`
  是 gitignore 的测试运行产物）+ 本停点 4 份证据档
- 依赖：唯一新引入坐标 `jackson-databind` 已在根 `dependencyManagement` 的
  Jackson BOM 管理（`pom.xml:69`），无新增版本、无其它第三方坐标
- 评审重点（用户预告）：**① 探针失败闸是否真的会停**；**② 请求头与 key 不回显**；
  **③ 映射只取结构化结果、去重、无结果块即错的负路径**

## 0. 五行 TL;DR

1. **失败闸是结构性的**：模块只有一个 `WebSearchService` 实现，没有第二 provider、
   没有重试/降级/散文刮取分支；探针失败直接抛出并把状态/体积/错误原文打出来。
2. **真跑已通过**：实际 provider 代码打 `api.deepseek.com/anthropic/v1/messages`，
   `deepseek-v4-flash` + `web_search_20250305`，200、响应 62,461 B、5 条结构化来源、
   `truncated=true`、8.4s；日志 key 检查 NO。
3. **线上形状逐字段钉死**：`x-api-key` 与 `Authorization: Bearer` 双发、
   `anthropic-version: 2023-06-01`、`max_tokens=4096`、`max_uses=5`、请求体
   `text` + 单工具；错误消息双遮断（精确 key + `sk-...` 形态），突变 M5 必红。
4. **映射只吃结构**：`web_search_tool_result` → `web_search_result`；snippet 只来自
   `citations[].cited_text`；URL 首次出现胜；**无结果块 = `WEB_PROVIDER_ERROR`**，
   块在零结果 = 合法空结果。散文答案不刮取（M3 突变必红）。
5. **验证**：默认聚焦 **45（44 执行 + 探针 1 skip）**、`validate` EXIT=0；
   突变 M1–M11 全部“破坏必红 → 逐字节还原”；残余 4 项在 §6 明列。**未 commit，停点表仍 ☐。**

## 1. 评审重点 ①：探针失败闸真的会停

### 1.1 代码事实

- 模块内唯一实现：

```text
$ grep -rn "implements WebSearchService" web/search-deepseek/src/main
DeepSeekSearchProvider.java:40:final class DeepSeekSearchProvider implements WebSearchService {
```

- 主源码无 HTML/正文解析，也无“失败后再试一条路”的代码：

```text
$ grep -rniE "fallback|scrap|html" web/search-deepseek/src/main
DeepSeekSearchConfig.java:169: private static int intValue(Object raw, int fallback, String where, int max)
DeepSeekSearchConfig.java:171:     return fallback;
```

  仅命中配置默认值参数名，不是网络降级路径。
- 每次 `search` 只发一次请求；`sendAsync` 失败、非 2xx、坏 JSON、形状不符、无结果块
  都翻译成 `WebException` 向上抛（`DeepSeekSearchProvider.java:110-217`）。
- 映射层唯一出口是 `AnthropicSearchMapper.map`；无结果块直接抛
  `WEB_PROVIDER_ERROR`（`AnthropicSearchMapper.java:63-67`）。

### 1.2 探针如何失败

`DeepSeekSearchProbeTest` 走实际 provider，非 2xx/映射失败时：

```java
catch (WebException error) {
    System.out.println("PROBE_FAILED code=... observed=" + observed.get()
        + " detail=" + error.getMessage());
    throw error;                       // 测试即红，构建即停
}
```

成功判据只有两条硬事实：观察器状态以 `200:` 开头；结构化来源非空。
没有“失败则改用 mock / 改用散文 / 换 provider”的代码。

### 1.3 真跑结果

见 `stop2-real-probe.txt`：

```text
PROBE_OK endpoint=api.deepseek.com/anthropic/v1/messages model=deepseek-v4-flash
         max_tokens=4096 max_uses=5 request_bytes=262 observed=200:62461
         sources=5 truncated=true elapsed_ms=8420
```

端点 `https://api.deepseek.com/anthropic/v1/messages`；真实响应来源多于
`maxResults=5`，seam 截断与 `truncated` 在真跑中被触发。配额消耗 = 3 次请求
（含前两轮补丁前复跑）× `max_uses=5` 上限；日志 key 检查 NO。

## 2. 评审重点 ②：请求头、请求体与 key 不回显

### 2.1 线上形状（`DeepSeekSearchProvider.java:165-176`）

| 项 | 值 | 钉死用例 |
|---|---|---|
| 方法/路径 | `POST {baseUrl}/messages` | `sendsDshRequestBodyAndDualAuthHeadersWithoutKeyInBody` |
| `x-api-key` | 解析后的 key | 同上 |
| `authorization` | `Bearer <key>` | 同上 |
| `anthropic-version` | `2023-06-01` | 同上 |
| `content-type` / `accept` | `application/json` / `application/json` | 同上 |
| `user-agent` | `javanatic-harness/0.3` | 同上 |
| body | `model`、`max_tokens`、`messages[0].content[0].text = "Perform a web search for the query: <query>"`、`tools[0] = {type:"web_search_20250305", name:"web_search", max_uses}` | 同上（逐字段断言） |

`request.bodyText()` 断言 **不含 key**；key 只出现在请求头。

### 2.2 错误不回显

- `sanitize`（`DeepSeekSearchProvider.java:291-309`）先精确替换 key，再按
  `sk-[A-Za-z0-9_-]{8,}` 兜底，折叠控制字符/空白，截断 512 字符 + `…`。
- 用例：401 回显 key → 消息含 `****`、不含 key（M5 同时删两种遮断必红）；
  429 非 JSON 文本有界；500 provider message 采用；4 KiB 文本截到 512 + `…`
  （M10 必红）；>1 MiB 错误体只读 16 KiB 前缀且保留状态码（M11 必红）。
- 探针日志 key 检查 `NO`（`stop2-real-probe.txt`）。

## 3. 评审重点 ③：结构化映射与负路径

`AnthropicSearchMapper`（dsh `mapAnthropicResponse` 同口径）：

| 规则 | 实现 | 用例 |
|---|---|---|
| 只取 `web_search_tool_result` 块 | 其它块只用于 citation，不产生来源 | `mapsStructuredResultsAndCitationSnippetsInProviderOrder` |
| 只取 `web_search_result` 项 | 未知 item 类型跳过 | `unknownItemTypesAreSkipped` |
| URL 必填非空白 | 缺失 → `WEB_PROVIDER_ERROR` | `malformedResultBlocksAndItemsAreProviderErrors` |
| snippet | 仅 `text.citations[].cited_text`，按 URL 首次出现 | `mapsStructuredResultsAndCitationSnippetsInProviderOrder` |
| 去重 | `HashSet` 首次出现胜（跨块） | `dedupesAcrossResultBlocksAndFirstOccurrenceWins`（M4） |
| 截断 | seam 侧按 `maxResults` 截断 + `truncated` | `truncatesToMaxResultsAndFlags`；真跑 `truncated=true` |
| 无结果块 | **`WEB_PROVIDER_ERROR`**，不刮散文 | `proseWithoutResultBlockIsAProviderError`（M3）、provider 侧 `proseWithoutResultBlockFailsInsteadOfScraping` |
| 块在零结果 | 合法空结果 | `presentResultBlockWithZeroResultsIsAValidEmptyResult`、`resultBlockWithoutContentIsAValidEmptyResult` |
| 块内容是错误对象 | `WEB_PROVIDER_ERROR`，带 `error_code`/`type` | `resultBlockErrorObjectIsAProviderError` |
| 坏根/坏块/坏引用 | 前两者报错，坏引用跳过不编造 | `malformedRootAndContentShapesAreProviderErrors`、`malformedCitationsAreIgnoredButDoNotFabricateSnippets` |

`summary` 恒空：DeepSeek 原生搜索不返回摘要，Definition 已允许缺失。

## 4. 配置、装配与传输边界

- 插件 id `web-search-deepseek`，`requires` 为空，`provide(WebSearchService.KEY)`，
  回收挂自身 scope（`WebSearchDeepSeekPlugin.java:27-38`）。
- 配置键白名单：`baseUrl/model/apiKey/apiKeyEnv/maxTokens/maxUses/timeoutSeconds`；
  未知键/坏值 fail loud（`DeepSeekSearchConfig.java:58-108`）。API key 字面优先，
  否则 `System.getenv(apiKeyEnv)`；缺失时抛
  `no API key ... keyless compositions should disable this row`，只点名环境变量名。
- `baseUrl` 仅 https，拒 userinfo/query/fragment/坏端口，去尾斜杠；`endpoint()` 追加
  `/messages`。`toString()` 恒遮蔽字面 key。
- 装配客户端 `Redirect.NEVER` + 10s connect timeout，由
  `assembledClientNeverFollowsRedirectsAndPinsConnectTimeout` 直接断言（M6 必红）；
  3xx 不被跟随，provider 用例断言只打第一跳且消息含 `HTTP 302`。
- 请求截止 `timeoutSeconds`（缺省 30）；`HttpRequest.timeout` 管响应头，
  `BodyWatchdog` 在剩余截止时间关闭响应流唤醒阻塞读；超时 → `WEB_TIMEOUT`。
- 成功响应体 1 MiB 上限（流式累计，超限 `WEB_PROVIDER_ERROR`，M7 必红）；
  非 2xx 诊断体单独 16 KiB 上限，保住状态码（M11）。
- 取消：`signal.onCancel(() -> future.cancel(true))` + 响应体 `onCancel` 关闭流；
  阻塞读被唤醒后先 `checkAbort()`，最终穿透 `AbortedException`（M8 必红）。
- `available()` 在构造后恒真（key 非空、配置已校验）；无 key 的组合在装配期即失败，
  不会留下“挂载但不可用”的 provider。基础行 `disabled: ${env:DEEPSEEK_API_KEY} == null`
  在停 3 落地。
- `ResponseObserver` 是包内测试缝（默认 no-op），只观测状态/体积，不改变线上行为、
  不暴露为配置面；探针用它记录 `200:62461`。

## 5. 验证证据

| 证据 | 结果 |
|---|---|
| `mvn -B -q validate` | EXIT=0 |
| 默认聚焦（`stop2-focused-tests.txt`） | 45 total / 44 executed / 0 fail / 1 skip（探针） |
| 真跑（`stop2-real-probe.txt`） | 200 / 62,461 B / 5 来源 / truncated / 8.4s / key-in-log NO |
| 突变（`stop2-mutation-checks.txt`） | M1–M11 破坏必红，逐字节还原，终检零残留 |

突变的两个校准已如实记录：M7 首轮因用例复用常量而假绿 → 改字面量后转红；
M9 首轮删行触发 spotless → 改等价字节数变异后在断言处转红。

## 6. 偏离、裁决点与残余风险

1. **错误体专用 16 KiB 上限（新增，非草案原文）**：草案只给“非 2xx 有界、脱敏
   文本”。若沿用 1 MiB 通用上限，超大错误体会先触发上限错误而丢状态码；故拆出
   `readErrorBody`，保留状态码并获得更紧的内存/时间界。已配 M11。
2. **`ResponseObserver` 测试缝进生产类**：包内构造器、默认 no-op，仅探针取证用；
   若评审认为不应存在，可改为夹具侧 `HttpClient` 包装，但会牺牲响应体字节数的
   直接观测（HTTP 客户端回调拿不到实际读入量）。
3. **搜索端点不过公网地址闸**：`baseUrl` 来自受信 bundle 配置，不是用户输入；
   抓取面（停 1）才承载“任意 URL”威胁。若未来允许终端用户改 `baseUrl`，必须把
   停 1 的地址闸接进来并扩 B17。
4. **来源 URL 只校验非空白**：映射层不强制 http(s)/不解析 URL；它们是数据，停 3
   的工具结果会原样展示，消费面不得自动抓取来源 URL。B17 收口时登记。
5. **T9 提醒**：辅助搜索是一次真实模型调用，其 token 不进 loop-guard 预算账；
   按用户裁定在 07 收口时明写一句（本停点未改预算面）。

## 7. 停点状态

- 停 2 未提交，`docs/plan/iteration-28.md` 停 2 行保持 ☐。
- 等新鲜放行后：提交（含本 packet 与证据）→ 停 3（`web/tool`、HTML 提取、信任前缀、
  装配与同步面）。停 3 的评审重点仍是草案所定：HTML 提取器矩阵 + 信任前缀突变。
