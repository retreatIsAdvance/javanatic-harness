# 0008 — 搜索 Provider：原生结构化来源，keyless 双态与 token 缺口

- 状态：已实施（it28 停 2）
- 日期：2026-10-21

## 背景

路线要求「搜索作为可替换 provider」，且网页只能当资料。MCP 已把外部工具服务器做成 seam；Web 搜索若引入第三方搜索 SDK/服务，会新增依赖、新增凭据面、新增失效点。dsh 的 `packages/web/web-search-deepseek/src/provider.ts` 给出先例：复用 Anthropic 兼容 Messages + 原生 `web_search_20250305` 工具，直接从结构化结果块取来源，不解析散文答案。

## 裁决

- **零新第三方依赖**：`web/search-deepseek` 只复用既有 DeepSeek key（`DEEPSEEK_API_KEY`）与 Jackson（第七边界），不引入搜索 SDK。请求与 dsh 同形：`POST {baseUrl}/messages`、`anthropic-version: 2023-06-01`、`max_tokens` 4096、`max_uses` 5、文本 `Perform a web search for the query: <query>`。
- **只取结构化面**：遍历 `content[]` 的 `web_search_tool_result` 块 → `web_search_result` 项；snippet 取 `text` 块 `citations[]` 的 `cited_text`（按 url 首次出现）；按 url 去重。**无结果块 = `WEB_PROVIDER_ERROR`，绝不把散文答案刮成来源**；块在但零结果 = 合法空结果。
- **keyless 双态**：base 行 `disabled: ${env:DEEPSEEK_API_KEY} == null`；`available()` 是廉价本地检查（key 在场），绝不联网。消费面只在该行在场且 `available()` 时注册 `web_search`——缺 key 是**少一个工具**，不是运行时失败；`web_fetch` 恒注册。
- **端点信任面**：`baseUrl` 是部署配置（不是模型输入），不经公网地址闸；`Redirect.NEVER`；凭据只进请求头，不入日志/错误/结果；响应体有字节上限与体读看门狗。
- **T9 token 缺口（显式接受）**：搜索辅助调用的 token **不进 agent 预算账**——预算只记主对话模型请求。这是登记过的口径，不是遗漏；结果的 provider/model 首部外显，补足可审计性。

## 推翻对象

- 「搜索必须引入专用搜索 API/SDK」——原生工具已能返回结构化来源，成本与依赖面更小。
- 「把模型答案整段当搜索结果」——散文不可引用、不可去重、不可审计；无结构化块即错。
- 「缺 key 时工具仍在、运行时失败」——装配期双态比运行时错误更早暴露、更少噪声。

## 后果

- 搜索能力随 DeepSeek key 开合；换 provider 只需实现 `WebSearchService` 并改一行，消费面与工具面不动（seam 三角色）。
- 抓取与搜索的信任面不对称：抓取 URL 过公网闸（B17），搜索端点是组合者信任（B18）。
- token 缺口使预算面**低估**搜索成本——组合者需要知道这一点；后续若要计入，属于预算语义变更（另开裁决）。
- 搜索结果零来源显示 `No sources returned`；`maxResults` 截断是内容事实（`truncated`），不是错误。

## 证据

- [stop2-focused-tests.txt](../../docs/plan/evidence/iteration-28/stop2-focused-tests.txt)：请求形状、结构化映射、错误分档、去重与截断。
- [stop2-mutation-checks.txt](../../docs/plan/evidence/iteration-28/stop2-mutation-checks.txt)：散文刮取 / 无块即错 / key 泄漏路径的突变红。
- [stop2-real-probe.txt](../../docs/plan/evidence/iteration-28/stop2-real-probe.txt)：真实端点探针（凭据掩码，不入证据）。
- 设计同步：[05 §7.2](../../docs/design/05-capability-seam.md)、[07 §5](../../docs/design/07-profile-bundle.md)、[12 §5](../../docs/design/12-api-stability.md)。
- 边界登记：[B18 / B19](../../docs/plan/boundary-register.md)。

## 关联

- 前驱：[0007](0007-web-egress-pinned-public-only.md)（抓取出口与测试缝）。
