# it36 停 1 review packet —— 静态服务 seam + 路由契约重钉 + dump 读面

**新增模块自问**：本停**零新增模块、零 base 行**——WorkbenchUi 与 ui.html 资源落既有 workbench/rpc（it35 已注册 moditect），base bundle 零加行、零新配置键（`--verify` 34/41/0 不变）。四件套逐项：① META-INF/services 双通道——无 Plugin、无 ServiceLoader，N/A；② bundle/base test 域 + headless 编译域承载——base 零加行，headless 对 workbench/rpc 的依赖 it35 已在，N/A；③ headless 钉死面同步——12 §6（冻结清单 + --dump 语义句 + --serve 四方法 + 退出码 0 行 + 导出面表注记）+ USAGE 三处 + HeadlessDumpTest/HeadlessOptionsTest 钉死，本停已落；④ moditect 清单登记——零改动（资源随 workbench/rpc jar 进镜像，镜像 69 不变，停 3 --list-modules 取证）。

**TL;DR（五行）**

- **不变量增量**：① 路由契约重钉（T1 附精度）——GET/HEAD 仅精确 `/` → 200 + `text/html; charset=utf-8` + `Cache-Control: no-store` + ui.html 字节，其余一切 GET（含 `/favicon.ico`、`/index.html`）维持 404，不泛化成目录服务；POST `/` JSON-RPC 不变；其余方法 405 + `Allow: GET, POST`；② WorkbenchUi 静态 seam——classpath 构造期加载一次 + 内存缓存 + 缺失 fail loud（IllegalStateException，拒绝服务空页）+ html() 防御性拷贝；③ dump 读面模块图纪律——workbench/rpc 不依赖 bundle/base，HeadlessMain 启动期算 `AppBoot.dump(AppBoot.compose(boot))` String 快照注入，compositionDump 返回快照（params 忽略）、预算统一 call() 施加（-32004）；④ CLI --dump keyless 只读旁路——post-boot 与 --verify 同段、8 项互斥、stdout = dump 文本（compose 口径 = 组合期行序含禁用行注解 + masked 脱敏）；⑤ HEAD body 由 jdk.httpserver 自动抑制（本会话实证，dispatch 无需特判）。
- **裁决点**：设计 §1–§3 已钉死全部形状，实现照设计落地无新裁决。WorkbenchRpc 旧 2参/3参构造器移除换新形状（compositionDump 必注入）——pre-release 面内 breaking change，T1 裁决已批契约变化，全部 call-site 同步（HeadlessMain + 两测试类）。ui.html 为停 1 最小占位页（标记 `javanatic-workbench-ui`），功能面属停 2——设计预期分段，非偏离。
- **风险自报**：① run() 内 serve 腿的 dump 接线表达式无直接单测面——runServe 直调 e2e 覆盖注入消费、--dump CLI 覆盖同表达式，真机保真留停 3 curl 补证；② S2 红/突变跑为 console 直察无日志落盘，红计数按会话实录登记（突变形态表可复验）；③ M3 第三用例呈 ERROR（NPE）非 FAILURE——空串 dump 使预算用例 error 节点缺席，有效杀突变形态如实记录；④ JS 零单测为 T4 裁决登记的缺口（--verify 干跑 server 侧可测补偿，UI DOM 断言代验留停 3）；⑤ 单文件软预算 ~800 行观察项从停 2 起计（T4）。
- **证据入口**：[stop1-tdd-evidence.md](stop1-tdd-evidence.md)（聚焦 85/85 surefire XML 口径 = rpc 40 + headless 45；validate EXIT=0；全量 package EXIT=0，185 XML = 1471 tests / 0 failures / 0 errors / 31 skipped（mtime 过滤排除 2 份源码已删的孤儿残留）；M1–M5 破坏必红→还原复绿 + 零残留 grep 新鲜复核；keyless --verify 真镜像实跑 34/41/0 不变 + --dump 48 行禁用注解/脱敏实证）。
- **若只能改一处**：改 `RpcHttpServer.dispatch` 的精确路径判定（`"/".equals(exchange.getRequestURI().getPath())`）——它是 T1 附精度裁决「仅精确 /、不泛化目录服务」的唯一闸门，M2 反转（`!root`→`root`）即 4 红；静态 seam 与 dump 读面可各自漂移而此判定独担路由契约。

## 契约面（路由表 + 新公共 API）

路由表（设计 §1，重钉后）：

| 请求 | 响应 |
|---|---|
| GET/HEAD 精确 `/` | 200 + `text/html; charset=utf-8` + `Cache-Control: no-store` + ui.html 字节（HEAD body 自动抑制） |
| GET/HEAD 其余路径 | 404（含 /favicon.ico、/index.html——不泛化目录服务） |
| POST `/` | JSON-RPC（wire 层，不变） |
| POST 其余路径 | 404（不变） |
| 其余方法 | 405 + `Allow: GET, POST` |

