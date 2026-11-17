# 停 2 设计定稿：verifyComposition 干跑 seam + UI 单文件功能面 + stdout 提示行（it36 W4）

停 1 通过并关闭（2026-11-17，[verdict](stop1-verdict.md)）。本档钉死停 2 三件的契约面、装配面与同步面；实现与测试随 S1/S2（TDD）。停 2 范围 = ①server 第五方法 `workbench/verifyComposition`（干跑 seam + 装配注入）②ui.html 功能面（会话清单 / 五视图 / tail 轮询 / 组合编辑器 + blob 下载）③`--serve` stdout UI 地址提示行。**软预算 ~800 行从本停起计（T4 观察项），packet 报告行数**；**突变跑必须落日志文件**（停 1 verdict 备注 2，写入 §7 出包门槛）。

## 1. server 方法 `workbench/verifyComposition`（第五方法）

### 1.1 注入 seam（模块图纪律延续）

`workbench/rpc` 仍不依赖 bundle/base——verify 能力由装配面（HeadlessMain）注入**函数接口**（停 1 §4 预告的形状钉死）：

```java
// WorkbenchRpc 嵌套函数接口（seam 词表归 rpc 模块，实现归装配面）
@FunctionalInterface
public interface CompositionVerifier {
    /**
     * 干跑校验编辑内容。空列表 = 通过；非空 = 违规清单（逐项人读文本）。
     * 组合级失败（YAML 解析 / 未知 bundle / 双向校验 / workspace 漂移 / 策略违规）
     * 一律由实现翻译为违规项——rpc 面只见 List<String>，不见 AppBoot 异常词表。
     * @throws IOException 基础设施失败（临时文件写失败等）→ handler 翻译 -32603
     */
    List<String> verify(String yaml) throws IOException;
}
```

- **错误翻译分工钉死**：`VerifyFailedException.violations()` / `IllegalStateException`（组合错误）/ profile 解析 `IOException`（内容错误）→ 装配面归一为违规项；仅基础设施 IO 失败上抛 → `-32603`。理由：编辑器的 UX 是「这份组合能不能用」——一切组合级失败都该进违规清单逐项展示（fail-loud 直接变 UX，预登记原文），而不是变成协议错误码让用户看 JSON。
- **构造器**（pre-release 调整，call-site 随改）：两构造均增 `CompositionVerifier` 参数——
  - `WorkbenchRpc(persistence, tail, compositionDump, CompositionVerifier verifier)` → 缺省预算。
  - `WorkbenchRpc(persistence, tail, compositionDump, CompositionVerifier verifier, long maxResponseBytes)` → 全参。
  - call-site：RpcHttpServerTest ×2、WorkbenchRpcTest 夹具、HeadlessMain.runServe ×1。`Objects.requireNonNull` 同族。
- **module-info 零改动**：`List`/`IOException` 均 java.base；零新 requires、零新导出。

### 1.2 方法契约

- params `{yaml: "<编辑内容>"}`——**yaml 必填非空字符串**；缺失 / 空串 / 型错 → `-32602`（新 helper `RpcJson.yaml(params)`，`session()` 同形；wire 层已保证 params 恒非 null、非对象在 wire 即 -32602）。
- result：通过 → `{"ok": true}`；违规 → `{"ok": false, "violations": ["…", …]}`（字符串数组，序 = 装配面清单序）。
- 响应预算统一在 `call()` 施加（超限 -32004，既有面）；未知方法仍 -32601。
- **零 server 写面承诺不破**：干跑写的是临时文件（§2），不触碰用户已落盘 profile；方法本身对 rpc 模块是纯 seam 消费。

## 2. 装配面干跑（HeadlessMain，bundle/base 消费侧）

### 2.1 干跑 helper（静态纯接线，可单测）

```java
/** 组合编辑干跑:临时 profile + 启动期同 overlays/policy 跑 AppBoot verify 断言。 */
static List<String> dryRunVerify(String yaml, AppBoot.BootOptions serverBoot) throws IOException
```

流程钉死：

