# 停 2 TDD 证据档（it36 W4：verifyComposition 干跑 + UI 功能面 + stdout 提示行）

日期：2026-11-17。设计事实源：[stop2-ui-design.md](stop2-ui-design.md)。所有 mvn 调用 = `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` + `~/Documents/apache-maven-3.10.0/bin/mvn`，工作目录 = 仓库根。**所有跑均落日志 `/tmp/it36-s2-*.log`（停 1 verdict 备注 2 兑现）**；计数权威面 = surefire XML 求和 + **mtime 窗口过滤**（AGENTS.md 计数条，孤儿 XML 第三变体）。

## 1. 红绿链（S1：server 面 + 装配干跑 + stdout 行）

| 步 | 内容 | 日志 | 结果 |
|---|---|---|---|
| RED1 | WorkbenchRpcTest +7 用例（FakeVerifier 注入）+ RpcHttpServerTest e2e 五方法扩展先行 → rpc 编译红（第五方法/新构造不存在） | /tmp/it36-s2-red1.log | COMPILATION ERROR（预期红） |
| GREEN1 | WorkbenchRpc 第五方法 + CompositionVerifier seam + RpcJson.yaml + 构造器 pre-release 调整 → rpc 全模块 | /tmp/it36-s2-green1.log | **60/60**（wire 13 + WorkbenchRpcTest 33 + RpcHttpServerTest 11 + WorkbenchUiTest 3；surefire XML，mtime 窗口 600s） |
| RED2 | HeadlessDryRunVerifyTest（新类 5 用例）+ HeadlessServeTest 干跑往返/UI 行断言先行 → headless 编译红（dryRunVerify/runServe 新签名不存在） | /tmp/it36-s2-red2.log | COMPILATION ERROR（预期红） |
| GREEN2 意外红 | 实现后聚焦跑 2 errors + 1 failure：坏 YAML 用例期望 ok:false 实得 -32603 | surefire XML 实读 | **根因**：snakeyaml 坏 YAML 抛 `MarkedYAMLException`（RuntimeException 而非 IOException），窄 catch 漏给 wire 兜底 |
| 修复 | VerifyFailedException 之后宽 catch RuntimeException → `"组合错误: <SimpleName>: <msg>"`；设计 §2.1 item 4 同步回填实测发现 | — | 复跑全绿 |
| GREEN2 | headless 聚焦（HeadlessDryRunVerifyTest + HeadlessServeTest） | /tmp/it36-s2-green2.log | **8/8**（5+3；mtime 窗口 300s） |
| GREEN3 | headless 全模块回归 | /tmp/it36-s2-green3.log | **130/130**（28 新鲜 XML 类；mtime 窗口 1500s；126 基线 + 5 新 − 1 合并口径见 §7 孤儿注） |

spotless 先拦插曲：HeadlessDryRunVerifyTest 新文件缺尾换行，spotless-check 在编译前失败——`mvn -pl examples/headless,workbench/rpc spotless:apply`（/tmp/it36-s2-spotless.log）后重跑取得真编译红 RED2。

## 2. 红绿链（S2：ui.html 功能面 + 资源内容守卫）

| 步 | 内容 | 日志 | 结果 |
|---|---|---|---|
| RED3 | WorkbenchUiTest +3 守卫用例（五方法名串 / 功能面元素 id / Blob+download+零 innerHTML）对占位页跑 | /tmp/it36-s2-red3.log | **3 failures**（WorkbenchUiTest 6 例中 3 红，预期红） |
| GREEN4 | ui.html 重写 441 行功能面（两页签 / 清单 / 五视图子页签 / tail 2s 轮询 / 编辑器三按钮 + blob 下载）→ rpc 全模块 | /tmp/it36-s2-green4.log | **63/63**（60 + 3 守卫；mtime 窗口 600s） |

**行数报告（T4 软预算）**：`wc -l workbench/rpc/src/main/resources/io/javanatic/harness/workbench/rpc/ui.html` = **441 行 < ~800 软预算**。

## 3. S3 门禁

| 门 | 命令 | 日志 | 结果 |
|---|---|---|---|
| spotless | `mvn -B -q -pl examples/headless,workbench/rpc spotless:apply` | /tmp/it36-s2-spotless-s3.log | EXIT=0 |
| validate | `mvn -B -q validate` | /tmp/it36-s2-validate.log | EXIT=0 |
| 全量 package | `mvn -B package`（起点时间戳 /tmp/it36-s2-full-start.ts） | /tmp/it36-s2-full-package.log | **FULL_EXIT=0**，BUILD SUCCESS 03:48 min；surefire XML mtime ≥ 起点：**186 类 1486 测试 0 失败 0 错误 31 跳过**（既有平台门），孤儿过滤 2 件 |
| 同步面残留 | `grep -rn "四方法\|三方法\|四件\|三件" docs/design/ workbench/rpc/src/main examples/headless/src/main` | — | 规范面零残留（命中均为 kernel/seam 无关旧词「三件套」） |

## 4. 突变台账 M1–M5（全部落日志；破坏必红 → 还原复绿）

