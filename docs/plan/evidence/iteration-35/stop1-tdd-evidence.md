# it35 停 1 TDD 取证 —— workbench/rpc 三层（契约 + wire + 绑定面）

取证时间：2026-11-13。命令惯例（macOS/zsh，全档同一命令形态）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
MVN=~/Documents/apache-maven-3.10.0/bin/mvn
$MVN -q -pl workbench/rpc -am test "-Dtest=<Class>#<method>" \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-<tag>.log 2>&1; echo "EXIT=$?"
```

测试矩阵 43 = 13（JsonRpcWireTest 纯帧层）+ 23（WorkbenchRpcTest handler 三方法）+ 7（RpcHttpServerTest 绑定面）。S2 全程 TDD：每用例先写测试→跑红（编译失败或断言失败）→最小实现→跑绿，无先实现后补测的用例。红证据留两条链：① S2 夹具订正实录（测试先行、预期错误、订正预期——见 §5）；② 突变 M1–M4 的破坏红（同一断言面的红，见 §2）。

## 1. 聚焦面（S2 逐类绿 + 新鲜聚焦复跑）

S2 期间逐类聚焦跑至绿；本段复跑留证（关键输出行以 surefire XML 求和为准）：

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=JsonRpcWireTest" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m1-green.log 2>&1; echo "EXIT=$?"
# 输出：EXIT=0（wire 聚焦复跑绿，日志 /tmp/w3-m1-green.log）
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m2-green.log 2>&1; echo "EXIT=$?"
# 输出：EXIT=0（handler 聚焦复跑绿，日志 /tmp/w3-m2-green.log）
$MVN -q -pl workbench/rpc -am test "-Dtest=RpcHttpServerTest" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m3-green.log 2>&1; echo "EXIT=$?"
# 输出：EXIT=0（绑定面聚焦复跑绿，日志 /tmp/w3-m3-green.log）
```

## 2. 突变面 M1–M4（破坏必红 → 还原逐字节复绿）

设计 §6 突变矩阵四件；施加/还原均经 Edit 逆操作（还原后 grep 零残留，见 §3）。

### M1 域码翻译单点破坏（设计 T6 守）

突变形态：sessionViews 的 `NoSuchElementException` 翻译码字面量改动（-32001 → 邻码）。

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest#unknownSessionYieldsSessionNotFound" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m1-red.log 2>&1; echo "M1_RED_EXIT=$?"
# M1_RED_EXIT=1
# [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.143 s <<< FAILURE! -- in io.javanatic.harness.workbench.rpc.WorkbenchRpcTest
# [ERROR] io.javanatic.harness.workbench.rpc.WorkbenchRpcTest.unknownSessionYieldsSessionNotFound -- Time elapsed: 0.057 s <<< FAILURE!
# expected: "-32001"
```

还原（Edit 逆操作）→ 复绿：

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest#unknownSessionYieldsSessionNotFound" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m1-green.log 2>&1; echo "M1_GREEN_EXIT=$?"
# M1_GREEN_EXIT=0
```

### M2 游标 clamp 摘除（设计 T9 守）

突变形态：删除 `if (lastSeq + 1 > all.size()) { throw new RpcException(-32602, "invalid params"); }`。

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest#tailAheadOfLogYieldsInvalidParams" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m2-red.log 2>&1; echo "M2_RED_EXIT=$?"
# M2_RED_EXIT=1
# [ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.149 s <<< FAILURE! -- in io.javanatic.harness.workbench.rpc.WorkbenchRpcTest
# java.lang.NullPointerException: Cannot invoke "com.fasterxml.jackson.databind.JsonNode.get(String)" because the return value of "com.fasterxml.jackson.databind.JsonNode.get(String)" is null
# [ERROR]   WorkbenchRpcTest.tailAheadOfLogYieldsInvalidParams:233->errorCode:45 NullPointer
```

失败形态 = 测试 helper NPE（clamp 摘除后响应无 error 节点，`errorCode()` 直取 `.get("error")` 空指针）。仍是有效杀突变（测试红），形态如实记录。

还原（Edit 逆操作）→ 复绿：

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest#tailAheadOfLogYieldsInvalidParams" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m2-green.log 2>&1; echo "M2_GREEN_EXIT=$?"
# M2_GREEN_EXIT=0
```

### M3 loopback 换 wildcard（设计 T12 守）

