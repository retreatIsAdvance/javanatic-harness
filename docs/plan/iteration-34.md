# 迭代 34 — W2 TUI 工作台（状态：进行中——四确认已裁；停 1 已放行；停 2 修订版已出包待复审（F1/F2/F3 已修 + 三项铁证）；停 3 修订版已出包待复审（文档同步面 7 项订正），2026-11-13）

**裁决记录（2026-11-12）**：W2 四确认讨论稿评估通过，三类可核实事实对实后落盘——**必裁项 JLine 4 同意**（版本钉 4.6.0 + 四落盘条件：jar --describe-module 实测、BSD-3-Clause 进依赖登记、只引端子层 jline-terminal、jlink `--enable-native-access` 集成成本进设计增量）；**入口形态 `jh --workbench` 独立 flag 同意**（REPL 是 12 §6 冻结面，混装即破坏稳定面）；**live 面读法流式逐行读面同意**（四契约条款化钉死；降级路径改「若流式实现遇阻，本停点降级为检视态 only」，删除「可刷新快照」伪选项——该档与跟随态同撞写者锁，已被证伪）；**四确认草稿本体同意，两处精化**：不要求运行中的进程配合从口号升为四契约条款；live 视图消费投影每 poll 全量重投影（增量投影不做，写进「不做」防过度设计）。

基线：it33 已关账（0.5 W1 落地），master `5c50433`（`45dfebb` run 号回填、`35601af` workflow 维护账两笔随本落盘批捎带推），工作版本 `0.5.0-SNAPSHOT`。

## 四确认

- **内容**：①`workbench/tui` 新模块——全屏 TUI 工作台，纯消费 W1 投影（workbench/projection 五视图）+ 本迭代新增的 seam 流式读面；终端层 JLine 4.6.0（jline-terminal + jline-terminal-ffm 两坐标直引，jline-native 为 terminal 的传递依赖必进镜像——terminal 模块描述符硬 requires `org.jline.nativ`（非 static），S3 jlink 实测排除即败，§2 已订正；jline-reader 不引）。②session/persistence-jsonl 新增**只读流式 tail seam**（四契约，条款见设计增量 §1）——W2 TUI 与 W3 server 的唯一读侧地基，三站不各自手搓解析。③CLI `jh --workbench [--session=<id>] [--follow]` 独立 flag：检视态（复用 W1 五视图投影渲染）＋跟随态（--follow 对流式读面轮询、每 poll 全量重投影 live tail）。④装配面：headless 依赖 workbench/tui；dist/jh moditect 清单四行（含 org.jline.nativ）+ `--enable-native-access` 烧入（antrun 后置替换，见设计增量 §3）；同步面 02/12/AGENTS/README/current-state。
- **目标**：复盘一次运行不手工翻 JSONL 的第二站——全屏工作台交互式检视会话（任务执行可视化）；跟随态对进行中会话 live tail。渲染器纯函数可测，端子 IO 薄壳最小化。
- **为什么**：it33 L13 目标延续（投影层成为 W2 TUI 与 W3 server 的唯一读侧地基）；**用户点透的关键矛盾**——`JsonlPersistence.load()`（session/persistence-jsonl/…/JsonlPersistence.java:125-145）对外来会话先 `WriterLock.acquire(dir)` 探针（占用即拒）再 `repairTornTail`（修复是写操作），跟随态 tail 的进行中会话按定义被活跃写者占用 ⇒ load() 必抛 WriterLockException。推论：无锁流式读不是「推荐」而是观察进行中会话的**唯一路径**；「可刷新快照」降级档同样撞写者锁，不成立。顺带修正 W1 措辞（检视「零写路径」实况是「零新事件」）。
- **不做**：写侧任何面（工作台只读）；运行中任务（W4/W5 面）；动态配置/组合编辑器；鼠标支持；**增量投影**（每 poll 全量重投影——主会话 1593 事件余量充足，W3 server 若需要再裁）；**jline-reader**（首版输入面＝导航式按键自管解析；命令编辑/补全需求出现再按输入面设计裁）；JSON-RPC server（W3，B29 预登记在案）、Web UI（W4）；会话「最新活动」自动发现（--follow 必配 --session，显式＞隐式）。

