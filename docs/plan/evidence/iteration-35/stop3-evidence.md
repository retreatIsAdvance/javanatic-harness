# it35 停 3 真机验收证据档（2026-11-14）

必验清单六项逐项取证。状态：item 1–3 已取证、item 4（CI）随收口批、item 5（收口批）随收口批、item 6（用户检视实录）三方法段已入档、SIGINT 段待补。

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

## S3 用户检视实录（item 6）

- **原文入档**：[stop3-user-round.txt](stop3-user-round.txt)（zsh 实录，2026-11-14 用户执行；入档时按门禁剥除行尾空白 4 处，JSON 载荷与命令原文逐字节未动）。
- 三方法核实（解析自原文，与 S2 我跑面数值逐项吻合）：
  - `sessionsList` `{max:3}` → 3 条摘要 + `total: 82`；首条 `headless-1794139537162-5124`（`eventCount: 1593`、`cwd: /Users/abel_yang/study/deepseek/javanatic-harness`、`busy: false`）+ 两条 child-del 子会话（379 / 352 事件）。
  - `sessionViews` → 六组件全在场：header（`createdAt` / `id` / `manifest` / `parentSession` / `seedLength` / `version`）、`turns` 1 轮、`tools` 5 条、`usage`（`perTurn` / `total`）、`timeline` 1593 条、`messages` 14 条。
  - `sessionTail` `{lastSeq: -1}` → `events` 1593 条（seq 0..1592 连续）、`nextSeq: 1592`。
- **SIGINT 收口段：待补**——实录附件仅含三方法 curl；serve 进程 96082 截至本档落笔仍在监听（lsof 一行 `TCP 127.0.0.1:8787 (LISTEN)`）。用户补 `kill -INT 96082` + 端口复查实录后闭环。

## S4 现场探针补录（2026-11-14，对用户轮在跑 serve 复证落盘）

[stop3-my-round-probes.txt](stop3-my-round-probes.txt)：B29a LAN IP EXIT=7 / B29b `[::1]` EXIT=7 / loopback GET / → 405 / -32601（id 回显）/ -32001（`data.session` 回显）/ -32602（lastSeq 超前）。文件首行含探针时点进程状态（PID 96082 LISTEN）。
