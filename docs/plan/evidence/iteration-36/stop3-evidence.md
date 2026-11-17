# it36 停 3 证据档 —— 0.5 终停（W4 关账）

## S1 我跑面真机取证（2026-11-17）

### 1. 新鲜镜像构建 + 计数面定论（131 vs 130 → **130**）

- 命令（仓库根）：`export JAVA_HOME=$(/usr/libexec/java_home -v 25) && date +%s > /tmp/it36-s3-build-start.ts && ~/Documents/apache-maven-3.10.0/bin/mvn -B -pl dist/jh -am package` → **IMAGE_BUILD_EXIT=0**（日志 /tmp/it36-s3-image-build.log）。
- **headless 权威计数 = 130**，双证吻合：
  1. 构建日志模块汇总行原文：`[INFO] Tests run: 130, Failures: 0, Errors: 0, Skipped: 0`（harness-examples-headless SUCCESS 段）；
  2. surefire XML mtime ≥ build-start 求和：headless **28 类 = 130 / 0F / 0E / 0 skip**。
- **定论**：停 2 verdict 登记的「用户复跑 131 vs 我 130」差异，131 为计数误（可能含孤儿或双计），**130 为真**——stop2 证据档原值正确，无需订正；本段即 verdict 要求的「S1 新鲜复跑附命令+测量面定论」。
- 本构建面（`-pl dist/jh -am`，52 模块）：180 新鲜 XML = **1478 tests / 0 failures / 0 errors / 29 skipped**；窗口外排除 8 份旧 XML（含 AskVisibilityProbe 孤儿 + agent-spine/llm-deepseek 等非本 reactor 依赖模块的历史残留——残留 ≠ 执行数）。全量面（含非依赖模块）门禁在 S3 收口新鲜跑。
- rpc 同批复核：workbench/rpc 4 类 = **63**（与停 2 一致）。

### 2. 镜像与 keyless 复核（新鲜镜像）

- `dist/jh/target/jlink-image/bin/java --list-modules` → EXIT=0，**69 行**（含 `io.javanatic.harness.workbench.rpc@0.5.0-SNAPSHOT` + `jdk.httpserver@25.0.4.1`；jh 自身无 --list-modules 参数，首次误打 jh 得 exit 2「未知参数」，改镜像 java launcher 即 it35 口径）。日志 /tmp/it36-s3-list-modules.log。
- `env -u DEEPSEEK_API_KEY dist/jh/target/jlink-image/bin/jh --verify` → EXIT=0，`composition: 34 rows, 41 discovered, 0 unreferenced`（停 1/停 2 基线不变）。日志 /tmp/it36-s3-verify-keyless.log。

### 3. curl 五方法 + 静态面 + B29（[stop3-my-round-probes.txt](stop3-my-round-probes.txt)）

serve 为我跑面自起（keyless，缺省 8787；用户检视轮另起独立 serve）。banner 两行实证：`JSON-RPC server 127.0.0.1:8787（只读工作台面；Ctrl-C 退出）` + `UI: http://127.0.0.1:8787/（浏览器打开）`。