突变形态：`InetAddress.getLoopbackAddress()` → `(InetAddress) null`（绑定 0.0.0.0 wildcard；null 直传有重载歧义编译错，须显式 cast）。

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=RpcHttpServerTest#bindsToLoopbackAddressOnly" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m3-red.log 2>&1; echo "M3_RED_EXIT=$?"
# M3_RED_EXIT=1
# [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.273 s <<< FAILURE! -- in io.javanatic.harness.workbench.rpc.RpcHttpServerTest
# [ERROR] io.javanatic.harness.workbench.rpc.RpcHttpServerTest.bindsToLoopbackAddressOnly -- Time elapsed: 0.056 s <<< FAILURE!
# Expecting value to be true but was false
```

还原（Edit 逆操作）→ 复绿：

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=RpcHttpServerTest#bindsToLoopbackAddressOnly" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m3-green.log 2>&1; echo "M3_GREEN_EXIT=$?"
# M3_GREEN_EXIT=0
```

### M4 预算判断摘除（设计 T11 守）

突变形态：删除 `if (RpcJson.sizeOf(result) > maxResponseBytes) { throw new RpcException(-32004, "response too large"); }`。

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest#oversizedResponseYieldsResponseTooLarge" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m4-red.log 2>&1; echo "M4_RED_EXIT=$?"
# M4_RED_EXIT=1
# [ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.168 s <<< FAILURE! -- in io.javanatic.harness.workbench.rpc.WorkbenchRpcTest
# java.lang.NullPointerException: Cannot invoke "com.fasterxml.jackson.databind.JsonNode.get(String)" because the return value of "com.fasterxml.jackson.databind.JsonNode.get(String)" is null
# [ERROR]   WorkbenchRpcTest.oversizedResponseYieldsResponseTooLarge:311->errorCode:45 NullPointer
```

失败形态同 M2（helper NPE，如实记录）。

还原（Edit 逆操作）→ 复绿：

```bash
$MVN -q -pl workbench/rpc -am test "-Dtest=WorkbenchRpcTest#oversizedResponseYieldsResponseTooLarge" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-m4-green.log 2>&1; echo "M4_GREEN_EXIT=$?"
# M4_GREEN_EXIT=0
```

## 3. 复绿面（全模块新鲜跑 + 零残留终检）

```bash
$MVN -q -pl workbench/rpc -am test > /tmp/w3-full2.log 2>&1; echo "FULL_EXIT=$?"
# FULL_EXIT=0
# JsonRpcWireTest:    tests=13 failures=0 errors=0 skipped=0
# RpcHttpServerTest:  tests=7  failures=0 errors=0 skipped=0
# WorkbenchRpcTest:   tests=23 failures=0 errors=0 skipped=0
# SUREFIRE_SUM tests=43 failures=0 errors=0 skipped=0
```

零残留终检（四个突变点还原态 grep + 白空格闸）：

```bash
grep -c "\-32001\|\-32002\|\-32003" .../WorkbenchRpc.java     # 6（域码翻译还原）
grep -c "lastSeq + 1 > all.size()" .../WorkbenchRpc.java      # 1（clamp 还原）
grep -c "getLoopbackAddress" .../RpcHttpServer.java           # 1（loopback 还原）
grep -c "response too large" .../WorkbenchRpc.java            # 1（预算还原）
git diff --check                                                # DIFFCHECK_EXIT=0
```

## 4. 并发面（CPU 饱和重复跑，项目惯例）

4 个 CPU 饱和进程（`yes > /dev/null`）下，并发 tail 用例连跑 10 次：

```bash
yes > /dev/null & P1=$!; yes > /dev/null & P2=$!; yes > /dev/null & P3=$!; yes > /dev/null & P4=$!
for i in 1 2 3 4 5 6 7 8 9 10; do
  $MVN -q -pl workbench/rpc -am test "-Dtest=RpcHttpServerTest#concurrentTailRequestsShareNoState" \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false > /tmp/w3-conc-$i.log 2>&1; echo "CONC_${i}_EXIT=$?"
done; kill $P1 $P2 $P3 $P4
# CONC_1_EXIT=0 … CONC_10_EXIT=0（全 10 次）
# CONC_TOTAL_FAIL=0
```

## 5. 夹具面订正（S2 实录，测试先行抓到预期错误的红→改预期→绿）

- **视图 JSON 双 user 消息**：`sessionViewsMapsFiveViewJsonShape` 首跑红——预期 2 条消息（user+assistant），实际派生 3 条（user、user、assistant）。根因：`DeriveMessage.project` 逐节点派生，夹具同时含 ToolResultEvent（seq 3）与携带同块 ToolResultBlock 的 UserMessageEvent（seq 4），两节点各派生一条同形 user 消息。裁决：映射代码正确、测试预期错——订正预期为 3 条并在 `RpcFixtures.richEvents` Javadoc 记录派生规则。
- **FakeTail 多批排空**：初始 fake 单批吐出全部事件，只覆盖了排空循环的终止分支；改为 cursor + `batchSize` 版（`tail.batchSize=2`）并增 `tailDrainAccumulatesAcrossBatches` 钉死跨批累积——真实 seam 按批交付，单批夹具测不出丢批。
