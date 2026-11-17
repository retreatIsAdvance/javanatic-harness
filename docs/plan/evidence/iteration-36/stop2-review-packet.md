# it36 停 2 review packet —— verifyComposition 干跑 seam + UI 单文件功能面 + stdout 提示行

**新增模块自问**：本停**零新增模块、零 base 行**——第五方法与 CompositionVerifier 落既有 workbench/rpc，干跑 helper 落既有 HeadlessMain（装配面），ui.html 资源随 workbench/rpc jar 进镜像；base bundle 零加行、零新配置键（keyless `--verify` **34/41/0 不变**，真镜像实跑取证）。四件套逐项：① META-INF/services 双通道——无 Plugin、无 ServiceLoader，N/A；② bundle/base test 域 + headless 编译域承载——base 零加行，headless 对 workbench/rpc 依赖 it35 已在，N/A；③ headless 钉死面同步——12 §6（--serve 句五方法 + verifyComposition 干跑语义 + stdout 两行 + 导出面表行）+ 02 §6 workbench-rpc 行 + USAGE --serve 句 + module-info javadoc，本停已落（grep 旧措辞零残留）；④ moditect 清单登记——零改动（镜像 **69 模块**本停已 --list-modules 取证，资源进镜像 jimage 抽取实证）。

**行数报告（T4 软预算，用户点名）**：`wc -l ui.html` = **441 行**，软预算 ~800 内（55%）；镜像内抽取件同为 441 行。超预算触发「形态重议」的条款未触发。

**TL;DR（五行）**

- **不变量增量**：① 第五方法 `workbench/verifyComposition`——params `{yaml}` 必填非空字符串（缺失/空/型错 -32602），result `{ok:true}` 或 `{ok:false,violations:[…]}`（序 = 装配面清单序），预算统一 call() 施加（-32004）；② 干跑语义钉死——「把这份内容存为 profile、以 server 启动时同样的 overlays/policy 跑 jh --verify，会不会过」：临时 profile（前缀 jh-verify-composition，finally 删除零残留）+ bootReported + Runtime 即开即关，**零 server 写面承诺不破**；③ 异常归一——VerifyFailedException → violations()、RuntimeException（含坏 YAML 的 MarkedYAMLException）→ 单项「组合错误: …」、声明型 IOException → 「profile 解析失败: …」，仅基础设施 IO 上抛 → -32603；④ UI 单文件功能面 441 行——两页签（会话清单/五视图子页签/tail 2s 轮询游标增量 + 组合编辑器三按钮）、动态内容一律 textContent/createElement **零 innerHTML**、Blob+download 浏览器端落盘（T3 增强）、零外部请求零 localStorage；⑤ stdout 两行——既有地址行不变 + `UI: http://127.0.0.1:<实际端口>/（浏览器打开）`。
- **裁决点**：设计 §1–§4 已钉死全部形状，实现照设计落地无新裁决。两处 pre-release 签名调整（WorkbenchRpc 两构造增 verifier 参数、runServe 增 serverBoot 参数）设计 §1.1/§2.3 已预告，call-site 全同步。唯一实测订正：坏 YAML 抛 MarkedYAMLException（RuntimeException 而非 IOException）——TDD 红实测发现，宽 catch 修复 + 设计 §2.1 item 4 回填（非偏离，是设计预告的异常归一条款的实测精确化）。
- **风险自报**：① JS 零单测为 T4 裁决登记的缺口——资源内容守卫（3 用例）+ server 侧全测补偿，真浏览器交互闭环留停 3 用户检视实录；② tail 轮询/页签切换的浏览器时序行为无自动化覆盖（同上缺口面）；③ 干跑开销 = 一次插件发现 + 加载（与 --verify 同量级），单用户回环面按需触发可接受，不做缓存（设计 §2.3 已裁）；④ M2/M3 部分守用例呈 ERROR（NPE）非 FAILURE——期望 error 节点缺席所致，有效杀突变形态如实记录；⑤ run() serve 腿的 boot 传参表达式无直接单测面——runServe 直调 e2e 用真 AppBoot 装配覆盖同形状，真机保真留停 3。
- **证据入口**：[stop2-tdd-evidence.md](stop2-tdd-evidence.md)（红绿链 RED1–3/GREEN1–4 全落日志；聚焦 rpc 63/63 + headless 130/130 surefire XML mtime 窗口口径；validate EXIT=0；全量 package FULL_EXIT=0，**186 类 1486 测试 0/0/31 跳过**，孤儿过滤 2 件——含 AskVisibilityProbe 第三变体活例实证；**M1–M5 破坏必红→还原复绿全部落日志** + diff/status 双指纹逐字节还原 + git diff --check 净；keyless --verify 34/41/0 + 镜像 69 + jimage 资源抽取 441 行实证）。
- **若只能改一处**：改 `HeadlessMain.dryRunVerify` 的异常归一阶梯（VerifyFailedException → RuntimeException → IOException 的 catch 顺序与翻译）——它独担「一切组合级失败进违规清单逐项展示」的 UX 承诺（fail-loud 直接变 UX，预登记原文）；本停 GREEN2 意外红恰在此处（窄 catch 漏 MarkedYAMLException → 用户看到 -32603 JSON 而非违规清单），HeadlessDryRunVerifyTest 坏 YAML 用例 + HeadlessServeTest bad 往返双守。

## 契约面（新公共 API + 方法契约）

