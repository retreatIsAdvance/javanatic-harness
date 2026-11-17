# 停 1 设计定稿：静态服务 seam + 路由契约重钉 + dump 读面（it36 W4）

四确认 T1–T5 全批（2026-11-17，[iteration-36.md](../../iteration-36.md) 裁决记录）。本档钉死停 1 三件的契约面、装配面与同步面；实现与测试随 S2（TDD）。停 1 范围 = 静态服务 seam + 路由契约重钉 + dump 读面（CLI flag + server 方法）；**组合编辑器 UI（文本域 + verify 干跑接线）属停 2**，本档只预告其方法契约。

## 1. 路由契约重钉（T1 精度落地）

`RpcHttpServer.dispatch` 现状：仅 POST `/` → JSON-RPC；非 POST → 405（Allow: POST）；非 `/` → 404。W4 重钉为按方法 + 精确路径分派：

| 方法 | 路径 | 响应 |
|---|---|---|
| GET / HEAD | 精确 `/` | **200** + `Content-Type: text/html; charset=utf-8` + `Cache-Control: no-store` + body = ui.html 字节（HEAD 由 jdk.httpserver 自动抑制 body） |
| GET / HEAD | 其余一切（含 `/favicon.ico`、`/index.html`、`/ui`） | **404**（T1 精度：不泛化成目录服务，单文件无资产子路径） |
| POST | 精确 `/` | JSON-RPC（既有 `wire.handle`，契约不变） |
| POST | 其余 | **404**（既有） |
| 其余方法（PUT/DELETE/…） | 任意 | **405** + `Allow: GET, POST`（词表从 `POST` 扩为 `GET, POST`） |

- **契约变化面**：GET `/` 从 405 → 200（pre-release 面内，四确认已批）；405 Allow 词表 `POST` → `GET, POST`。既有测试 `nonPostYields405WithAllowHeader`（GET `/` 断 405）改判为 PUT `/` 断 405 + 新 GET `/` 断 200 用例。
- **缓存头**：`Cache-Control: no-store`——单用户回环面，每次取最新（改 .html 重启即生效，无缓存 stale 顾虑）；不设 ETag/Last-Modified（单文件、无版本协商需求）。
- **B29 硬线不变**：绑定面 `InetAddress.getLoopbackAddress()` 恒 127.0.0.1，静态服务不引入任何新监听/远程面。

## 2. 静态服务 seam：`WorkbenchUi`（新类，workbench/rpc 内）

- **职责**：从 classpath 加载单文件 .html 一次（构造期），内存缓存字节；供 dispatch 读取。这是「静态服务抽纯函数可单测」的承载面。
- **资源路径**：`workbench/rpc/src/main/resources/io/javanatic/harness/workbench/rpc/ui.html`（随模块 jar 进 jlink 镜像——jlink 复制模块资源，**零 moditect 改动、镜像 69 不变**，停 3 取证）。
- **形状**：
  - `public static final String CONTENT_TYPE = "text/html; charset=utf-8"`。
  - `public WorkbenchUi()`：加载缺省资源路径；**缺失即 fail loud**（`IllegalStateException`——资源是模块自带，缺失 = 打包错误，不静默服务空页）。
  - 包私有 `WorkbenchUi(String resourcePath)`：测试注入路径，钉 fail-loud 负例（不存在的路径 → 抛）。
  - `public byte[] html()`：返回缓存字节的防御性拷贝（dispatch 只读消费；拷贝防调用方 mutate 缓存）。
- **停 1 资源内容**：最小占位页（标题 + 「W4 UI——功能面停 2 落地」说明 + 一个可断言的标记串）。**停 1 只证服务面，不证 UI 功能**；停 2 填清单/五视图/tail/编辑器。软预算 ~800 行观察项从停 2 起计（T4）。
- **RpcHttpServer 装配**：内部持 `WorkbenchUi`（构造期 `new WorkbenchUi()`）；单参构造 `RpcHttpServer(handler)` 不变（UI 是模块静态资源，无 fake 需求，不注入）。dispatch 增 GET/HEAD 分支读 `ui.html()`。
- **module-info 零改动**：资源不需 requires；jdk.httpserver 已 requires；零新依赖、零新导出包。

## 3. dump 读面（T3 读面实体化；CLI flag + server 方法两者）

**模块图纪律**：`workbench/rpc` 不依赖 `bundle/base`——server 方法不能直接调 `AppBoot`。dump 文本由 HeadlessMain（依赖 bundle/base）在启动期算好，作为 **String 快照**注入 `WorkbenchRpc`。组合启动后不变（热重载维持不做）⇒ String 快照是正确形状（非 Supplier）。

### 3.1 dump 文本口径（钉死）

`AppBoot.dump(AppBoot.compose(boot))`——**组合期行序（含禁用行注解）**，非 resolve 后的启用行：
- `compose(boot)` = 三层叠加（bundle + profile + overlays）行序，表达式未求值、config 经 `ConfigValues.masked` 递归脱敏（it27 词表）。
- 选 compose 而非 resolve 的理由：编辑器读的是「组合如何配置」的忠实投影——禁用行（如 `lsp-jdtls (disabled: …)`）可见、原始表达式可见，最贴近用户编辑对象；resolve 会滤掉禁用行、丢失 `(disabled: …)` 分支。
- `Booted` 不保留行序 ⇒ 经 `compose(boot)` 重算（纯组合期、读 profile/bundle，零插件加载；与 bootReported 内部 `compose(profile, options)` 同源确定性）。重读开销可忽略（profile YAML 小）。

