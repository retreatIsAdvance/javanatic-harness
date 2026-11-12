# it34 停 3 验证取证 —— 真机 TTY 冒烟 + 关闭钩子 + 收口门禁

命令面（仓库根、`export JAVA_HOME=$(/usr/libexec/java_home -v 25)`、Maven `~/Documents/apache-maven-3.10.0/bin/mvn`；驱动脚本 `/tmp/it34-stop3/pty-smoke.py`、`pty-follow.py`——非仓内工件）。

## 1. Follower 关闭钩子（停 2 评审 P3 遗留，TDD）

- RED（实现前）：`HeadlessWorkbenchTest` 新增 `followSourceCloseReleasesTailCursor`（child scope 注入计数 wrapper——父链取真实现,无递归），首跑编译红逐字：`找不到符号 方法 close() 位置: 类型为 io.javanatic.harness.workbench.tui.WorkbenchSource 的变量 followed`（HeadlessWorkbenchTest.java:211）。
- 首实现踩一坑（诚实记录）：只给 `WorkbenchSource` 加 `default close()` 不够——try-with-resources 要求静态类型实现 `AutoCloseable`，编译红 `WorkbenchSource 无法转换为 java.lang.AutoCloseable`；补 `extends AutoCloseable`（close 收窄为 throws IOException）后过。
- GREEN：`WorkbenchSource extends AutoCloseable` + default close（检视态空实现）；`HeadlessMain.workbenchSource` 跟随分支改匿名类透传 `follower.close()`；`runWorkbench` 改 `try (io; source)`（source 先关、io 后关）。HeadlessWorkbenchTest **10/10**（9 + 新 1）。
- 突变：摘除 `follower.close()` 透传 → `followSourceCloseReleasesTailCursor` 单测 1 run / 1 failure（单点精确击杀）→ 还原复绿 10/10。
- 变更面：WorkbenchSource.java / HeadlessMain.java / HeadlessWorkbenchTest.java 三文件。

## 2. 镜像重建（含关闭钩子改动）

`mvn -pl dist/jh -am package -DskipTests` BUILD SUCCESS（16:39 重建）。

- `bin/jh` = `-rwxr-xr-x`（执行位 755,与 java/keytool 同——F3 修复保持）。
- 烧入行 = `JLINK_VM_OPTIONS="--enable-native-access=org.jline.terminal.ffm,org.jline.nativ"`（F1 逗号清单保持）。
- `bin/java --list-modules` 含三 JLine 模块 + workbench 两模块。
- keyless `--verify` EXIT=0，`composition: 34 rows, 41 discovered, 0 unreferenced` 不变。

## 3. 真机 TTY 冒烟（方法学声明）

**PTTY 即真终端**：`pty.fork()`（macOS）给子进程 setsid + 真控制端子（stdin/out/err 全接 slave、`TERM=xterm-256color`）——JLine 视角与真人终端同面（对照：停 2 修订取证的哑终端双环境在此法下不存在）。驱动脚本喂键、`select` 收屏、waitpid 取退出码；stderr 并入 PTY 流,零 WARNING 检查在捕获面做。

### 3a. 检视态：真实会话五视图 + q 退出

`jh --workbench --session=headless-1794139537162-5124`（1593 事件主会话，只读），依次喂键 `1 2 3 4 5 q`：

```
== inspect-five-views: EXIT=0 bytes=5174 WARNING=0
   [HIT ] view=turns      [HIT ] view=tools      [HIT ] view=usage
   [HIT ] view=timeline   [HIT ] view=messages
   [HIT ] session=headless-1794139537162-5124  events=1593
```

五视图逐键命中（缺省 turns,键 1-5 切换）、状态栏 session/events 正确、q 退出 EXIT=0、**真终端零 WARNING**（烧入双模块生效的实跑面）。

### 3b. 跟随态：live tail 实录（外部追加模拟写者）

夹具：`~/.harness/sessions/stop3-live-smoke/`（header.json 复制自主会话；log.jsonl 取主会话前 2 完整行 = seq 0/1）。`jh --workbench --session=stop3-live-smoke --follow`,初渲染后向 log.jsonl 追加主会话第 3/4 行（seq 2/3 完整行）,等 3 秒（poll 节拍 100ms）,喂 `q`：

```
== follow-live-tail: EXIT=0 bytes=873 WARNING=0
   [HIT ] events=2 → [HIT ] events=4（追加后屏面事件数增长）
cleanup: True（夹具目录已删,零残留）
```

帧首行逐字（转储 follow-raw.bin）：初帧 `session=headless-1794139537162-5124  view=timeline  events=2  [follow]`；末帧窗口含追加行 `2  ...  project/instructions  ...`、`3  ...  skill/catalog`；footer `[跟随中] 1-5 视图 q 退出`。

**live tail 四契约实跑面**：无锁（全程无 .writer.lock 创建）、完整行纪律（追加即完整行,下个 poll 交付）、seq 单调（seq 2/3 按序出现）、EXIT=0。

## 4. 收口门禁

- 全反应器 `mvn test`：见 [stop3-closeout.md](stop3-closeout.md)（随本停点落盘,含 CI 四腿随收口批 push 后回填）。
- `.bat` 烧入面：随 Windows 档案走 archive-windows profile（本地 macOS 无 .bat 生成）,留 CI Windows 腿取证（停 2 已声明,维持）。

## 5. 已知项（诚实交易）

- **外部追加 = 模拟写者**：跟随态实录的「写者」是 python 追加完整行,非持锁 jh 写进程——seam 四契约使写者身份对读者不可观察（无锁、只认完整行与 seq）,读者面证据不受影响;真写进程的对照面已有单测（workbenchSourceTailReadsLiveSessionWhileWriterHoldsLock：写者持锁时跟随可读、检视即拒）。
- **夹具 header 复用**：throwaway 会话的 header.json 复制自主会话——屏面 session= 显示 header 内 id（查找按目录名,显示按 header id,两者不同源是 open() 的既有语义,如实记录）。
- **探针词面两处首跑 MISS（非产品缺陷）**：跟随态缺省视图是 timeline（检视态缺省 turns）,首版探针 `view=turns` 误;`turn=1 completed` 在「turn 未闭合」夹具本就不该命中。改用 `view=timeline`/`[follow]`/追加行探针后全 HIT。
- **JLine 启动期端子能力探测**：帧前出现 `\x1b[?u` / `\x1b[?2026$p` / `\x1b[c` 等探测序列（Kitty 键盘协议 / DA1）——真终端会应答,无人应答时 JLine 超时继续,无害。
