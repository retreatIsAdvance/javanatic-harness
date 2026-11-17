# it36 停 1 TDD 取证 —— 静态服务 seam + 路由契约重钉 + dump 读面

取证时间：2026-11-17。命令惯例（macOS/zsh，dir_path 恒仓根）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MVN=~/Documents/apache-maven-3.10.0/bin/mvn
```

聚焦矩阵 85 = workbench/rpc 40（WorkbenchUiTest 3 + RpcHttpServerTest 11 + WorkbenchRpcTest 26）+ examples/headless 45（HeadlessDumpTest 5 + HeadlessServeTest 3 + HeadlessOptionsTest 29 + HeadlessVerifyTest 8）。JsonRpcWireTest 13 为 it35 既有帧层，不在本停聚焦命令内，回归由全量 package 覆盖。S2 全程 TDD：每用例先写测试→跑红→最小实现→跑绿；红证据两条链：① 两波红绿实录（§1）；② 突变 M1–M5 破坏红（§2）。

## 1. 聚焦面（S2 两波红绿 + 终态复绿，surefire XML 口径）

聚焦命令（S2 终态复绿跑，2026-11-17 09:50）：

```bash
$MVN -B -pl workbench/rpc,examples/headless -am \
  -Dtest=WorkbenchRpcTest,RpcHttpServerTest,WorkbenchUiTest,HeadlessDumpTest,HeadlessServeTest,HeadlessOptionsTest,HeadlessVerifyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
