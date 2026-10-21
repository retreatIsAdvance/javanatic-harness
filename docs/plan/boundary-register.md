# 边界登记表

> 单一登记处：外部输入、信任面、预算上限与**刻意不做**。新边界先在这里挂行，再落到代码与设计文档；口径变化同提交更新本表。最近更新：2026-10-21（it28 停 1）。

| # | 边界 | 规则 | 落地位置 | 状态 / 证据 |
|---|---|---|---|---|
| B1 | MCP stdio 进程信任 | server 以用户全权运行、**不入沙箱**；env = 最小环境（PATH/HOME/TMPDIR）+ 行配置白名单；装配期启动，失败 fail loud 并回滚 | `mcp/stdio`；05 §7.1、07 §5 | 已实施（it27） |
| B2 | MCP HTTP 网络信任 | 远端端点不受沙箱治理（治理边界 = 工具调用面）；TLS 用 JDK 默认信任面；**不做**自定义 trust store / 代理配置 | `mcp/http`；05 §7.1 | 已实施（it27.1） |
| B3 | 传输帧预算 | 单帧 **8 MiB**：stdio 一行 / HTTP 响应体 / 单条 SSE 事件；超限 = 协议错误（stdio 断裂重连、HTTP 请求失败），**不静默截断** | `mcp/stdio`、`mcp/http`（T7） | 已实施；`stop2-http-focused.txt` |
| B4 | 消费结果预算 | 单条工具结果 **256 KiB**（`mcp-tool.maxResultBytes`，正整数）；UTF-8 完整边界截断 + `" (output truncated)"`（标记不计入上限）；success / 工具级错误同口径 | `mcp/tool`（T7） | 已实施；`stop3-cap-and-masking.txt` |
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

## 维护规则

- 新增外部输入（新配置键 / 新协议字段 / 新远端来源）必须同时登记 B 行、实现校验、补测试、同步设计文档——四件缺一即视为半装配。
- 「刻意不做」行必须有**重访条件**；无条件的不做项不进本表。
- 本表只登记契约级边界；模块内部上限若不影响调用方与治理面，留在模块 Javadoc。