| 面 | 结果 |
|---|---|
| GET / | 200 + `text/html; charset=utf-8` + `Cache-control: no-store` + 19307 字节 = **441 行** + 标记 grep 1 命中 |
| HEAD / | 200 同头、body 抑制（jdk.httpserver 自动；serve 日志有 sendResponseHeaders HEAD 警告行——JDK 内部提示，非错误） |
| GET /favicon.ico、/index.html | 404 / 404（不泛化目录服务） |
| PUT / | 405 + `Allow: GET, POST` |
| POST /nope | 404 |
| B29a LAN `http://10.0.33.99:8787/` | curl **EXIT=7**（connection refused） |
| B29b `http://[::1]:8787/` | curl **EXIT=7**（仅绑 IPv4 回环） |
| ① sessionsList(max=5) | total=**82**（真 ~/.harness/sessions）、返回 5 条含 eventCount/busy |
| ② sessionViews（headless-1789702472876-c2dd，2MB 真会话） | 六组件全在场；timeline=**10006**、turns=1、messages=100、tools=40；usage.total input=847205/output=11004；manifest 全程随账 |
| ③ sessionTail 游标矩阵 | lastSeq=-1 → 10006 事件/nextSeq=10005；lastSeq=10005 → **0 增量**/nextSeq=10005；lastSeq=999999 → -32602 |
| ④ compositionDump | 48 行、7 行 `(disabled: …)` 注解、1 行 masked(****)——与 CLI --dump 同口径（见 §5 观察项） |
| ⑤ verifyComposition 三态 | 合法最小 profile → `{ok:true}`；未知 bundle → `{ok:false,violations:["组合错误: IllegalStateException: profile references unknown bundle: no-such-bundle"]}`；语法破坏 YAML → `{ok:false,violations:["组合错误: ParserException: …"]}`（MarkedYAMLException 归一阶梯真机实证——GREEN2 发现的线上形态） |
| 错误码探针 | -32601（id 回显）/ -32001（data.session 回显）/ -32602×2（tail 游标超前 + verify 缺 yaml） |
| 零写面旁证 | `jh-verify-composition*` 临时目录残留 **0**；~/.harness/profiles 最新 mtime 仍 Nov 8（干跑三发未触碰） |

### 4. SIGINT 收口——三执行伪影如实记档 + 复验绿（[stop3-sigint-retest.txt](stop3-sigint-retest.txt)）

**首轮失败与根因**（systematic-debugging 全链，非产品缺陷）：

1. 探针脚本 `kill -INT $!` 打到 **sh 包装壳**（it18/it35 已知坑第三现）——`bin/jh` 是 /bin/sh 包装，`$!` 拿壳 PID；JVM 定向后仍不退出。
2. JVM 定向 `kill -INT <jvm>` 两次 EXIT=0 但进程不动；jstack 取证：main 仍 park 在 `ServeShutdown.await`、Signal Dispatcher cpu≈0（handler 从未被调）——**信号未送达 handler**。
3. 对照探针 `kill -USR1` → 进程即死（jlink 精简镜像无 attach 机制，SIGUSR1 走默认处置杀进程——**我误期望线程转储，执行伪影**；serve 日志留下包装壳 `User defined signal 1: 30` 报行）。USR1 可达而 INT 不达 ⇒ 差异在 **INT 的处置被继承为 SIG_IGN**：非交互壳的后台作业（`cmd &`）按 POSIX 继承 SIGINT=SIG_IGN，JVM 的 sun.misc.Signal 绑定不改已忽略处置。it35 同法成功因其启动语境不同——本轮脚本经非交互 bash 后台起，语境差异即根因。

**复验轮（假设检验）**：python `subprocess.Popen`（缺省 `restore_signals=True` → 子进程 SIGINT 还原 SIG_DFL）起 serve → JVM 定向 `kill -INT` → **wrapper exit=0（0.02s）、JVM 零残留、lsof 8787 零行**。首按干净关停契约真机成立；用户交互终端起 serve 天然 SIGINT=SIG_DFL，不受此伪影影响（runbook 已注）。

**精确图景固化（停 3 用户裁决③）**：非交互定向信号须打 **JVM pid**（包装壳 pid 不生效）；进程组发送/交互终端不受影响——交互终端 Ctrl-C 发的是前台**进程组**，壳与 JVM 同组同收，真用户路径天然正确。用户检视轮独立复现同图景（见 S2）。

### 5. dump 同源比对观察项（登记待裁决，不改码）