## 设计增量（评审裁决固化）

### §1 seam 流式面四契约（session/persistence-jsonl 只读 tail seam）

用户裁决条款化，逐条进新接口 Javadoc 与契约测试：

1. **无锁**——不探针、不获取写者锁（与 load() 的探针面正相反）；
2. **不修复撕裂尾**——修复是写操作，并发读者只容忍（撕裂尾归 load() 的事务域管）；
3. **完整行纪律**——末行不完整＝「尚不可用」，下轮再读，不得上抛 IllegalStateException；
4. **顺序消费、seq 单调**——按文件追加序逐行交付，事件 seq 单调不减。

接口形状（如 `SessionTail`/tail 方法名）停 1 设计钉死；W2 TUI 与（未来）W3 server 共同消费。若流式实现遇阻，本停点降级为检视态 only（用户裁决的降级路径，无第三选项）。

### §2 终端库依赖登记（JLine 4.6.0，BSD-3-Clause）

| 坐标 | 版本 | 许可证 | 用途 / 实测事实 |
|---|---|---|---|
| org.jline:jline-terminal | 4.6.0 | BSD-3-Clause（Bundle-License + parent POM 双证） | 端子抽象层；命名模块 `org.jline.terminal`（root module-info）；`requires java.desktop static transitive` 但 jdeps 字节码引用 0 |
| org.jline:jline-terminal-ffm | 4.6.0 | BSD-3-Clause | FFM 端子提供者；多版本 jar（releases: 22）；**JDK 25 ModuleFinder 实测解析为命名模块 `org.jline.terminal.ffm`**（读 META-INF/versions/22/module-info.class，automatic=false，manifest 的 Automatic-Module-Name 仅为低版本退路）⇒ 无需 moditect 注入 module-info |
| org.jline:jline-native | 4.6.0 | BSD-3-Clause | terminal POM 的 compile 传递依赖。**S3 jh 镜像实测推翻 W2 开工时「排除」论断**：`org.jline.terminal` 模块描述符为 `requires org.jline.nativ`（非 static），且字节码直引 `JLineLibrary`/`JLineNativeLoader`（JNI 回退面）；javac 惰性解析不报错，但 jlink 全图解析缺之即败（`Module org.jline.nativ not found`）。⇒ workbench/tui 不排除，moditect 清单加 `org.jline.nativ` 一行，三坐标同进镜像 |
| org.jline:jline-reader | （不引） | — | 首版输入面不需要（见「不做」） |

前置实证全文：[preflight-jline-460.txt](evidence/iteration-34/preflight-jline-460.txt)（repo1 元数据、jar --describe-module、ModuleFinder 决定性实测、jdeps、许可证、moditect 无 options 面、launcher 逐字、活链）。JPMS 文档活链：<https://jline.org/docs/modules/terminal-providers>（FFM Provider 专页，替换草案死链）。

### §3 jlink `--enable-native-access` 集成行

- dist/jh moditect `<modules>` 清单加四行：`io.javanatic.harness.workbench.tui`、`org.jline.terminal`、`org.jline.terminal.ffm`、`org.jline.nativ`（native 行 = S3 jlink 实测订正，见 §2）。
- 烧入通路（实测钉死）：moditect 1.2.2 的 Launcher 仅 name/module/mainClass、core 命令无 jlink options 透传 ⇒ jlink 官方 `--add-options` 插件不可达 ⇒ **dist/jh package 相位在 moditect create-runtime-image 之后、assembly 归档之前，用 maven-antrun-plugin 替换 launcher 空行**：`bin/jh`（sh）的 `JLINK_VM_OPTIONS=` → `JLINK_VM_OPTIONS="--enable-native-access=org.jline.terminal.ffm,org.jline.nativ"`；`bin/jh.bat`（Windows）的 `set JLINK_VM_OPTIONS=` 行同规则（.bat 模板实况以首次 jlink 冒烟取证为准）。
- java.desktop 不进镜像（static requires 不触发 jlink 解析；jdeps 零引用背书）。

### §4 W1 措辞修正（12 §6 两层语义）

