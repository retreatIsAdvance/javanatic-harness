# 迭代 19.1 — 两条挂账收口：不变式复核 turn 口径 + Append-sources wire 保真（状态：进行中——S-a 编辑与取证完成、随本地提交放行（未 push）；余 ④ 全量 package / ⑤ 文档同步）

模块：`core/session`（`SessionInvariants` + 夹具）+ `session/persistence-jsonl`（`CoreCodecs` 三处）+ 文档（03 §6 / it19 挂账行）

挂账来源：it19 设计偏离表两行（`SessionInvariants` turn 校验 0-based；`sourceEventSeqs` 仅 Replace 落盘）——用户 2026-09-21 指示「清理本地挂账的两条待办」。

## 四确认

- **内容**：
  1. **turn 口径修正**：`SessionInvariants.validate` 的 turn 期望改 1-based（`e.turn() == nextTurn + 1`，失败消息里的期望值同步）——对齐 03 §「turn 号 = TurnStart 计数」与 04「TurnStart 个数 + 1」，生产 `AgentLoopImpl` 与真实日志（`turn/start` `turn=1` 起）即此口径。夹具随动：`SessionInvariantsTest` 5 处 + `SessionRecoveryTest` 种子 5 处（及恢复事实的 turn/step 断言）。
  2. **Append 带 sources 的 wire 保真**：`CoreCodecs` 三处 surface 事件（`user/message` / `assistant/message` / `tool/result`）——写侧 `sourceEventSeqs` 非 null 即写（不再局限于 Replace 分支）；读侧按 key 存在即解析（不再以 Replace 判据决定是否读）；后两者读路径由硬编码 `Append, null` 改为读回。效果：it19 恢复事实的 provenance 由**进程内契约**升为**盘上事实**（重载后除 `toolUseId` 外还有 seq 引用可核）。
- **目标**：两条挂账清零——`validate` 对真实日志可用（现状必拒）；Append 携带来源的事件经 save/load 往返不丢 provenance，03 措辞与实现一致。
- **为什么**：① 复核器口径与生产不一致 = 对真实数据不可用；且因它只被测试夹具喂（未接 load/resume），夹具反被迁就成 0-based——「文档对、代码错、测试跟着错」的典型，越晚修越贵；② provenance 仅在 Replace 落盘时，Append（含 it19 恢复事实）重载丢来源，R1 与审计的「引用可核」在崩溃重载场景打折——正是 it19 记下的设计偏离。
- **不做**：
  - 不把 `validate` 接入 load/resume 路径（行为变更，另议）
  - 不为 `assistant/message` / `tool/result` 引入 Replace 语义（op 仍恒 Append）
  - 不改信封布局、事件类型集、codec 键名；不写兼容垫片（旧日志缺该键 → 读为 null，v0 口径）
  - **不改变 it19 已断言的行为**（新增 `sourceEventSeqs` 键属盘上字节新增；it19 断言集无「缺键」absence 面——已核）；不 push

### 裁决记录（2026-09-21）

草案成立。三项测试面要求 + 一处措辞精确化（均并入锚点/验收）：

1. **防传染**：turn 1-based / step 0-based 各随生产——javadoc 与设计增量点明「不对称是有意口径」，防后人把不对称当 bug 再修。
2. **往返测试矩阵**：三事件（`user/message`、`assistant/message`、`tool/result`）× 两态（带/不带 seqs）+ 一条**手写旧格式行（无键 → null）**读侧用例——把「v0 天然兼容」从论证变成断言。
3. **突变**：删 `CoreCodecs` 写分支 → 往返必红（延续突变惯例）。
4. **措辞**：「不动 it19 已放行行为」精确化为「**不改变 it19 已断言的行为**」（新增键字节许可；absence 面已核无）。

同意：收口标注（it19 设计偏离行引用本迭代号）+ 03 §6 权威化（一处事实一个家）；`validate` 不接 load/resume（行为变更另裁）。

## 设计增量（ADDED / MODIFIED / REMOVED）

