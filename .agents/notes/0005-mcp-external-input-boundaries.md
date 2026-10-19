# 0005 — 凭据词表单源 + HTTP URL/header 外部输入校验

- 状态：已实施（it27.1）
- 日期：2026-10-19

## 背景

MCP 把凭据搬进了两种配置面：stdio 的 `servers[].env` 与 HTTP 的 `servers[].headers`。it27 的写入期脱敏词表止于 `apikey|token|secret|password|key`，**`Authorization` 与 `Cookie` 不在表内**——`mcp-http` 一旦落地，常见头会以明文进 `header.json`。同时 URL 是第三个泄漏面：query 里的 `access_token`/`api_key` 会进日志、代理与错误消息。

## 裁决（T6）

- 词表唯一：`apikey|token|secret|password|key|auth|cookie`（不区分大小写），承载体 `ConfigValues.SENSITIVE_MARKERS`；公开谓词 `ConfigValues.isSensitiveKey`，`masked` 与所有消费者共用——**不允许第二份词表**。
- 脱敏仍在写入/展示边界：任意深度、值替换 `****`、**键与键序保留**、非字符串键 fail loud；内存 manifest 保留原值。
- `mcp-http` 配置校验：URL 仅 http/https；拒 userinfo / fragment；query **参数名** percent-decode 后过同一词表（拒绝疑似凭据进 URL，凭据应放 headers）；headers 拒保留头（Host / Content-Length / Transfer-Encoding / Connection / MCP 协议头等）与控制字符；header 名大小写不敏感重名 fail loud。
- HTTP 客户端固定 `Redirect.NEVER`：不跟随重定向（避免认证头被带到他源）。

## 推翻对象

- it27 词表（缺 `auth|cookie`）与其「`mcp-stdio.servers[].env` 是第一个把凭据搬进 header 的配置面」的断言——`mcp-http.servers[].headers` 是第二个，且词表必须覆盖。
- 「URL 不做敏感校验」的默认——query 是本迭代新引入的外部输入面。

## 后果

- `Authorization` / `Cookie` / `X-Auth-*` 在 header 与 dump 中恒 `****`；`X-Tenant` 等非敏感头保持原值（脱敏不是整行打码）。
- 词表变化是**行为变化**：新增 marker 会改变脱敏结果与 URL 拒绝面，须同步设计文档与测试。
- 单源谓词让「脱敏」与「URL 校验」不会漂移；`AppBoot.java:143`（dump）与 `JsonlPersistence.java:539`（header）是仅有的两个消费者。

## 证据

- [stop3-cap-and-masking.txt](../../docs/plan/evidence/iteration-27.1/stop3-cap-and-masking.txt)：ConfigValuesTest 6 / McpHttpServerConfigTest 26 / JsonlPersistenceTest 26 绿；M6/M6b/M7/M8 突变。
- 设计同步：[03 §组合清单脱敏](../../docs/design/03-session-event-sourcing.md)、[07 §5](../../docs/design/07-profile-bundle.md)、[12 §5](../../docs/design/12-api-stability.md)。