动 seam 时 12 §6 写准：`--session` 检视 = **零新事件**（load() 会探锁 + 可能修撕裂尾，是写文件操作）；流式读面 = **真零写**。

### §5 每 poll 全量重投影

live 视图每轮 poll 对已消费事件全量重投影（JH 会话规模余量充足，主会话 1593 事件）；增量投影不做（用户裁决进「不做」，防过度设计）。

## 锚点

- [iteration-33.md](iteration-33.md) L13-15：W1 目标「复盘一次运行不手工翻 JSONL…该层成为 W2 TUI 与 W3 server 的唯一读侧地基（三站不各自手搓解析）」；L15「不做…实时 tail/流式订阅（live 面属 W2 TUI）」。
- [iteration-30.md](iteration-30.md) L7：路线评审预登记——W2 终端库选型（JLine 3 / Lanterna / 裸 ANSI 对比）为 W2 四确认置顶必裁项（新增第三方依赖须用户确认）。
- [boundary-register.md](boundary-register.md)：B29（W3 JSON-RPC 仅 127.0.0.1 回环预登记）；B34（core Session 空 seed 挂账，it33 挂账批）。

## 审查停点（3 停，按评审精化）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| 停 1：seam 流式面四契约 | session/persistence-jsonl 只读 tail seam 全模块（四契约逐条钉死 + 真实会话 tail 取证 + load() 锁语义对照 + 突变） | 已放行（评审通过；P3 header 原子性随独立 mini-fix 关闭，见 [stop1-f1-header-atomicity.md](evidence/iteration-34/stop1-f1-header-atomicity.md)） |
| 停 2：workbench/tui 渲染与按键面 + CLI 接线 + 装配面 | 渲染器纯函数五视图 + 按键解析 + 端子薄壳；HeadlessOptions 词表/互斥/usage；headless 依赖 + dist/jh 四行 + antrun 烧入；同步面 02/12/AGENTS/README/current-state | 修订版已出包待复审（评审 F1/F2/F3 已修 + 三项铁证；[stop2-review-packet.md](evidence/iteration-34/stop2-review-packet.md) §5 修订记录） |
| 停 3：真机验收 + 收口 | jlink 真 TTY 冒烟（检视态五视图 + 跟随态 live tail + launcher 烧入取证）+ CI 四腿 + review packet | 修订版已出包待复审（PTY 真终端双态实录 + Follower 关闭钩子 TDD；修订批文档同步面订正见 [stop3-review-packet.md](evidence/iteration-34/stop3-review-packet.md) §6） |

## 取证（packet 前置：命令 / 关键输出行 / EXIT 回显落盘 docs/plan/evidence/iteration-34/）

