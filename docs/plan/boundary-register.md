# 边界登记表

> 单一登记处：外部输入、信任面、预算上限与**刻意不做**。新边界先在这里挂行，再落到代码与设计文档；口径变化同提交更新本表。最近更新：2026-11-04（it30 开工；0.5 路线评审预登记 B29——W3 四确认时转正）。
>
> 编号消歧：本表原 B4「MCP 消费结果预算」现为 `B-MCP-RESULT`；冻结历史中的旧编号仍按本映射解读。图生成器挂账保留 [it26 提案 B4](evidence/iteration-26/roadmap-diff-proposal.md#b4-capability-graph-机械化建议挂账)，与本表预算项无关。

| # | 边界 | 规则 | 落地位置 | 状态 / 证据 |
|---|---|---|---|---|
| B1 | MCP stdio 进程信任 | server 以用户全权运行、**不入沙箱**；env = 最小环境（PATH/HOME/TMPDIR）+ 行配置白名单；装配期启动，失败 fail loud 并回滚 | `mcp/stdio`；05 §7.1、07 §5 | 已实施（it27） |
| B2 | MCP HTTP 网络信任 | 远端端点不受沙箱治理（治理边界 = 工具调用面）；TLS 用 JDK 默认信任面；**不做**自定义 trust store / 代理配置 | `mcp/http`；05 §7.1 | 已实施（it27.1） |
| B3 | 传输帧预算 | 单帧 **8 MiB**：stdio 一行 / HTTP 响应体 / 单条 SSE 事件；超限 = 协议错误（stdio 断裂重连、HTTP 请求失败），**不静默截断** | `mcp/stdio`、`mcp/http`（T7） | 已实施；`stop2-http-focused.txt` |
| B-MCP-RESULT | MCP 消费结果预算 | 单条工具结果 **256 KiB**（`mcp-tool.maxResultBytes`，正整数）；UTF-8 完整边界截断 + `" (output truncated)"`（标记不计入上限）；success / 工具级错误同口径 | `mcp/tool`（T7） | 已实施；`stop3-cap-and-masking.txt` |
| B5 | 凭据词表 | `apikey\|token\|secret\|password\|key\|auth\|cookie`（不区分大小写）**单源** `ConfigValues.SENSITIVE_MARKERS`；公开谓词 `isSensitiveKey`；`masked` 与 URL 校验共用 | `kernel/config`（T6） | 已实施；`stop3-cap-and-masking.txt` |
| B6 | 写入期脱敏 | header/dump 任意深度、敏感值 → `****`、**键与键序保留**；非字符串键 fail loud；内存 manifest 保留原值（R1 只需结构） | `JsonlPersistence`、`AppBoot`；03 §8、07 §5 | 已实施（it27，it27.1 扩词表） |
| B7 | URL 外部输入 | 仅 http/https；拒 userinfo / fragment / 敏感 query 参数名（percent-decode 后过 B5 词表）；凭据应放 headers | `mcp/http` `McpHttpServerConfig` | 已实施（it27.1） |
| B8 | Header 外部输入 | 拒保留头（Host / Content-Length / Transfer-Encoding / Connection / Accept / Content-Type / Mcp-Session-Id / MCP-Protocol-Version）与控制字符；**大小写不敏感重名 fail loud** | `mcp/http` | 已实施（it27.1） |
| B9 | 重定向 | `HttpClient.Redirect.NEVER`——凭据不随 3xx 转发 | `mcp/http` | 已实施（it27.1） |
| B10 | 清单换代时点 | 只在 `TURN_STOPPING` / `PRE_STEP` 采纳；轮内变化延后；被拒轮不采纳不落账；候选预检先于注册面改动，失败旧代保活 | `mcp/tool`（T4/T5） | 已实施；`stop3-swap-and-ledger.txt` |
| B11 | Schema 白名单 | 只映射 `type`/`description`/`properties`/`required`/`items`；`enum`/`const` 只提示不执法；不支持构造 → 该工具排除 + WARN；`outputSchema` / `taskSupport` 不消费 | `mcp/tool` `McpSchemaMapping` | 已实施（it27，T9 维持） |
| B12 | MCP 协议面 | 首版**不做** Resources / Prompts / Sampling / Roots / Elicitation / server instructions；HTTP 不做异步任务协商 | — | 刻意不做（二版重访；T9） |
| B13 | 命名算法残余风险 | `_hash8` 与合法 raw 名理论上可撞（如 `x_1234abcd`）；注册表 fail loud，退路 hash12 / 前缀转义——**须重裁 catalog 算法版本** | `mcp/tool` | 挂账；不属 it27.1 执行面 |
| B14 | 审批 | MCP 工具 `approvalExempt=false`，**恒需审批**；无豁免出口 | `mcp/tool` | 已实施（it27） |
| B15 | 零噪声 | MCP `servers: []` → 零进程、零网络、零工具、零事件、零注册 | 两个 transport + registry | 已实施 |
| B16 | 消息/事件形状 | `mcp/catalog` 每代全量快照、**无 generation 字段**（日志序即代，fold 末值胜） | `mcp/tool`（T8） | 已实施；`stop3-swap-and-ledger.txt` |
| B17 | Web 抓取网络信任与解析残余 | 抓取只经公网地址闸（DNS 整组 + 字面 IP 同闸）；egress 不受沙箱治理（治理 = 审批 + 审计 + 公网闸）。**解析期 `getAllByName` 不受 deadline/cancel 约束**：deadline 自解析返回后生效，取消不能唤醒系统解析调用（`AddressResolver` 为可替换缝，升级路径 = 独立解析线程 + 限时 join）。**不做** RFC 7050 自定义 NAT64 前缀发现：只封 `64:ff9b::/96` 与 `64:ff9b:1::/48`，自定义前缀在地址形态上仍是 `2000::/3` 全球单播 | `web/fetch-http` `PinnedHttpClient` / `PublicAddressGate`；05/07 | 已实施（it28 停 1）；残余范围见 `stop1-fetch-policy.txt` §6、`stop1-pinning-and-redirect.txt` §4 |
| B18 | Web 搜索端点信任 | `web-search-deepseek.baseUrl` 是**组合者显式配置的信任面**（与抓取的 URL 不同：后者来自模型输入、逐次过公网闸），不经地址闸；TLS 用 JDK 默认信任面；`Redirect.NEVER`；凭据只进请求头（不入日志/错误/结果）；响应体字节上限 + 体读看门狗 | `web/search-deepseek`；05 §7.2 | 已实施（it28 停 2） |
| B19 | 搜索结果形状 | 只认结构化 `web_search_tool_result` 块（无块 = `WEB_PROVIDER_ERROR`，**绝不散文刮取**）；来源 `url` 缺失/空白即丢弃；snippet 取 citation `cited_text`（按 url 首次出现）；按 url 去重；`maxResults` 截断置 `truncated`；块在但零结果 = 合法空结果 | `web/search-deepseek` `DeepSeekSearchProvider` | 已实施（it28 停 2）；`stop2-mapping-and-errors.txt` |
| B20 | 外部内容信任面 | 成功结果首行恒 `EXTERNAL_WEB_CONTENT_NOTICE`；同源常量注册 `Static(70)` 提示段。**软防线**：不保证注入失败；硬边界 = 公网地址闸 + 无凭据转发 + 审批 + 审计。HTML 提取（script/style 等剔除）是**可读性**处理，不是安全边界 | `web/tool` `WebContentTrust` / `HtmlTextExtractor` | 已实施（it28 停 3）；`stop3-tools-html-trust.txt` |
| B21 | Web 消费预算 | 分层截断：源码 512K 字符（`maxSourceChars`）→ HTML 提取/文本归一 → 输出 256 KiB（`maxOutputBytes`，UTF-8 完整字符边界、标记不计入）；搜索来源 `maxResults` 缺省 8；三者正整数且硬上限；传输 `maxBodyBytes` 2 MiB 是读侧上限，与本帽分层 | `web/tool`（T7）；`web/fetch-http` | 已实施（it28 停 3）；`stop3-tools-html-trust.txt` |
| B22 | Web 测试缝 | `PinnedHttpClient` 的 resolver / gate / `SSLContext` 只经构造器注入（生产装配固定 `PublicAddressGate.INSTANCE` + 默认上下文）；`ResponseObserver` 包内接口、生产 no-op、只观测 status/bytes。**不得演化为生产回调面**（不设静态 setter / SPI / 系统属性开关），否则地址闸可被组合面绕开 | `web/fetch-http`、`web/search-deepseek` | 已实施（it28 停 1/2，评审确认） |
| B-WEB-SEARCH-BUDGET | Web 搜索辅助预算 | 搜索辅助调用的 token **不进 agent 预算账**；预算只记主对话模型请求，不能据此宣称覆盖搜索总费用。重访条件：明确要求统一辅助调用计费，先另裁预算语义 | `web/search-deepseek`；[裁决 0008](../../.agents/notes/0008-web-search-provider-and-token-gap.md) | 已接受边界（it28 T9），非待修缺陷；本次仅补登记 |

## LSP Boundaries（it29，已裁决 / 已实施；停 0–3 全部通过并关闭）

| # | 边界 | 规则 | 落地与证据 |
|---|---|---|---|
| B23 | LSP 外部进程与主动宿主探测 | 可信 LS 以用户全权运行、不入沙箱；治理在工具调用面。私有 HOME、`user.home` 与 env 白名单不是访问隔离；LS 会主动发现宿主 JDK，可写项目配置 / 编译产物、缓存 / 索引并下载依赖。工具只读 / 拒批不等于进程只读 / 无副作用；停止不回滚文件；快照非全机审计 | [停 0 终裁](evidence/iteration-29/stop0-acceptance.txt)、[有效设置](evidence/iteration-29/stop0-run-01/effective-settings.txt)；产品 Provider 已交付，[停 2 已通过并关闭](evidence/iteration-29/stop2-acceptance.txt)；产品验收随[停 3 终裁](evidence/iteration-29/stop3-acceptance.txt)完成 |
| B24 | LSP 观察锚与空诊断 | JH 在屏障拉取时计算并落账文件 / 项目 hash；LS version 仅参考。拉取成功、项目代稳定且完整等待窗后无诊断，只称“拉取时点无诊断”，不证明 LS 分析完成 / 来源，也不称“项目无错误”；后台缓存不得未经落账改模型上下文 | [停 0 终裁](evidence/iteration-29/stop0-acceptance.txt)；seam 契约随[停 1 终裁](evidence/iteration-29/stop1-acceptance.txt)落定；Provider 面措辞纪律自检 0 违例（[停 2 packet](evidence/iteration-29/stop2-review-packet.md) §6）；Consumer 面措辞纪律双突变必红（含「项目无错误」，[停 3 回放](evidence/iteration-29/stop3-replay.txt)）；回放零联网零进程 |
| B25 | LSP 产品退出与协议应答 | 有界 shutdown / exit，失败 TERM→KILL 后复验专属组，及时 wait；EPERM 不是组消失。产品验收包含 ≥3 次重复启停、忽略 TERM 路径、全部 server 请求应答（含退出阶段）。只回收自有进程，不按 Java 名称全机杀；不宣称数字 PID/PGID 快照覆盖任意脱组后代 | [终裁](evidence/iteration-29/stop0-acceptance.txt)、[探针尾流缺口](evidence/iteration-29/stop0-run-01/protocol-tail-analysis.txt)；Provider 真实生命周期取证已完成（[S5 证据](evidence/iteration-29/stop2-s5-real-evidence.txt)：3 轮启停 unanswered=0 + 夹具 KILL 升级复验）；Consumer 面已随停 3 取证（真机探针 3 轮 close 四要素 + 零残留；jlink 两腿真实进程随 scope 关闭收敛 + pgrep 零残留） |
| B26 | LSP Provider 输入面 | `JdtlsLaunch` 输入（javaHome / serverDir / configurationDir / projectRoot / indexDir / homeDir / mavenUserSettings / mavenGlobalSettings）**路径只校验存在，不验证内容**——`javaHome/bin/java`（Windows 为 `bin/java.exe`，`javaBinaryName` 纯函数判定）须可执行，其余目录 / 文件内容正确性由 LS 进程自己判定；索引 / home 目录写入是 LS 进程行为，工具治理不覆盖进程 | `lsp/jdtls` `JdtlsLaunch`（构造校验 + `JdtlsLaunchTest` 12 项）；[停 2 设计](evidence/iteration-29/stop2-provider-design.md) §2；装配面已现（停 3 S1/S2），配置键另行登记见 B27/B28 |
| B27 | LSP 行配置（jdtls） | 键白名单 8 个：`javaHome`（缺省探测 `java.home`，主动探测披露见治理摘要）/ `serverDir` / `configurationDir` / `projectRoot`（三者必填，`JdtlsLaunch` 校验存在）/ `indexDir`（缺省 `homeDir/index`）/ `homeDir`（缺省 `<tmpdir>/jh-lsp-<8位随机>`，LS 缓存 / 索引不触碰用户真实 HOME）/ `mavenUserSettings` / `mavenGlobalSettings`（Optional）。未知键 / 坏值 fail loud；目录创建（homeDir / indexDir）发生在 `toLaunch()`——解析本身无文件系统副作用 | `lsp/jdtls` `JdtlsPluginConfig`；12 §6；[停 3 终裁](evidence/iteration-29/stop3-acceptance.txt) |
| B28 | LSP 行配置（tool） | 键白名单 5 个，预算缺省有界：`timeoutSeconds` 30 / `waitSeconds` 15 / `maxDiagnostics` 50 / `maxLocations` 50 / `maxOutputBytes` 262144（256 KiB）；全部正整数，`waitSeconds ≤ timeoutSeconds`（LspQuery 构造契约的配置面预校验）；未知键 / 非正 fail loud | `lsp/tool` `LspToolConfig`；12 §6；[停 3 终裁](evidence/iteration-29/stop3-acceptance.txt) |

## Jobs Boundaries（it30）

| # | 边界 | 规则 | 落地与证据 |
|---|---|---|---|
| B31 | jobs 工具读取预算 | `maxReadBytes`（jobs-tool，正整数缺省 262144）；游标语义使上限只影响单片不影响可读总量；与 fs/shell/mcp/web 输出口径同族 | `jobs/tool` `JobsToolConfig`；[停 3 评审批](evidence/iteration-30/stop3-review-packet.md) |
| B30 | jobs 任务树根 | 行配置 `dataDir`（jobs-local），缺省 `${home}/.harness/jobs/`——「Lost 后只读遗物」的契约承诺要求跨重启确定性存活，不依赖 OS tmp 清理策略；测试注入例外；retention/清理面停 3 裁 | `jobs/local` `LocalJobsPlugin`；[停 2 复跑改判](evidence/iteration-30/stop2-race-fix.txt) |

## 0.5 工作台预登记（2026-11-04 路线评审）

| # | 边界 | 规则 | 落地与证据 |
|---|---|---|---|
| B29 | JSON-RPC server 监听面（预登记） | 全仓**第一个监听套接字**：仅绑定 **127.0.0.1 回环**、单用户、无远程暴露、无 TLS（本地回环语义）——W3 验收硬线。授权依据 = [00 §6](../design/00-overview.md)「headless runner + JSON-RPC（ACP 等价物）足够」；Web UI 本体的非目标改判另行决策记录（W3 开工前，全局 grep 旧措辞同步） | 预登记（it30 路线评审）；W3 四确认时转正 |

## 维护规则

- 新增外部输入（新配置键 / 新协议字段 / 新远端来源）必须同时登记 B 行、实现校验、补测试、同步设计文档——四件缺一即视为半装配。
- 「刻意不做」行必须有**重访条件**；无条件的不做项不进本表。
- 本表只登记契约级边界；模块内部上限若不影响调用方与治理面，留在模块 Javadoc。
