# 迭代 20 — 长任务资源边界（状态：进行中——四确认与三项裁决已于 2026-09-21 裁定）

模块：`core/agent-loop`（压缩触发与失败策略）+ `fs/fs` / `fs/local` / `fs/tool`（有界读写列举）+ `examples/headless`（统计出口与 `--verify` 治理摘要）+ 文档（04 §7/§15、03 §6、07 §6、12 §5/§6）

路线来源：README 0.2.0 表 it20「**长任务资源边界**：明确 token 预算口径；压缩触发与失败策略；工具输出进入上下文及落盘的限额」——验收「压缩后仍可完成长任务；大输出不撑爆内存/上下文；资源统计与估算边界可解释，不宣称无法保证的费用硬上限」。

## 四确认

- **内容**：
  1. **压缩触发口径修正与失败策略**：`CompactionPlugin.lastInputTokens` 由「全日志 inputTokens 的 **max()**（高位水位）」改为「**末次** assistant 消息的 inputTokens」——与 03 §6「触发用末次 inputTokens 实数」及 `CompactionService.shouldCompact` javadoc 的既有口径对齐（现状是「文档对、代码错」）；压力路径 `keepFrom <= 0`（tail 覆盖全部 surface）由 **throw IllegalStateException（杀轮，FailureKind.UNKNOWN）** 改为**跳过 + WARN 日志**（返回 null），真空由请求侧 OVERFLOW 显形（溢出恢复路径不变：Kind.OVERFLOW → `compactNow` 一次 → 失败仍 `RequestErrorDecision`/重抛）。
  2. **fs 有界化**：`fs_read` 有界读取（默认 256 KiB，与 shell-bash-local `maxOutputBytes` 对称），截断内容以 ` (output truncated)` 结尾（与 shell-tool 同标记文本，模型面可见，`isError` 不受影响）；`fs_edit` 对超限文件 fail loud（`IllegalArgumentException`，消息含实际大小与上限——edit 需整文件读+写+返回，超限即拒）；`fs_list` 条目上限 1000，截断时尾行 `… (list truncated)`；两个配额键（`maxReadBytes` / `maxListEntries`）挂 **fs-local 行配置**。
  3. **资源统计可见**：`--verify` 成功路径按 07 §6 承诺打印治理摘要到 **stdout**（composition 行数 / approval 模式 / audit 耐久 / stop limits——均来自实现自述）；REPL 轮末一行统计（`turn/end` 时：steps / tokens in-out / 耗时）；one-shot **成功路径** stderr 一行同形统计（stdout 契约不动：成功=答案文本）。
  4. **预算口径文档化**：04 落定区新增明确口径——预算 = 全日志（含 resume 前轮）`AssistantMessageEvent.usage().outputTokens` 累计，**只计输出**（输入跨压缩/重放会重复计，不可比）；超限经 GuardReject → `turn/end(Error)` → exit 3（kind `UNKNOWN`，12 §6 已裁「不设预算专属码」）；PRODUCTION 档要求非零。仅落文档与统计展示，不动计算逻辑。
- **目标**：真实阈值下压缩后长任务可继续完成（不再每 step top 复发压缩、不再因「无可压缩区间」杀轮）；工具输出进入上下文与内存的限额可验证；资源统计与估算边界在三个出口（verify / REPL / one-shot）可见且措辞不夸大。
- **为什么**：
  - ① it15 首步核对已记录 max() 高位水位（[iteration-15.md](iteration-15.md)「一旦跨阈，之后**每个 step top 都会复发压缩**」），当时的测试以 `retainTokens=1` 迂回；`shouldCompact` javadoc（「末次模型请求」）与 03 §6 均已按「末次」措辞——实现是唯一偏离者。复发压缩在真实阈值下会连续消耗摘要调用；`keepFrom<=0` 抛错则直接把可挽救的轮判死（UNKNOWN）。
  - ② `LocalFs.read` = `Files.readString` 整文件入内存/入上下文；`edit` 读整文件并**返回整文件**；`list` 无界——README 验收「大输出不撑爆内存/上下文」当前无实现支撑；bash 已有 256 KiB 先例与标记文本，fs 缺同构约束。
  - ③ 07 §6 的治理摘要（`stop: max-turns=… max-steps=… budget=…`）是文档承诺但未实现（verify 只 `LOG`）；REPL/one-shot 全程无 token 统计——「资源统计与估算边界可解释」无从谈起。
  - ④ 预算口径（跨 resume、只计输出、无专属 FailureKind）散落 04/12，无一处完整陈述；README 要求「明确 token 预算口径」。
