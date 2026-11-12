# it34 停 2 验证取证 —— CLI 接线 + 装配四件套（--workbench / --follow）

命令面（全部在仓库根、`export JAVA_HOME=$(/usr/libexec/java_home -v 25)`、Maven `~/Documents/apache-maven-3.10.0/bin/mvn`）。

## 1. 聚焦红绿（9/9 + 8/8）

```
mvn -pl examples/headless -am test \
  -Dtest='HeadlessWorkbenchTest,HeadlessVerifyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- RED（实现前，TDD 先写测试）：`HeadlessWorkbenchTest` 引用未实现符号（`RunnerOptions.workbench()/follow()`、`runWorkbench`、`workbenchSource`、`workbenchNoTerminalText`）——编译失败 = 功能缺失的预期红；随实现逐项转绿。
- GREEN（终态，2026-11-12 12:36 全绿）：
  - `HeadlessWorkbenchTest` tests="9" errors="0" skipped="0" failures="0"（解析矩阵 / 词表闸 / 互斥矩阵 / usage 广告 / source 两态装配 / 未知会话双态 fail loud / live tail 双 Runtime / 未知 id exit 3 / busy 检视 exit 3）
  - `HeadlessVerifyTest` tests="8" errors="0" skipped="0" failures="0"（verify 摘要计数断言）
  - BUILD SUCCESS（10.4s）

## 2. 突变检查（破坏必红 → 还原复绿）

### 2a. S3 CLI 接线面（M1–M7，全部闭环）

| 突变 | 内容 | 红证据 | 还原 |
|---|---|---|---|
| M1 | `workbenchSource` 两态分支互换（follow 走 load、检视走 tail） | 跟随态 live-tail 测试红：busy 会话 load 探锁即抛 WriterLockException | 复绿 |
| M2 | 删跟随态 `events.addAll(follower.nextEvents())`（快照恒空） | live-tail 测试红：Expected size 1 but was 0 | 复绿 |
| M3 | 删 `--follow 需与 --workbench 同用` 校验 throw | 词表闸测试红 | 复绿 |
| M4 | 删 `--workbench 需与 --session 同用` 校验 throw | 词表闸测试红 | 复绿 |
| M5 | `runWorkbench` 未知 id 分支 exit 3 → exit 0 | unknown-session exit 3 测试红（期望 3 得 0） | 复绿 |
| M6 | 删 `--workbench 与 --sessions 互斥` 校验 | 互斥矩阵测试红 | 复绿 |
| M7 | 删 `writerLockCause` 提取（IOException 一律原样上抛） | busy-inspection exit 3 测试红（error 非 exit 3） | 复绿 |

M1 首跑全量套件时在 `TermIo.open()` 挂起（Bash 工具 PTY 使端子打开成功 → TUI 循环）——改聚焦 `-Dtest` 后正常红绿。教训沿用单测纪律：涉 dispatch 的突变须聚焦运行避开端子打开路径（单测本身绝不触 `TermIo.open()`）。

### 2b. S1/S2 渲染与按键面（M-a–M-c，本停点复跑取证）

| 突变 | 内容 | 红证据 | 还原 |
|---|---|---|---|
| M-a | `WorkbenchRenderer` 状态栏 `"session="` → `"sessX="` | WorkbenchRendererTest 13 run / 8 failures（全部状态栏断言逐字失配） | git checkout 还原 → 13/13 绿 |
| M-b | `KeyParser` quit 词表删 `code == 'q'` | KeyParserTest 12 run / 4 failures（quit 词表 + 三个以 'q' 为未知键探针的复位用例） | 还原 → 12/12 绿 |
| M-c | `WorkbenchState.apply` Quit 分支 `false` → `true`（退出不停跑） | WorkbenchStateTest 14 run / 1 failure——恰为 `quitStopsRunningAndKeepsViewport`（单点精确击杀） | 还原 → 14/14 绿 |

还原方式 = `git checkout`（与已提交基线逐字节一致，`git status` 干净为证）。

## 3. 门禁与四件套

```
mvn validate            # EXIT=0；全反应器 0 Checkstyle violations
mvn test                # 全反应器 BUILD SUCCESS 3:46（2026-11-12 12:40）
                        # 新鲜 surefire XML 179 件 = 1399 tests / 0 errors / 0 failures / 31 skipped
