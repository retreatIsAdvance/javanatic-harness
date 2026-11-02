# it29 停 3 验收 — 模型在环真跑 runbook（LSP 三工具）

覆盖验收（iteration-29.md 验收「固定任务经真实模型 / LS 完成诊断 → 定位」）：
真实 deepseek 模型在 jlink 镜像 + 启用档（`lsp-jdtls` replace 启用、钉定 1.63.0
真机）下，自主调用 `lsp_diagnostics` / `lsp_definition` / `lsp_references`
完成诊断与定位，观察结果文本逐字措辞纪律、锚首行与审批 / 治理面。

## 前置

- 镜像：`dist/jh/target/jlink-image/bin/jh`（S4 已构建；如需重建：
  `mvn -B -pl dist/jh -am package -DskipTests`）。
- key：在你的交互式环境注入 `DEEPSEEK_API_KEY`（**勿贴入任何对话 / 文件**），
  建议 `zsh -ic` 内执行使 key 只存在于该 shell 环境。
- 启用档：`/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/profiles/enabled.yml`
  （B27 八键 config 指向停 0 `server-1.63.0` / `config_mac_arm` 与运行目录 project /
  index / home；home 双 settings 指向暖 .m2）。
- 隔离：`JDK_JAVA_OPTIONS=-Duser.home=…/jh/userhome`，`--workspace=…/jh/ws`，
  `--approval=auto`（三工具恒 `approval-required` 声明，AUTO 档放行——沿 it28
  web_fetch/web_search 先例）。
- 夹具：`…/jh/project`（停 0 夹具复制：App.java 清洁版 `String label = "ok"`，
  未使用导入警告 268435844；Calc.java 定义 `twice`；App.java 第 5 行第 15 列
  （0-based）= `Calc` 引用位，其定义 = Calc.java@2:19）。

## Run A — lsp_diagnostics（warning-only 夹具）

```sh
export JDK_JAVA_OPTIONS=-Duser.home=/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/userhome
export JH=/Users/abel_yang/study/deepseek/javanatic-harness/dist/jh/target/jlink-image/bin/jh
"$JH" --workspace=/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/ws \
  --profile=/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/profiles/enabled.yml \
  --approval=auto \
  "用 lsp_diagnostics 工具查看 /Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/project/src/main/java/probe/App.java 的诊断，然后把工具返回的文本逐字复述出来。"
```

**观察点**（stdout 之外，以会话账为准）：

```sh
ls -t /Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/userhome/.harness/sessions | head -1
S=/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/userhome/.harness/sessions/<上一步 id>/log.jsonl
grep -m1 '"type":"tool/call"' "$S"
grep -m1 '"type":"tool/result"' "$S"
grep -m1 '"name":"lsp_diagnostics"' "$S"
```

**预期**：

- 退出码 0；`tool/call` 的 name = `lsp_diagnostics`，参数含 App.java 的 file:// URI。
- `tool/result` 内容逐字以「拉取成功（hash …，项目代 …，等待窗 …s）；诊断 1 条：」
  开头，条目行 `src/main/java/probe/App.java:<行>:<列> [WARNING] The import module
  java.base is never used (268435844)`——**不得出现「项目无错误」等改写措辞**
  （措辞纪律：非空批次走「诊断 n 条」模板，空批次才走「拉取时点无诊断」）。
- stdout 中模型复述与工具文本一致（逐字，不近义改写）。

## Run B — lsp_definition + lsp_references（真实定位）

```sh
"$JH" --workspace=/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/ws \
  --profile=/Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/profiles/enabled.yml \
  --approval=auto \
  "用 lsp_definition 查 /Users/abel_yang/study/deepseek/jh-it29-stop3-20261031/jh/project/src/main/java/probe/App.java 第 5 行第 15 列（0-based）符号的定义位置，再用 lsp_references 查同一位置的所有引用；把两个工具返回的位置列表逐字列出来。"
```

**观察点**：

```sh
grep -o '"name":"lsp_definition"' "$S" | wc -l
grep -o '"name":"lsp_references"' "$S" | wc -l
```

**预期**：

- 退出码 0；两工具各出账一次（`tool/call` + `tool/result` 成对）。
- definition 结果：位置 1 条，`src/main/java/probe/Calc.java:2:19`（Calc 类定义）。
- references 结果：位置 2 条，`Calc.java:2:19`（声明，带 includeDeclaration）+
  `App.java:5:15`（调用位）。
- 结果文本以「拉取成功（hash …）；位置 n 条：」开头，锚 hash 首行恒在。

## §四 治理面与凭据结构检查（两轮跑完后执行）

```sh
grep -c 'Authorization' "$S" || true
grep -c 'x-api-key' "$S" || true
grep -o '"approval":[^,]*' "$S" | sort | uniq -c
```

**预期**：`Authorization` / `x-api-key` 计数均为 0（日志只落账结构与结果，
不落 key 材料）；`approval` 字段记录 AUTO 放行面。只回贴计数与结构行，
**任何 key 值禁入对话**。

## 回贴清单（评审用）

1. Run A / Run B 各自的：退出码、`stats:` 行（turn/steps/tokens/elapsed）、
   session id、stdout 中模型最终输出逐字。
2. `tool/call` + `tool/result` 逐字（lsp_diagnostics / lsp_definition /
   lsp_references 各一对）。
3. §四 三个 grep 的输出（计数行）。
4. 实况偏离（如有）：如实记录，不改夹具、不改镜像凑绿。