| 文件 | 覆盖（停点/验收项） |
|---|---|
| [preflight-jline-460.txt](evidence/iteration-34/preflight-jline-460.txt) | 落盘前置：repo1 元数据 / jar --describe-module / ModuleFinder 决定性实测 / jdeps / 许可证 / moditect 无 options 面 / launcher 逐字 / 活链 |
| [stop1-seam-design.md](evidence/iteration-34/stop1-seam-design.md) | 停 1 设计定稿：四契约条款 / 接口形状 / 机制（位置推进、clamp）/ 测试矩阵 T1–T10 / 突变 M1–M4 / 同步面 |
| [stop1-tdd-evidence.md](evidence/iteration-34/stop1-tdd-evidence.md) | 停 1 TDD 实录：RED（10 红 ServiceNotAvailable）/ 夹具订正 / GREEN（10 绿 + 全反应器回归）/ M1–M4 突变逐条红绿 |
| [stop1-f1-header-atomicity.md](evidence/iteration-34/stop1-f1-header-atomicity.md) | 停 1-F1 mini-fix：backfill header 原子化（竞态 RED 逐字症状 → temp+ATOMIC_MOVE GREEN → 突变复红 → 还原复绿 → 44/44 回归） |
| [stop2-evidence.md](evidence/iteration-34/stop2-evidence.md) | 停 2 取证：S1/S2 渲染/按键/状态 TDD 复跑突变 M-a–M-c；S3 CLI 接线突变 M1–M7 + 门禁（validate 0 checkstyle / 全反应器 1399/0/0）+ jlink 镜像冒烟（burn 行 / 五模块 / exit 3 keyless / verify 计数不变）+ §6 修订取证（F2 TDD 红绿突变 + F1/F3 修复 + 三项铁证） |
| [stop2-review-packet.md](evidence/iteration-34/stop2-review-packet.md) | 停 2 审查包：交付物 / 不变量增量 / 取证摘要 / 诚实交易（派发顺序单元层不可观察 + preflight 订正）/ §5 修订记录（F1/F2/F3 → 三项铁证）/ 请裁决 |
| [stop3-evidence.md](evidence/iteration-34/stop3-evidence.md) | 停 3 取证：Follower 关闭钩子 TDD（RED 编译红 + AutoCloseable 踩坑 + GREEN 10/10 + 突变单点红）；镜像重建（755/逗号清单/模块在镜/verify 不变）；PTY 真终端双态冒烟（五视图逐键 + live tail events=2→4 + 双态零 WARNING + 清理零残留）；诚实交易四项 |
| [stop3-review-packet.md](evidence/iteration-34/stop3-review-packet.md) | 停 3 审查包：关闭钩子交付 / 真机不变量 / PTTY 方法学声明 / 验证摘要 / 诚实交易 / 请裁决 |
| [stop3-closeout.md](evidence/iteration-34/stop3-closeout.md) | 停 3 门禁面：全反应器 179 XML = 1403/0/0/31（与停 2 的 1399 逐项对账：+3 TermIoTest 修订批 + 1 关闭钩子）；AskVisibilityProbe Nov 6 / IZProbeTest Oct 13 陈旧件隔离；CI 四腿回填位 |

## 验收

