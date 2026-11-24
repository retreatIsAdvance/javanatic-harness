# it39 停 1 评审 packet —— 0.6.0 翻转批 + notes 定稿 + 稳定面审计三法

**本停是否新增模块 / base 加行：全零**（新模块 0 / 新发布坐标 0 / 导出包 0 / 事件 0 /
配置键 0 / bundle 行 0 / 第三方依赖 0；`bundle/base` 仅加 `requires interaction.approval`，
`examples/headless` 仅加 4 个 requires——均为 it38 已交付并在 0.5.0→0.6.0 审计面核对）。

## TL;DR（五行）

1. **不变量增量**：版本树 `0.6.0-SNAPSHOT → 0.6.0`（含版本串 77→0 残留；全仓 POM 78 /
   reactor 76 / 发布坐标 71 不变）；5 处硬编码版本串改齐（UA ×4 = 0.6，JDT clientInfo =
   0.6.0）——pits 首次在翻转前拦截同族滞后；`docs/design/02` 6 处 POM 片段同批翻转。
2. **裁决点**：无新裁——四确认四点全批后按模板执行；停 1 只做「翻转 + notes + 审计」，
   未 commit、未 push、未进 rc。
3. **风险自报**：① 审计发现 [12] 导出面计数旧记 55/59 实为 56/60（it35 W3 rpc 入表未抬，
   **非 0.6.0 增量**；已在 12 §2 加四测修正注，两版本树逐字相同）；② `release.md` 只改了
   两处 78/77 POM 计数，其余 0.5.0 化按 it37 先例留收尾批；③ consumer-sample 发布腿
   注释仍 0.5.0（Publish 后重指，属收尾）；④ notes 的相对链接风格沿用 0.5.0 采用稿。
4. **证据入口**：[flip-evidence.txt](flip-evidence.txt)（翻转 / 门禁 / verify / 镜像）+
   [audit-stability-surface.txt](audit-stability-surface.txt)（导出面 / 签名 / 事件 /
   配置 / 依赖 / CLI）+ [release-notes-0.6.0.md](../../release-notes-0.6.0.md)。
5. **若只能改一处**：`docs/design/12-api-stability.md` §2 的计数修正注——它把「审计测到
   56/60」与「旧文档 55/59」的差异钉死为 **it35 遗留的单模块计数滞后**，防止评审把审计
   数字误读成 0.6.0 的导出面漂移（升级条款的唯一触发面）。

## 一、翻转面（证据见 flip-evidence.txt）

- **SNAPSHOT 残留 0**：`grep -rl '0.6.0-SNAPSHOT' --include=pom.xml .` 无命中；
  `0.5.0-SNAPSHOT` 亦无命中。
- **版本树**：reactor `<version>0.6.0</version>` = 76；非 reactor 单点
  `<harness.version>0.6.0</harness.version>` = 1（consumer-sample）；POM 总数 78
  （= reactor 76 + consumer-sample 1 + negatives/no-version 1 无版本串）。
- **发布坐标 71（不变）**：reactor 76 − `excludeArtifacts` 5（examples 3 + dist 2）；
  POM 文件集合与 `<modules>` 清单 v0.5.0→HEAD 逐字相同（78/78、111/111）。
- **bundle 1398 预期（不变）**：233 主件 + 233 `.asc` + 932 校验和，以真 deploy 日志定案；
  消费闭包 34（不变）。
- **镜像模块 69（不变）**：`jlink-image/bin/java --list-modules` = 69，其中
  `io.javanatic` 53。
- **硬编码版本串**：UA 4 处全部 `javanatic-harness/0.6`（主源 2 + 测试断言 2）；
  JDT `clientInfo` 1 处 `0.6.0`；`docs/design/02` 6 处 POM 片段 `0.6.0`。
- **`jh --version`** = `0.6.0`，EXIT=0。

门禁：`mvn -B -q validate` EXIT=0；`mvn -B package` **PACKAGE_EXIT=0 / BUILD SUCCESS**
（04:45 min）；fresh surefire XML（窗口 = 日志 birth 09:31:18，202 份）= **1670/0/0/25**
（与 it38 关账终态一致，翻转不改测试数；300s 真钟哨兵不重复跑）。

## 二、notes 定稿（三层叙事，逐字对齐裁定）

1. **对 0.5 用户的承诺**（升级必读首行）：「无破坏性变更，缺省行为与 0.5.0 完全一致」，
   `--serve` 不带 `--web-write` 时方法面 / UI / 监听 / 围栏 / 退出码全原样。
2. **写面 opt-in**：`--web-write` 缺省关、无 `--serve` exit 2、`--verify --serve
   --web-write` 为 keyless 纯预检；`web-write: off/on` 可断言。
3. **围栏先行半页因果**：0.5.0 已先船运 B36/B37（Host 白名单 + Origin 同源 + POST
   application/json），0.6.0 写方法组与 `/events` 无条件过同一围栏——「先有只读围栏，
   再有写面」；B29 不变，写面扩大的是本机浏览器能做什么，不是谁能连进来。