# BUILD SUCCESS；console 面 rpc 40/40 + headless 45/45
```

surefire XML 求和（只取 7 个聚焦类；两模块 surefire-reports 目录含 11-16 旧全量跑残留 XML——JsonRpcWireTest/AskVisibilityProbe 等 25 份，mtime 11-16/11-06，不计入本测量面；7 份聚焦 XML mtime 均 11-17 09:50）：

```text
workbench/rpc:   RpcHttpServerTest 11 + WorkbenchRpcTest 26 + WorkbenchUiTest 3 = 40
examples/headless: HeadlessDumpTest 5 + HeadlessServeTest 3 + HeadlessOptionsTest 29 + HeadlessVerifyTest 8 = 45
SUREFIRE_SUM tests=85 failures=0 errors=0 skipped=0
```

两波红绿实录（S2 console 直察，未落盘日志文件——形态如实记录）：

- **波 1（rpc 面）**：WorkbenchUiTest 3 用例 + RpcHttpServerTest 路由 5 用例先行——WorkbenchUi 类缺席致编译红（挡住路由测试执行）；先实现 WorkbenchUi 让 Ui 3 用例转绿，再单独观察路由 5 红：getRootServesUiHtmlWithNoStore / getFaviconYields404 / getIndexHtmlYields404 / headRootYields200WithoutBody 均 405≠预期（200/404），unsupportedMethodYields405WithAllowGetPost 的 Allow 头 `POST`≠`GET, POST`。dispatch 重钉后 5 绿。WorkbenchRpcTest compositionDump 3 用例先红（方法不存在 -32601 / 构造器形状编译红）→ 构造器 + 方法实现后绿。
- **波 2（headless 面）**：HeadlessDumpTest parse/互斥用例先红（--dump 未识别 → 用法错误文案不含 --dump）→ parse + 8 项互斥实现后绿；dump stdout 用例红（旁路缺席 → 走任务腿 exit 2）→ run() dump 旁路实现后绿；HeadlessServeTest runServe 新签名编译红 → 接线后绿（e2e 增 compositionDump 往返断言）。

## 2. 突变面 M1–M5（破坏必红 → 还原复绿；console 直察）

设计 §6 突变矩阵三件扩为五件（路由两件 + dump 注入 + fail-loud + CLI 旁路）。施加/还原均经 Edit 逆操作；红计数为当次聚焦跑 console 观察值。

| # | 突变点 | 形态 | 红（计数/形态） |
|---|---|---|---|
| M1 | RpcHttpServer.dispatch | `"GET".equals(method)` → `"XGET".equals(method)` | 3 红：getRoot/getFavicon/getIndexHtml（GET 落 405 分支；HEAD 分支不受影响仍绿） |
| M2 | RpcHttpServer.dispatch GET/HEAD 分支 | `if (!root)` → `if (root)`（精确路径判定反转） | 4 红：M1 三面 + headRootYields200WithoutBody（HEAD `/` 也 404） |
| M3 | WorkbenchRpc.compositionDump | `out.put("dump", compositionDump)` → `out.put("dump", "")` | 3 可观察红：compositionDumpReturnsInjectedSnapshot / compositionDumpIgnoresParams 断言红 + compositionDumpOversizedYieldsResponseTooLarge 呈 ERROR（NPE——空串小于预算、error 节点缺席，helper 直取空指针；有效杀突变，形态如实记录） |
| M4 | WorkbenchUi 构造器 | 缺失资源 fail-loud 吞掉（`throw IllegalStateException` → `html = new byte[0]`） | 1 红：missingResourceFailsLoud |
| M5 | HeadlessMain.run() dump 旁路 | `out.print(AppBoot.dump(AppBoot.compose(boot)))` → `out.print("")` | 2 红：dumpPrintsCompositionRowsKeylessWithDisabledAnnotations / dumpMasksSensitiveConfigValues |

还原后全套聚焦复绿：85/85（§1 终态跑即还原后复绿跑）。

## 3. 零残留终检（新鲜复核，2026-11-17 S3）

```bash
grep -rn "XGET" workbench/rpc/src/main/ examples/headless/src/main/   # 零命中（exit 1）
grep -n 'out.print("")' .../HeadlessMain.java                          # 零命中（exit 1）
grep -n "new byte\[0\]" .../WorkbenchUi.java                           # 零命中（exit 1）
git diff --check                                                       # DIFFCHECK_EXIT=0
```

## 4. 门禁面（闸单场跑，禁并行构建）

```bash
$MVN -B -q validate; echo "VALIDATE_EXIT=$?"
# VALIDATE_EXIT=0（spotless + checkstyle 全 reactor；sun.misc.Unsafe WARNING 为 spotless 既有噪声）
$MVN -B package > /tmp/it36-stop1-package.log 2>&1; echo "PACKAGE_EXIT=$?"
# PACKAGE_EXIT=0；BUILD SUCCESS（2026-11-17 10:02 收）
```

全量 package surefire 汇总（mtime ≥ 11-17 09:58 过滤，只计本次跑写出的 XML）：

```text
FULL_SUREFIRE_SUM（185 XML）tests=1471 failures=0 errors=0 skipped=31
# skipped 31 = assumption-gated 探针（POSIX/真 TTY/key 在环等），既有口径
# 排除 2 份孤儿残留 XML（源码已删、本次未跑）：
#   skill/tool IZProbeTest（10-13）、examples/headless AskVisibilityProbe（11-06，failures=1 旧红）
# 目录全量求和会误计孤儿——测量面以 mtime 过滤为准（find 确认两源码均不存在）
```

## 5. keyless --verify 计数不变取证（34/41/0）

真镜像实跑（本次 package 新构建的 jlink 镜像；`env -u DEEPSEEK_API_KEY` 保证 keyless；输出留档 /tmp/it36-stop1-verify.txt、/tmp/it36-stop1-dump.txt）：

```bash
TMPHOME=$(mktemp -d)
env -u DEEPSEEK_API_KEY HOME="$TMPHOME" dist/jh/target/jlink-image/bin/jh --verify
# VERIFY_EXIT=0
# Profile: headless   policy: STANDARD
#   composition: 34 rows, 41 discovered, 0 unreferenced   ← 计数不变（零 bundle 行、零新配置键）
#   approval: AUTO (approval-auto; exempt=ask_user)
#   stop: max-turns=50 max-steps=40 budget=unlimited
env -u DEEPSEEK_API_KEY HOME="$TMPHOME" dist/jh/target/jlink-image/bin/jh --dump
# DUMP_EXIT=0；48 行；禁用行注解 + 脱敏实证：
#   llm-openai-compat {name=deepseek, baseUrl=…}  (disabled: ${env:DEEPSEEK_API_KEY} == null)
#   approval-ask  (disabled: true)
#   web-search-deepseek {…, maxTokens=****, …, apiKeyEnv=****}  (disabled: …)
```

备注：① HOME 覆盖未改 JVM user.home（macOS 从 passwd 取），audit 行仍显示真实 ~/.harness/sessions——两腿均只读旁路，零会话创建零写；② stderr 的 sandbox INFO 行为 it24 既有观察面，非本停噪声；③ 聚焦测试 HeadlessVerifyTest 8 用例同钉 34/41/0（双测量面一致）。

## 6. 测量面备注

- 聚焦计数只认 surefire XML 求和（项目惯例）；本档 §1 已注明残留 XML 排除口径（按类名 + mtime 双重核对）。
- S2 红/突变跑为 console 直察（无 /tmp 日志落盘），红计数与形态按会话实录登记；可复验面 = §2 突变形态表（施加后按 §1 聚焦命令重跑即红）。
- run() 内 serve 腿的 dump 接线（`AppBoot.dump(AppBoot.compose(boot))` 表达式）无直接单测面：runServe 直调 e2e 覆盖注入消费（HeadlessServeTest）、--dump CLI 覆盖同表达式（HeadlessDumpTest）；真机 serve compositionDump 往返保真留停 3 curl 补证。