```java
// 静态 seam（新）：构造期加载 + 缓存 + fail loud + 防御性拷贝
public final class WorkbenchUi {
    public static final String CONTENT_TYPE = "text/html; charset=utf-8";
    public WorkbenchUi();                    // 缺省资源;缺失 IllegalStateException
    WorkbenchUi(String resourcePath);        // 包私有路径注入 = 测试面,非生产回调
    public byte[] html();                    // 缓存字节的防御性拷贝
}

// handler：构造器新形状（compositionDump 必注入;旧 2参/3参移除）
public WorkbenchRpc(SessionPersistence persistence, SessionTail tail, String compositionDump);
public WorkbenchRpc(SessionPersistence persistence, SessionTail tail,
                    String compositionDump, long maxResponseBytes);
// 新方法面:workbench/compositionDump → {"dump": "<快照>"}（params 忽略;预算 -32004 统一施加）

// CLI：--dump keyless 只读旁路（8 项互斥;stdout = dump 文本;exit 0）
// runServe 新签名:static int runServe(Scope root, int port, String compositionDump, PrintStream out, PrintStream err)
```

## 三行映射（设计增量条目 → 实现 → 测试）

| 设计增量（stop1-seam-design.md） | 实现 | 测试 |
|---|---|---|
| §1 路由契约重钉（T1 附精度） | RpcHttpServer.dispatch GET/HEAD 分支 + 精确路径判定 + Allow 头 | RpcHttpServerTest 11（新增 getRoot/getFavicon/getIndexHtml/headRoot 4 + 405 改判 1；M1/M2 守） |
| §2 WorkbenchUi 形状 | WorkbenchUi + ui.html 资源（停 1 占位页） | WorkbenchUiTest 3（加载/缺失 fail loud/防御拷贝；M4 守） |
| §3.2 compositionDump 读面 | WorkbenchRpc 构造新形状 + compositionDump 方法 + HeadlessMain runServe 接线 | WorkbenchRpcTest 26（新增快照/忽略 params/预算 3；M3 守）+ HeadlessServeTest e2e 往返 |
| §3.3 CLI --dump | RunnerOptions.dump + parse + 8 项互斥 + run() 旁路 + USAGE 三处 | HeadlessDumpTest 5（parse/互斥矩阵/行序+禁用注解/脱敏/usage；M5 守）+ HeadlessOptionsTest 29 回归 |
| §5 同步面 | module-info javadoc（四方法 + 静态服务面）+ 12 §6 四处 | HeadlessVerifyTest 8 回归（34/41/0 计数钉死） |

## 偏离清单

| 出现什么 | 为什么 | 待裁决 |
|---|---|---|
| WorkbenchRpc 旧 2参/3参构造器移除 | 设计 §3.2 钉死新形状（compositionDump 必注入）；pre-release 面内 breaking change，call-site 全同步 | 无——设计已钉 |
| 12 §6 --serve 句「三方法」→「四方法」+ GET `/` UI 子句 | 设计 §5 清单点名导出面表行注记；--serve 句方法计数是同源事实漂移面，随同步（超清单半句，登记） | 无——事实校正 |
| S2 红/突变跑无日志落盘（console 直察） | 当次跑直接观察输出未 tee 文件；红计数与形态按会话实录登记 | 无——证据档 §2 突变形态表可复验 |
| M3 第三用例 ERROR（NPE）非 FAILURE | 空串 dump 小于预算 → error 节点缺席 → helper 空指针；有效杀突变 | 无——形态如实记录 |

## 为什么是这个形状 / 若错靠什么发现

- **为什么 String 快照注入而非 rpc 依赖 bundle**：模块图纪律（workbench/rpc 只挂 API 模块，it35 钉死）；组合启动后不变（热重载不做）⇒ 快照是正确形状；测试可注入任意字符串（预算用例注入 128 字节 + 64 上限即证）。
- **为什么缺失资源 fail loud**：资源是模块自带（随 jar 进镜像），缺失 = 打包错误——静默服务空页会把打包缺陷藏到用户面前；构造期抛 IllegalStateException 让装配即炸。
- **为什么防御性拷贝**：缓存字节跨请求共享，html() 返回 clone 防调用方 mutate 污染缓存（WorkbenchUiTest 第三用例钉死）。
- **为什么 --dump 与 --verify 同段 post-boot**：dump 是组合期行序的忠实投影（含禁用行注解），compose 重算与 boot 内部同源确定性；不进 API key 检查、不建 agent——keyless 只读旁路与 --sessions/--session 同族。
- **若错靠什么发现**：路由矩阵 11 用例 + M1/M2 突变；seam 3 用例 + M4；dump 3 用例 + M3 + e2e 往返；CLI 5 用例 + M5 + 互斥矩阵 8 组；停 3 真机（curl GET / 与 compositionDump、资源进镜像 --list-modules 69 取证、用户检视实录）+ CI 四腿。