- **ADDED**：无
- **MODIFIED**：03 §6 codec 说明（`sourceEventSeqs` 落盘口径：非 null 即写、读回对称、Append 亦然；**turn 1-based / step 0-based 不对称口径点明，勿当 bug 修**）；it19 设计偏离表第一行处理列标「已收口（it19.1）」；`CoreCodecs` 三处 surface codec；`SessionInvariants` turn 期望 + javadoc 口径注记；两处测试夹具
- **REMOVED**：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `core/session/.../SessionInvariants.java:45` | `e.turn() == nextTurn + 1` + 消息文本同步 + javadoc 点明「turn 1-based / step 0-based」不对称口径 | ✅ S-a |
| `core/session/src/test/.../SessionInvariantsTest.java`（:28/:31/:40/:58/:66） | 夹具改 1-based | ✅ S-a |
| `core/session/src/test/.../SessionRecoveryTest.java`（:39/:56/:70/:83/:97 + 断言区 :114-121） | 种子改 1-based；恢复事实 turn/step 断言随动 | ✅ S-a |
| `session/persistence-jsonl/.../CoreCodecs.java:73-99 / :100-106 / :130-139` | 三处 surface codec 的 sources 写/读对称 | ✅ S-a |
| `session/persistence-jsonl/src/test/.../JsonlPersistenceTest.java` | 往返矩阵用例（三事件 × 带/不带 seqs，逐元素相等）+ 手写旧格式行（无键 → null）读侧用例 | ✅ S-a |
| 文档：03 §6（口径权威化 + 两侧行为参照）/ it19 设计偏离表行（标收口 + 迭代号） | 同步 | |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| **S-a wire 保真**：`CoreCodecs` 三处（承载契约——盘上格式）+ 往返测试 | 锚点 4、5 | 编辑与取证（聚焦 + 突变 A/B + 复绿）完成 → **已放行（随本切片本地提交；未 push）** |

（① 为机械修正，不设停点；本切片单停点——纯机械 + 一处契约面。）

## 验收（证据 = 实际执行的命令与结果）

- [x] ① `SessionInvariantsTest` 与 `SessionRecoveryTest` 全绿（1-based 夹具），`mvn -B -q -pl core/session -am test`；javadoc 口径注记（turn 1-based / step 0-based 不对称）在案 —— **取证**：BUILD SUCCESS、`core/session` 模块汇总 **41 测试 / 0 失败 0 错误**（/tmp/it19.1-core-session-1.log，EXIT=0）；`SessionInvariants` javadoc 明写「turn 号 = TurnStart 计数（**1 起**）；step = turn 内序号（**0 起**）——两侧起点不对称是有意口径，勿当不一致修正」
- [x] ② `JsonlPersistenceTest` 往返矩阵：三事件（`user/message` / `assistant/message` / `tool/result`）× 两态（带/不带 seqs）save→load 逐元素保真；Replace + sources 旧行为不回归；**手写旧格式行（无键 → null）读侧用例**把 v0 兼容变断言 —— **取证**：BUILD SUCCESS、模块汇总 **22 测试 / 0 失败**（/tmp/it19.1-jsonl-1.log，EXIT=0）。新例 `surfaceSourceSeqsRoundTripInBothStates`（`"sourceEventSeqs"` 键出现恰 3 次 + `containsExactlyElementsOf` 逐元素保真）+ `oldLogWithoutSourceSeqsKeyReadsAsNull`（手写旧行无键 → null）；`fullHistoryRoundTripsThroughDisk`（Replace + sources 旧行为）不回归
- [x] ③ 突变检查：删 `CoreCodecs` 写分支 → 往返必红；turn 期望回退 0-based → `core/session` 必红（各自恰红、还原复绿）—— **取证**：MUT A（删 `CoreCodecs` tool/result 写侧分支）→ 恰 1 红 `surfaceSourceSeqsRoundTripInBothStates:558`（键计数 4→3；/tmp/it19.1-mutA-wire-red.log，BUILD FAILURE、22 测试 1 失败）；MUT B（turn 期望回退 0-based）→ `core/session` 41 测试 **3 失败 + 1 错误**（`validConversationPasses:34` / `brokenSeqContiguityRejected:44` / `logEndingInsideOpenTurnRejected:68` 三个先撞 turn 号消息 + `closeInterruptedAppendsFactsAfterEndSeedAndIsIdempotent:125 » turn/start number 1 != expected 0`；/tmp/it19.1-mutB-turn-red.log，BUILD FAILURE）；各自还原复绿（/tmp/it19.1-mutA-wire-green.log、/tmp/it19.1-mutB-turn-green.log，BUILD SUCCESS）
- [ ] ④ 全量 `mvn -B -q package` 绿
- [ ] ⑤ 文档同步：03 §6（口径权威化）/ it19 挂账行标注收口（引用本迭代号）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
| 「夹具随动：`SessionInvariantsTest` 5 处 + `SessionRecoveryTest` 种子 5 处」（四确认内容 ① 措辞） | `SessionTest.java:165` 的 seed 夹具亦被 1-based 化（超出锚点列名范围） | 防传染面不限于经 `validate` 的夹具——留 0-based 种子会伪装成「口径未变」误导后来者；该用例只断言 seq 与 end-seed 语义，行为不变 | 迭代内已同步（一行，可随时还原） |