其余：W5 四能力面 + 审批中继 300s fail-closed 单源 + SSE `Last-Event-ID=seq` + 四态
状态机（含 `ask_user` 后「等待答复」不被 `turn/end` 覆盖）+ 已知边界（steer/队列不做、
问题无超时、dump 键序挂账、jobs 面未并站）+「0.5.0 仍然成立」。

## 三、审计三法（证据见 audit-stability-surface.txt）

- **导出面**：`v0.5.0..HEAD` 的 module-info diff = 2 文件 8+/0−（`bundle/base` +1
  requires、`examples/headless` +4 requires）；exports 集合 **60 → 60 逐字相同**，
  module-info 文件 **56 → 56**，零新模块 / 零新导出包。
- **签名**：`-`/`+` 双向看，公开签名唯一变化是 `RpcHttpServer.start/address` 两个
  public 成员**新增** `synchronized`（`-` 侧为原无修饰符签名，成员与参数不变）；
  **零删除签名**；`WorkbenchRpc` / `RpcHttpServer` / `AppBoot` 构造/方法均为**新增
  重载**，既有签名保留。方向已在下方「订正」中修正。
- **事件**：`SessionEvent.java` diff 为空（permits 不变）。
- **配置/资源**：`kernel/config/**` 与 `**/resources/**/*.yml` diff 仅版本行；
  base bundle **41 → 41** 行。
- **依赖**：非 harness `<artifactId>` 集合 **24 → 24** 相同。
- **CLI**：主源 flag 字面量 9 → 10，唯一新增 `--web-write`；`jh --help` / `--verify`
  / `--verify --serve --web-write` 三态实测见证据。

结论：**零破坏性稳定面变化；预登记增量（+1 flag、+4 RPC、SSE、导出包内纯增量 seam
与构造重载）全部在案**。未触发「非预期签名 / 事件 / 配置 / 坐标变化 → 升级代码停点」。

## 四、偏离与观察（不含静默项）

| # | 事项 | 性质 | 处理 |
|---|---|---|---|
| 1 | [12] §2 旧记 55/59，实核 56/60 | 既有文档计数滞后（it35 rpc 入表未抬），非 0.6.0 增量 | 本批已在 12 §2 加四测修正注；不改任何代码 |
| 2 | `release.md` 仅改两处 78/77 POM 计数 | 其余 0.5.0 化按 it37 先例留收尾批 | 已记入 iteration-39 锚点表 |
| 3 | consumer-sample 发布腿注释 / README 发布件行仍 0.5.0 | Publish 前不能重指（发布腿要验 0.5.0 真实发布件） | 收尾批 `0.5.0 → 0.6.0` |
| 4 | notes 中相对链接在 GitHub Release 页不可点 | 沿用 0.5.0 采用稿风格（仓内阅读为准） | 评审可改；不改则保持 |
| 5 | UI 未改动 | 830 行 / sha256 与 it38 停 3 逐字相同 | 无需动作 |

## 订正（评审后，2026-11-24）

评审抓出审计**方向倒置**：`RpcHttpServer` 的 `synchronized` 是 it38 `27b2d88` **新增**
（v0.5.0 = 0 → 0.6.0 = 4：`:98`/`:111` 方法修饰符 + `:219`/`:253` 内部块），旧稿写成
「删除」。已订正：

- [audit §二](audit-stability-surface.txt)：方向改为**新增**；补「为什么该同步是对的」——
  ① `serveEvents` 迟到 open vs `close()`（锁内 `accepted = !closed`，锁外撤销，绝不发
  SSE 200；测试 `openReturningAfterServerCloseCannotLeakRegistration`）；② `close()` 锁内
  快照、锁外回调（测试 `closeCallbacksRunOutsideGlobalLockAndOneFailureCannotLeakOtherConnections`，
  回调内探测 `address()` 持锁即失败）；③ `start()` check-then-act 与 `close()`/`address()`
  同锁。`:219`/`:253` 为 private 方法体内部块，不在导出面，仅登记原因。
- [audit §九](audit-stability-surface.txt)：其余「相同」结论按**双向集合差**重跑——exports
  60/60 两侧差 0、非 harness 依赖 24/24 两侧差 0、base bundle 41/41 两侧差 0、
  `SessionEvent.java` 逐字节相同；唯一例外 = synchronized 新增 4。
- 根源：diff 本身方向正确（`-` 旧 / `+` 新）；audit 只抽了 `git diff | grep '^-'` 的删除侧，
  packet §三 又把 `+` 侧的修饰符转写到删除侧——**单方向抽取 + 人工转写**，非 diff 箭头。
- 翻转 / notes / 门禁不受影响，未重跑。pits 候选：发布审计 delta 方向机器判定 + 双向断言。

## 五、本停的边界

- **未 commit、未 push、未进 rc**；停 2（rc 排练）须等本 packet 放行。
- 未做 release 剖面（`-P release` / 真签名 / 上传）——属停 3 前对账门与用户侧。
- 未改任何产品主源语义；未修任何已知缺陷（发布站纪律）。
- 本机契约测试不外推 CI / 浏览器 / 生产 serve；rc 五腿与真 deploy 是后续门的证据面。
