# 迭代 12.6 — 硬化：耐久 / 错误分类 / 围栏真路径 / 平台预警（状态：进行中）

模块：`session/persistence-jsonl`（fsync + 撕裂尾）、`llm/llm` + `llm/openai-compat`（LlmError 分类）、`fs/local`（realpath 围栏）、`sandbox/sandbox` + `sandbox/local` + `bundle/base`（`--verify` 预警）、`core/agent-loop`（LoopGuard 注释订正）、文档（02 漂移订正；契约面变更同步 05）

四确认日期：2026-09-14（用户确认：照草案落盘并开工；裁项一不加 per-turn force、裁项二预警含 linux 探针失败——均按草案推荐）

## 四确认

- **内容**：
  1. **JSONL 耐久（fsync + 撕裂尾）**：`JsonlPersistence` 两类硬化——(a) `flushBarrier()` 从空操作实装为**落盘 barrier**（`FileChannel.force`；dispose 挂点已在 `AgentLoopPlugin` 调 `SessionStore.flush`，接通即得真耐久）；(b) 打开（load/续写）时识别**未完成尾行**（进程被杀于半行写入——「撕裂尾」）：仅当**最后一个非空行**不完整/不可解析时截断到最后一条完整行（丢失至多半行），内部行破损仍 fail loud；(c) 注入测试 + 本机 kill -9 真跑 `--resume` 验证。
     - 已裁（2026-09-14）：**不加 per-turn force**——it15 的 kill/resume 不需要（`write()` 过的行在 page cache 中存活进程死亡）；force 只对 OS/断电级崩溃起作用，dispose barrier + 尾行截断已覆盖诚实叙事。
  2. **LlmError 分类（llm seam）**：`llm/llm` 新增 typed 失败（暂名 `LlmCallException`，含 `Kind` 词表：`AUTH`(401/403) / `RATE_LIMIT`(429) / `SERVER`(5xx) / `NETWORK`(连接/IO) / `TIMEOUT`(空闲看门狗) / `PROTOCOL`(wire 解析)）；`openai-compat` 把现装 `IllegalStateException("… http " + code)` 与裸 IO/Timeout 异常映射为 typed 抛出；重试判定改读 `Kind`（可重试性不再散落为数值判断）。原则沿用 it12.5 已定：**分类器产出 typed code、按 code 路由，不解析消息文本**。
     - 不做：重试策略本身重做（it6 已定形状不动）；seam 主签名不动（只新增类型）。
  3. **LocalFs realpath（围栏真路径）**：`fs/local` 根围栏从**词法归一**（`toAbsolutePath().normalize()` + `startsWith(root)`）升级为**真实路径**：构造期 `root.toRealPath()`；`resolve()` 对「最深已存在祖先」取 realpath 后拼剩余段，再校验 `startsWith(realRoot)`——工作区内一条 `ln -s /outside` 今天可让 in-process fs 工具写出界（seatbelt/bwrap 只管 shell 子进程，无 OS 兜底）。测试：symlink 指外拒 / 指内放行 / `/tmp` 与 `/private/tmp` 归一（darwin 先例见 `WritableRoots`）。
  4. **`--verify` 在无同机后端平台的预警**：`--verify` 今天在 win32（平台链空）照样「通过」exit 0——受限档的 fail-closed 延迟到首次 bash。硬化：(a) 查询面——`sandbox/sandbox` 的 `SandboxProvider` 增最小状态查询（`backendStatus()`：可用 / 平台无后端（点名）/ 候选探针失败（点名二进制））；(b) verify 路径（`AppBoot`，已依赖 sandbox.sandbox）在**组合含受限档**时输出 stderr 预警（`System.Logger` WARNING），点名平台、后果（首次受限调用 fail-closed）与出路（显式 `danger-full-access` overlay 弃权 / 装 bubblewrap / windows-acl 入 0.2.0）；(c) **exit 码不变**（预警非违规——PRODUCTION 违规仍 exit 1）。
     - 已裁（2026-09-14）：**含**——与平台无后端同类（首调用才炸），同一种预警。
     - 触 05 契约（SandboxProvider 增方法）→ 05 同步在案。
  5. **LoopGuard 滞后注释订正**（纯注释，两处）：`LoopGuard.java` `checkBudget` Javadoc「（未来 budget 档读 usage 累计）」→ 已实现（it10）；`LoopGuardPlugin` 类注释「budget 档（token 计量）随 deepseek 切片。」→ 订正为现状。
  6. **02 遗留漂移订正**（四组）：§3 Core 表补 `core-todo` / `core-plan` / `core-preset` 三行；§3 LLM 表补 `openai-compat` 行、deepseek 行改「薄壳」（wire/韧性在 openai-compat）；Jackson 归属注三处改为实况（core-tools / openai-compat / persistence-jsonl）；§5 目录树补 `core/todo|plan|preset`、`llm/openai-compat`。

- **目标**：0.1.0 前的质量债清口——四条行为面（耐久 / 错误 / 围栏 / preflight 预警）硬化 + 文档事实性收口；it15（kill/resume + R1 全比对进 CI）的写路径保险先行。