RPC compositionDump 与 CLI --dump 逐行比对（[stop3-dump-rpc.txt](stop3-dump-rpc.txt) vs [stop3-dump-cli.txt](stop3-dump-cli.txt)）：**48 行行序、禁用注解（7）、脱敏（1）全同**；差异仅两类——① 临时 workspace 目录名每进程不同（预期）；② **行内 config 键序跨 JVM 不稳定**（如 `{name=…, baseUrl=…}` vs `{baseUrl=…, name=…}`）——既有 compose 路径 HashMap 迭代序所致（it27 dump 以来既有行为，非 it36 引入）。停 1 契约钉的是「组合期**行序**」，行序稳定 ⇒ 契约未违反。**用户裁决（检视轮②）：挂账不入本停**——确定性润色（LinkedHashMap 化）搭 B4 生成器家族或下次触 dump 码的批，不值得专批。

## S2 用户检视实录（2026-11-17，通过）——[stop3-user-round.txt](stop3-user-round.txt)

serve 由用户亲手起（交互终端），真浏览器 Chrome 执验，全程用户本会话亲手（it35「用户验证实录须亲手全链」纪律延续）。

- **DOM 七断言 7/7 PASS**（runbook 提供的断言脚本真浏览器执行）：标题/两页签/清单非空（50 条）/五视图/编辑器骨架/三按钮/错误条隐藏。
- **手动观察四项全 ✓**：详情 header、五视图切换 + 时间线大量事件、tail 空闲会话零错误条（零增量是预期）、页签切换停 tail。
- **编辑器干跑闭环逐字命中**：载入 dump → 改坏 `no-such-bundle` → 红框违规清单 → 修好 → 绿框指引。dump 显示「49 行」= 48 行内容 + 末行换行渲染的空行（AppBoot.dump 每行尾 `\n`，与 S1 的 48 行口径同源，非差异）。
- **下载链拦截法全路径验证**：blob（application/yaml）→ 动态锚 download=profile.yml + blob: URL → 内容 = 编辑器 YAML——零 server 写面承诺在下载路径成立。
- **SIGINT**：JVM 定向 INT → exit 0 + 端口释放；用户独立复现 S1 包装壳伪影并给出精确图景（交互终端发进程组，真用户不受影响）——已固化入 S1 §4（裁决③）。
- **用户两个自动化伪影如实记**（执行伪影非产品缺陷）：① 编辑器首轮断言 800ms 等待太短（干跑全量组合装配 1–2s），加长后全中；② 下载属性断言问错形状（download-profile 是 BUTTON，blob 锚动态创建——拦截法才是对的验法）。
- **裁决①（131→130）**：用户接受并认账——其 131 为无 mtime 过滤朴素求和，正撞计数坑第三变体（孤儿 XML）；stop2 证据档原值正确。计数坑家族一周内两次活捉（我 S2 一次、用户检视轮一次）——AGENTS.md 计数条 mtime 半句的实战价值双证。

### 6. 归档与测量面备注

- 归档四件：stop3-my-round-probes.txt（探针全录）/ stop3-sigint-retest.txt（复验轮）/ stop3-dump-rpc.txt / stop3-dump-cli.txt。
- probes 入档时按门禁（.githooks/pre-commit = git diff --cached --check）剥除行尾空白 **1 处**（L116，snakeyaml 错误摘录内的纯空格行）——JSON 载荷行与命令原文逐字节未动（it35 同源纪律）。
- 日志台账（/tmp）：it36-s3-{build-start.ts, image-build, list-modules, verify-keyless, probes(.sh/.txt), serve, serve2, serve3, sigint-retest(.py/.txt), dump-cli, dump-rpc, jstack, get-root.html}。
## S3 收口（2026-11-17，用户检视轮通过后授权）

