# it37 停 1 评审 packet — B36 读面信任围栏 + B37 Content-Type 严格性闸

**本停是否新增模块 / base 加行：全零**（新模块 0 / bundle 行 0 / 配置键 0 / 事件 0；`RequestFence` 是 workbench/rpc 包内 package-private 类，不导出、module-info 不动、零新依赖）。

## TL;DR（五行）

1. **不变量增量**：B36 读面已实施（Host 白名单 `127.0.0.1:<port>`/`localhost:<port>` 精确比对实际绑定端口 + Origin 存在即须同源，一切方法先于路由，拒 = 403 具名理由行）+ B37 新边界转正（POST `/` 仅收 `Content-Type: application/json`，读 body 前判定，其余 415——堵简单请求绕 CORS 预检变体）。W4 T1 路由矩阵在围栏放行域内逐字成立；写面准入条件维持 it38 预登记。
2. **裁决点**：无新裁——形状全按裁决②与设计稿执行；唯一增量 = 测试矩阵 +4 例（Origin 大写/空、CT 大写带参/trim，钉死规范化规则使突变可观察）与 `post()` helper 加 json 头（行为变更改测试），均取证档注明。
3. **风险自报**：::1 边缘 fail-closed 403（白名单钉死两形不扩面，重访条件在案）；M3 突变 HTTP 红 = 2 非设计稿写的 3（缺头用例走 null 早退分支未被触及，该分支由 `missingContentTypeRejected` 独立钉死——如实注不掩盖）；围栏是信任层非 auth 层，B29 单用户假设不变。
4. **证据入口**：[stop1-tdd-evidence.md](stop1-tdd-evidence.md)（红绿两段 + M1–M4 突变日志 + 全量门禁 + 消费面排查）；设计稿 [stop1-fence-design.md](stop1-fence-design.md)。
5. **若只能改一处**：`RpcHttpServer.dispatch` 头部守卫序——围栏必须先于路由（恶 Host GET `/` 得 403 而非 200 UI），这是「横切前置条件」定性的全部承载点；raw Socket 用例 `evilHostGetRootYields403BeforeRouting` 专钉此序。

## 用户评审重点（开工指令点名）：Host/Origin 矩阵 + Content-Type 闸

### 纯函数矩阵 `RequestFenceTest`（29 例，PORT=8787 字面量钉死）

| 面 | 过 | 拒 |
|---|---|---|
| Host（8） | `127.0.0.1:8787` / `localhost:8787` / `LOCALHOST:8787`（大小写） | null / 空 / `evil.com:8787` / `127.0.0.1:9999`（异端口）/ `127.0.0.1`（无端口） |
| Origin（10） | 缺失（curl/非浏览器放行）/ `http://127.0.0.1:8787` / `http://localhost:8787` / `HTTP://LOCALHOST:8787`（lowercase 规则） | `https://…`（scheme 仅 http）/ `http://evil.com` / 异端口 / `"null"` 字面量 / 尾斜杠 / 空串（存在即校验） |
| Content-Type（11） | `application/json` / 带 `; charset=utf-8` / `APPLICATION/JSON` / 大写带参 / 前后空白（trim） | form-urlencoded / text/plain / multipart / 缺失 / 空 / `application/json-patch+json`（精确媒体类型，+json 后缀不放行） |

拒绝理由字面量钉死（不与被测共享魔法值）：`host not allowed` / `origin not allowed` / `content-type must be application/json`。

### HTTP 层真往返 `RpcHttpServerTest`（+6，终值 17）

- 恶 Origin POST → **403**（Origin 非受限头，java.net.http 直设）
- 恶 Host GET `/` → **403**（raw Socket 绕发送侧 Host 限制；**证围栏先于路由**——闸前此用例得 200 UI 即 RED① 实锤）+ 正对照 loopback Host 同写法得 200（防假红）
- POST form-urlencoded / text/plain / 缺 Content-Type → **415** 三例（缺头例显式不走 helper，钉死缺头拒）
- 既有 `post()` helper 加 `Content-Type: application/json`（闸后合法 RPC 调用必带头）；五方法 e2e / 并发 tail / 405 / 404 矩阵原样复绿——405/404 用例不带 json 头仍过，闸仅在 POST `/` 处理路径

### 三行映射（规则 → 代码 → 测试）

| 边界规则 | 代码落点 | 钉死测试 |
|---|---|---|
| B36 Host/Origin（403，先于路由） | `RequestFence.hostOriginRejection` + `dispatch` 头部守卫 | Host/Origin 18 纯函数 + evilHost/evilOrigin/正对照 3 HTTP；突变 M1/M2/M4 |
| B37 Content-Type（415，读 body 前） | `RequestFence.jsonContentType` + POST `/` 分支前置闸 | CT 11 纯函数 + 415 三例 HTTP；突变 M3 |
| W4 T1 路由契约不变 | 既有路由逐字未动 | 既有 11 用例复绿（405/404/五方法/tail） |

## 验证账（全部本机实跑，命令与测量面见取证档）

- **RED①**：HTTP 层 17 跑 5 红（expected 403/415 but was 200，恰为新闸用例）；**RED②**：纯函数层编译红（找不到符号 RequestFence）。
- **GREEN**：聚焦 46/46（17+29）BUILD SUCCESS。
- **突变 M1–M4**（单文件 python 精确替换 + SHA-256 还原对账）：M1 摘 Host = 6 红 / M2 摘 Origin = 7 红 / M3 CT 恒真 = 7 红 / M4 端口通配 = 4 红；每轮 RESTORE_SHA_OK，终检复绿 46/46。日志 `/tmp/it37-stop1-mutations.log`。
- **全量 `mvn -B package`**：FULL_EXIT=0 BUILD SUCCESS；surefire XML mtime 窗口（≥09:27）求和 = **187 XML / 1521 tests / 0 failures / 0 errors / 31 skipped**（it36 基线 186/1486 → +1 XML +35 测试 = 本停新用例，口径吻合）。
- **keyless `jh --verify`**：EXIT=0，**34 rows / 41 discovered / 0 unreferenced 不变**——组合面零扰动。
- **消费面排查零额外改动**：ui.html L162 与 HeadlessServeTest.rpc() L202 均已带 json 头；活文档无 curl 示例（it35/it36 证据档 `curl -d` 冻结历史不改写，后续 runbook 一律带 `-H 'Content-Type: application/json'`）。

## 同步面（随本停提交）

boundary-register.md（B36 翻「读面已实施」+ B37 新行转正）/ 12-api-stability.md（workbench/rpc 行 + §CLI `--serve` 段围栏句）/ RpcHttpServer javadoc（守卫序 + 403/415）/ iteration-37.md 停 1 状态 / current-state.md it37 bullet。

## 不做（本停，与设计稿 §7 一致）

Sec-Fetch-Site（it38 T 项候选）/ 写面任何方法（it38）/ auth 层 / ::1 扩面 / 新模块/行/键/事件（全零已自问）。

---

**停在此处等用户新鲜点名放行**；放行后翻转批起步（versions:set 0.5.0 + 坐标机械复测 71 定案）。