- **为什么**：
  - JSONL：it15 判据正面压「中途 kill/resume」——撕裂尾今天让 `load` 直接抛（resume 失败）；`durable()` 声称耐久但 barrier 空转、无 force，PRODUCTION 档的耐久承诺缺实体。
  - LlmError：it14 交互面要渲染**可行动**的失败（AUTH 引查 key、RATE_LIMIT 稍后可试）；今天 `"deepseek http 401"` 只能解析文本，it12.5 已把「typed code、不解析消息」定为原则——LLM 侧同款欠账。
  - realpath：in-process fs 围栏是 fs 工具的唯一边界（OS 沙箱只包 subprocess）——symlink 洞是 0.1.0「敢让人跑」的安全缺口。
  - 预警：`--verify` 是 R4 的台面——今天在 win32 上「全绿」但首条 bash 必 fail-closed；预警让部署者在组合期就看到事实与出路。
  - 5/6 两条：本迭代（it13）取证时发现的既存事实性欠账（挂账已记 README 12.6 行）。

- **不做**：
  - persistence 多进程锁（挂账照旧）
  - JSONL 异步写 / 批量提交（barrier 语义已足；不扩）
  - LLM 重试策略重做 / 传输参数面变更（it6 形状不动，只加分类）
  - Windows 同机后端实现（windows-acl 入 0.2.0）、Landlock（0.2.0 第二候选）
  - REPL / 流式渲染（it14）、生产模拟进 CI（it15）、jlink 压缩与模块收窄（it13 挂账照旧）
  - 公开 API 破坏性变更（新增类型；`SandboxProvider` 只增查询方法）

## 设计增量（ADDED / MODIFIED / REMOVED）

- ADDED：`llm/llm` typed 失败（`LlmCallException` + `Kind` 词表）；`SandboxProvider.backendStatus()` 查询面；JSONL 撕裂尾截断语义
- MODIFIED：`JsonlPersistence.flushBarrier`（空操作 → force）；`LocalFs` 围栏（词法 → realpath）；`AppBoot` verify 路径（+ 预警输出）；`02` 四组订正；`05`（SandboxProvider 面）
- REMOVED：无

## 锚点（开工前填写：本次将改动的既有代码位置）

| 锚点（文件:符号） | 预期改动 | 完成 |
|---|---|---|
| `session/persistence-jsonl` · `JsonlPersistence`：`SessionWriter.flushBarrier/writeEnvelope`、`load` | force barrier + 撕裂尾截断 + 注入测试 | ✓ |
| `llm/llm` · 新 `LlmCallException`（+`Kind`）；`llm/openai-compat` · `OpenAiCompatAdapter.sendWithRetry/pump` | typed 映射；重试判定读 Kind | |
| `fs/local` · `LocalFs`：构造器、`resolve` | realpath 归一 + 深祖先校验 + symlink 测试 | |
| `sandbox/sandbox` · `SandboxProvider`；`sandbox/local` · `ChainedBackend`；`bundle/base` · `AppBoot.boot` verify 分支 | backendStatus 查询 + stderr 预警（exit 码不变） | |
| `core/agent-loop` · `LoopGuard.java` / `LoopGuardPlugin.java` 注释 | 两处订正 | |
| `docs/design/02-module-layout.md` §3/§5 | 四组漂移订正 | |

## 审查停点（开工前填写：按锚点分组的必停点；到点 agent 停下出 packet 等放行）

| 停点 | 覆盖锚点/类 | 状态 |
|---|---|---|
| S1 持久化耐久语义 | `JsonlPersistence`（force/截断——it15 依赖的承载类） | 已放行（edc8ef4） |
| S2 错误与查询面契约 | `LlmCallException`（新 seam 类型）+ `SandboxProvider.backendStatus()`（跨模块） | 待开工 |
| S3 围栏真路径 | `LocalFs`（安全边界——symlink 语义与 TOCTOU 面） | 待开工 |

## 验收（证据 = 实际执行的命令与结果）

- [ ] 全 reactor `mvn -B package` 绿（新增测试记账）
- [ ] JSONL：注入半行尾 → `load` 成功且文件截到最后完整行；内部行破损 → fail loud；`flushBarrier` 为 force 实现且 dispose 挂点触发（接线测试）；本机 kill -9 真跑 → `--resume` 成功
- [ ] LlmError：假服务端 401/429/500/断流 → typed `Kind` 断言；真跑失效 key → AUTH 文案（可选）
- [ ] realpath：symlink 指外拒 / 指内放 / `/tmp`↔`/private/tmp` 归一转测
- [ ] `--verify` 预警：注伪平台链 / 探针失败 → stderr 预警且 exit 0；darwin 真机无预警
- [ ] 注释 + 02 订正 diff 在案（05 随契约同步）
- [ ] 双 job CI 绿（push 后取证 run id）

## 修正（如有）

| 提交 | 缺陷 | 修正 |
|---|---|---|

## 设计偏离（如有）

| 设计文档条目 | 实现实况 | 偏离理由 | 处理（迭代内已同步 / 挂账） |
|---|---|---|---|
