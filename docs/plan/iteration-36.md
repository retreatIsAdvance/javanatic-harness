# 迭代 36 — W4 本地 Web UI（状态：四确认讨论稿——待评审；不停裁不动代码）

基线：it35 已关账（0.5 W3 JSON-RPC server 收官，master `5e37062`），工作版本 `0.5.0-SNAPSHOT`。0.5 路线（[总体计划](../design/README.md#phased-evolution-plan)）：W1 事件投影与查询（it33）→ W2 TUI 工作台（it34）→ W3 JSON-RPC server（it35）→ **W4 本地 Web UI（最后一站）**。改判主体已在 it35 T2 执行完毕（[notes 0011](../../.agents/notes/0011-web-ui-non-goal-reversal.md) + [00 §6](../design/00-overview.md#6-非-target明确不做) L198 改写 + B29 转正 + README L88 同步）——W4 四确认只需验证并引用，不重裁改判本体。

## 四确认（草案）

- **内容**：①静态服务面——W3 `RpcHttpServer` 加分派分支，单文件 .html（HTML+CSS+JS 内联，vanilla JS）随模块资源进镜像，server 直接服务（零 node 工具链、零构建链——预登记约束兑现）；浏览器打开即 UI，JS 同源 POST "/" 调 JSON-RPC（零 CORS）。②UI 功能面——会话清单 / 单会话五视图 / tail 轮询（W2 TUI 同功能面、浏览器形态），纯消费 W1 投影 + W3 JSON-RPC 方法，UI 侧零新增 RPC 方法。③组合编辑器——读面复用既有 `AppBoot.dump` 纯函数（it27 脱敏版：masked 递归脱敏、行序保留）新增只读暴露面；编辑 + 「保存前校验」复用 `--verify` 干跑（fail-loud 直接变 UX——预登记原文）；落盘由用户手动（v1 零 server 写面）。④同步面：design/README L114/L123 状态基准行更新（W1/W2/W3 已关账 + W4 计划中——it34/it35 关账时遗漏的过时行）+ AGENTS / current-state / 12 §6（若动 CLI 面）+ 全局 grep「不做 Web UI」规范面零残留复核（it35 T2 已执行，W4 复核取证）。

- **目标**：0.5 终点站——「个人工作台」的浏览器形态：读侧地基（投影五视图 + tail）的第四个消费面，同一 `--serve` 端口既是协议面也是 UI 面；组合编辑器让「换组合」从手改 YAML 变成带先验校验的可视编辑。

- **为什么**：改判裁决（0011）已把本地回环 Web UI 定为 0.5 W4 的既定形状——单用户本机消费自己的读侧数据，与「headless runner + JSON-RPC 足够」的原始立场不冲突（反对的是服务/平台化，不是本机读面）；无构建链约束（预登记 L7④）使首版成本 = 一个 .html 文件 + server 分派分支，无新第三方、无新工具链；组合编辑器复用 dump/--verify 是「现有能力当 UX 用」的最小兑现。

- **不做**：远程/多租户 Web UI（0011 边界：B29 硬线对 UI 同成立——仅 127.0.0.1、单用户、无 TLS）；会话控制写侧（任务提交/审批/答复/取消——T6 维持 v1 只读，见待裁 T3）；server 写面（组合编辑落盘由用户手动——见待裁 T3）；jobs 运行中任务面（W4/W5 面归属见待裁 T2）；SSE/WebSocket 推送（v1 轮询面）；动态配置热重载（README L123 已钉：新会话换组合）；认证/多用户；浏览器自动化测试与 node 工具链（测试面 = HTTP 契约测试 + 真机验收实录）。

## 待裁（四确认一并裁定）

| # | 待裁项 | 拟案 | 依据 |
|---|---|---|---|
| T1 | **静态服务路由形态** | 拟案 A："/" 上按方法分派——GET/HEAD → 服务 .html，POST → JSON-RPC；其余方法 405（Allow: GET, POST）；非 "/" 路径 404（单文件约束，无资产子路径）。浏览器打开 `http://127.0.0.1:8787/` 即 UI。代价：dispatch 承载契约变化（GET 405→200），既有测试改动，停 1 重新钉契约。否决 B（独立 context 如 `/ui`）：入口路径要记、与「server 直接服务 .html」的预登记语境不符、"/" 契约反而裂成两处 | [iteration-30.md](iteration-30.md) L7④「server 直接服务 .html」；`RpcHttpServer` 现状（dispatch 仅 POST "/"） |
| T2 | **jobs 任务面归属**（it34 挂「W4/W5 面」） | W4 首版**不做**，挂到 W5 或另站：jobs 是 0.4 后台任务域，W4 是会话读侧消费面，混入稀释首版；重访条件 = 用户点名 jobs 视图 | [iteration-34.md](iteration-34.md) L12「运行中任务（W4/W5 面）」 |
| T3 | **组合编辑器入首版 + 写面划界** | **入首版**（预登记已点名）。范围：读面 = dump 只读暴露（复用 `AppBoot.dump` 纯函数，形态停 1 钉：CLI flag / server 读方法 / 两者）；编辑 = 文本域编辑组合行；校验 = 保存前 `--verify` 干跑（违规清单逐项展示，复用 VerifyFailedException 面）；**落盘由用户手动（v1 零 server 写面）**——B29 只读品格延续、显式＞隐式。写面划界：组合配置写 ≠ 会话控制写——T6「控制面/写侧」指任务提交/审批/答复/取消，组合编辑不在其列，**T6 不重访**（除非 W4 点名单项控制动作）；动态配置投影（it35 T4 推迟面）以 dump 读面形态被点名实体化——T4 重访条件满足，范围 = 组合读面 only，运行中热重载维持不做 | [iteration-30.md](iteration-30.md) L7④「组合编辑器复用 dump/`--verify` 干跑做『启动前校验』」；[iteration-35.md](iteration-35.md) T4/T6 重访条件；[07](../design/07-profile-bundle.md) L224（dump 纯函数现状：masked 递归脱敏，无 CLI 读面） |
| T4 | **资源形态与模块归属** | 单文件 .html 放 `workbench/rpc` 模块资源（零新模块、零 moditect 改动——镜像模块数 69 不变）；server 从 classpath 加载 + 内存缓存；静态服务抽纯函数（path → bytes + content-type）可单测。否决外部文件路径（交付完整性优先：用户只拿 jh 二进制；热改需求 = 开发者改源重建，非首版目标）。UI 逻辑测试面 = **无浏览器测试、JS 零单测**（无 node 工具链的登记缺口）：验证 = HTTP 契约测试（GET 200 + content-type + 内容断言）+ 停 3 真机验收实录（用户检视）+ JS 保持极简（纯消费 JSON-RPC 结果直绘，渲染逻辑最小化） | [iteration-30.md](iteration-30.md) L7④「无构建链单文件 vanilla JS」；it35 停 3 `--list-modules` 实录（基线 69） |
| T5 | **CLI/交付面** | `--serve` 复用零新参数；启动 stdout 提示行补 UI 地址（`http://127.0.0.1:8787/`）；零 bundle 行、零新配置键（静态资源非插件面，`--verify` 计数 34/41/0 不变） | it35 停 2 设计定稿 stdout 一行惯例；it34 tui / it35 rpc 装配先例 |

## 停 1 设计点（裁决登记，不停裁——停 1 钉死）

1. **dump 读面形态**（T3 拟案批后）：CLI `jh --dump`（keyless 只读，与 `--serve`/`--verify` 互斥矩阵并入）vs server 读方法 vs 两者——倾向两者（CLI 给脚本面、server 给 UI 面），停 1 钉。
2. **路由分派契约细节**（T1 批后）：GET "/" 服务 .html 的 content-type / 缓存头（无缓存 vs no-store——单用户回环面，no-store 最直白）；HEAD 语义；非 "/" 404 与 POST 非 "/" 404 的同面处理；405 Allow 头词表。
3. **组合编辑「保存前校验」接线**：UI 把编辑内容 POST 给哪个面做 verify 干跑——新增 server 方法（如 `workbench/verifyComposition`，把内容写入临时 profile 干跑 AppBoot verify 断言）vs 复用 CLI 子进程——倾向 server 方法（零子进程、同步响应；写面 = 临时文件非用户落盘，不违反零写面划界）。干跑对象 = 编辑内容（不触碰用户已落盘的 profile）。

## 锚点

- [iteration-30.md](iteration-30.md) L5/L7④：Web UI 非目标改判推迟到 W3 开工前（已执行）+ W4 首版约束（无构建链单文件 vanilla JS、server 直接服务 .html、不引入 node 工具链；组合编辑器复用 dump/--verify 干跑做启动前校验）。
- [.agents/notes/0011](../../.agents/notes/0011-web-ui-non-goal-reversal.md)：改判裁决（推翻对象 = 00 §6 L198 整行；替代 = 本地回环 Web UI 入 0.5 W4 + 远程/多租户维持不做；B29 硬线对 UI 同成立）。
- [00 §6](../design/00-overview.md#6-非-target明确不做) L198（已改写）：本地回环 Web UI 入 0.5 W4（约束括注）+「ACP 等价物 = 形态类比依据，非控制面授权」（T6 措辞解释）。
- [iteration-33.md](iteration-33.md) L15：动态配置投影「属后续站，W3 四确认时裁」→ it35 T4 已裁（重访条件 = W4 配置页需求实况点名——本四确认 T3 点名）。
- [iteration-34.md](iteration-34.md) L12：运行中任务（W4/W5 面）；Web UI（W4）。
- [iteration-35.md](iteration-35.md) T4/T6：动态配置投影推迟（重访条件如上）；控制面 v1 只读（重访条件 = W4 UI 点名单项控制动作需求——T3 划界：组合编辑不触发）。
- [boundary-register.md](boundary-register.md) B29：仅 127.0.0.1 回环、单用户、无远程暴露、无 TLS——UI 面同硬线；引向 notes 0011。
- [07](../design/07-profile-bundle.md) L22（命名组合存 `~/.harness/profiles/<name>/`——编辑对象）/ L224（dump 纯函数现状：masked 递归脱敏、行序保留，无 CLI 读面）。
- [design/README](../design/README.md) L114/L123：状态基准行过时（it34/it35 已关账仍写「仍为计划」）——W4 同步面必改。

## 审查停点（草案，3 停）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1：静态服务 seam + 路由契约 + dump 读面 | 承载契约（GET/POST 分派、405/404 面、content-type、缓存头）+ 单文件资源加载 + 静态服务纯函数 + B29 硬线不变 + dump 读面形态（T3 拟案批后）+ 停 1 设计点三条钉死 | 待开工 |
| 停 2：UI 单文件 + 组合编辑器 | 单文件 .html（清单/五视图/tail 轮询 vanilla JS）+ 组合编辑器（dump 展示 / 编辑 / 保存前 verify 干跑接线）+ stdout 提示行 | 待开工 |
| 停 3：装配/同步面 + 真机验收 + 收口 | jlink 镜像（--list-modules 仍 69 + 资源进镜像取证）+ `--serve` 真机实录（浏览器打开 / 五视图 / tail 轮询 / 组合校验链——我跑面 + **用户检视实录（用户跑）**）+ B29 硬线复验 + CI 四腿 + 验收清单全勾 + 同步面（README L114/L123 更新 + grep 零残留复核）+ 收口批 | 待开工 |

停 1/停 2 出包按 review packet 仪式（本迭代零新增模块、零 base 行——资源进 workbench/rpc；四件套中「META-INF/services 双通道」不适用（无 Plugin），其余照勾）。

## 验收清单（骨架，随停点细化）

- [ ] 停 1 评审放行（packet + 裁决归档）
- [ ] 停 2 评审放行（packet + 裁决归档）
- [ ] 停 3 真机验收：jlink `--serve` → 浏览器打开 `http://127.0.0.1:8787/` 见 UI → 会话清单 / 五视图 / tail 轮询闭环 + 组合编辑器校验链（坏组合 → verify 违规清单展示 → 好组合 → 校验通过）+ **用户检视实录（用户跑）** + B29 硬线复验（监听地址实录 + 非回环访问拒绝）
- [ ] validate EXIT=0 + 全量 package EXIT=0（门禁）
- [ ] keyless `--verify` 计数不变（34/41/0——零 bundle 行、零新配置键；dump 读面与静态资源不增行）
- [ ] jlink 镜像 `--list-modules` 实录仍 69（零新模块）+ 单文件 .html 随镜像资源取证
- [ ] CI 四腿全绿 + run 号回填
- [ ] 同步面 grep 零旧措辞残留（「不做 Web UI」规范面零残留复核——it35 T2 已执行，W4 复核取证）+ design/README L114/L123 状态基准行更新

随 W4 开工的既有裁决（无需重裁）：it35 停 2 已完成的 Follower.close 赋义；lsp flake 观察账（CI 再现才动，与本迭代无关）；it34 open ledger（B34、TwoHostLostProbe 硬化、CI 工作流维护——另裁，不入 W4）。
