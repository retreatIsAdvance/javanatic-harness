# it33 停 1 取证（workbench/projection 契约 + 五视图）

全部命令自仓根 `javanatic-harness/` 执行；`EXIT=` 回显为命令自身打印。
日志原件：`/Users/abel_yang/.qoder/tmp/it33-stop1-{red,green,green3,final-green,validate,validate2,m1..m5-red}.log`。

## 1. TDD 红阶段（桩实现）

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25) && ~/Documents/apache-maven-3.10.0/bin/mvn -B -q -pl workbench/projection -am test
EXIT=1
Tests run: 30, Failures: 0, Errors: 30, Skipped: 0
UnsupportedOperationException: not yet × 30        （桩抛；证明 30 用例接线全部命中实现）
```

## 2. 首轮实现绿→断言修钉（三处期望与事实不符，TDD 内修正）

首次实现后运行 `-pl workbench/projection -am test`：EXIT=1，3 失败，全部为
**测试期望钉错事实**而非实现缺陷（messages 视图是 `deriveMessages()` 透传，
derive 语义以 core 实现为准；usage 视图则是实现偏离了自己写下的契约，属实现缺陷）：

| 失败 | 根因 | 处置 |
|---|---|---|
| `UsageViewTest.turnWithoutReportedUsageIsNullNotZero` | 实现漏掉「含 assistant 但未报告计量的轮必现于 perTurn」——与 `UsageSummary` 自己文档化的契约相违 | **改实现**：`putIfAbsent(turn, null)` + 有 usage 才 merge |
| `ToolsViewTest.carriesErrorAndConcludesTurnFlags` | 夹具 `result()` 钉死 concludesTurn=false，无法表达 true | 夹具增 `resultConcluding()` 工厂（6 参，避 ParameterNumber） |
| `MessagesViewTest.emptyAssistantContentIsDropped` | 期望「空文本块被丢弃」；core `DeriveMessage` 实况：content 列表为空才丢，单空 TextBlock 保留 | **改测试**为两条：空 content 列表丢弃 / 空文本块保留（钉 derive 实语义） |

## 3. 终态绿

```
~/Documents/apache-maven-3.10.0/bin/mvn -B -q -pl workbench/projection -am test
EXIT=0
surefire XML 合计：tests=30  errors+failures=0
（MessagesViewTest 5 / SessionProjectionTest 1 / TimelineViewTest 6 / ToolsViewTest 6 / TurnsViewTest 8 / UsageViewTest 4）
```

## 4. 门禁 validate（spotless + checkstyle 全 reactor）

```
~/Documents/apache-maven-3.10.0/bin/mvn -B -q validate
EXIT=0   [ERROR] 行数 = 0      （实施后一次；全部突变还原后复跑一次，同绿）
```

## 5. 突变检查（破坏必红 → 还原逐字节 → 终检零残留）

每例：Edit 引入突变 → 聚焦该视图测试类 → 期望红 → Edit 精确还原 → 全量复绿（见 §3）。

| 突变 | 破坏点 | 红证据（surefire） |
|---|---|---|
| M1 | usage：`putIfAbsent` 换成 `remove` | EXIT=1；UsageViewTest 4 跑 2 败：`rollsUpPerTurnAndTotal`、`turnWithoutReportedUsageIsNullNotZero` |
| M2 | tools：callId 配对改位置配对（首结果配首调用） | EXIT=1；ToolsViewTest 6 跑 2 败：`outOfOrderResultsPairByCallIdNotPosition`、`resultWithoutCallBecomesOrphan` |
| M3 | timeline：未知扩展改 `known(...,null)`（recognized=true） | EXIT=1；TimelineViewTest 6 跑 2 败：`unknownExtensionRendersTypeOnlyAndNeverTouchesPayload`、`interleavedKnownAndUnknownKeepOrder` |
| M4 | turns：`toolCalls++` 折叠置空 | EXIT=1；TurnsViewTest 8 跑 3 败：`closedTurnCarriesLifecycleAndStats`、`implicitTurnWithoutTurnStartOpensAtFirstEvent`、`statsDoNotLeakAcrossTurns` |
| M5 | messages：删除空 seed 短路守卫 | EXIT=1；MessagesViewTest 5 跑 0 败 1 错：`emptyLogYieldsEmptyMessages`（IndexOutOfBoundsException——core `Session.create` 空 seed 潜在边缘被守卫兜住） |

```
grep -n "MUTATION" workbench/projection/src/main/java/io/javanatic/harness/workbench/projection/SessionProjection.java
RESIDUE_EXIT=1   （零残留）
```

## 6. 未跑的验证（如实声明）

- 全量 `package`、dist/jh `--verify`、真实会话检视：属停 2 / 收口（停 1 尚无 CLI 消费面）。
- jqwik 属性 `foldsNeverThrowAndSeqEqualsIndex` 1000 试次随全量测试通过（SessionProjectionTest tests=1 含该属性；messages 视图因 surface 校验可抛，属性面排除，见测试注释）。