### 1. 新鲜全量门禁（verify-stop full 层）

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export MVN="$HOME/Documents/apache-maven-3.10.0/bin/mvn"
date +%s > /tmp/it36-s3-full-start.ts        # 窗口起点 2026-11-17T14:36:05（本地）
bash integration/verify-stop.sh full --log-dir docs/plan/evidence/iteration-36/stop3-closeout
VERIFY_STOP_EXIT=0
```

[stop3-closeout/summary.txt](stop3-closeout/summary.txt)：11 步全 EXIT=0（含 10-validate `mvn -B -q validate` 与 11-package `mvn -B -q package`）；head=`53f16f0`；changed_files=[]（门禁跑时工作树仅文档/证据改动，源码零变化）。

### 2. 计数定论（全量测量面，mtime 窗口过滤）

surefire XML 求和（`**/target/surefire-reports/TEST-*.xml` + failsafe，mtime ≥ 窗口起点）：**186 XML = 1486 tests / 0 failures / 0 errors / 31 skipped**。窗口外陈旧 XML 2 件不计：`skill/tool IZProbeTest`（2026-10-13）与 `examples/headless AskVisibilityProbe`（2026-11-06）——后者即计数坑第三变体本尊（AGENTS.md 计数条已登记）。对比 it35 收口同口径 183 XML = 1456/0/0/31：+3 XML +30 tests 为 it36 停 1/停 2 新增（headless --dump/静态分派 + rpc 第五方法/资源守卫），同测量面可比。

### 3. 收口新鲜复证（full 门禁重建镜像后）

- keyless `--verify` EXIT=0，**34 rows / 41 discovered / 0 unreferenced 不变**（[stop3-closeout-verify-keyless.txt](stop3-closeout-verify-keyless.txt)）。
- 镜像 `bin/java --list-modules` EXIT=0，**69 行不变**；**ui.html 终值 441 行**（`wc -l`，软预算 ~800 的 55%——停 2 放行附言「停 3 报终值」兑现，[stop3-closeout-image.txt](stop3-closeout-image.txt)）。

### 4. 同步面（一次改终态）

- 根 README：workbench/ 行补 rpc（it35）+ Web UI（it36）；0.5 表行「计划」→「W1–W4 已关账，0.5.0 发布另议」；规划原则「0.5 尚未开工」句订正。
- docs/design/README：状态基准段 2026-11-11→2026-11-17、W2/W3/W4「仍为计划」→ 四站全通；0.5 表行逐站点名 it33–it36 交付形态。
- docs/plan/current-state：日期行 / 版本段挂账句 / 工作台能力行（补 it35 rpc + it36 Web UI + --dump）/ it36 条目改关账终态（CI run 号留 push 批回填）/ 裁决②键序挂账登记入另批挂账区。
- AGENTS.md 现状段：it36 W4「进行中」→「已验收 + 关账」终态。
- iteration-36.md：标题/停 3 行/验收清单勾选（CI 项留 push 批）；锚点 L43 注「已随收口批更新」。
- plan/README.md：无 0.5 状态行，无需改。

### 5. grep 零残留取证（[stop3-sync-grep.txt](stop3-sync-grep.txt)）

- 「不做 Web UI / 不做本地 Web UI」规范面：**零命中**（GREP2_EXIT=1；it35 T2 改判后 W4 复核成立）。
- 旧状态措辞（仍为计划 / 0.5 尚未开工 / 0.5（计划）等）：现状入口零残留；3 处命中均为引文非状态——current-state L10「当前进行中」为节名引用、iteration-36 L43 历史锚点引文（已注更新）、iteration-36 L64 验收行自引 grep 词表。

### 6. 收口批

本证据档 S3 段 + 归档三件（closeout-verify-keyless / closeout-image / sync-grep）+ stop3-closeout/ 门禁日志 + stop3-user-round.txt + 同步面五文件（README / AGENTS / design/README / current-state / iteration-36）随收口批提交（不 push）。门禁日志入档按门禁剥行尾空白（11-package.txt 5 处 jqwik 时间戳行尾 `= ` 空格；命令行与载荷行逐字节未动，it35 同源纪律）。**W4 关账 = 0.5「个人工作台」四站全通**；CI 四腿与 run 号随 push 批回填（待用户点名授权）；0.5.0 发布另议。
