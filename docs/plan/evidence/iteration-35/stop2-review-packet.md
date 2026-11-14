# it35 停 2 review packet —— CLI --serve 接线 + Follower.close 赋义 + 装配/同步面

**新增模块自问（it30 裁决）**：本停新增模块 = 无（`workbench/rpc` 为停 1 新增）；**base bundle 加行 = 零**（grep 实证：bundle/base module-info/pom 无 workbench.rpc 引用）；`dist/jh` moditect 模块清单 **+2 行**（workbench.rpc + jdk.httpserver，headless 开始真运行时消费的登记，it33 pits #38④ 先例）；headless pom 依赖 +1 行、module-info 改 2 行（rpc requires + jdk.httpserver static→非 static 转正）。四件套自问：① META-INF/services 双通道——本停无新 plugin、无 ServiceLoader，N/A；② bundle/base 零加行——✓（serve 面装配在 headless CLI 层，四确认裁决「零 bundle 行」）；③ headless 钉死面同步——✓（12 §6 CLI 冻结项 + USAGE 段 + HeadlessOptionsTest 4 新测试）；④ moditect 清单登记——✓（+2 行，停 3 jlink list-modules 实录验收）。

**TL;DR（五行）**

- **不变量增量**：`jh --serve [--port=<N>]`——词表（`--port` 1-65535、缺省 8787、仅与 `--serve` 同用、port=0 CLI 不收）；互斥矩阵七件（任务文本/resume/verify/sessions/session/session-view/workbench）+ `--follow` 经 `--workbench` 配对传导拦截；dispatch **keyless**（任何 API key 检查之前，缺省组合零监听）；端口占用 → exit 3 + stderr 换端口指引 + stdout 空；SIGINT 首按 close+exit 0、再按 halt(130)；`Follower.close` = 语义终结（幂等；close 后 header()/nextEvents() 抛 IllegalStateException 拒读 fail loud；四契约口径不动）。
- **裁决点**：停机裁决层（ServeShutdown：close/halt 全注入、`CountDownLatch stopped` + `AtomicBoolean closing`、onSignal 同步可测）与真信号 e2e 分离——halt 只记码不真 halt（测试 JVM 保护，真 halt 由 jlink 停 3 用户检视实录覆盖）；bindSigint 拆 `Runnable` 重载供 ServeShutdown 复用；B29 转正（硬线文本 + 运行时断言 + M3 突变三件证据入边界表）；B35 登记（预算 + -32004，停 1 已实现，本停落账）。
- **风险自报**：① macOS 端口占用夹具失效（`new ServerSocket(0)` 双栈通配监听与 `127.0.0.1` 绑定在 BSD SO_REUSEADDR 语义下可并存，占用不检出 → serve 启动 → await 永等 → fork 挂死）——夹具改 `ServerSocketChannel` + 显式 `SO_REUSEADDR=false` + 绑 127.0.0.1，**产品零改动**；② 互斥矩阵 sessionView 格影子覆盖（`--session-view 需与 --session 同用` 先行拦截）→ 测试派生断言钉「--serve 与 --session-view 互斥」词面（TDD 红绿闭环）；③ surefire 两坑（`-Dtest=A+B` 静默零执行 / fork 挂死即无报告）已入 pits。
- **证据入口**：SessionTailTest 13/0/0（S1 close 赋义）+ HeadlessOptionsTest 29/0/0（serve 词表/端口域/互斥矩阵/--help 4 新测试 + 派生断言）+ HeadlessServeTest 3/0/0（真 SIGINT e2e 往返停机 + 端口占用 exit 3）；突变三件套全闭环（exit 3 改码必红 / close 摘除 e2e 必红 / 互斥摘除→首测漏红→覆盖缺口修复后必红）+ 还原逐字节复绿 + 零残留 grep；全量 `mvn -B -pl examples/headless -am test` BUILD SUCCESS（FULL_EXIT=0，51 模块 177 测试类 1448 测试全绿——surefire XML 求和，mtime 窗口 2026-11-14 14:20+）；keyless `--verify` 34/41/0 不变（HeadlessVerifyTest 8/0/0——`composition: 34 rows, 41 discovered, 0 unreferenced` 断言绿）。
- **若只能改一处**：改 HeadlessMain dispatch 的 `if (options.serve())` 分支位置——它必须在任何 API key 检查之前（keyless 语义 =「无 key 可跑」裁决的一环）；ServeShutdown/词表/互斥各自可漂移而这条顺序线独担「缺省组合零监听 + 无 key 可跑」两面。

## 三行映射（设计增量条目 → 实现 → 测试）

