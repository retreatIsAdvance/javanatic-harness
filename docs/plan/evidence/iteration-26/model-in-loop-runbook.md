# it26 验收 — 模型在环真跑 runbook（2026-10-13）

覆盖两条验收（iteration-26.md 验收 ①②）：

1. **可移植到独立项目**：独立夹具（无 JH 源码）放技能 → 真模型跑装载闭环，观察
   `skill/catalog` 轮首装载 + `skill(deploy)` 装载工具出账。
2. **ask 档技能内命令仍弹审批、sandbox 仍拦**：技能正文指示的 shell 命令不豁免审批，
   沙箱授予面不变。

## 前置

- 镜像：`dist/jh/target/jlink-image/bin/jh`（已自修正批树重建，2026-10-13；
  如需重建：`mvn -B -q -pl dist/jh -am package`）。
- key：在你的环境注入 `DEEPSEEK_API_KEY`（勿贴入任何对话/文件）。
- 夹具：`/tmp/it26-fixture`（`.harness/skills/deploy` + `.agents/skills/review` +
  非法名 `broken_name`）；`/tmp/it26-fixture-home`（`.harness/skills/notes`，用户根）。
- home 重定向：`JDK_JAVA_OPTIONS=-Duser.home=/tmp/it26-fixture-home`——用户根（rank
  400）指向夹具 home，审计与配置落 `/tmp/it26-fixture-home/.harness/`，不碰真实 home。

## Run A — 装载闭环 + 损坏跳过（STANDARD + auto，非交互）

```sh
export JDK_JAVA_OPTIONS=-Duser.home=/tmp/it26-fixture-home
export JH=/Users/abel_yang/study/deepseek/javanatic-harness/dist/jh/target/jlink-image/bin/jh
"$JH" --workspace=/tmp/it26-fixture "先列出你看到的可用技能目录（原文引用），然后装载 deploy 技能，只复述其正文要点，不要执行任何 shell 命令"
```

**观察点**（stdout + stderr 之外，以会话账为准）：

```sh
ls -t /tmp/it26-fixture-home/.harness/sessions | head -1
S=/tmp/it26-fixture-home/.harness/sessions/<上一步 id>/log.jsonl
grep -m1 '"type":"skill/catalog"' "$S"
grep -o '"name":"skill"' "$S" | wc -l
grep -m1 -o '"source":"project-harness"' "$S"
grep -n '"type":"skill/catalog"\|"type":"user/message"' "$S" | head -3
```

**预期**：

- 目录 = 3 条：deploy（project-harness）/ review（project-agents）/ notes（user-harness）；
  `broken_name` **不在目录**——stderr 应有 WARN 行
  `skill 'broken_name' at ... skipped: name not kebab-case`（损坏跳过实况）。
- `skill/catalog` 事件在首条 `user/message` 之前（轮首装载相位——F6 修复面实况）。
- 装载工具出账：`"name":"skill"` 的 `tool/call` + 对应 `tool/result` 含
  `[skill: deploy | source: project-harness]` 与正文。
- stdout 复述正文要点（部署三步）；退出码 0。

## Run B — 技能内命令仍弹审批（PRODUCTION + ask + budget）

```sh
"$JH" --workspace=/tmp/it26-fixture --policy=PRODUCTION --approval=ask --budget=100000 "装载 deploy 技能并执行其正文第 1 步（构建）"
```

**观察点与预期**：

- 终端依次出现审批行（技能装载工具本身 + 技能正文指示的 shell 命令各一次）——
  **技能路径不豁免 T2 审批**；按提示裁决。
- 启动行含 `sandbox: confining policy "workspace-write" is enforced by backend
  "seatbelt" (enforcement FULL)`——沙箱授予面在 PRODUCTION + ask 组合下不变。
- 会话账中该 shell 命令的 `tool/call` 带审批裁决结果出账；退出码 0（拒绝该命令时
  模型应给出替代说明，仍 0；若停轮提问则 exit 5，按提示 `--resume=<id> "答复"`）。
- 沙箱实际拦截面由 HostileInstructionsTest 回归 + 本行 boot 断言双覆盖（本跑不要求
  真突破沙箱的对抗用例）。

## 回贴清单（贴回对话，由我据实整理进证据档 stop3-model-in-loop.txt）

1. Run A：stats 行 + 上面五条 grep 的输出 + WARN 行。
2. Run B：审批行原文（两处）+ boot sandbox 行 + stats 行 + 退出码。
3. 任一步异常：原样贴 stderr（会话 id 与事件清单在 stderr）。

## 退出码对照

| 码 | 含义 |
|---|---|
| 0 | 任务完成 |
| 2 | 用法错误 / 缺 key |
| 3 | 任务失败（厂商错误 / 守卫超限；原因在 stderr） |
| 4 | 取消 |
| 5 | 模型提问停轮（stdout = 提问；`--resume=<id> "答复"` 续跑） |
