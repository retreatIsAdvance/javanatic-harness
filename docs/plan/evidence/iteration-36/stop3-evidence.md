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

### 5. dump 同源比对观察项（登记待裁决，不改码）

RPC compositionDump 与 CLI --dump 逐行比对（[stop3-dump-rpc.txt](stop3-dump-rpc.txt) vs [stop3-dump-cli.txt](stop3-dump-cli.txt)）：**48 行行序、禁用注解（7）、脱敏（1）全同**；差异仅两类——① 临时 workspace 目录名每进程不同（预期）；② **行内 config 键序跨 JVM 不稳定**（如 `{name=…, baseUrl=…}` vs `{baseUrl=…, name=…}`）——既有 compose 路径 HashMap 迭代序所致（it27 dump 以来既有行为，非 it36 引入）。停 1 契约钉的是「组合期**行序**」，行序稳定 ⇒ 契约未违反；键序是否要钉（LinkedHashMap 化）留用户裁决，本停不动码。

### 6. 归档与测量面备注

- 归档四件：stop3-my-round-probes.txt（探针全录）/ stop3-sigint-retest.txt（复验轮）/ stop3-dump-rpc.txt / stop3-dump-cli.txt。
- probes 入档时按门禁（.githooks/pre-commit = git diff --cached --check）剥除行尾空白 **1 处**（L116，snakeyaml 错误摘录内的纯空格行）——JSON 载荷行与命令原文逐字节未动（it35 同源纪律）。
- 日志台账（/tmp）：it36-s3-{build-start.ts, image-build, list-modules, verify-keyless, probes(.sh/.txt), serve, serve2, serve3, sigint-retest(.py/.txt), dump-cli, dump-rpc, jstack, get-root.html}。