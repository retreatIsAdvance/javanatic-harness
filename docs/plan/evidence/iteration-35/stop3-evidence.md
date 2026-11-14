# it35 停 3 真机验收证据档（2026-11-14）

必验清单六项逐项取证。状态：item 1–3 已取证、item 5（收口批）已闭环（S5 收口证据：verify-stop full 层 EXIT=0 + 183 XML = 1456/0/0/31 + keyless --verify 34/41/0 + consumer 候选腿失败项 0）、item 6（用户检视实录）已闭环（2026-11-14 用户完整轮）；item 4（CI）随 push 批。

## S1 jlink 构建与镜像面（item 1 前置）

- 构建：`mvn -B -q -pl dist/jh -am package` BUILD SUCCESS（2026-11-14 实录，停 3-S1）。
- 镜像 `--list-modules` = **69 模块**：基线 67 + moditect 新增两行恰两模块（`io.javanatic.harness.workbench.rpc` + `jdk.httpserver`）。四确认「67→68」为算术滑点，本停实录订正为 67→69，已同步五处：`dist/jh/pom.xml`、`iteration-35.md`（T3 行 + 裁决记录段）、`stop1-seam-design.md`、`stop2-serve-design.md`、`stop2-review-packet.md`。
- 关键模块在场：`io.javanatic.harness.workbench.rpc@0.5.0-SNAPSHOT`、`jdk.httpserver@25.0.4.1`、`java.logging`、`java.net.http`。
- `bin/` = java / jh / jwebserver / keytool；`jwebserver -h` 打印用法退出 0——jdk.httpserver 回镜像回归（it16 出链史回补）。

## S2 我跑面（真机，serve = jlink 镜像，无 API key）

- serve 横幅（/tmp/w3-s3-serve.log）：`JSON-RPC server 127.0.0.1:8787（只读工作台面；Ctrl-C 退出）`——keyless 可跑（dispatch 先于 key 检查的行为学证明）。
- 三方法（item 1，jlink --serve curl）：
  - `workbench/sessionsList` max=3 → 3 条摘要 + `total=82`（截断语义：size < total）；max 缺省 20。
  - `workbench/sessionViews` → 六组件全在场：header / messages / timeline / tools / turns / usage。
  - `workbench/sessionTail` 游标矩阵：`lastSeq=-1` → 1593 事件 / `nextSeq=1592`；`lastSeq=1592` → 0 增量 / `nextSeq=1592`（无增量回传）；`lastSeq=999999` → -32602（游标超前 fail loud）。
- B29 非回环拒绝（item 3）：LAN IP `http://10.0.33.99:8787/` → curl EXIT=7（connection refused）；`http://[::1]:8787/` → EXIT=7；`127.0.0.1` 与 `localhost` 正常。
- 错误码面：-32601 method not found（id 回显）；-32001 SESSION_NOT_FOUND（`data.session` 回显）；GET / → 405。
- SIGINT 收口（我跑面轮）：`kill -INT 95466` → serve 后台任务 exit 0、lsof 端口 0 行释放。会话观察；用户检视轮为独立实录（见 S3）。

## S3 用户检视实录（item 6）——已闭环

- 裁决链 = 用户执行「jlink 起 serve → curl 三方法 → SIGINT 收口」。首轮 curl 对打的是我跑面后台 serve（用户澄清「我并没有跑 serve」），已停后台 serve、释放 8787 后用户重跑完整轮，本段为完整轮实录。
- 用户完整轮原文：[stop3-user-round.txt](stop3-user-round.txt)（curl 终端实录，2026-11-14；入档时按门禁剥除行尾空白 1 处，JSON 载荷与命令原文逐字节未动）。
- 三方法核实（解析自原文，与 S2 我跑面数值逐项吻合）：
  - `sessionsList` `{max:3}` → 3 条摘要 + `total: 82`；首条 `headless-1794139537162-5124`（`eventCount: 1593`、`cwd: /Users/abel_yang/study/deepseek/javanatic-harness`、`busy: false`）+ 两条 child-del 子会话（379 / 352 事件）。
  - `sessionViews` → 六组件全在场：header（`createdAt` / `id` / `manifest` / `parentSession` / `seedLength` / `version`）、`turns` 1 轮、`tools` 5 条、`usage`（`perTurn` / `total`）、`timeline` 1593 条、`messages` 14 条。
  - `sessionTail` `{lastSeq: -1}` → `events` 1593 条（seq 0..1592 连续）、`nextSeq: 1592`。
- SIGINT 收口：serve 启动与 Ctrl-C 在另一终端执行未随实录；收口证据双面——用户贴回 `lsof -nP -iTCP:8787 -sTCP:LISTEN` **空输出**（端口已释放），我复核同刻实况（`lsof` 8787 零监听、`pgrep 'jh --serve'` 零进程）。serve 为用户按 runbook 所起所停：我停止后台 serve 后 8787 无其他监听者，三条 curl 命中即用户所起之 serve；收口后零残留。

## S4 现场探针补录（2026-11-14，对我跑面后台 serve 复证落盘）

[stop3-my-round-probes.txt](stop3-my-round-probes.txt)：B29a LAN IP EXIT=7 / B29b `[::1]` EXIT=7 / loopback GET / → 405 / -32601（id 回显）/ -32001（`data.session` 回显）/ -32602（lastSeq 超前）。文件首行含探针时点进程状态（PID 96082 LISTEN）。

## S5 收口批（item 5 必验；2026-11-14）

原始抓取日志入档时按门禁剥除行尾空白（11-package.txt 等），载荷与命令原文逐字节未动。

- verify-stop **full 层 EXIT=0**：[stop3-closeout/summary.txt](stop3-closeout/summary.txt)（layer=full、head=`d6f3fd0`、`changed_files=[]`、11 步全 exit 0，2026-11-14 09:10:59Z–09:15:06Z；步骤实录 01–11 号日志同目录）。
- 门禁测试统计（surefire XML 求和；mtime 窗口 = full 层 package 跨度 2026-11-14 17:11–17:16 本地）：**183 XML = 1456 tests / 0 failures / 0 errors / 31 skipped，全绿**。全盘 185 XML 减 2 残留旧 XML——AskVisibilityProbe.xml（Nov 6，类已不存在）与 IZProbeTest.xml（Oct 13，it34 停 3 复审点名）——残留 ≠ 执行数，窗口外排除并点名。
- keyless `--verify` 命令 EXIT=0：[12-verify-keyless.txt](stop3-closeout/12-verify-keyless.txt)——`composition: 34 rows, 41 discovered, 0 unreferenced`（停 2 基线不变——零 bundle 行、零新配置键的预期面）。
- consumer 候选腿命令 EXIT=0：[13-consumer-candidate.txt](stop3-closeout/13-consumer-candidate.txt)——候选 7 项 ok（工作树 install → 隔离仓 / 示例编译 / 运行类路径 / 治理自证运行 / 自证通过 / 自有工具可见 / 自有工具可执行）+ 四负例全按预期拒绝（坏版本 / 去显式版本 / 坏治理配置 / 生产档撞 AUTO 审批）；汇总**失败项：0**。
- 同步面 grep 零残留（T2 改判 + 其余）：「67→68」仅存订正登记文本；「不做 Web UI」仅存决策记录 0011 与 T2 裁决行叙述，设计文档零残留。