- [x] 流式 seam 四契约逐条有契约测试，load() 锁语义对照测试成立（进行中会话 load() 抛 WriterLockException vs tail 无锁可读）——T1–T10 全绿 + M1–M4 突变全闭环（[实录](evidence/iteration-34/stop1-tdd-evidence.md)）
- [x] `jh --workbench [--session=<id>] [--follow]` 解析 + 互斥矩阵 + `--help` 读/写区分（钉死测试）
- [x] 检视态五视图全屏渲染正确（夹具会话，聚焦测试绿 + 突变检查）
- [x] 跟随态对进行中会话 live tail 真机实录（无锁、完整行纪律、seq 单调）——PTY 真终端 `events=2→4` + 追加行入窗 + `[follow]` 逐字（[实录](evidence/iteration-34/stop3-evidence.md) §3b）
- [x] jlink 镜像 launcher 含 `--enable-native-access=org.jline.terminal.ffm,org.jline.nativ`（sh 面已取证、bat 面随 CI Windows 腿），真 TTY 冒烟零警告——PTY 双态 WARNING=0（[实录](evidence/iteration-34/stop3-evidence.md) §3）
- [x] `--verify` 计数不变（34 rows / 41 discovered / 0 unreferenced——tui 非 bundle 行）
- [x] 同步面（02/12/AGENTS/README/current-state）与模块表一致；12 §6 检视=零新事件/流式面=真零写
- [ ] validate + 全量 package 绿；CI 四腿绿（本地全反应器随 [stop3-closeout.md](evidence/iteration-34/stop3-closeout.md) 落盘；CI 四腿随收口批 push）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|
| （停 1 批，随停 1 提交） | T4 残片与 T5 手工行原写 `"seq":2`——turn/start 实为 seq 0（seed null 无重放），严格连续规则下「写者的下一条」应为 seq 1 | 实现前订正为 seq 1（RED 重验仍 10 红 ServiceNotAvailable）；T3 残片（第三事件位 seq 2）与 T6 跳号（seq 5）不动；诚实交易记入 [stop1-tdd-evidence.md](evidence/iteration-34/stop1-tdd-evidence.md) §2 |
| （停 1-F1 mini-fix，随停 1-F1 提交） | 停 1 评审 P3：`backfill()` 裸 `Files.writeString(header.json)` 非原子写，并发无锁 `open()` 在「文件已建、内容未写完」窗口可读到撕裂/空 header（此暴露 `list()` 早年就有） | `atomicWriteString` 助手（同目录 temp + `ATOMIC_MOVE`/`REPLACE_EXISTING` + 降级普通 move + finally 清理）；HeaderAtomicWriteTest 竞态测试钉死（TDD RED 逐字命中预测症状 + 突变复红）；实录见 [stop1-f1-header-atomicity.md](evidence/iteration-34/stop1-f1-header-atomicity.md) |
| （停 2 批，随停 2 提交） | preflight 断言错误：W2 开工时称「jline-terminal 的 module-info 不 requires org.jline.nativ ⇒ 排除 jline-native」——S3 jh 镜像实测 jlink 报 `Module org.jline.nativ not found, required by org.jline.terminal` | 订正：describe-module 完整输出含非 static `requires org.jline.nativ` + 字节码直引 `JLineLibrary`/`JLineNativeLoader`（javac 惰性解析不报错、jlink 全图解析必败）；workbench/tui 移除排除、moditect 清单加 `org.jline.nativ` 一行；§2/§3 与 [preflight-jline-460.txt](evidence/iteration-34/preflight-jline-460.txt) 已订正（证据后置偏离，如实登记） |
| （停 2 修订批） | 停 2 评审 F1（阻塞）：烧入清单只授 `org.jline.terminal.ffm`——真调受限方法（`System::load` JNI）的是 `org.jline.nativ`，每次启动 4 行 WARNING | sh/bat 双面烧入值 → `--enable-native-access=org.jline.terminal.ffm,org.jline.nativ`；重建镜像后三面（--version/--verify/unknown-id）stderr 零 WARNING（铁证 ①，[实录](evidence/iteration-34/stop2-evidence.md) §6） |
| （停 2 修订批） | 停 2 评审 F2（阻塞）：「无真 TTY exit 2」是广告词不是行为——JLine 4 `TerminalBuilder` 退化环境建哑终端不抛，exit-2 分支死码（四种退化环境全渲染 ANSI 帧后 exit 0） | `TermIo.checkedOpen` 判定：JLine 两哑型（TYPE_DUMB="dumb"/TYPE_DUMB_COLOR="dumb-color"，javap 实测）先关端子再抛 IOException「无真 TTY」；TDD（RED 编译红 → GREEN 11/11 → 突变 1 红 → 还原复绿）；哑终端双环境（os.setsid 无控制终端 / TERM=dumb 管道）EXIT=2 + stdout 0 字节 + 指引词面（铁证 ③） |
| （停 2 修订批） | 停 2 评审 F3（非阻塞）：antrun replace 经 java.nio 临时文件改写剥 `bin/jh` 执行位（600，直接执行 126；发行归档不受影响——assembly 强制 0755） | sh 执行体 `<replace>` 后补 `<chmod perm="755">`；`ls -l` 实测 755 与 java/keytool 同、`./bin/jh --version`（非 sh）EXIT=0（铁证 ②） |
| （停 3 修订批） | closeout 待推批次手工枚举漏列停 1 两笔（`9b6cf22`/`de828ea`）——收口批按清单推会漏推 | 清单补全 9 笔 + 本批 = 10 笔；批清单以 `git log --oneline origin/master..HEAD` 为准 |
| （停 3 修订批） | F1 后同步面欠账：AGENTS.md 现状行与 §3 正文/preflight 烧入值仍为旧单模块值（「同步面按语义覆盖范围划」实撞） | AGENTS.md 现状行 / §3 / preflight 订正为逗号清单；AGENTS.md 补 F3 chmod 755 面 |
| （停 3 修订批） | 停 3 packet 关闭钩子出处引「停 2 评审 P3 遗留」无法追溯（停 2 评审归档仅 F1/F2/F3，无 P3）——引文保真度纪律（it33 先例） | packet / evidence / 测试 Javadoc 三处出处订正为可追溯事实（停 2 交付 source seam 无 close 生命周期面，W3 长驻消费前置缺口）；pits 回填 |
| （停 3 修订批） | packet 笔误「验收清单两项勾结」；current-state 进行中段陈旧（仍写停 2 出包） | 「两项收勾」；current-state 更新为停 3 修订版出包待复审；closeout 陈旧件补具名 IZProbeTest |

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
