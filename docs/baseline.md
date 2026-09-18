# 真实任务基线（0.2.0 校准输入）

在固定快照上跑一组小型真实任务，用**退出码 + stdout 契约**（it17）做二值判读，记录成功率、耗时与 token 消耗——为 0.2.0「可靠单 Agent」的 27/30 成功率目标提供首轮校准输入（目标由后续轮次积累，首轮只建基线）。

**执行侧 = 维护者（用户）**：真跑产生计费与凭证使用；AI 会话只备料（本文件）——不执行真跑、不经手凭证、不代判任务达成。首轮完成后把记录表回收进本文件「首轮记录」节（或就近的轮次档案），供后续迭代对照。

## 1. 种子与环境

### 种子（钉提交，冻结）

```sh
# 在 javanatic-harness 仓库工作树执行；cf6a7e3 = it17 S1（任务结果契约）落地后的提交
git archive --format=tar -o ~/jh-baseline/seed.tar cf6a7e3

BASE=~/jh-baseline
mkdir -p $BASE
for i in 01 02 03 04 05 06 07 08 09 10; do
  mkdir -p $BASE/case$i && tar -xf $BASE/seed.tar -C $BASE/case$i
done
```

- 快照无 `.git`；判据用「文件包含性检查」而非 diff（见任务表）。
- 每个任务在自己的 case 目录里跑——任务间的文件改动互不污染。

### 镜像（跑基线的 jh）

在 javanatic-harness 工作树（非快照）构建含 it17 契约的镜像：

```sh
mvn -B -q -pl dist/jh -am package
JH="$PWD/dist/jh/target/jlink-image/bin/jh"
"$JH" --help        # 冒烟：应含「退出码」段
```

### 前置（宿主 shell）

```sh
export PATH="$HOME/Documents/apache-maven-3.8.8/bin:$PATH"   # 本机 mvn 不在 PATH
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export DEEPSEEK_API_KEY=sk-...                                # 用户自管；不进记录、不入库
```

### 预热（构建类任务推荐）

任务 04–08 会让 Agent 跑 maven。快照首次构建（全依赖链、无 target）**实测约 10 秒/目录**（本机，2026-09-18 备料演练），建议先在各 case 目录预热一次（5 个目录一分钟内跑完）：

```sh
for i in 04 05 06 07 08; do
  (cd $BASE/case$i && mvn -B -q -pl examples/headless -am -DskipTests package)
done
```

不预热也能跑：maven 增量编译，超时被 kill 后未丢进度，重试会接着编——但会拉低首轮成功率，且「重试」本身不在任务文本里（超时记未达成 + 备注原因属正常记录）。

### 已知边界

- shell 命令 60s 硬超时（组合期上限，不可由模型调大）。实测（本机备料演练）：冷启动预热 ~9s、模块 `test`（48 例）~4s、半热全量 `package` ~37s——任务文本里的模块级命令都在安全区内；唯一可能触顶的是全冷状态下的全量构建（如任务 09 若选择跑全量核对），触顶按「未达成 + 备注原因」记录（maven 增量不丢进度，重试通常更快）。
- 工作区 = case 目录（`--workspace`）；fs 围栏与沙箱授予面都钉在此目录。maven 只读本地仓库、写 case 内 `target/`，不触发围栏。
- 会话（JSONL）落 `~/.harness/sessions/<sessionId>/`，与工作区无关；`--resume` 靠会话 id。

## 2. 记录协议

每次运行必记（缺项即记录不完整）：

| 字段 | 口径 |
|---|---|
| 日期 | 运行当日（YYYY-MM-DD） |
| 模型 / 参数 | `deepseek-chat`（默认，不传 `--model`）；无显式采样参数（厂商默认；`llm/request` 事件的 `params` 可复核） |
| exit | 进程退出码（0 完成 / 3 失败 / 4 取消 / 2 用法缺 key；词表见 `jh --help`） |
| stdout 判据 | 对照任务表判据做包含性检查（✓/✗）；空 stdout 在成功路径是合法边界的，按判据写明 |
| 耗时 | 墙钟秒（`time` 或手工记） |
| tokens | input / output 合计（下方 jq 命令；一条任务多次运行时逐次记） |
| 费用 | 可选：按 DeepSeek 控制台当日账单核对填；不逐条强求 |
| 判定 | **达成 / 未达成**（二值）；未达成在备注写原因（判据 ✗ / exit≠0 / 超时 / 其他） |