### 3.2 server 方法 `workbench/compositionDump`

- `WorkbenchRpc` 构造注入 dump 快照。构造器形状（pre-release 调整，既有 call-site 随改）：
  - `WorkbenchRpc(persistence, tail, String compositionDump)` → 缺省预算 8 MiB。
  - `WorkbenchRpc(persistence, tail, String compositionDump, long maxResponseBytes)` → 全参。
  - 既有 2 参 / 3 参（maxResponseBytes）构造移除，call-site 更新（RpcHttpServerTest ×2、WorkbenchRpcTest ×1、HeadlessMain.runServe ×1）。
- 方法面：`workbench/compositionDump`（无参，忽略 params）→ 返回 `{"dump": "<text>"}`；受既有响应预算约束（超限 -32004）。未知方法仍 -32601。
- `runServe` 签名增 dump 参数：`runServe(Scope root, int port, String compositionDump, PrintStream out, PrintStream err)`；dump 文本在 `run()`（boot 在作用域）算好传入。

### 3.3 CLI `jh --dump`（新 flag，T3 授权内；非 T5「--serve 零新参数」所指）

- **词表**：`jh --dump`——keyless 只读旁路，打印 `AppBoot.dump(AppBoot.compose(boot))` 到 stdout，exit 0。
- **dispatch 位置**：与 `--verify`/`--sessions` 同段（bootReported 之后的旁路块）——**post-boot 一致性**优先于「纯组合期免 boot」的轻量优化（后者是可能的后续精化，非停 1 范围；代价 = 随既有旁路加载插件，如实登记）。
- **互斥**：与任务文本 / `--resume` / `--verify` / `--sessions` / `--session` / `--session-view` / `--workbench` / `--serve` 互斥（exit 2，同族「一次只做一件事」）。
- **RunnerOptions** record 增 `boolean dump`；parse 增 `--dump` 分支；usage/help 增行。
- **stdout 契约**：成功 = dump 文本（一行一插件）；失败为空、诊断走 stderr（既有口径）。

## 4. 停 2 预告：组合编辑器 verify 干跑方法契约（本停不实现）

- 方法 `workbench/verifyComposition`（params `{yaml: "<编辑内容>"}`）→ 把内容写**临时 profile** 干跑 AppBoot verify 断言 → 返回 `{ok: true}` 或 `{ok: false, violations: [...]}`（复用 `VerifyFailedException.violations()` 面）。
- 干跑对象 = 编辑内容（临时文件），**不触碰用户已落盘 profile**——零 server 写面承诺不破（写的是临时干跑文件，非用户数据落盘）。
- 装配注入 verify 能力（bundle/base 的 AppBoot），workbench/rpc 仍不依赖 bundle——注入函数接口。形状停 2 钉。
- UI 侧：文本域编辑 + 「保存前校验」按钮调此方法 + blob 下载（`download` 属性，浏览器端落盘不经 server，T3 增强）。

## 5. 装配面 / 同步面清单

| 面 | 改动 |
|---|---|
| workbench/rpc 模块 javadoc + module-info 注释 | 方法面 v1 三件 → 四件（+ compositionDump）；静态服务面一句 |
| `WorkbenchUi`（新）+ ui.html 资源（新） | §2 |
| `RpcHttpServer.dispatch` | §1 路由重钉 |
| `WorkbenchRpc` 构造 + compositionDump 方法 | §3.2 |
| `HeadlessMain`：RunnerOptions.dump + parse + 互斥 + help + run() dump 旁路 + runServe 签名 | §3.3 / §3.2 |
| 12 §6 CLI 冻结面 | `--dump` flag + 语义句；导出面表 workbench/rpc 行注记 compositionDump（0.5 增量面） |
| 02 §6 / 00 §6 | 若涉模块职责措辞（workbench/rpc 静态服务 + dump 读面）随停 2/停 3 同步 |
| iteration-36.md | 停 1 状态 + 设计点钉死回填 |
| dist/jh moditect | **零改动**（资源随 workbench/rpc jar 进镜像；镜像 69 不变，停 3 --list-modules 取证） |
| `--verify` 计数 | **34/41/0 不变**（零 bundle 行、零新配置键；dump/静态资源非插件面） |

## 6. 测试面（S2 TDD）

- **路由契约矩阵**（RpcHttpServerTest，真 HTTP 往返）：GET `/` → 200 + content-type + no-store + body 含标记；GET `/favicon.ico` → 404；GET `/index.html` → 404；POST `/nope` → 404（既有）；PUT `/` → 405 + Allow: GET, POST；HEAD `/` → 200 无 body；POST `/` 三方法闭环（既有，回归）。
- **WorkbenchUiTest**：缺省资源加载非空 + content-type 常量；不存在路径 → fail loud（包私有构造注入）。
- **WorkbenchRpcTest**：compositionDump 返回注入快照；未知方法 -32601（回归）；响应预算超限 -32004（回归）。
- **HeadlessDumpTest**（或并入 HeadlessOptionsTest/HeadlessServeTest）：`--dump` 解析 + 互斥矩阵 + stdout dump 文本 + exit 0；keyless。
- **突变检查**：路由分派（GET `/` 改回 405 / 精确路径判定泛化）、dump 快照注入（返回空串）、fail-loud（吞缺失资源）——破坏必红 → 还原复绿。

## 7. 出包门槛（S3）

聚焦 + validate EXIT=0 + 突变必红复绿 + keyless `--verify` 34/41/0 不变取证 + review packet（五行 TL;DR + 新增模块自问「零新增模块、零 base 行」+ 四件套）。停下等用户点名放行。