1. `Files.createTempFile("jh-verify-composition", ".yml")` 写入编辑内容（UTF-8）；**finally 删除**（零残留——测试钉 temp 目录前后计数）。
2. `new AppBoot.BootOptions(temp, serverBoot.overlays(), true, serverBoot.verifyPolicy())` → `AppBoot.bootReported(...)`。
3. 干跑 Runtime **try-with-resources 关闭**（bootReported 加载插件 ⇒ 有资源要收；与 --verify 同 JVM 插件加载幂等）。
4. 异常归一：`VerifyFailedException` → `e.violations()`；`IllegalStateException`（未知 bundle / 双向校验 / workspace 漂移）→ 单项 `"组合错误: " + msg`；profile 解析 `IOException` → 单项 `"profile 解析失败: " + msg`。临时文件写失败（基础设施）原样上抛 → rpc 翻译 -32603。
5. 通过 → `List.of()`。

### 2.2 干跑语义（钉死）

**「把这份内容存为 profile、以 server 启动时同样的 overlays/policy 跑 `jh --verify`，会不会过」**——overlays（workspace/sessions 等 CLI 覆盖）与 verifyPolicy 复用 serve 启动期的 `BootOptions`，不重新发明一套校验参数。编辑对象 = 完整 profile YAML（name/policy/bundles/rows），与 `--dump` 读面投影的组合期口径衔接（dump 展示行序，编辑保存的是 profile 源）。

### 2.3 runServe 接线

- 签名增 boot 参数（pre-release）：`runServe(Scope root, int port, String compositionDump, AppBoot.BootOptions serverBoot, PrintStream out, PrintStream err)`；run() 的 serve 腿把 `boot` 传入。
- verifier 装配 = `yaml -> dryRunVerify(yaml, serverBoot)`（lambda 注入，零新类）。
- **并发面**：每请求独立临时文件 + 独立干跑 Runtime，无共享态（Follower 每请求开→读→关闭环同族）。干跑开销 = 一次插件发现 + 加载（与 --verify 同量级），单用户回环面按需触发，可接受；不做缓存（编辑内容每次不同，缓存无收益）。

## 3. stdout UI 地址提示行（T5）

runServe 既有地址行**不变**，其后追加一行：

```
JSON-RPC server 127.0.0.1:<port>（只读工作台面；Ctrl-C 退出）
UI: http://127.0.0.1:<port>/（浏览器打开）
```

- 端口取 `server.address().getPort()` 实际值（ephemeral 测试面同样正确）。
- HeadlessServeTest 断言两行（既有地址行回归 + 新 UI 行）。
- USAGE `--serve` 句补「stdout 报 UI 地址」半句（随 §5 同步面）。

## 4. UI 单文件功能面（ui.html，vanilla JS，零依赖零构建）

### 4.1 功能面（W2 TUI 同功能面的浏览器形态，纯消费既有五方法）

- **布局**：顶栏标题 + 两个页签——「会话」「组合编辑器」。单页无路由（页签 = display 切换）。
- **会话页签**：
  - 清单：`workbench/sessionsList`（max=50）+ 刷新按钮；行 = id + 摘要字段（RpcJson.catalog 既有形状）。
  - 详情：点行 → `workbench/sessionViews` → header 摘要 + 五视图子页签（turns / tools / usage / timeline / messages，RpcJson.views 六组件既有形状）。
  - **tail 轮询**：跟随开关 → `workbench/sessionTail`（`{session, lastSeq}`，间隔 2s）增量追加 timeline 视图，游标 = 响应 `nextSeq`；切会话/切页签/关开关即停（clearInterval）；域错误（-32001/-32002/-32003）停轮询并展示错误条。
- **组合编辑器页签**：
  - 「载入当前组合」→ `workbench/compositionDump` → 文本域（dump 是行序投影非 profile 源——提示文案注明「编辑对象是 profile YAML；dump 供对照」，另提供空白 profile 骨架起点）。
  - 「保存前校验」→ `workbench/verifyComposition`（`{yaml: 文本域内容}`）→ ok 绿条 / violations 红条逐项列表。
  - **「下载 profile.yml」→ Blob + `URL.createObjectURL` + `a[download]`**（T3 裁决附的 UX 增强：浏览器端落盘、不经 server）。下载不被校验门禁（用户自主；未校验时提示文案一句）。
- **RPC helper**：`fetch("/", {method:"POST", body: JSON-RPC 2.0})` 同源零 CORS；id 递增；error 对象 → 展示 code + message（不泄堆栈——wire 已保证）。

### 4.2 安全与形态约束

- 动态内容一律 `textContent` / `createElement`——**零 innerHTML 注入面**（会话数据是不可信输入面）。
- 零外部请求（无 CDN / 字体 / 图标库）、零 localStorage、零 cookie——单文件自足。
- B29 硬线不涉 UI 代码（绑定面在 server，停 1 已钉）；UI 只同源调 127.0.0.1。

