# it33 停 2 验证取证 —— CLI 接线（--session / --session-view）

命令面（全部在仓库根、`export JAVA_HOME=$(/usr/libexec/java_home -v 25)`、Maven `~/Documents/apache-maven-3.10.0/bin/mvn`）。

## 1. 聚焦红绿（36/36）

```
mvn -pl examples/headless -am test \
  -Dtest='HeadlessOptionsTest,SessionViewRendererTest,HeadlessSessionViewTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- RED（实现前）：三测试类编译失败——SessionViewRenderer 找不到符号、busySessionText(String,WriterLockException) 不存在、unknownSessionText 参数长度不符、RunnerOptions.session()/sessionView() 缺失 = 功能缺失的预期红。
- GREEN（终态）：
  - `Tests run: 25, Failures: 0, Errors: 0 -- in HeadlessOptionsTest`
  - `Tests run: 8, Failures: 0, Errors: 0 -- in SessionViewRendererTest`
  - `Tests run: 3, Failures: 0, Errors: 0 -- in HeadlessSessionViewTest`
  - 汇总 `Tests run: 36, Failures: 0, Errors: 0, Skipped: 0`、BUILD SUCCESS。
- 首绿 2 败处置（测试期望笔误，非实现错）：(1) tools 测试把事件 time 当 seq——seq=列表下标 0/1/2/3，改期望；(2) 空会话 usage 视图恒有 `total  (无计量)` 行（与 usageViewRendersNoMeteringWhenNothingReported 矛盾）——改期望为四视图只出头行 + usage 加 total 行。修后 36/36。

## 2. 突变检查（破坏必红 → 还原逐字节比对）

快照 `/tmp/it33-pre-mutation.diff`（190 行，HeadlessMain.java 的 git diff）。

| 突变 | 内容 | 红证据 | 还原 |
|---|---|---|---|
| M1 | SessionViewRenderer 头行 `"session="` → `"sessionX="` | SessionViewRendererTest 7/8 红（unknownViewFailsLoud 先抛不渲染仍绿——符合预期）+ HeadlessSessionViewTest 1/3 红 | 逐字节 |
| M2 | 删 HeadlessMain.parse 缺省三行（`if (sessionView == null) { sessionView = "turns"; … }`） | HeadlessOptionsTest 1 fail（缺省断言）+ HeadlessSessionViewTest 1 error（e2e 缺省路径拿 null view）、BUILD FAILURE | 逐字节 |
| M3 | busySessionText `写者锁冲突` → `写锁冲突`（断言词面整体消失） | HeadlessSessionViewTest `tests="3" … failures="1"` | 逐字节 |

注：M3 首版 `写者锁冲突` → `写者锁冲突X` 未杀（contains 子串仍命中，failures=0），换 `写锁冲突` 后必红——突变选择需使断言词面整体消失。

- 还原终检：`git diff -- HeadlessMain.java | diff - /tmp/it33-pre-mutation.diff` 输出 `BYTE-IDENTICAL`；`grep 'sessionX\|写者锁冲突X\|写锁冲突'` 两生产文件零命中（exit 1）。
- 还原复绿：聚焦三测试类 36/36（见 §1 终态）。

## 3. 门禁与四件套

```
mvn -pl examples/headless -am validate          # BUILD SUCCESS；You have 0 Checkstyle violations（全模块）
mvn -pl examples/headless -am test -Dtest='HeadlessVerifyTest' -Dsurefire.failIfNoSpecifiedTests=false
                                                # tests="8" failures="0"——verify 摘要计数断言
                                                # "composition: 34 rows, 41 discovered, 0 unreferenced" 未变
```

- headless 依赖 ✓：`examples/headless/pom.xml` 加 `harness-workbench-projection`；`module-info.java` 加 `requires io.javanatic.harness.workbench.projection;`。
- moditect ✓：`dist/jh/pom.xml` `<modules>` 加 `<module>io.javanatic.harness.workbench.projection</module>`（`io.javanatic.harness.examples.headless` 后）。
- 敌意测试面不动 ✓：全量回归（突变检查前）全 reactor 3:46 绿——HostileInstructionsTest 敌意面通过。
- verify 计数不变 ✓：HeadlessVerifyTest 8/8（§3 上行）。

## 4. 真实会话检视实录（jlink 镜像，~/.harness/sessions 82 会话）

构建：`mvn -pl dist/jh -am package -DskipTests`（BUILD SUCCESS，jlink-image 重建）。`--help` 已含 `--session=`/`--session-view=` 与互斥面。

主会话（it31 双臂父会话，委派树；events=1593）：
```
jh --session=headless-1794139537162-5124 --session-view=turns
  session=headless-1794139537162-5124  view=turns  events=1593
  turn=1  completed  duration=12488ms  steps=6  tools=7  tokens_in=85288  tokens_out=1739  tokens_reason=0
jh --session=headless-1794139537162-5124 --session-view=tools   # 3 并行 delegate（seq 540/541/542）
  turn=1 step=0  call seq=540 delegate  args={"task": "…00-overview.md…", "cwd": "…", "budgetTokens": 15000}  ->  seq=547  ok  12ms
  turn=1 step=0  call seq=541 delegate  args={"task": "…05-capability-seam.md…"}  ->  seq=548  ok  12ms
  turn=1 step=0  call seq=542 delegate  args={"task": "…10-testing.md…"}  ->  seq=546  ok  12ms   # 乱序结果按 callId 配对正确
  turn=1 step=1..4  call seq=557/567/577/590 delegate_list  args={}  ->  ok  5/7/5/3ms   # 轮询子代
jh --session=headless-1794139537162-5124 --session-view=usage
  total  tokens_in=85288  tokens_out=1739  tokens_reason=0
jh --session=headless-1794139537162-5124 --session-view=timeline   # 1593 行：turn/start → request/header(cwd,date)
  → project/instructions(AGENTS.md sha256=… chars=16293) → skill/catalog → user/message → step/start → llm/request → assistant/chunk × N → …
jh --session=headless-1794139537162-5124 --session-view=messages   # deriveMessages 派生（含 tool-use/tool-result 块）
```
五视图全部 exit=0。

委派子会话（events=379）：`child-del-1794139540565-516f562f` turns：turn=1 completed 3747ms steps=2 tools=1；tools：fs_read → ok。

故障面：`child-del-1794139129058-6193f7ca`（多轮 + 中断 + 工具失败）：
```
turns:  turn=1  aborted:interrupted  duration=37535ms … / turn=2  completed …
tools:  turn=1 step=0  call seq=25 fs_list … -> seq=26  error  4ms
messages: [1] User … / [3] Tool(…) <tool-result … error> NoSuchFileException: /private/tmp/it31-dual/ws/data …
```
工具失败面：`headless-1794139347428-fcce` tools：shell → `error  61ms`。

keyless 全程（无 DEEPSEEK_API_KEY 参与）；stdout 只有视图行、stderr 空（成功路径）。

## 5. 已知项

- `AskVisibilityProbe.xml failures=1`：Nov 6 陈旧报告（非本次 -Dtest 选择），非本次失败。
- 既有行 HeadlessMain:598/688、HeadlessOptionsTest:87 超 140 列：改动前既有行、当前 checkstyle 绿，未动。
