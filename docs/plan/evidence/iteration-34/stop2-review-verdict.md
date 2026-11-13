# it34 停 2 评审结论归档（2026-11-12，用户侧评审原文）

**结论：暂不放行——修订后重出 packet。** 三处发现，其中两处阻塞；其余全部通过。

（以下为评审原文摘录，冻结不改写。）

## 通过面（独立复核）

| 声明 | 复核 | 结果 |
|---|---|---|
| 聚焦 9/9 + 8/8 | 用户重跑 | HeadlessWorkbenchTest 9/9 + HeadlessVerifyTest 8/8 ✓ |
| 五模块在镜 | `bin/java --list-modules` | projection/tui/terminal/terminal.ffm/nativ 全在 ✓（最初查 `lib/` 散装 jar 是查错了地方——jlink 并入 `lib/modules`） |
| 接线代码 | 逐行读 diff | 旁路在 key 检查前、互斥矩阵七条、两态 seam 界限（load 定值快照 vs tail 累积重投影）与裁决一致 ✓ |
| 突变十例 + 证据保真 | 取证档 | M1 挂起教训（PTY 使 TermIo 打开成功→聚焦跑）如实记录 ✓ |
| 上轮 P3 裁决兑现 | `de828ea` | header 原子化独立提交，含 TDD 种族测试 + 突变复红——packet 没提但已落，好评 ✓ |
| jline-native 自我订正 | 订正链 | preflight 误断 → S3 实测推翻 → 订正入 §2/§3 + 修正表，诚实交易范本 ✓ |

## 阻塞发现

**F1 · 烧入模块清单不全——每次启动 4 行 WARNING，烧入目标没达成**。真调受限方法的是 `org.jline.nativ`（`System::load` 装 JNI 库），烧入行只放行了 `org.jline.terminal.ffm`。全部复跑 stderr 都有 394 字节四行 WARNING（`Use --enable-native-access=org.jline.nativ to avoid a warning`）。「烧入行在镜」只验证了行存在，没验证目标达成——停 3 的「零警告取证」现在必挂。修法：sh/bat 两处 antrun 值改为 `--enable-native-access=org.jline.terminal.ffm,org.jline.nativ`（逗号列表），重建镜像后以「启动 stderr 零 WARNING」为铁证重新取证。讽刺的是这正是诚实交易第②项的连锁——nativ 被实测强制入镜，它的 JNI 装载也要放行。

**F2 · 「无真 TTY exit 2」是广告词，不是行为**。`runWorkbench` 的 exit-2 分支在能构造的**一切环境**都不触发：PTY 环境、`os.setsid()` 无控制终端、`TERM=dumb`、`TERM` unset——四种情况 TUI 都照常渲染 ANSI 帧进管道 stdout（1139 字节）然后 EOF 退出 exit 0。原因在 `TermIo.open()`：JLine 4 的 `TerminalBuilder` 在退化环境不抛异常而是建哑终端，exit-2 分支成了死码。且此词面钉在 `--help` 退出码表、packet 不变量 #6、`runWorkbench` Javadoc 三处，单元层也无测试（取证档自己承认 TermIo 测试不 `open()`）。二选一：
- **（推荐）实现契约**：`TermIo.open()` 检测 JLine 哑终端类型（`TYPE_DUMB` → 抛 IOException「无真 TTY」），单测钉死该判定（勿用 `System.console()` 判——agent 工具的 PTY 冒烟会误杀）；向管道倾倒 ANSI 帧比 fail loud 更糟，广告的行为也是对的行为。
- 或改契约：删 exit-2 承诺、文档化「哑终端降级渲染、EOF 退出」——但这要在三处词面同步改且是坏行为。

## 非阻塞发现

**F3 · antrun replace 剥掉 `bin/jh` 执行位**。松散镜像里 `jh` 是 `-rw-------`（`java`/`keytool` 都 755）——`java.nio` 临时文件 600 签名，replace 重写后未恢复。直接执行 126；发行归档不受影响（assembly 强制 `bin/ 0755`）。冒烟全程 `sh bin/jh`（取证如实记录，恰好掩盖了这题）——但 README/用户习惯/停 3 冒烟都是直接执行。修法：同 antrun target 里 replace 后补 `<chmod perm="755">`。

## 处置

修 F1 + F3（两处 pom 小改）+ F2 二选一（推荐实现 TYPE_DUMB 判定），重建镜像，冒烟面补三项铁证：①启动 stderr 零 WARNING；②直接执行 `bin/jh`（非 `sh`）exit 正常；③哑终端环境 exit 2 + 指引词面。更新取证与 packet 后重出包。停 3 范围不变（真 TTY 全屏五视图 + live tail 实录 + `.bat` CI 腿）。

四笔提交留置不动，修订作为增量提交即可——诚实交易两项与突变/门禁记录都有效，不需要重跑。

## 附注（放行裁决附言，2026-11-13 补档）

停 2 修订版复审结论（放行）裁决附言第 2 条原文（冻结不改写）：

> 2. 一个遗留 P3 顺带看一眼：跟随态 `workbenchSource` 里的 `Follower` 无关闭钩子（今天 close 是空操作，W3 长驻面才有意义）——停 3 若触到该文件顺手补，触不到就留 W3 开工再办。

补档缘由：放行裁决未随本档（否决裁决）归档——归档不对称，停 3 packet 凭会话记忆引「停 2 评审 P3 遗留」断链。该 P3 即停 3 关闭钩子的出处（`WorkbenchSource extends AutoCloseable` + Follower.close 透传）；packet / evidence / 测试 Javadoc 三处引用已随停 3 修订批订正为可追溯表述，本附注闭合链条。
