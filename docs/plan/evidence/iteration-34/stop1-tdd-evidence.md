# 停 1 TDD 实录：SessionTail 只读流式 seam（W2 四确认 → 四契约实现）

状态：完成（2026-11-12）。设计与契约见 [stop1-seam-design.md](stop1-seam-design.md)；
迭代盘见 [iteration-34.md](../iteration-34.md)。

## 1. RED（先写测试，观看失败）

10 个测试（SessionTailTest）先于任何实现写入，首跑全红——失败模式单一且为预期原因：

```
Tests run: 10, Failures: 1, Errors: 9, Skipped: 0
ServiceNotAvailable: Service 'session-tail' not provided in scope chain
```

T7 为 Failure（期望 NoSuchElementException，实际 ServiceNotAvailable——两者都是「服务缺失」的
不同断言面），其余 9 个 Error。即：失败全部因 `SessionTail.KEY` 未提供，无一因测试自身写错。

## 2. 夹具订正（诚实交易）

T4 残片与 T5 手工行原写 `"seq":2`。核对 `LoggedEvent`（seq = append 锁内 log.size()，从 0 起）
与 `CreateOptions.empty()`（seed = null，无重放、无 end-seed 标记）后确认 turn/start 落盘为
seq 0 ⇒ 手工模拟「写者的下一条」应为 seq 1。实现前订正（RED 仍成立：订正后重跑同失败面，
仍 10 红 ServiceNotAvailable），T3 的 `{"seq":2,` 残片（第三事件位）与 T6 的 seq 5（故意跳号）
保持原样。T4/T5/T6 三例在严格连续规则下互洽：T4/T5 连续交付、T6 跳号必抛。

## 3. GREEN

实现面（无生产代码先于 RED）：

- `SessionTail` 接口（session/persistence，additive 新接口——不碰 SessionPersistence）。
- `JsonlPersistence implements SessionPersistence, SessionTail`：`open()` 只读 header（缺失
  NoSuchElementException，与 load 同款），**无锁探针、无 createDirectories**；`JsonlFollower`
  内部类持字节位置游标，`nextEvents()` 每次开 READ 通道读 position→EOF 即关，按字节扫描
  '\n'（multibyte 安全），只交付完整行；`expectedSeq` 严格连续校验；`decodeEnvelope` 从
  readLoaded 提取共用（未知 ignorable → ExtensionEvent；未知非 ignorable → fail loud——
  parity by construction）。
- `JsonlPersistencePlugin.apply`：同实例 `scope.provide(SessionTail.KEY, persistence)`。

结果：`Tests run: 10, Failures: 0, Errors: 0`；`-am` 全反应器（kernel/core/session 模块 +
persistence 两模块）BUILD SUCCESS——readLoaded 重构经 JsonlPersistenceTest 等全量回归。

## 4. 突变 M1–M4（破坏必红 → 还原复绿）

| # | 破坏 | 红面 | 复绿 |
|---|---|---|---|
| M1 | open() 加 WriterLock.acquire 探针 | 7 红：T1 ERROR（探针撞活锁拒读）、T2 FAILURE（锁文件被创建）、T4/T5/T6/T9/T10 ERROR（活写者会话全拒）——设计探测器 T1/T2 必红 | 还原后 10/10 绿 |
| M2 | nextEvents 首行调 repairTornTail | 2 红：T3 FAILURE（字节逐字变——「was」少了残片）、T4 ERROR（残片被截断后半行解析崩）——设计探测器 T3 必红 | 还原后 10/10 绿 |
| M3 | 残片时上抛 IllegalStateException | 2 红：T4 ERROR + T3 ERROR（完整行纪律破坏）——设计探测器 T4 必红 | 还原后 10/10 绿 |
| M4 | 删除 seq 连续校验 | 1 红：T6 FAILURE（跳号不再抛）——设计探测器 T6 必红，且是唯一红（校验面精确） | 还原后 10/10 绿 |

四契约各有可观察断言且各被突变打红过：无锁（M1→T1/T2）、不修（M2→T3）、完整行（M3→T4）、
seq 连续（M4→T6）。

## 5. 同步面（本停点）

- 12 §6：`--session` 检视旁路措辞「零写路径」→「零新事件——load 会探写者锁并可能修复撕裂尾，
  「真零写」属 session/persistence 的 `SessionTail` 流式读面（it34）」。
- 02 模块表：`harness-session-persistence` 行增 `SessionTail`（it34 四契约一句）。
- 无 bundle 行变化（tail 是 SPI 能力，不是插件行）；无 --verify 计数变化。
