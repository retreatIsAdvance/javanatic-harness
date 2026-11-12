# 停 1 seam 契约设计：只读流式 tail 读面（W2 四确认 → 设计钉死）

状态：设计定稿（2026-11-12），TDD 实现完成（同日，实录见 [stop1-tdd-evidence.md](stop1-tdd-evidence.md)）。四契约条款 = 用户裁决逐字固化。

## 1. 动机与对立面（load() 锁语义，用户点透）

`JsonlPersistence.load()`（JsonlPersistence.java:125-145）对外来写者：
`WriterLock.acquire(dir)` 探针（**占用即拒**，WriterLockException）→ `repairTornTail`（**写操作**：补换行或截断）→ 全量读。跟随态 tail 的进行中会话按定义被活跃写者占用 ⇒ load() 必拒；「可刷新快照」降级档同样撞锁。⇒ **无锁流式读是观察进行中会话的唯一路径**（不是性能优化）。本 seam 与 load() 的每一步正相反。

## 2. 四契约（用户裁决条款，逐字进 Javadoc）

1. **无锁**——不探针、不获取写者锁；
2. **不修复撕裂尾**——修复是写操作，并发读者只容忍（撕裂尾归 load() 的事务域管）；
3. **完整行纪律**——末行不完整＝「尚不可用」，下轮再读，不得上抛 IllegalStateException；
4. **顺序消费、seq 单调**——按追加序逐行交付，事件 seq 连续不减。

## 3. 接口形状

位置：`session/persistence` SPI 模块（与 `SessionPersistence` 同包，**additive 新接口**——不碰既有接口面，对外实现者零负担）。实现：`JsonlPersistence` 双实现（SessionPersistence + SessionTail），`JsonlPersistencePlugin.apply` 同实例 `scope.provide(SessionTail.KEY, persistence)`（JsonlPersistencePlugin.java:63 旁）。

```java
public interface SessionTail {
    ServiceKey<SessionTail> KEY = new ServiceKey<>("session-tail");

    /** 打开只读跟随读面（四契约）。@throws NoSuchElementException 会话不在盘上（header.json 缺失，load 同款） */
    Follower open(Id<Session> id) throws IOException;

    interface Follower extends AutoCloseable {
        /** 会话头（open 时读一次；header 由 backfill 一次写成、之后不改）。 */
        SessionHeader header();
        /** 下一批新事件（仅完整行；无新内容=空列表；末行不完整=尚不可用，不抛）。 */
        List<SessionEvent> nextEvents() throws IOException;
        @Override
        void close(); // 无持久资源；AutoCloseable 为 W3 长驻消费面预留
    }
}
```

交付物 = 解码后的 `SessionEvent`（与 readLoaded 同款解码：未知 ignorable → ExtensionEvent(time+type)；未知非 ignorable → IllegalStateException fail loud）——W1 投影（`project(List<SessionEvent>)`/`project(Loaded)`）与 W3 server 都吃这个形状。

## 4. 机制（四契约 → 实现）

- **位置推进**：Follower 持字节偏移 `position` = 最后交付完整行的行尾（'\n' 边界）。`nextEvents()` 每次打开 **READ** 通道（无 WRITE、无 CREATE）读 position→EOF 即关——不持有长通道（跨平台写者并发面最安全，Windows 亦无占用面）。
- **① 无锁**：open/nextEvents 不 `createDirectories`、不打开/不创建 `.writer.lock`、不探针。可观察断言（不是靠代码审查）：无锁文件会话 tail 读后 **`.writer.lock` 仍不存在**（`WriterLock.acquire` 会 CREATE 锁文件——不存在即证明未获取）。
- **② 不修复**：零写调用。可观察断言：撕裂尾会话 tail 读后文件字节逐字不变（size + content）。
- **③ 完整行**：只交付 '\n' 终止的行；残片不交付、位置停在残片前；下轮残片补齐则交付。空行跳过（readLoaded 同款 parity）。**防御性 clamp**：若文件缩小使 position > size（repairTornTail 截断只删尾部残片、理论到不了此态），clamp 到 size + warning，不抛。
- **④ seq 连续**：交付事件 seq == 已交付计数（readLoaded 同款严格连续，跳号 fail loud——比「单调不减」更强，与 load parity；写者侧 writeEnvelope 本就拒跳号，出现即破损）。

## 5. 测试矩阵（TDD 红绿，夹具 = 既有双 Runtime 模式）

夹具：rt（写者，live 会话持续 append）+ rt2（外来观察者）共享同一 root——`secondWriterOnSameSessionFailsLoudAndFirstWriterUnaffected` 同款（JsonlPersistenceTest.java:561 起）。

| # | 契约 | 用例（SessionTailTest） |
|---|---|---|
| T1 | ①对照 | busy 会话（rt 写者持锁）：rt2 `load()` 抛 WriterLockException **而** `open()+nextEvents()` 成功交付全部已有事件（成对断言，对照面） |
| T2 | ① | 无 `.writer.lock` 会话：tail 读后锁文件仍不存在（未获取的铁证） |
| T3 | ② | 撕裂尾会话：nextEvents 只交付完整行；文件字节逐字不变 |
| T4 | ③ | 残片在尾：nextEvents 不含残片、不抛 IllegalStateException；补 '\n' 后下轮交付 |
| T5 | ③ | 空行跳过（与 readLoaded parity） |
| T6 | ④ | 跨轮 poll seq 连续递增；手工跳号行 → IllegalStateException |
| T7 | — | 会话不在盘上 → NoSuchElementException |
| T8 | — | header() 与 load() 的 header 一致 |
| T9 | — | 增量语义：无新事件 → 空列表；追加后只交付增量不重放 |
| T10 | — | 多轮 poll 间 close/重开 open() 从头继续（position 生命周期属 Follower） |

突变（破坏必红→还原复绿）：M1 无锁→open 时加锁探针（T1 必红）；M2 不修复→nextEvents 调 repairTornTail（T3 必红）；M3 完整行→残片时上抛（T4 必红）；M4 seq→去掉跳号校验（T6 必红）。

## 6. 同步面（本停点）

- 12 §6 冻结清单：`--session` 检视旁路措辞「零写路径」→「零新事件」（load 会探锁+可能修撕裂尾）；新增流式读面句：「真零写」属 tail seam。
- 02 模块表：session/persistence 增 `SessionTail` 接口句（随停 1 交付；模块表全文同步随停 2 装配批，it33 先例）。
- 无 bundle 行变化（tail 是 SPI 能力，不是插件行）；无 --verify 计数变化。
