# it31 停 3 模型在环 runbook（用户真 key 执行）+ 双臂收益验收任务集

## A. 模型在环（先跑——验证委派可用性，含问答回路）

前置：`mvn -B -q -pl dist/jh -am package -DskipTests`；`export DEEPSEEK_API_KEY=...`；
行配置把 delegation-local 指向真 provider（profile overlay：
`delegation-local: {provider: deepseek, model: <真模型名>}`）。

场景 1（基本委派 + 观察）：
```sh
bin/jh --workspace=<ws> "用 delegate 把「检索 <repo> 三个模块的 TODO 并汇总」派给子代
（cwd=<ws>，预算 5000），然后 delegate_list 等终局并把子代结果转述给我。"
```
断言面：模型走 delegate → delegate_list 轮询 → 转述 succeeded(结果)；父会话上下文里
**没有子代的中间轮**（核心价值主张的直接观测）。

场景 2（问答回路全链）：
```sh
bin/jh --workspace=<ws> "用 delegate 派子代做 X；若子代提问（needs_parent_input），
你把问题原样转给我，用 delegate_answer 把我的答复发给它，等终局后汇报。"
```
断言面：子问逐字上达 → 父答经 delegate_answer → resume → 终局；全程单一人闸（用户），
无嵌套审批。

## B. 双臂收益验收（四确认裁定③全套）

任务集（成员按准入标准可换，≤10 × 两臂）：
- 并行族（拟 3 任务）：多文件检索汇总类 ×2、并行构建排错类 ×1（各 ≥3 独立可验子任务，
  机械化断言 = 退出码 / grep 关键行）
- 串行族（拟 2 任务）：单文件深改 ×1、读后改链 ×1（真依赖链）

跑法：同任务文本同工具面，单 Agent 臂（不委派直接做）vs 委派臂（要求用 delegate）；
基线臂先行验证准入；每任务记录三量纲（总 token / 墙钟 / 父上下文占用）+ 成败。
判读：并行族中位数父上下文降 ≥50% 且总 token ≤2×；串行族任一量纲不劣超 1.5×；
墙钟只入档不判赢；成功率委派臂不低于单臂。

记录：终端实录（或要点）贴回，由 AI 整理成 stop3-dual-arm.txt + stop3-model-in-loop.txt。
