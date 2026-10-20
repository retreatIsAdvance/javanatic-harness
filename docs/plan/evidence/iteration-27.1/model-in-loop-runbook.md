# it27.1 验收 — 模型在环真跑 runbook（2026-10-20）

覆盖唯一未勾验收项（iteration-27.1.md「模型在环 runbook」）。key 由用户环境注入
（~/.zshrc → interactive zsh；未贴入任何对话/文件）。

## 前置

- 镜像：dist/jh/target/jlink-image/bin/jh（自 4722928 树重建，EXIT=0）。
- 夹具：`/tmp/it271-runbook/servers`（FixtureServer + FixtureHttpServer，javac 直编
  自两模块 test resources，零 JH 源码依赖）；HTTP 夹具 pid 9129、端口 59585、
  tools=echo,calc（curl ping 探针 200）。
- profile：`~/.harness/profiles/mcp-runbook/profile.yml`（装在重定向 home——
  `--workspace` 语义下 profile 查找随 user.home，首跑 EXIT=2 即此，已修正）。
  mcp-stdio servers=[{id:fixture, java FixtureServer}]；mcp-http servers=[{id:web,
  url http://127.0.0.1:59585/mcp}]。
- home 重定向：`JDK_JAVA_OPTIONS=-Duser.home=/tmp/it271-runbook/home`（审计/配置
  不碰真实 home）。

## Run A — 双 transport 装载与调用闭环（STANDARD + auto，非交互）

```sh
jh --profile=mcp-runbook --workspace=/tmp/it271-runbook/ws \
  "分别调用 mcp__fixture__echo 与 mcp__web__echo 两个工具，message 参数都填
   hello-from-jh；然后把两次返回原文汇总复述"
```

EXIT=0；stdout = 模型汇总（fixture echo 无参数 schema → 返回 `echo: `，web echo
返回 `echo: hello-from-jh`；模型如实报告了 schema 差异）；stats turn=1 steps=2
tokens_in=3493 tokens_out=174。

## 会话账（headless-1792455986778-bc9b/log.jsonl）

- `mcp/catalog` ×1（双 transport 工具同快照；`serverId":"fixture"` 与
  `"serverId":"web"` 均在账）；
- `tool/call` ×2 + `tool/result` ×2：`mcp__fixture__echo`（stdio 子进程）与
  `mcp__web__echo`（HTTP）各一对；
- 事件序：turn/start → user/message → step/start → mcp/catalog → llm/request →
  tool/call → tool/result → step/end → … → turn/end；
- **子进程回收**：jh 退出后 `pgrep -fl "it271-runbook/servers FixtureServer"` = 0
  （PROCESS_REAPED 在真模型在环下复现）。

## 环境遗留清理

- 本 runbook 的 HTTP 夹具（pid 9129）取证后 kill；stdio 夹具已随会话回收。
- 另见：上一位协作会话停 3 真跑遗留的两个 FixtureHttpServer（`it271-loop2` 域，
  pid 6892/595856892）仍在运行——非本 runbook 所有，处置留给用户/该会话。
