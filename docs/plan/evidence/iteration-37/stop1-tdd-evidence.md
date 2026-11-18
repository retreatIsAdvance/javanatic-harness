# it37 停 1 TDD 取证 — B36 读面信任围栏 + B37 Content-Type 闸

> 日期：2026-11-18。基线：开工批 `e831117`（裁决归档 + 设计稿）；实现与测试随本停提交。
> 设计稿：[stop1-fence-design.md](stop1-fence-design.md)。测量面：本机 macOS arm64，JDK 25（`/usr/libexec/java_home -v 25`），Maven 3.10.0（`~/Documents/apache-maven-3.10.0`）。
> 聚焦命令口径：`mvn -B -pl workbench/rpc -am test -Dtest=RequestFenceTest,RpcHttpServerTest -Dsurefire.failIfNoSpecifiedTests=false`（并集逗号 + 无匹配模块静默跳过，pits 纪律）。

## 1. RED①（HTTP 层行为红）

先增 `RpcHttpServerTest` 六用例（不依赖新类，真 HTTP 往返），跑聚焦：

```
Tests run: 17, Failures: 5, Errors: 0, Skipped: 0 -- in RpcHttpServerTest
BUILD FAILURE
```

五红恰为新闸用例（断言失败，非编译错）：

| 用例 | 期望 | 实际（闸前） |
|---|---|---|
| evilOriginPostYields403 | 403 | 200 |
| evilHostGetRootYields403BeforeRouting（raw Socket） | HTTP/1.1 403 | HTTP/1.1 200（UI 被服务——证围栏须先于路由） |
| postFormEncodedYields415 | 415 | 200 |
| postTextPlainYields415 | 415 | 200 |
| postWithoutContentTypeYields415 | 415 | 200 |

正对照 `loopbackHostGetRootYields200ViaRawSocket` 绿（裸套接字写法本身合法，防假红）；既有 11 用例全绿（helper 未动、闸未落）。

## 2. RED②（纯函数层编译红）

增 `RequestFenceTest`（29 例）后跑聚焦：`找不到符号 RequestFence`（类缺失 = 功能缺失红），BUILD FAILURE。

## 3. GREEN

实现 `RequestFence`（package-private final，纯静态零状态零 IO，module-info 不动）+ `RpcHttpServer.dispatch` 头部守卫（Host/Origin 一切方法先于路由 → 403 具名理由行；POST `/` 读 body 前 Content-Type 闸 → 415）+ `respondText` 共用拒绝响应（text/plain; charset=utf-8）。既有 `post()` helper 加 `Content-Type: application/json` 头（行为变更改测试——闸后缺头 POST 一律 415，helper 承载的是合法 RPC 调用）。

```
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in RpcHttpServerTest
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 -- in RequestFenceTest
BUILD SUCCESS（MVN_EXIT=0）
```

**矩阵增项（设计稿 §4 之上 +4，钉死规范化规则否则突变不可观察）**：Origin `HTTP://LOCALHOST:8787` 过（lowercase 规则）/ Origin 空串拒（存在即校验，fail-closed）；Content-Type `APPLICATION/JSON; charset=UTF-8` 过（lowercase+参数组合）/ `  application/json  ` 过（trim 规则）。终矩阵 = Host 8 + Origin 10 + Content-Type 11 = 29 纯函数 + 6 HTTP = 35 新用例。

**拒绝理由字面量钉死**（不与被测共享魔法值）：`host not allowed` / `origin not allowed` / `content-type must be application/json`。

## 4. 突变 M1–M4（破坏必红 → 还原逐字节 → 复绿）

脚本 `/tmp/it37-mutate.sh`；原文件 SHA-256 = `195de627cfbe68c90811f99e046d92e7e1a5514441715522b33be099d7b97757`；每轮还原后 SHA 对账 = RESTORE_SHA_OK。日志：`/tmp/it37-stop1-mutations.log`（各轮完整输出 `/tmp/it37-m{1,2,3,4}.log`）。