聚焦命令形：`mvn -B -o -pl <模块> -am test -Dtest=<守类> -Dsurefire.failIfNoSpecifiedTests=false > /tmp/it36-s2-m<N>-{red,green}.log 2>&1; echo EXIT=$?`

| # | 突变 | 靶点 | 红日志/EXIT | 守用例（实测命中） | 绿日志/EXIT |
|---|---|---|---|---|---|
| M1 | ok 判定反转（`violations.isEmpty()` → `true`） | WorkbenchRpc.verifyComposition | m1-red.log / 1 | verifyCompositionPassesViolationsThroughInOrder（33 例 1F） | m1-green.log / 0（33/33） |
| M2 | violations 数组摘除（if 块删） | 同上 | m2-red.log / 1 | 同用例 + verifyCompositionOversizedYieldsResponseTooLarge（1F+1E：清单摘除后响应缩入预算，-32004 期望落空） | m2-green.log / 0（33/33） |
| M3 | yaml 校验摘除（缺失/空/型错放行） | RpcJson.yaml | m3-red.log / 1 | Missing/Empty/NonString 三用例全中（3E） | m3-green.log / 0（33/33） |
| M4 | UI 提示行摘除 | HeadlessMain.runServe stdout | m4-red.log / 1 | serveEphemeralRoundTripThenSigintStops（stdout 两行断言） | m4-green.log / 0（3/3） |
| M5 | ui.html 标记串摘除 | ui.html L92 注释 | m5-red.log / 1 | defaultResourceLoadsNonEmptyHtmlWithMarker + getRootServesUiHtmlWithNoStore（双守：内容守卫 + HTTP 静态面） | m5-green.log / 0（6/6 + 11/11） |

**零残留实证**：突变前 `git diff | shasum -a 256` = `70f34731…6028c3`、`git status --short | shasum` = `b51f506f…39e62`；五轮还原后两指纹**逐字节一致**；`git diff --check` EXIT=0。

## 5. keyless 真镜像取证（T5：零 bundle 行、零新配置键）

- 镜像 = 全量 package 产物 `dist/jh/target/jlink-image`（含本停全部改动）。
- `env -u DEEPSEEK_API_KEY dist/jh/target/jlink-image/bin/jh --verify` → **VERIFY_EXIT=0**，`composition: 34 rows, 41 discovered, 0 unreferenced`（停 2 基线不变）。日志：/tmp/it36-s2-verify-keyless.log。
- `java --list-modules` = **69 模块**（镜像不变，T4）。日志：/tmp/it36-s2-list-modules.log。
- **资源进镜像实证**：`$JAVA_HOME/bin/jimage extract --dir /tmp/it36-s2-jimage dist/jh/target/jlink-image/lib/modules` → `io.javanatic.harness.workbench.rpc/io/javanatic/harness/workbench/rpc/ui.html` 存在，**441 行**、标记串与 `workbench/verifyComposition` 方法名各 1 命中（jimage 压缩格式，裸 grep lib/modules 不作数，抽取后 grep 为准）。抽取目录取证后已清理。

## 6. 交付面行数与形态

- ui.html **441 行**（软预算 ~800 内）；vanilla JS 零依赖、零外部请求、零 localStorage；动态内容一律 textContent/createElement（守卫断言 doesNotContain("innerHTML")）；Blob+download 浏览器端落盘（T3 增强）。
- 新增测试：WorkbenchRpcTest +7（26→33）、WorkbenchUiTest +3（3→6）、HeadlessDryRunVerifyTest 新类 5、RpcHttpServerTest e2e 改名扩展（11 不变）、HeadlessServeTest 断言扩展（3 不变）。
- 生产改动：WorkbenchRpc（第五方法 + seam + 构造器）、RpcJson（yaml helper）、HeadlessMain（dryRunVerify + runServe 签名 + UI 行 + USAGE）、ui.html（功能面）、module-info javadoc。

## 7. 计数命令与测量面（含孤儿 XML 活例）

```sh
python3 - <<'EOF'
import glob, os, xml.etree.ElementTree as ET
start = int(open('/tmp/it36-s2-full-start.ts').read().strip())
t=f=e=s=0; classes=0; stale=0
for p in glob.glob('**/target/surefire-reports/TEST-*.xml', recursive=True):
    if os.path.getmtime(p) < start: stale += 1; continue
    r = ET.parse(p).getroot()
    t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors')); s+=int(r.get('skipped')); classes+=1
print(f"classes={classes} tests={t} failures={f} errors={e} skipped={s} stale_filtered={stale}")
EOF
```

- 全量测量面 = 全仓 `**/target/surefire-reports/TEST-*.xml`，mtime ≥ 全量跑起点；聚焦测量面 = 单模块目录 + 跑时窗口。
- **孤儿 XML 活例（第三变体实证）**：`examples/headless/target/surefire-reports/TEST-…AskVisibilityProbe.xml`（Nov 6 16:52，源码已删、带 1 旧红）——无过滤求和会报 131 测试 1 失败；mtime 窗口过滤后新鲜面 = **28 类 130 测试 0 失败**（GREEN3 口径）。停 1 verdict 备注①补进 AGENTS.md 的半句在本停真实拦下一次污染。
- 31 跳过 = 仓内既有平台门（Windows/SIGINT assumption 等），非本停新增。