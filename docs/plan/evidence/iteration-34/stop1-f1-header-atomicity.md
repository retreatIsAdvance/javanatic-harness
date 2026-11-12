# 停 1-F1：backfill header 原子化 mini-fix 取证

**来源**：停 1 评审 P3——`backfill()` 用裸 `Files.writeString(header.json)` 非原子写（CREATE/TRUNCATE 后逐块写入），并发无锁 `open()` 在「文件已建、内容未写完」窗口可读到撕裂/空 header（此暴露 `list()` 早年就有，tail 是新增的第二个读者）。

**处置**：用户裁决「独立 mini-fix 先行」——停 2 开工前单独修复。

## 修复形状

`JsonlPersistence` 新增静态助手 `atomicWriteString(Path target, String content)`：同目录 `Files.createTempFile` → 写全 → `Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)` → `AtomicMoveNotSupportedException` 降级普通 move（同目录 rename 在主流平台仍原子，远好于原位截断重写）→ `finally deleteIfExists(temp)`。`backfill()` 改走该助手。

观察者语义：header 只可能「缺席」（`NoSuchElementException`，原子写下唯一合法失败态）或「完整」（读到的内容与某次已完成的 backfill 逐字节一致），不再有中间态。

## TDD 实录

### RED（实现前，非原子形态）

```
mvn -pl session/persistence-jsonl -am test -Dtest=HeaderAtomicWriteTest -Dsurefire.failIfNoSpecifiedTests=false
EXIT=1
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 ... <<< FAILURE!
Expecting empty but was: [java.lang.IllegalStateException: event body is not a JSON object:
```

与评审预测的撕裂症状逐字一致（空 header 被读为 JSON → 解析失败）。0.7s 内 1000 次 backfill 竞态即捕获多例。

### GREEN（原子写实现）

首次编译失败自报：误用 `StandardOpenOption.ATOMIC_MOVE/REPLACE_EXISTING`（不存在于该类），改为 `StandardCopyOption` 后：

```
mvn -pl session/persistence-jsonl -am test -Dtest=HeaderAtomicWriteTest -Dsurefire.failIfNoSpecifiedTests=false
EXIT=0
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 突变检查（破坏必红 → 还原复绿）

突变：backfill 还原为裸 `Files.writeString`（其余代码不动，测试不动）：

```
EXIT=1
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 ... <<< FAILURE!
Expecting empty but was: [java.lang.IllegalStateException: event body is not a JSON object:
```

还原修复后（`git diff` 复核仅剩预期三文件改动）：

```
EXIT=0
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 测试形状（HeaderAtomicWriteTest）

- 双 Runtime 夹具（rt 写者 + rt2 观察者，共享同一 root）：写者线程 1000 次 `store.create(...)`（每次触发 backfill），主线程自旋并发 `tail.open(id)`。
- 容忍面：仅 `NoSuchElementException`（header 尚未原子落地）；其余任何 `Throwable` 记入 violations。
- 断言：violations 为空；`opened` 计数为正（观察者确实读到了 header）；终局确定性检查 `tail.open(latest).header().id()` 与最后一次写入一致。
- 无 sleep、无固定窗口——自旋至写者 done，天然覆盖「文件已建、内容未写完」窗口的高频命中。

## 回归

```
mvn -pl session/persistence-jsonl -am test
EXIT=0
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0   (1 + 7 + 26 + 10)
BUILD SUCCESS
```

四个测试类全绿：HeaderAtomicWriteTest 1 / JsonlPersistenceListTest 7 / JsonlPersistenceTest 26 / SessionTailTest 10。