- **不做**：
  - 不引入 tokenizer、不改 chars/2.5 估价口径（维持诚实估算，文档标注）；不做费用货币换算、不宣称可保证的费用硬上限
  - 不改 stdout 契约（成功=答案；统计只进 stderr / REPL 面板）；不新增退出码 / `FailureKind` / 事件类型；不改信封布局
  - 不做「压缩后仍溢出」的二次自动恢复（维持每轮一次，it10/it14 已定）；不改摘要重试数、保留预算默认值
  - fs 不新增 search/grep 工具；不给 fs-tool 加配置行（`fs-tool` 维持无 config，列表上限是**工具面输出策略**常量）；不删/不重命名既有 `FsService` 方法（`list` 返回类型改为携带截断标志的记录——0.2.0 窗口内破坏面变更，随 release notes；见设计增量）
  - 不动 sandbox / 审批 / 取消面；不为统计做持久化事件（统计自既有事实派生）

### 裁决记录（2026-09-21）

草案成立（用户「确认」）。三裁决点：

1. **触发口径** = 末次 LLM 响应的 inputTokens（非 max 水位）；压力路径 nothing-to-compact → **跳过 + WARN**（不杀轮；真实失败由请求侧 OVERFLOW 显形）。
2. **fs 阈值** = 读 256 KiB（与 bash 对称，标记 ` (output truncated)`）；list 1000 条（尾行 `… (list truncated)`）。
3. **统计出口** = REPL 轮末一行 + one-shot **成功路径** stderr 一行（stdout 契约不动）。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：
  - `FsService.Listing`（record：`entries` + `truncated`）——列表截断以结构位承载（文本载体在文内、结构载体以标志位；同 `ShellResult.outputTruncated` 先例）
  - 配置键：`fs-local.maxReadBytes`（默认 262144）/ `fs-local.maxListEntries`（默认 1000）
  - 04 新规格小节「压缩与溢出恢复」（补 §7 伪码漂移：现状伪码无压缩步骤与 request-error catch）+ 预算口径落定
  - 测试：`CompactionTest` 两用例（末次口径回归：跨阈压缩后不再复发；nothing-to-compact 跳过不杀轮）、`LocalFsTest`/`FsToolEndToEndTest` 有界化用例、`StreamRendererTest` 轮末行、`HeadlessOneShotResultTest` 成功 stderr 统计行、`HeadlessVerifyTest` 治理摘要