```java
// seam（rpc 模块词表,实现归装配面;模块图纪律:rpc 不依赖 bundle/base）
@FunctionalInterface
public interface WorkbenchRpc.CompositionVerifier {
    List<String> verify(String yaml) throws IOException;  // 空列表=通过;IO=基础设施失败→-32603
}

// 构造器（pre-release 调整,旧形状移除,call-site 全同步）
public WorkbenchRpc(SessionPersistence, SessionTail, String compositionDump, CompositionVerifier);
public WorkbenchRpc(SessionPersistence, SessionTail, String compositionDump, CompositionVerifier, long maxResponseBytes);

// 第五方法:workbench/verifyComposition
//   params {yaml:"…"} 必填非空字符串（缺失/空/型错 → -32602,RpcJson.yaml 与 session() 同形）
//   result {ok:true} | {ok:false,violations:["…"]}（序=装配面清单序;预算 -32004 统一 call()）

// 装配面（HeadlessMain）
static List<String> dryRunVerify(String yaml, AppBoot.BootOptions serverBoot) throws IOException;
static int runServe(Scope root, int port, String compositionDump,
                    AppBoot.BootOptions serverBoot, PrintStream out, PrintStream err);
// stdout:地址行（不变）+ UI: http://127.0.0.1:<server.address().getPort()>/（浏览器打开）
```

## 三行映射（设计增量条目 → 实现 → 测试）

| 设计增量（stop2-ui-design.md） | 实现 | 测试 |
|---|---|---|
| §1 verifyComposition 方法 + seam | WorkbenchRpc 第五方法 + CompositionVerifier + RpcJson.yaml + 构造器 | WorkbenchRpcTest +7（33；M1/M2/M3 守）+ RpcHttpServerTest e2e 五方法真 HTTP 往返 |
| §2 装配干跑 + 异常归一 + runServe 接线 | HeadlessMain.dryRunVerify + verifier lambda + runServe 新签名 | HeadlessDryRunVerifyTest 5（好/策略违规/坏 YAML/未知 bundle/temp 零残留）+ HeadlessServeTest ok/bad 往返 |
| §3 stdout UI 提示行 | runServe 第二行 println（实际端口） | HeadlessServeTest stdout 两行断言（M4 守） |
| §4 UI 功能面 + 安全约束 | ui.html 441 行（两页签/五视图/tail 轮询/编辑器/blob 下载/零 innerHTML） | WorkbenchUiTest +3 内容守卫（6；M5 守）；浏览器闭环留停 3 用户实录 |
| §5 同步面 | 12 §6 两处 + 02 §6 + module-info javadoc + USAGE | grep 旧措辞零残留 + keyless --verify 34/41/0（HeadlessVerifyTest 8 回归在全量内） |

## 偏离清单

| 出现什么 | 为什么 | 待裁决 |
|---|---|---|
| RuntimeException 宽 catch（设计原稿窄 catch 列举 IllegalStateException） | TDD 红实测：snakeyaml MarkedYAMLException 是 RuntimeException，窄 catch 漏给 wire 兜底 -32603；宽 catch + 异常类名文案修复，设计 §2.1 item 4 已回填 | 无——设计条款的实测精确化，已同步事实源 |
| WorkbenchRpc 构造器/runServe 签名 pre-release 调整 | 设计 §1.1/§2.3 已钉死预告；call-site 全同步（两测试类 + HeadlessMain） | 无——设计已钉 |
| RpcHttpServerTest e2e 改名扩展（三方法→五方法） | 第五方法入闭环是设计 §6 点名项；改名反映契约面 | 无——设计已钉 |
| M2/M3 部分守用例 ERROR（NPE）非 FAILURE | 清单摘除/校验摘除后期望的 error 节点缺席 → helper 空指针；有效杀突变 | 无——形态如实记录（停 1 M3 同族先例） |

## 为什么是这个形状 / 若错靠什么发现

- **为什么函数接口注入而非 rpc 依赖 bundle**：模块图纪律延续（it35 钉死）——rpc 只挂 API 模块；verify 能力词表归 rpc（List<String>），AppBoot 异常词表归装配面翻译，测试可注入任意违规清单/IO 故障（FakeVerifier 即证）。
- **为什么干跑复用启动期 BootOptions**：干跑语义 = 「以 server 同样的 overlays/policy 跑 --verify 会不会过」——重新发明校验参数会造成「编辑器说能过、真启动过不了」的语义漂移；复用即钉死同源。
- **为什么组合级失败全进违规清单**：编辑器 UX 是「这份组合能不能用」——坏 YAML/未知 bundle/策略违规都该逐项人读展示（fail-loud 变 UX），而不是协议错误码让用户看 JSON；仅基础设施失败（temp 写失败）才走 -32603。
- **为什么零 innerHTML + blob 下载**：会话数据是不可信输入面（textContent/createElement 守卫断言钉死）；下载浏览器端落盘不经 server——v1 零 server 写面承诺（T3 裁决）不破。
- **若错靠什么发现**：方法契约 7 用例 + M1/M2/M3；干跑 5 用例（含 temp 零残留计数）+ e2e ok/bad 真 HTTP 往返；stdout 两行断言 + M4；内容守卫 3 用例 + M5（双守）；停 3 真机（curl 五方法 + 资源进镜像已预证 + **用户检视实录：真浏览器面 + 编辑器干跑闭环 + 下载链**）+ CI 四腿。