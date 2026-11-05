# it30 停 3 模型在环 runbook（用户真 key 执行）

目的：证明模型经四工具正确编排后台任务，且读结果三态措辞被模型如实转述（不把「暂无更多」说成「无输出」、不把截断说成「读尽」）。

## 前置

- jlink 镜像：`mvn -B -q -pl dist/jh -am package -DskipTests`
- key：`export DEEPSEEK_API_KEY=sk-...`
- 工作区：已存在目录（如 `/tmp/jh-mil`）

## 场景（一次会话内依序）

```sh
dist/jh/target/jlink-image/bin/jh --workspace=/tmp/jh-mil "帮我起一个后台任务跑
'mvn -v && sleep 20'（cwd 用 /tmp/jh-mil，超时 120 秒），然后用 job_status 等它，
用 job_read 从头读输出并告诉我读到了什么、任务状态如何；最后 job_cancel 收尾。"
```

## 断言面（模型回复须满足）

1. **编排**：job_start → 携 id 轮询 job_status（waitSeconds）→ job_read（fromByte=0 起步）→ job_cancel
2. **三态措辞复述**：
   - 运行中读到末尾 → 模型应说「暂无更多输出 / 还在跑」，**不得**说「任务没有输出」
   - 终局后读尽 → 「已读尽」；若被截断 → 「捕获已达上限」而非「输出结束」
   - mvn -v 的版本行应出现在转述里（遗物/实时输出等价）
3. **取消终局**：cancel 后复述「已取消」，不再宣称任务完成
4. 会话事件账含 job/snapshot ≥ 2 条（Running + 终局），tool/call + tool/result 齐备

## 记录

执行后把终端实录（或要点）贴回会话即可，由我整理成 `stop3-model-in-loop.txt` 入档（用户执行、AI 归档的 it29 S5 同分工）。