- **MODIFIED**：
  - `CompactionPlugin.lastInputTokens`（max → 末次）+ `compact` 压力路径跳过语义；`CompactionService` javadoc（`@return` null 语义、`@throws` 收窄）
  - `FsService.read` javadoc = **有界读取契约**（配额 + 标记；实现方义务）；`list` 返回 `Listing`
  - `LocalFs`（构造增配额参数，带默认；read 流式有界；edit 超限 fail loud；list 截断）+ `FsLocalPlugin`（键解析）
  - `FsToolPlugin`（list 尾行标记）；`HeadlessMain`（verify 摘要；one-shot 成功 stderr 统计行）；`StreamRenderer`（轮末统计行）
  - 文档：04 §7 伪码 + 新规格节 + §15 落定；03 §6（compaction 段：末次口径点明 + 「错误串匹配」措辞订正为 it14 的 Kind 分类 + nothing-to-compact 语义）；07 §6（输出样例对齐实现）；12 §5 配置表（fs-local 两键）+ §6（stderr 面补「运行统计」）
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `core/agent-loop/.../CompactionPlugin.java:287-295` | `lastInputTokens` max() → 反向取末条带 usage 的 assistant | |
| `core/agent-loop/.../CompactionPlugin.java:187-196` | 压力路径 `keepFrom<=0` throw → 跳过 + WARN（返回 null）；类头 javadoc 同步 | |
| `core/agent-loop/.../CompactionService.java:16-40` | `shouldCompact`/`compact` javadoc（null 语义、`@throws` 收窄） | |
| `core/agent-loop/.../AgentLoopImpl.java:356-359` | 调用点注释（null=跳过） | |
| `core/agent-loop/src/test/.../CompactionTest.java` | +2 用例（末次回归 / 跳过不杀轮）；Rig 脚本支持逐次 usage | |
| `docs/design/04-agent-loop.md` §7 伪码 + 新规格节 + §15 | 压缩与溢出恢复规格（修伪码漂移）+ 预算口径 | |
| `fs/fs/.../FsService.java:10-40`（含 `docs/design/05-capability-seam.md` §4 接口摘录同步） | `read` javadoc 有界契约 + `Listing` 记录 + `list` 返回类型 | |
| `fs/local/.../LocalFs.java:20-30 / :65-68 / :79-91 / :98-107` | 配额字段；read 流式有界+标记；edit 超限 fail loud；list 截断 | |
| `fs/local/.../FsLocalPlugin.java:32-40` | 键解析 `maxReadBytes` / `maxListEntries` | |
| `fs/tool/.../FsToolPlugin.java:105-112` | list 渲染截断尾行 | |
| `fs/local/src/test/.../LocalFsTest.java`、`FsLocalConfigTest.java`、`fs/tool/src/test/.../FsToolEndToEndTest.java` | 有界化用例（截断/多字节边界/超限 fail loud/配置键） | |
| `examples/headless/.../HeadlessMain.java:355-358` | verify 治理摘要（stdout） | |
| `examples/headless/.../HeadlessMain.java:438-455` | one-shot 成功 stderr 统计行 | |
| `examples/headless/.../StreamRenderer.java:104-117` | 轮末统计行（含 Usage 累积） | |
| `examples/headless/src/test/.../{HeadlessVerifyTest,HeadlessOneShotResultTest,StreamRendererTest}.java` | 三出口断言 | |
| `docs/design/03-session-event-sourcing.md:470`、`07-profile-bundle.md:187-204`、`12-api-stability.md:90-102/:127` | 口径与样例同步 | |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a 压缩触发与失败策略**：`CompactionPlugin`/`CompactionService`/`AgentLoopImpl` 调用点 + 两用例 + 04 规格节 | 锚点 1–6 | 编辑与取证（聚焦 + 突变 + 还原复绿）完成 → **已放行（提交 380c109）** |
| **S-b fs 有界化**：`FsService`（承载契约——接口返回型变更）+ 三个实现/消费类 + 用例 | 锚点 7–11 | 编辑与取证（聚焦 + 突变 + 还原复绿）完成 → **已放行（提交 5ff3480）** |
| **S-c 统计出口与文档**：verify / REPL / one-shot 三面 + 07/12 文档 | 锚点 12–16 | 编辑与取证（聚焦 + MUT-1/2/3 + 全量复绿 + 真跑）完成 → **待放行** |

## 验收（证据 = 实际执行的命令与结果）

- [ ] ① 压缩：`mvn -B -q -pl core/agent-loop -am test` 绿；新用例覆盖「跨阈压缩后末次 input 回落 → 不再复发压缩（compaction/start 恰 1 次）」与「nothing-to-compact → 跳过 + WARN，turn Completed」
- [ ] ② fs：`mvn -B -q -pl fs/fs,fs/local,fs/tool -am test` 绿；用例覆盖读截断标记（含多字节边界不留半字符）、edit 超限 fail loud（error result，消息含大小）、list 截断尾行、配置键解析
- [ ] ③ 统计：`mvn -B -q -pl examples/headless -am test` 绿；verify 摘要进 stdout（`stop: max-turns=…` 形状）、one-shot 成功 stderr 含统计行且 stdout 逐字节不回归、REPL 轮末行（StreamRendererTest）
- [ ] ④ 突变检查：`lastInputTokens` 回退 max() → ①回归用例必红；跳过改回 throw → ②用例必红；删 fs 读上限 → 截断用例必红；删统计行 → ③用例必红（各自恰红、还原复绿）
- [ ] ⑤ 全量 `mvn -B -q package` 绿
- [ ] ⑥ 真跑：`--verify` 实测输出治理摘要（jlink 或 java -jar）；一条 one-shot 小任务观测 stderr 统计行与 stdout 纯净
- [ ] ⑦ 文档同步：04（新规格节 + 伪码 + 预算口径）、03 §6、07 §6、12 §5/§6

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
