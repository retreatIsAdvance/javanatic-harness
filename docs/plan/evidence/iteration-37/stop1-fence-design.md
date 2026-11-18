# it37 停 1 设计 — B36 读面信任围栏 + Content-Type 严格性闸（回补）

> 状态：设计稿（2026-11-17，用户裁决②带入 0.5.0 + Content-Type 增项后落盘）。
> 裁决依据：[iteration-37.md](../../iteration-37.md) 裁决记录②；边界 [B36](../../boundary-register.md)（Host/Origin）+ 本停新增 B37（Content-Type 闸，随实施转正）。
> 基线：`RpcHttpServer.java` 107 行全文实核（dispatch 唯一入口，Host/Origin/Content-Type 检查为零）。

## 1. 为什么（两层防御，正交闭合）

- **第一层 Host/Origin（B36）**：B29 回环恒绑防网络面（远程主机连不进来），不防浏览器面——SOP 只挡读响应不挡发请求，恶意网页可从用户浏览器向 127.0.0.1:8787 发 POST。读面时代风险低但非零（任意网站可触发读操作）；写面一开 = 任何网站都能驱动 agent。围栏是信任层非 auth 层（dsh api-request-trust 先例同口径），B29 单用户假设不变。
- **第二层 Content-Type（B37，用户裁决增项）**：跨站 JSON POST 理论上被 CORS 预检墙挡（无预检头则请求不发），**但「简单请求」形态（form-encoded / text/plain / multipart）不触发预检**——若 RPC 端点不强制 `application/json`，简单请求形态能绕预检直达。钉死「POST 仅收 `Content-Type: application/json`，其余 415」堵这一变体。

## 2. 现状实核（2026-11-17）

- `RpcHttpServer.dispatch`：GET/HEAD 仅精确 `/` 服务 UI、其余 404；非 POST 405（Allow: GET, POST）；POST 非 `/` 404；POST `/` 直读 body 进 wire。**零头部检查**。
- `ui.html` L162 fetch 已带 `headers: { "Content-Type": "application/json" }`——**UI 面零改动**。
- 活文档（README×2 / embedding.md / 12）无 curl 示例——无 runbook 同步面；it35/it36 证据档内 `curl -d` 命令是冻结历史（`-d` 缺省 form-urlencoded，新闸下会 415），不改写，**后续 runbook 一律带 `-H 'Content-Type: application/json'`**（it38 与发布后文档注意）。
- 既有测试 `RpcHttpServerTest.post()` helper 不带 Content-Type（java.net.http 缺省不发）——新闸下须更新 helper 加头（行为变更改测试，packet 说明）。

## 3. 形状（纯函数围栏 + dispatch 前置守卫）

**新类 `RequestFence`**（workbench/rpc 包内 **package-private** final，纯静态函数，零状态零 IO；同包测试可达；不导出、module-info 不动、零新依赖）：

```java
/** Host/Origin 围栏：返回拒绝理由；empty = 放行。port = 实际绑定端口。 */
static Optional<String> hostOriginRejection(String host, String origin, int port)

/** Content-Type 严格性：媒体类型（参数前、trim、 lowercase）== application/json。 */
static boolean jsonContentType(String contentType)
```

规则（全部 fail-closed，拒绝给具名理由）：

| 面 | 规则 |
|---|---|
| Host | lowercase 后**精确**等于 `127.0.0.1:<port>` 或 `localhost:<port>`（port = `server.getAddress().getPort()` 实际绑定面）；缺失/空/其余一律拒 |
| Origin | **缺失放行**（curl/非浏览器）；存在即须精确等于 `http://127.0.0.1:<port>` 或 `http://localhost:<port>`（lowercase 比较；scheme 仅 http——本地无 TLS；`null`、带尾斜杠、异端口、异主机、https 一律拒） |
| Content-Type | 仅 POST `/`（唯一处理路径）、**读 body 之前**；缺失拒；媒体类型（`;` 前、trim、lowercase）精确 `application/json`——参数（charset 等）放行，form-urlencoded / text/plain / multipart / 空 / 其余一律拒 |

**dispatch 守卫序**（围栏先于路由，横切前置）：

