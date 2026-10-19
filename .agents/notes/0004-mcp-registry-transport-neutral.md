# 0004 — 注册表居中：`mcp-registry` 聚合，transport 不 provide

- 状态：已实施（it27.1）
- 日期：2026-10-19

## 背景

it27 只有 stdio 一个 transport，直接由 `mcp-stdio` 插件 provide `McpClientService`。it27.1 引入 Streamable HTTP 后，若沿用旧形状：两个 transport 各 provide 同一 service key（kernel 同 scope 重复 provide fail loud），消费面要么被迫 `requires` 某个具体 transport，要么按配置二选一——二者都违背「transport 对消费面不可见」。

## 裁决

- `mcp/mcp` 同时承载契约与注册表：新增插件行 `mcp-registry`（无 config），由它 provide `McpClientService`。
- `mcp-stdio` / `mcp-http` 只 `register(connection)`，不 provide 同一 key；注册返回幂等 `Disposable`，连接与子进程/HttpClient 生命周期归各自 scope（R3）。
- server id 全局唯一；跨 transport 撞 id fail loud（不静默遮蔽）；`connections()` 按 id 排序（稳定面）；零 server = 空列表。
- 消费面插件绑定改为 `requires "mcp-registry"`；运行期仍只经接口取连接面。

## 推翻对象

- it27 形状：`mcp-stdio` provide `McpClientService`；mcp/tool `requires "mcp-stdio"`。
- it27 挂账：「Streamable HTTP 引入时改 transport 中立 provides/requires，挂 it27.1」——已执行。

## 后果

- 两个 transport 可同时在场，消费面零改动；新增 transport 只需实现 `McpServerConnection` 并注册。
- 注册表成为组合面绑定点，装配顺序要求 `mcp-registry` 行先于 transport 行（base bundle 已按此排序）。
- it27.1 发现的类路径发现缺口（`mcp/http` 缺 `META-INF/services`）说明：**JPMS `provides` 与 classpath ServiceLoader 是两个面**，transport 行同时需要两者。

## 证据

- [stop1-registry-and-generation.txt](../../docs/plan/evidence/iteration-27.1/stop1-registry-and-generation.txt)。
- [stop3-assembly-and-verify.txt](../../docs/plan/evidence/iteration-27.1/stop3-assembly-and-verify.txt)（ServiceLoader 缺口红→绿）。
- 设计同步：[02 §MCP](../../docs/design/02-module-layout.md)、[05 §Registry](../../docs/design/05-capability-seam.md)、[12 §3](../../docs/design/12-api-stability.md)。