```

- headless 依赖 ✓：`examples/headless/pom.xml` 加 `harness-workbench-tui`；`module-info.java` 加 `requires io.javanatic.harness.workbench.tui;`。
- moditect ✓：`dist/jh/pom.xml` `<modules>` 加四行——`io.javanatic.harness.workbench.tui`、`org.jline.terminal`、`org.jline.terminal.ffm`、`org.jline.nativ`。
- launcher 烧入 ✓：antrun package 相位后置替换，sh/bat 双执行体 `failOnNoReplacements="true"`（token 缺失 fail loud）。
- verify 计数不变 ✓：`HeadlessVerifyTest` 8/8（断言 `composition: 34 rows, 41 discovered, 0 unreferenced` 未变）+ 镜像真跑（§4）。
- tui 模块新鲜计数 ✓：5 个测试类 55 tests 全绿（Renderer 13 / Parser 12 / State 14 / App 8 / TermIo 8）——TermIo 测试只触纯助手面，不 `open()`。

## 4. jlink 镜像冒烟（dist/jh target/jlink-image，2026-11-12 12:41 重建）

构建 `mvn -pl dist/jh -am package -DskipTests`（BUILD SUCCESS）。

- 烧入行在镜：`bin/jh` line 2 = `JLINK_VM_OPTIONS="--enable-native-access=org.jline.terminal.ffm"`（mtime 12:41:18 新鲜）。
- 五模块在镜（jimage list 尾部 Module 行）：`io.javanatic.harness.workbench.projection` / `.tui` / `org.jline.terminal` / `.terminal.ffm` / `.nativ`；`org.jline.nativ` 携带全平台 JNI 库（Mac/Linux/Windows 的 .jnilib/.so/.dll 均在镜像资源面）。
- `sh bin/jh --version` → `0.5.0-SNAPSHOT`，EXIT=0（烧入 VM 选项的 launcher 真实起跑）。
- `sh bin/jh --workbench --session=no-such-id`（keyless）→ stderr `会话不存在: no-such-id；用 jh --sessions 查看可用会话…`，EXIT=3。
- keyless `sh bin/jh --verify` → EXIT=0，摘要 `composition: 34 rows, 41 discovered, 0 unreferenced`（计数不变实跑面）。
- `sh bin/jh --help` → 含 `--workbench`/`--follow` 三行（用法/选项/互斥）与 exit 2 行 `用法错误 / 缺少 API key / --workbench 无真 TTY`。

## 5. 已知项（诚实交易）

- **派发顺序突变在单元层不可观察**：`HeadlessWorkbenchTest` 经 seam 直测 `runWorkbench`/`workbenchSource`，不经 `main()` 派发——「workbench 旁路先于 API key 检查」的换序突变在单元套件无信号，不伪称有测试钉死。实跑面有真实观察：keyless 镜像 `jh --workbench --session=no-such-id` 出 exit 3（会话诊断）而非 exit 2（缺 key）——若顺序相反必先报 key 错误，§4 输出为证。
- **preflight 订正（jline-native，证据后置偏离）**：W2 开工 preflight 断「terminal module-info 无 requires 边 ⇒ 排除 jline-native」；S3 镜像实测推翻（describe-module 完整输出含 `requires org.jline.nativ` 非 static + 字节码直引 `JLineLibrary`/`JLineNativeLoader`；javac 惰性解析不报错而 jlink 全图解析必败）。处置：workbench/tui 移除排除 + moditect 加行；iteration-34 §2/§3 与 [preflight-jline-460.txt](preflight-jline-460.txt) 已订正（随停 2 批提交）。
- 真 TTY 面（检视态全屏五视图 + 跟随态 live tail 实录 + launcher 零警告取证）留停 3 真机冒烟；`.bat` 烧入随 Windows 档案走 archive-windows profile，本地 macOS 无 .bat 生成，留 CI Windows 腿取证。