判读链：`exit` 判定成败 → `stdout` 对照判据 → `stderr`（失败文案、模型遗言）定位原因。

### tokens 汇总命令（已验证）

```sh
SID=<stderr 里的 session id，形如 headless-1789...-fd95>
jq -s '[.[] | select(.type=="assistant/message")] |
  {calls: length, input: (map(.data.usage.input) | add + 0), output: (map(.data.usage.output) | add + 0)}' \
  ~/.harness/sessions/$SID/log.jsonl
```

resume 任务（10）记第二次运行时，把两次输出的差值作为本轮增量（或直接记录累计值并注明）。

## 3. 任务集（10 条任务 / 11 次运行）

覆盖：定位 ×3、修改+验证 ×3（含跨模块多步）、测试运行 ×1、重构 ×1、文档 ×1（双语）、resume 续跑 ×1（两段 = 两次运行）。每条 = 一条完整任务文本（直接复制）+ 判据（仅判读用，不进任务文本）。

### 01 定位 — firstLiveSeq（case01）

> 任务文本：`Session.firstLiveSeq() 定义在哪个模块的哪个类？它的返回值语义是什么（相对 seed）？`

判据：exit 0 且 stdout 提到 `core/session` 模块的 `Session.java`，且语义回答含「本进程首个 append 的 seq」=「seed 长度」之义。

### 02 定位 — 退出码 3 的映射（case02）

> 任务文本：`headless 一次性任务失败时，退出码 3 是由哪个函数映射的？它把哪些终局映射为 3？`

判据：exit 0 且 stdout 提到 `HeadlessMain` 的 `oneShotExitCode`，且覆盖 Error→3 与「无终局（含未知变体）→3」。

### 03 定位（多步） — PRODUCTION 违规类别（case03）

> 任务文本：`jh --verify --policy=PRODUCTION 会拒绝哪些非法组合？从代码里找出判据条目，至少列出 3 类。`

判据：exit 0 且 stdout 列出 ≥3 类且与 `Policy.java` 一致（可选自：AUTO 审批 / 非耐久持久化 / LoopGuard limits 为零 / budget 为零 / 缺 SandboxPolicyService / danger-full-access）。

### 04 修改+验证 — budget 正例（case04）

> 任务文本：`在 examples/headless 的 HeadlessOneShotResultTest 中新增一个用例：--budget 大于本次累计输出 tokens 时任务正常完成（exit 0 且 stdout 有答案的正例）。跑 mvn -B -q -pl examples/headless test 确认全绿。`

判据：exit 0；`HeadlessOneShotResultTest.java` 出现新增 @Test（原 7 例 → 8 例）；用户复跑模块测试绿。

### 05 修改+验证（多步跨模块） — 等值边界（case05）

> 任务文本：`在 core/agent-loop 的 BudgetGuardTest 中新增一个边界用例：累计输出恰好等于 maxBudgetTokens（不多不少）时放行。跑 mvn -B -q -pl core/agent-loop -am test 确认全绿。`

判据：exit 0；`BudgetGuardTest.java` 出现新增 @Test；用户复跑该模块测试绿。（实现实况：`spent > max` 拒、`==` 放行——用例应断言放行。）

### 06 修改+验证 — USAGE 补示例（case06）

> 任务文本：`--help 的示例段没有 --resume 用法示例；在 HeadlessMain 的 USAGE 里补一条简短示例（--resume 一次性续跑）。跑 mvn -B -q -pl examples/headless -am test 确认全绿。`

判据：exit 0；`HeadlessMain.java` 的 USAGE 文本出现 `--resume` 示例行；模块测试绿。

### 07 测试运行 — agent-loop 模块（case07）

> 任务文本：`跑 mvn -B -q -pl core/agent-loop test 并报告结果：通过数、失败数与结论。`

判据：exit 0；stdout 报告与真实一致（全绿，约 10 例）；用户复跑可复核。

### 08 重构 — finishOneShot 拆分（case08）