| 设计增量（stop2-serve-design.md） | 实现 | 测试 |
|---|---|---|
| S1 §2 `Follower.close` 赋义（幂等、关后拒读、四契约口径不动；R3 收口语义——tail 资源随服务关闭回收） | `SessionTail.Follower` Javadoc 契约 + `JsonlTailFollower.close` 实现 | `SessionTailTest` 13（close 幂等 + 关后 header()/nextEvents() 抛 IllegalStateException） |
| S2 §1 CLI 词表 + 互斥矩阵 + runServe + ServeShutdown + bindSigint + dispatch keyless | HeadlessMain（DEFAULT_SERVE_PORT=8787、USAGE 两段、RunnerOptions、parse 校验块、runServe、ServeShutdown、bindSigint(Runnable) 重载、dispatch 分支） | HeadlessOptionsTest 29（serve 词表/端口域/互斥矩阵/--help）+ HeadlessServeTest 3（停机裁决 + e2e + exit 3） |
| S3 §3 装配面（headless 直依赖 + module-info + dist/jh moditect） | headless pom 依赖 1 行 + module-info（rpc requires + jdk.httpserver static→非 static）+ dist/jh moditect +2 行 | 全量 headless（-am）+ 停 3 jlink（镜像 67→69 list-modules 实录——停 3 订正：四确认「67→68」算术滑点，67+2 行恰两模块） |
| S4 §4 同步面（B29 转正 / B35 登记 / 12 §6 / 02 §6 / 00 §6 / README / notes 0011 / AGENTS / current-state / pits） | 各文档同步 | keyless `--verify` 34/41/0 计数不变 |

## 偏离清单

| 出现什么 | 为什么 | 待裁决 |
|---|---|---|
| macOS 端口占用夹具失效 → 改 `ServerSocketChannel` + `SO_REUSEADDR=false` + 绑 127.0.0.1 | BSD SO_REUSEADDR 语义下双栈通配监听与回环绑定可并存，占用不检出（设计 §6 夹具「预占端口」写法在 macOS 不成立） | 无——夹具修正，产品零改动、契约零变化 |
| 互斥矩阵 sessionView 格测试改派生断言（钉「--serve 与 --session-view 互斥」词面，非泛词面 `--session-view`） | 突变③首测未红暴露覆盖缺口：`--session-view 需与 --session 同用` 检查（L491）先行拦截，摘除 serve 互斥检查后异常来源转移、断言仍绿——泛词面是影子覆盖 | 无——「gate 丢覆盖用派生断言」惯例（it24 起） |
| 突变③「互斥检查摘除 → 必红」首测未红 | 同上覆盖缺口；修复后必红闭环（红：派生断言在摘除态失败 → 绿：还原后 29/0/0） | 无——缺口即发现即修，已闭环 |
| surefire `-Dtest=A+B`（+ 分隔）静默零执行 | 3.2.5 把 `A+B` 当单个 pattern，零匹配 + `failIfNoSpecifiedTests=false` 静默 BUILD SUCCESS（聚焦取证假绿陷阱）；并集用逗号 `-Dtest=A,B`；`-Dtest` 应用到 -am 链每个模块（无匹配模块须 `failIfNoSpecifiedTests=false`） | 无——pits 两条登记 |
| HeadlessServeTest「报告缺席」非发现层问题 | jstack 取证：类被正常发现执行，第三个测试挂死 fork（await 永等）→ surefire 不写该类报告；「报告缺席」≠「未执行」 | 无——pits 登记（先 jstack fork，别先怀疑发现层） |
| `java.lang.Runtime.getRuntime().halt(code)` 用 FQN | kernel `io.javanatic.harness.kernel.scope.Runtime` 已在 HeadlessMain import，`Runtime.getRuntime()` 编译错；FQN 是唯一无歧义解 | 无 |
| 全量/聚焦计数以 surefire XML/TXT 报告为准，`-B` 批处理模式下 mvn 日志不含 "Tests run:" 行 | 旧日志 grep 惯例失效；报告文件是计数唯一事实源 | 无——packet 计数一律引报告文件 |

## 为什么是这个形状 / 若错靠什么发现

- **为什么停机裁决层与真信号分离**：halt 会杀测试 JVM，裁决层把 close/halt 变成注入面（Recorder 只记码），首按/再按状态机可纯单测；真信号只留一个 e2e（ephemeral 端口往返 + 首按 close + exit 0 + 端口释放），SIGINT 不可用环境（Windows/-Xrs）assumption 跳过。若错：再按不 halt → Recorder 断言必红；首按不 close → e2e「再连被拒」必红（突变②实证）。
- **为什么 dispatch 在 API key 检查之前**：serve 是只读旁路、无 key 可跑（裁决词面），与 `--verify` 同为 keyless 面；缺省组合（无 --serve）不触发任何新路径。若错：HeadlessServeTest e2e（不传 key）即红。
- **为什么端口占用失败面走 exit 3 + stderr 指引 + stdout 空**：与 --resume/--session 的写者锁冲突、未知 id 同族（exit 3 fail loud 出口一致）；stdout 空 = 失败输出契约（与 --verify 面同款）。若错：词面改码 → 断言必红（突变①实证）。
- **若错靠什么发现**：32 项聚焦（29+3）+ 13 项 S1 + 突变三件套 + 全量 + 停 3 真机（jlink `--serve` curl 三方法 + 真 jsonl 后端三方法 curl 实测【停 1 裁决硬条件升格必验项】+ 用户检视实录 + B29 硬线 jlink 监听地址实录 + 非回环访问拒绝）+ CI 四腿。词表漂移在 HeadlessOptionsTest 即红；停机状态机在裁决层单测即红；装配缺行在停 3 jlink 即红。