| # | 突变（RequestFence.java 单文件） | 红（纯函数 + HTTP） | 红用例 |
|---|---|---|---|
| M1 | Host 条件改 `!normalizedHost.equals(normalizedHost)`（恒放行） | 5 + 1 = 6 | missingHost/emptyHost/foreignHost/wrongPortHost/portlessHost + evilHostGetRootYields403BeforeRouting |
| M2 | Origin 守卫改 `origin != null && !origin.equals(origin)`（恒跳过） | 6 + 1 = 7 | httpsOrigin/foreignOrigin/wrongPortOrigin/nullLiteralOrigin/trailingSlashOrigin/emptyOrigin + evilOriginPostYields403 |
| M3 | `return mediaType.equals("application/json") \|\| mediaType != null;`（恒 true） | 5 + 2 = 7 | formUrlencoded/textPlain/multipart/emptyContentType/jsonPatch + postFormEncoded/postTextPlain |
| M4 | 四处 equals 改 startsWith（端口通配） | 4 + 0 = 4 | wrongPortHost/portlessHost/wrongPortOrigin/trailingSlashOrigin |

每轮 BUILD FAILURE（MVN_EXIT=1）；还原后终检聚焦 **46/46 全绿 BUILD SUCCESS**。

**M3 如实注**：HTTP 红 = 2 非 3——`postWithoutContentTypeYields415` 走 null 早退分支（`if (contentType == null) return false;`），本突变不触该分支；该分支由 `missingContentTypeRejected` 钉死（摘 null 守卫 → indexOf NPE → 红），覆盖自洽。

## 5. 消费面排查（零额外改动）

- `ui.html` L162 fetch 已带 `Content-Type: application/json`——UI 零改动。
- `HeadlessServeTest.rpc()`（examples/headless CLI 集成）L202 已带 json 头——零改动。
- 活文档无 curl 示例；it35/it36 证据档 `curl -d` 为冻结历史不改写（后续 runbook 带 `-H 'Content-Type: application/json'`）。
- 405/404 用例不带 json 头仍绿——闸仅在 POST `/` 处理路径（围栏放行后、路由分派内），与设计守卫序一致。

## 6. 全量门禁（2026-11-18 09:31–09:35）

**全量 `mvn -B package`**（`export JAVA_HOME=$(/usr/libexec/java_home -v 25)` + `~/Documents/apache-maven-3.10.0/bin/mvn`，全量输出落 `/tmp/it37-stop1-full.log` 再 grep——不接管道）：

```
BUILD SUCCESS（Total time 03:47，Finished 2026-11-18T09:31:46+08:00）
FULL_EXIT=0
```

**测试计数（测量面 = 全 reactor surefire XML，mtime 窗口 ≥ 09:27:00 过滤陈旧/孤儿 XML）**：

```
find . -path '*/surefire-reports/TEST-*.xml' -newermt '2026-11-18 09:27:00' | python3 <求和 tests/failures/errors/skipped>
→ XML files=187 tests=1521 failures=0 errors=0 skipped=31
```

较 it36 基线（186 XML = 1486/0/0/31）净增 35 测试 + 1 XML 文件 = 本停新用例（29 纯函数 `RequestFenceTest` + 6 HTTP），口径吻合。

**keyless `dist/jh/target/jlink-image/bin/jh --verify`**（`/tmp/it37-stop1-verify.log`）：

```
VERIFY_EXIT=0
composition: 34 rows, 41 discovered, 0 unreferenced
```

34/41/0 与发布口径不变——围栏不新增模块/行/键，组合面零扰动。

## 7. 已知边缘（如实记）

- ::1：bind 用 `InetAddress.getLoopbackAddress()`（缺省 IPv4）；若宿主栈返回 ::1，Host `[::1]:port` 不在白名单 → fail-closed 403。白名单按裁决钉死两形，扩面留重访。
- 围栏是信任层非 auth 层；B29 单用户假设不变；写面准入条件维持 it38 预登记。