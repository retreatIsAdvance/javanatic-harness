# 停 1 审查包：SessionTail 只读流式 seam（W2 四确认 → 四契约落地）

状态：待放行。放行后进停 2（workbench/tui 渲染与按键面 + CLI 接线 + 装配面）。

## 1. 交付物

| 文件 | 角色 |
|---|---|
| `session/persistence/…/SessionTail.java` | SPI 新接口（additive，不碰 SessionPersistence）：`open(Id)` + `Follower{header(), nextEvents(), close()}`，四契约逐字进 Javadoc |
| `session/persistence-jsonl/…/JsonlPersistence.java` | 双实现（+SessionTail）：`open()` 无锁读 header；`JsonlFollower` 内部类（字节位置游标、每 poll 开 READ 通道即关、完整行纪律、seq 严格连续）；`decodeEnvelope` 从 readLoaded 提取共用（parity by construction） |
| `session/persistence-jsonl/…/JsonlPersistencePlugin.java` | 同实例双 provide：`SessionPersistence.KEY` + `SessionTail.KEY` |
| `…/SessionTailTest.java` | 10 测试 T1–T10（契约对照 / 铁证断言 / 增量语义 / 重开重放） |
| 文档 | `stop1-seam-design.md`（设计定稿）、`stop1-tdd-evidence.md`（TDD+突变实录）、`iteration-34.md`（停点状态/取证表/验收①/修正表）、12 §6 措辞、02 模块表 |

## 2. 不变量增量（四契约 → 可观察断言）

1. **无锁**：open/nextEvents 不探针、不创建 `.writer.lock`——T2 以「读后锁文件仍不存在」为铁证（`WriterLock.acquire` 会 CREATE 锁文件）。
2. **不修复撕裂尾**：零写调用——T3 以「读取前后文件字节逐字不变」为铁证。
3. **完整行纪律**：末行无 '\n' 终止 = 尚不可用，下轮再读，不抛——T4 残片两段拼合后交付。
4. **seq 连续**：交付事件 seq == 已交付计数（与 readLoaded 同款严格，强于「单调不减」）——T5 空行不占号、T6 跳号必抛。

对照面（用户点透的矛盾）：T1 同一 busy 会话，`load()` 抛 WriterLockException（占用即拒）**而** tail 成功交付——无锁流式读是观察进行中会话的唯一路径。

## 3. 验证取证（全文见 [stop1-tdd-evidence.md](stop1-tdd-evidence.md)）

- RED：10 红，全为 `ServiceNotAvailable: session-tail`（失败原因单一且为预期）。
- 夹具订正（诚实交易）：T4/T5 手工行 seq 2→1（turn/start 实为 seq 0）——实现前订正，RED 重验同失败面。
- GREEN：10/10 绿；`-am` 全反应器 BUILD SUCCESS（readLoaded 重构经 JsonlPersistenceTest 等回归）。
- 突变 M1–M4 破坏必红→还原复绿：M1（open 加锁探针）→ T1/T2 红；M2（nextEvents 调 repairTornTail）→ T3 红（字节变）；M3（残片上抛）→ T4 红；M4（删 seq 校验）→ 仅 T6 红（校验面精确）。

## 4. 同步面（本停点已做）

- 12 §6：`--session` 检视「零写路径」→「零新事件——load 会探写者锁并可能修复撕裂尾；真零写属 SessionTail 流式读面」。
- 02 模块表：session/persistence 行增 SessionTail（四契约一句）。
- 无 bundle 行变化；无 --verify 计数变化。

## 5. 请裁决

- 放行停 1 → 开工停 2（workbench/tui 脚手架 + 渲染纯函数 TDD，按键解析 + 端子薄壳，CLI 接线 + 装配面四件套）。
- 若指出缺陷 → 修订后重出 packet。
- 停 1 提交不 push（迭代仪式：门禁 + CI 收口时随批）。