> 任务文本：`把 HeadlessMain.finishOneShot 里的失败诊断块（「失败前最后输出」与终局文案的打印）拆成独立的私有方法，行为与对外包可见性不变。跑 mvn -B -q -pl examples/headless test 确认全绿。`

判据：exit 0；`HeadlessMain.java` 出现新私有方法（如 `printFailureDiagnostics`）且 `finishOneShot` 仍在；模块测试 48 例全绿（含 HeadlessOneShotResultTest 7 例）。

### 09 文档 — README 计数核对（case09）

> 任务文本：`README.md 与 README.zh-CN.md 的构建段都写着旧计数（362 tests / 362 项测试）。核对当前实际测试总数，并把两个文件同步修正（说明你用什么方式得到的数字）。`

判据：exit 0；两个 README 中 `362` 消失且替换为核对值（本快照冻结值 = **379**）；备注记拿数方式。

### 10 resume 续跑 — 上下文延续（case10，两段 = 两次运行）

运行 1：

> 任务文本：`记住这个标记：BASELINE-MARKER-7391（不要写入任何文件）。然后用一句话概括 Session.firstLiveSeq() 的语义。`

运行 2（用运行 1 stderr 打印的 session id）：

> 任务文本：`我第一轮让你记住的标记是什么？只回答标记本身。`

判据：两次 exit 0；运行 2 的 stdout 含 `BASELINE-MARKER-7391`（上下文跨进程延续——新开会话答不出，落文件属违规路径，备注可记）。

## 4. 运行模板

```sh
BASE=~/jh-baseline
JH=<镜像路径>/bin/jh

# 一次性任务（04-09 同理，换 case 编号与任务文本）
"$JH" --workspace=$BASE/case01 "任务文本" >$BASE/case01.out 2>$BASE/case01.err
echo "exit=$?"

# resume 两段（10）
"$JH" --workspace=$BASE/case10 "记住这个标记：BASELINE-MARKER-7391（不要写入任何文件）。然后用一句话概括 Session.firstLiveSeq() 的语义。" \
  >$BASE/case10-run1.out 2>$BASE/case10-run1.err
grep 'session=' $BASE/case10-run1.err        # 取 session id
"$JH" --resume=<sessionId> --workspace=$BASE/case10 "我第一轮让你记住的标记是什么？只回答标记本身。" \
  >$BASE/case10-run2.out 2>$BASE/case10-run2.err
```

- `stdout`（判据对象）落 `.out`；会话 id、事件清单、失败诊断在 `.err`。
- 耗时：命令前加 `time`（zsh 内建），或手工记开始/结束。

## 5. 首轮记录（待填）

> 首轮由维护者执行；填完把本节替换为实际记录（或追加轮次小节），并在 `docs/plan/iteration-17.md` 勾对应验收项。

| # | 任务 | 运行 | 日期 | exit | stdout 判据 | 耗时 | in tok | out tok | 费用 | 判定 | 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 01 | 定位 firstLiveSeq | — | | | | | | | | | |
| 02 | 定位 退出码映射 | — | | | | | | | | | |
| 03 | 定位 PRODUCTION 违规类 | — | | | | | | | | | |
| 04 | 修改 budget 正例 | — | | | | | | | | | |
| 05 | 修改 等值边界 | — | | | | | | | | | |
| 06 | 修改 USAGE 示例 | — | | | | | | | | | |
| 07 | 测试运行 agent-loop | — | | | | | | | | | |
| 08 | 重构 finishOneShot | — | | | | | | | | | |
| 09 | 文档 README 计数 | — | | | | | | | | | |
| 10 | resume 上下文延续 | run1 | | | | | | | | | |
| 10 | resume 上下文延续 | run2 | | | | | | | | | |

汇总：达成 __ / 10 任务（运行 __ / 11）；总 tokens in/out：__ / __。

## 6. 维护

- 每个 0.2.0 子版本重跑一轮时换种子提交（快照冻结当版行为），本文件保留各轮记录小节。
- 任务集按 0.2.0 能力面演进增删（如 it18 取消 → 可加「中断后 resume 继续」任务）；改任务集须同步更新判据与 `docs/plan/iteration-17.md` 的挂账。
- 失败记录（未达成）优先于成功记录进入后续迭代的问题清单——基线的作用是暴露缺口，不是刷分。