1. Host/Origin 围栏（**一切方法**）→ 拒 = **403** + 具名理由行（text/plain; charset=utf-8）
2. 既有路由**逐字不变**（W4 T1 契约不动：GET/HEAD 精确 `/` 200、其余 GET 404、非 POST 405、POST 非 `/` 404）
3. POST `/` Content-Type 闸 → 拒 = **415** + 具名理由行
4. wire 派发（原路）

**已知边缘（如实记，不扩白名单）**：bind 用 `InetAddress.getLoopbackAddress()`（缺省配置 = IPv4 127.0.0.1）；若宿主栈返回 ::1，Host `[::1]:port` 不在白名单 → fail-closed 403。白名单按裁决钉死两形，::1 扩面留重访（出现真实 ::1 绑定诉求再裁）。

**契约增量定性**：围栏是横切前置条件，不是路由变更——W4 T1 矩阵在围栏放行域内逐字成立；增量显式记 12 §CLI `--serve` 段 + B36/B37 行，不静默。

## 4. 测试矩阵（先红后绿）

**纯函数层 `RequestFenceTest`（新增）**：

- Host：`127.0.0.1:p` 过 / `localhost:p` 过 / `LOCALHOST:p` 过（大小写）/ 缺失拒 / 空拒 / `evil.com:p` 拒 / `127.0.0.1:错port` 拒 / 无端口拒
- Origin：缺失过 / `http://127.0.0.1:p` 过 / `http://localhost:p` 过 / `https://127.0.0.1:p` 拒（scheme）/ `http://evil.com` 拒 / `http://127.0.0.1:异port` 拒 / `null` 拒 / 尾斜杠拒
- Content-Type：`application/json` 过 / `application/json; charset=utf-8` 过 / `APPLICATION/JSON` 过 / form-urlencoded 拒 / text/plain 拒 / multipart 拒 / 缺失拒 / 空拒 / `application/json-patch+json` 拒（精确媒体类型）

**HTTP 层 `RpcHttpServerTest` 增补（真往返）**：

- 恶 Origin（`http://evil.com`）POST → **403**（Origin 非受限头，HttpClient 直设）
- 恶 Host（raw Socket 写 `Host: evil.com`——java.net.http 限制 Host 头，裸套接字绕开发送侧限制）GET `/` → **403**（证围栏先于路由：不是 200）
- POST form-urlencoded / text/plain / 缺 Content-Type → **415** 三例
- 既有 `post()` helper 加 `Content-Type: application/json`；五方法 e2e / 并发 tail / 405 / 404 矩阵原样复绿（405/404 用例不带 json 头仍过——闸仅在 POST `/` 处理路径）

## 5. 突变清单（破坏必红 → 还原逐字节 → 复绿）

| # | 突变 | 预期红 |
|---|---|---|
| M1 | 摘 Host 检查（hostOriginRejection 忽略 host） | Host 矩阵 + HTTP 恶 Host |
| M2 | 摘 Origin 检查 | Origin 跨站矩阵 + HTTP 恶 Origin |
| M3 | 摘 Content-Type 闸（dispatch 跳过检查） | 415 三例 + 纯函数拒例 |
| M4 | 端口通配（Host/Origin 比对忽略 port） | 端口不匹配矩阵 |

断言用字面量钉死（403/415/理由词），不与被测共享魔法值（it28 M7 教训）。

## 6. 同步面（随停 1 提交）

- `RpcHttpServer` javadoc（守卫序 + 403/415）
- 12-api-stability.md：L67 workbench/rpc 行 + §CLI `--serve` 段增围栏句
- boundary-register.md：B36 行翻「读面已实施（it37 停 1）」+ **B37 新行**（Content-Type 严格性闸，随实施转正）
- iteration-37.md 裁决记录 + current-state.md it37 bullet
- 取证：`stop1-tdd-evidence.md`（红绿 + 突变日志）+ `stop1-review-packet.md`

## 7. 不做（本停）

- Sec-Fetch-Site defense-in-depth（it38 四确认 T 项候选，本停不预支）
- 写面任何方法（it38）；auth 层（围栏 ≠ 认证，B29 单用户不变）
- ::1 白名单扩面（fail-closed 边缘如实记，重访条件在案）
- 新模块 / base 行 / 配置键 / 事件（全零——packet 首行自问答案）