### 4.3 软预算与测试面（T4）

- **~800 行软预算从本停起计**：`wc -l ui.html` 实测，packet 报告行数；超了不是硬错，触发「是否该重新议形态」的四确认重访。
- **JS 零单测（登记缺口，T4 已批）**；可测侧两道：
  - **资源内容守卫**（WorkbenchUiTest 增用例）：标记串 `javanatic-workbench-ui` + 五方法名串 + 关键元素 id（页签/文本域/按钮）存在——内容回归钉子（占位页误覆盖、方法名拼错即红），**非浏览器测试替代品**。
  - **先验校验路径 server 侧全测**（§1/§2 矩阵）——编辑器最重要的逻辑面在可测侧（T4 论据）。
- 浏览器交互闭环 = 停 3 用户检视实录（真浏览器面 + 编辑器干跑闭环 + 下载链）。

## 5. 同步面清单

| 面 | 改动 |
|---|---|
| 12 §6 | --serve 句「四方法」→「五方法（+ verifyComposition 干跑校验）」+ 语义半句（临时 profile 干跑、零用户数据写）；导出面表 workbench/rpc 行注记随改 |
| workbench/rpc javadoc + module-info 注释 | 四方法 → 五方法；CompositionVerifier seam 一句 |
| HeadlessMain USAGE | --serve 句补 UI 提示行 + 五方法；--help 退出码面不变 |
| 02 §6 / 00 §6 | workbench/rpc 职责措辞若涉「四方法/只读三件」随改（grep 核） |
| iteration-36.md | 停 2 状态 + 设计点钉死回填 |
| dist/jh moditect / bundle 行 / 配置键 | **零改动**（镜像 69 不变停 3 取证；--verify 计数 34/41/0 不变） |

## 6. 测试面（S1/S2 TDD 矩阵）

- **WorkbenchRpcTest +6**（fake CompositionVerifier 注入）：ok 形状 `{ok:true}` / violations 透传序与内容 / yaml 缺失 -32602 / yaml 空串 -32602 / yaml 型错 -32602 / 预算超限 -32004（小预算注入）。
- **RpcHttpServerTest**：五方法端到端回归（verifyComposition 经注入 verifier 走真 HTTP 往返）。
- **HeadlessDryRunVerifyTest（新类，真 AppBoot）**：好组合 → 空列表 / 策略违规 → violations 非空 / 坏 YAML → 违规项（不抛）/ 未知 bundle → 违规项 / **临时文件零残留**（干跑前后 temp 目录 `jh-verify-composition*` 计数相等）。
- **HeadlessServeTest**：stdout 两行断言（地址行回归 + UI 行，ephemeral 端口实际值）。
- **WorkbenchUiTest**：资源内容守卫（§4.3）。
- **突变矩阵 M1–M5（破坏必红 → 还原复绿，每跑落日志）**：
  - M1 ok 判定反转（violations 非空仍返 `{ok:true}`）→ WorkbenchRpcTest violations 用例守。
  - M2 violations 数组摘除（`{ok:false}` 不带清单）→ 同用例守。
  - M3 yaml 校验摘除（缺失放行）→ -32602 矩阵守。
  - M4 UI 提示行摘除 → HeadlessServeTest 守。
  - M5 ui.html 标记串摘除 → WorkbenchUiTest 内容守卫守。

## 7. 出包门槛（S3）

聚焦（surefire XML **类名 + mtime 窗口过滤**——孤儿 XML 第三变体已入 AGENTS.md 计数条）+ validate EXIT=0 + 全量 package EXIT=0（mtime 窗口过滤）+ 突变必红复绿**全部落日志文件**（`> /tmp/it36-s2-*.log 2>&1` + EXIT 回显，关键输出行引证据档——停 1 verdict 备注 2 兑现）+ 零残留 grep + `git diff --check` + keyless `--verify` 34/41/0 不变 + **ui.html 行数报告（软预算对照）** + review packet（五行 TL;DR + 新增模块自问「零新增模块、零 base 行」+ 四件套）。停下等用户点名放行。

## 8. S 分段

- **S1**：verifyComposition server 面 + 装配干跑 + stdout 提示行（TDD：§6 前四项矩阵）。
- **S2**：ui.html 功能面 + 资源内容守卫 + 行数统计（TDD：守卫先行红 → 填功能面绿）。
- **S3**：同步面（§5）+ 取证（突变落日志）+ packet（含行数）→ 停下等放行。